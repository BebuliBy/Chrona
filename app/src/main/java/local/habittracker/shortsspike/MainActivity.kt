package local.habittracker.shortsspike

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private val refresh = object : Runnable {
        override fun run() { render(); handler.postDelayed(this, 1000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 64, 24, 40) }
        layout.addView(TextView(this).apply { text = "Shorts — локальная проверка"; textSize = 24f })
        layout.addView(TextView(this).apply {
            text = "Эксперимент, а не готовый трекер. Наблюдаем только структурные ID интерфейса YouTube и времена переходов. Без текстов видео, чатов, клавиатуры, скриншотов и сети. Гипотеза распознавания пока не проверена. Разрешение можно отозвать в любой момент."
            textSize = 16f
        })
        fun button(label: String, action: () -> Unit) {
            layout.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("Согласен на локальный тест → настройки службы") {
            AlertDialog.Builder(this).setTitle("Только YouTube, только локально")
                .setMessage("Разрешить прототипу локально сохранять структурные ID элементов YouTube и интервалы? Тексты и другие приложения не исследуются. Затем включите службу «Shorts — локальная проверка» самостоятельно в настройках Android.")
                .setPositiveButton("Согласен") { _, _ ->
                    SpikeStore.setAllowed(this, true)
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.setNegativeButton("Отмена", null).show()
        }
        button("Открыть YouTube") {
            val intent = packageManager.getLaunchIntentForPackage(SpikeStore.YOUTUBE)
            if (intent != null) startActivity(intent)
            else AlertDialog.Builder(this).setMessage("Официальный YouTube не найден.").setPositiveButton("OK", null).show()
        }
        button("Остановить локальное наблюдение") { SpikeStore.setAllowed(this, false); render() }
        status = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }

    override fun onResume() { super.onResume(); handler.post(refresh) }
    override fun onPause() { handler.removeCallbacks(refresh); super.onPause() }

    private fun render() {
        val manager = getSystemService(AccessibilityManager::class.java)
        val enabled = manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
        val data = SpikeStore.snapshot(this)
        fun time(value: Long): String = if (value == 0L) "—" else
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(value))
        val intervals = data.optJSONArray("intervals")
        val transitions = data.optJSONArray("transitions")
        status.text = buildString {
            append("Согласие: ${if (SpikeStore.allowed(this@MainActivity)) "да" else "нет / пауза"}\n")
            append("Служба в Android: ${if (enabled) "включена" else "ВЫКЛЮЧЕНА"}\n")
            append("Последнее состояние: ${data.optString("state", "UNKNOWN")}\n")
            append("Причина: ${data.optString("reason")}\n")
            append("Последний замер: ${time(data.optLong("lastSampleUtcMs"))}\n")
            append("YouTube замер: ${time(data.optLong("lastYoutubeSampleUtcMs"))}\n")
            append("Это эвристика. Отсутствие маркера пока означает UNKNOWN, а не обычное видео.\n\n")
            append("Закрытые интервалы Shorts:\n")
            if (intervals == null || intervals.length() == 0) append("Пока нет\n")
            else for (i in maxOf(0, intervals.length() - 10) until intervals.length()) {
                val item = intervals.getJSONObject(i)
                append("${time(item.getLong("startUtcMs"))}–${time(item.getLong("endUtcMs"))}: ${item.getLong("durationMs") / 1000.0} с\n")
            }
            append("\nПоследние переходы:\n")
            if (transitions != null) for (i in maxOf(0, transitions.length() - 8) until transitions.length()) {
                val item = transitions.getJSONObject(i)
                append("${time(item.getLong("utcMs"))}: ${item.getString("state")} / ${item.getString("reason")}\n")
            }
            append("\nСтруктурные ID последнего YouTube-окна (без текстов):\n")
            val ids = data.optJSONArray("visibleYoutubeResourceIds")
            if (ids != null) for (i in 0 until ids.length()) append(ids.getString(i) + "\n")
        }
    }
}
