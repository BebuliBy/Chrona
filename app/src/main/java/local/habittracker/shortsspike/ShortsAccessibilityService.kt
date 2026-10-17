package local.habittracker.shortsspike

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque

class ShortsAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var connected = false
    private val inspect = Runnable { inspectWindow() }
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!connected) return
            inspectWindow()
            handler.postDelayed(this, 1000)
        }
    }
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { handler.post(inspect) }
    }

    override fun onServiceConnected() {
        connected = true
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        handler.post(heartbeat)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Never read event text/contentDescription/source or log other packages.
        handler.removeCallbacks(inspect)
        handler.postDelayed(inspect, 200)
    }

    private fun inspectWindow() {
        if (!SpikeStore.allowed(this)) {
            SpikeStore.sample(this, Verdict(ScreenState.UNKNOWN, "awaiting_consent_or_paused")); return
        }
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!power.isInteractive || keyguard.isKeyguardLocked) {
            SpikeStore.sample(this, Verdict(ScreenState.NOT_SHORTS, "screen_off_or_locked")); return
        }
        val root = rootInActiveWindow
        if (root == null) {
            SpikeStore.sample(this, Verdict(ScreenState.UNKNOWN, "window_unavailable")); return
        }
        // Only the root package metadata is checked for other apps. No traversal there.
        if (root.packageName?.toString() != SpikeStore.YOUTUBE) {
            SpikeStore.sample(this, Verdict(ScreenState.NOT_SHORTS, "youtube_not_foreground")); return
        }
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        val ids = linkedSetOf<String>()
        var visited = 0
        val bounds = Rect()
        while (queue.isNotEmpty() && visited < 1500) {
            val node = queue.removeFirst()
            visited++
            if (node.packageName?.toString() != SpikeStore.YOUTUBE) continue
            if (node.isVisibleToUser) {
                node.getBoundsInScreen(bounds)
                if (!bounds.isEmpty) {
                    val id = node.viewIdResourceName
                    if (id != null && id.startsWith("${SpikeStore.YOUTUBE}:id/")) {
                        val name = id.substringAfter(":id/")
                        if (name.matches(Regex("[a-zA-Z0-9_]{1,100}"))) ids.add(name)
                    }
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
        }
        val truncated = queue.isNotEmpty()
        val verdict = ShortsClassifier.classify(true, visited > 0, ids)
        SpikeStore.sample(this, verdict, ids.take(300).toSet(), visited, truncated)
    }

    override fun onInterrupt() {
        SpikeStore.sample(this, Verdict(ScreenState.UNKNOWN, "service_interrupted"))
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopObservation("service_unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopObservation("service_destroyed")
        super.onDestroy()
    }

    private fun stopObservation(reason: String) {
        if (!connected) return
        connected = false
        handler.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screenReceiver) }
        SpikeStore.sample(this, Verdict(ScreenState.UNKNOWN, reason))
    }
}
