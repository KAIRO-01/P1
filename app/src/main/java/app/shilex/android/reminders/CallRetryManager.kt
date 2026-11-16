package app.shilex.android.reminders

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject

/**
 * Reminder call keeps retrying until Answer or Decline. One retry is scheduled
 * 60 seconds after each alert. Android can delay exact alarms under battery
 * restrictions; it is not a cellular telephone call.
 */
class CallRetryManager(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences("kairo_call_retries_v1", Context.MODE_PRIVATE)

    fun start(id: Long, text: String) {
        prefs.edit().putString(id.toString(), JSONObject().put("text", text).put("next", 0L).toString()).commit()
        schedule(id, System.currentTimeMillis() + MINUTE)
    }

    fun stop(id: Long) {
        prefs.edit().remove(id.toString()).commit()
        alarms.cancel(intent(id))
    }

    fun stopAll() {
        prefs.all.keys.mapNotNull(String::toLongOrNull).forEach { Reminders(context).stopCalling(it) }
    }

    fun retry(id: Long) {
        val record = prefs.getString(id.toString(), null) ?: return
        val text = runCatching { JSONObject(record).optString("text") }.getOrDefault("")
        if (text.isBlank()) {
            stop(id)
            return
        }
        Reminders(context).showRetry(id, text)
        if (prefs.contains(id.toString())) schedule(id, System.currentTimeMillis() + MINUTE)
    }

    fun restoreAfterBoot() {
        val now = System.currentTimeMillis()
        prefs.all.keys.mapNotNull(String::toLongOrNull).forEach { id ->
            val saved = runCatching {
                JSONObject(prefs.getString(id.toString(), null) ?: "").optLong("next")
            }.getOrDefault(0L)
            schedule(id, if (saved > now) saved else now + 10_000L)
        }
    }

    fun hasActive(): Boolean = prefs.all.isNotEmpty()

    private fun schedule(id: Long, at: Long) {
        val raw = prefs.getString(id.toString(), null) ?: return
        val updated = runCatching { JSONObject(raw).put("next", at).toString() }.getOrDefault(raw)
        prefs.edit().putString(id.toString(), updated).commit()
        val alarm = intent(id)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarm)
        }
    }

    private fun intent(id: Long) = PendingIntent.getBroadcast(
        context, id.toInt() xor 0x42CA,
        Intent(context, RetryReceiver::class.java).setAction("app.shilex.android.RETRY_CALL").putExtra("id", id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    class RetryReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == "app.shilex.android.RETRY_CALL") {
                CallRetryManager(context).retry(intent.getLongExtra("id", -1L))
            }
        }
    }

    companion object { private const val MINUTE = 60_000L }
}
