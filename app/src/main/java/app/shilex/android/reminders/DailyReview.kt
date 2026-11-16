package app.shilex.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.time.LocalDateTime
import java.time.ZoneId

/** One opt-in reminder call per day. No server and no account required. */
class DailyReview(private val context: Context) {
    private val prefs = context.getSharedPreferences("kairo_daily_review_v2", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    val hour: Int get() = prefs.getInt("hour", 9)
    val minute: Int get() = prefs.getInt("minute", 0)
    fun timeLabel(): String = "%02d:%02d".format(hour, minute)

    fun enable(h: Int, m: Int) {
        require(h in 0..23 && m in 0..59)
        prefs.edit().putInt("hour", h).putInt("minute", m).putBoolean("enabled", true).apply()
        scheduleNext()
    }

    fun disable() {
        prefs.edit().putBoolean("enabled", false).apply()
        alarms.cancel(pending())
    }

    /** AlarmManager alarms disappear after reboot, so the boot receiver calls this. */
    fun scheduleNext() {
        alarms.cancel(pending())
        if (!enabled) return
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(hour, minute)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending())
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending())
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending())
        }
    }

    private fun pending() = PendingIntent.getBroadcast(
        context, 8501, Intent(context, DailyReviewReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    class DailyReviewReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val review = DailyReview(context)
            if (!review.enabled) return
            // Keep the daily schedule even if showing a call fails once.
            review.scheduleNext()
            Reminders(context).callNow("Daily work review. Let's discuss today's pending projects and worker assignments.")
        }
    }
}
