package by.bebuliby.chrona.tracking

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.*
import android.graphics.Rect
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import by.bebuliby.chrona.data.ChronaApplication
import by.bebuliby.chrona.data.ChronaRepository
import by.bebuliby.chrona.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.util.ArrayDeque
import java.util.UUID

/** Inspects only structural YouTube markers, never event/source text or other-app contents. */
class ShortsAccessibilityService : AccessibilityService() {
    companion object { private const val YOUTUBE = "com.google.android.youtube" }
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frames = Channel<TrackingFrame>(Channel.UNLIMITED)
    private val container get() = (application as ChronaApplication).container
    private var connected = false
    private var enabled = false // fail closed until DataStore is loaded
    private var recordingFailed = false
    private var settingsUnavailable = false
    private var receiverRegistered = false
    private var scheduled = false
    private val bootId: String by lazy {
        runCatching { "boot-${Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT)}" }
            .getOrElse { "process-${UUID.randomUUID()}" }
    }
    private val inspect = Runnable { scheduled = false; if (connected) inspectWindow() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected) return
            inspectWindow()
            if (enabled && !recordingFailed) handler.postDelayed(this, 5_000)
            // 5s confirmed checkpoint/sync slices; disabled still has no heartbeat writes.
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && enabled && !recordingFailed) {
                submit(ScreenObservation.NOT_SHORTS, end = IntervalEndReason.SCREEN_LOCKED)
            } else schedule()
        }
    }

    override fun onServiceConnected() {
        if (connected) return
        connected = true
        // App-owned IO actor survives service teardown long enough to flush the final frame.
        container.scope.launch {
            try {
                container.ready.await()
                container.collectorLock.withLock {
                    val settings = container.settings.snapshots.first { it.deviceId.isNotBlank() }
                    val engine = TrackingEngine(ChronaRepository.SHORTS_ID, settings.deviceId)
                    var first = true
                    for (frame in frames) {
                        val batch = if (first) {
                            first = false; engine.recover(container.repository.checkpoint(), frame)
                        } else engine.accept(frame)
                        if (batch != null) container.repository.record(batch)
                    }
                }
            } catch (_: CancellationException) { throw CancellationException() }
            catch (_: Exception) {
                // No private exception details/logs. Stop sampling; health freshness exposes failure.
                frames.close()
                withContext(Dispatchers.Main) { recordingFailed = true; enabled = false; handler.removeCallbacks(heartbeat) }
            }
        }
        serviceScope.launch {
            try {
                container.ready.await()
                container.settings.snapshots.collect {
                    enabled = it.trackingEnabled
                    handler.removeCallbacks(heartbeat)
                    inspectWindow()
                    if (enabled && !recordingFailed) handler.postDelayed(heartbeat, 5_000)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                settingsUnavailable = true; enabled = false; inspectWindow()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Do not access event.text/contentDescription/source, including events from other packages.
        schedule()
    }
    private fun schedule() {
        if (!connected || !enabled || recordingFailed || scheduled) return
        scheduled = true; handler.postDelayed(inspect, 200) // throttle, not indefinitely postponed debounce
    }

    private fun submit(observation: ScreenObservation, gap: GapReason? = null,
                       end: IntervalEndReason = IntervalEndReason.UNKNOWN,
                       status: TrackerStatus = TrackerStatus.OBSERVING) {
        frames.trySend(TrackingFrame(System.currentTimeMillis(), SystemClock.elapsedRealtime(), bootId,
            observation, status, gap, end))
    }

    private fun inspectWindow() {
        if (!connected || recordingFailed) return
        try { inspectAllowedWindow() }
        catch (_: SecurityException) {
            submit(ScreenObservation.UNKNOWN, GapReason.PERMISSION_LOST, IntervalEndReason.PERMISSION_LOST, TrackerStatus.UNAVAILABLE)
        } catch (_: IllegalStateException) {
            submit(ScreenObservation.UNKNOWN, GapReason.SERVICE_UNAVAILABLE, IntervalEndReason.UNKNOWN, TrackerStatus.UNAVAILABLE)
        }
    }

    private fun inspectAllowedWindow() {
        if (settingsUnavailable) {
            submit(ScreenObservation.UNKNOWN, GapReason.SERVICE_UNAVAILABLE, IntervalEndReason.SERVICE_STOPPED, TrackerStatus.UNAVAILABLE)
            return
        }
        if (!enabled) {
            submit(ScreenObservation.UNKNOWN, GapReason.USER_DISABLED, IntervalEndReason.USER_DISABLED, TrackerStatus.DISABLED)
            return
        }
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            submit(ScreenObservation.NOT_SHORTS, end = IntervalEndReason.SCREEN_LOCKED); return
        }
        val root = rootInActiveWindow
        if (root == null) { submit(ScreenObservation.UNKNOWN, GapReason.UNKNOWN_SCREEN); return }
        if (root.packageName?.toString() != YOUTUBE) {
            // Only root package metadata is touched. Never traverse other applications.
            recycle(root); submit(ScreenObservation.NOT_SHORTS, end = IntervalEndReason.NOT_SHORTS); return
        }
        val rootBounds = Rect(); root.getBoundsInScreen(rootBounds)
        val metrics = resources.displayMetrics
        val fullWindow = rootBounds.width() >= metrics.widthPixels * .85 &&
            rootBounds.height() >= metrics.heightPixels * .70
        if (!fullWindow) {
            val small = rootBounds.width().toLong() * rootBounds.height() <
                metrics.widthPixels.toLong() * metrics.heightPixels / 3
            recycle(root)
            if (small) submit(ScreenObservation.NOT_SHORTS, end = IntervalEndReason.NOT_SHORTS)
            else submit(ScreenObservation.UNKNOWN, GapReason.UNSUPPORTED_WINDOW)
            return
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>(); queue.add(root)
        val markers = mutableSetOf<String>()
        val bounds = Rect()
        var modal = false
        var visited = 0
        while (queue.isNotEmpty() && visited < 1200) {
            val node = queue.removeFirst(); visited++
            if (node.packageName?.toString() != YOUTUBE) { recycle(node); continue }
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val id = node.viewIdResourceName
                    if (id in setOf("android:id/parentPanel", "android:id/buttonPanel")) modal = true
                    if (id?.startsWith("$YOUTUBE:id/") == true) {
                        val local = id.substringAfter(":id/")
                        if (local in ShortsClassifier.positiveIds) markers += local
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(queue::add)
            recycle(node)
        }
        val complete = queue.isEmpty()
        while (queue.isNotEmpty()) recycle(queue.removeFirst())
        val state = ShortsClassifier.classify(true, fullWindow, modal, complete, markers)
        submit(state, if (state == ScreenObservation.UNKNOWN) GapReason.UNKNOWN_SCREEN else null,
            if (state == ScreenObservation.NOT_SHORTS) IntervalEndReason.NOT_SHORTS else IntervalEndReason.UNKNOWN)
    }

    override fun onInterrupt() {
        submit(ScreenObservation.UNKNOWN, GapReason.SERVICE_UNAVAILABLE, IntervalEndReason.SERVICE_STOPPED, TrackerStatus.UNAVAILABLE)
    }
    @Suppress("DEPRECATION")
    private fun recycle(node: AccessibilityNodeInfo) { node.recycle() }
    override fun onUnbind(intent: Intent?): Boolean { stop(); return super.onUnbind(intent) }
    override fun onDestroy() { stop(); super.onDestroy() }
    private fun stop() {
        if (!connected) return
        connected = false
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) { unregisterReceiver(screenReceiver); receiverRegistered = false }
        submit(ScreenObservation.UNKNOWN, GapReason.PERMISSION_LOST, IntervalEndReason.PERMISSION_LOST, TrackerStatus.UNAVAILABLE)
        frames.close(); serviceScope.cancel()
    }
}
