package local.habittracker.shortsspike

import android.content.Context
import android.os.SystemClock
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Own-app diagnostics only: no account identifiers, UI text or other app content. */
object SpikeStore {
    const val YOUTUBE = "com.google.android.youtube"
    private val tracker = IntervalTracker()
    private var loaded = false
    private var report = JSONObject()

    fun allowed(context: Context) = context.getSharedPreferences("control", 0).getBoolean("consent", false)

    fun setAllowed(context: Context, value: Boolean) {
        context.getSharedPreferences("control", 0).edit().putBoolean("consent", value).apply()
        if (!value) sample(context, Verdict(ScreenState.UNKNOWN, "user_paused"))
    }

    @Synchronized
    private fun load(context: Context) {
        if (loaded) return
        report = try {
            JSONObject(AtomicFile(File(context.filesDir, "report.json")).openRead().bufferedReader().use { it.readText() })
        } catch (_: Exception) { JSONObject() }
        if (!report.has("intervals")) report.put("intervals", JSONArray())
        if (!report.has("transitions")) report.put("transitions", JSONArray())
        // A lost process must not extend a previous open interval to the next startup.
        if (report.optString("state") == ScreenState.SHORTS.name) {
            val last = report.optLong("lastSampleUtcMs")
            val start = report.optLong("openStartUtcMs")
            val duration = (report.optLong("lastSampleElapsedMs") - report.optLong("openStartElapsedMs")).coerceAtLeast(0)
            append("intervals", JSONObject().put("startUtcMs", start).put("endUtcMs", last)
                .put("durationMs", duration).put("endReason", "process_gap_last_observation"), 200)
        }
        report.put("state", ScreenState.UNKNOWN.name).put("reason", "process_start_unknown")
        report.remove("openStartUtcMs")
        report.remove("openStartElapsedMs")
        loaded = true
        save(context)
    }

    private fun append(key: String, item: JSONObject, limit: Int) {
        val list = report.optJSONArray(key) ?: JSONArray()
        list.put(item)
        while (list.length() > limit) list.remove(0)
        report.put(key, list)
    }

    @Synchronized
    fun sample(context: Context, verdict: Verdict, ids: Set<String>? = null, nodes: Int = 0, truncated: Boolean = false) {
        load(context)
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val previous = tracker.state
        val closed = tracker.accept(verdict.state, wall, elapsed, verdict.reason)
        if (closed != null) append("intervals", JSONObject()
            .put("startUtcMs", closed.startUtcMs).put("endUtcMs", closed.endUtcMs)
            .put("durationMs", closed.durationMs).put("endReason", closed.endReason), 200)
        if (previous != verdict.state || report.optString("reason") != verdict.reason) {
            append("transitions", JSONObject().put("utcMs", wall).put("elapsedMs", elapsed)
                .put("state", verdict.state.name).put("reason", verdict.reason), 300)
        }
        if (verdict.state == ScreenState.SHORTS && previous != ScreenState.SHORTS) {
            report.put("openStartUtcMs", wall).put("openStartElapsedMs", elapsed)
        } else if (verdict.state != ScreenState.SHORTS) {
            report.remove("openStartUtcMs"); report.remove("openStartElapsedMs")
        }
        report.put("state", verdict.state.name).put("reason", verdict.reason)
            .put("lastSampleUtcMs", wall).put("lastSampleElapsedMs", elapsed)
        if (ids != null) {
            report.put("lastYoutubeSampleUtcMs", wall).put("visibleYoutubeResourceIds", JSONArray(ids.sorted()))
                .put("visitedYoutubeNodes", nodes).put("truncated", truncated)
        }
        save(context)
    }

    @Synchronized
    fun snapshot(context: Context): JSONObject { load(context); return JSONObject(report.toString()) }

    private fun save(context: Context) {
        val file = AtomicFile(File(context.filesDir, "report.json"))
        val stream = file.startWrite()
        try { stream.write(report.toString(2).toByteArray()); file.finishWrite(stream) }
        catch (e: Exception) { file.failWrite(stream); throw e }
    }
}
