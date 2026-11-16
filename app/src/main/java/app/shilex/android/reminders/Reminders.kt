package app.shilex.android.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import app.shilex.android.Branding
import app.shilex.android.Diag
import app.shilex.android.R
import java.time.LocalDateTime
import java.time.ZoneId

/** Free on-device reminders: a call-like alert at the time, kept across restarts. */
class Reminders(private val context: Context) {
    private val prefs = context.getSharedPreferences("reminders", Context.MODE_PRIVATE)
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun all(): List<Reminder> = ReminderRules.decode(prefs.getString(KEY, null))

    fun add(text: String, at: LocalDateTime): Reminder {
        val reminder = Reminder(System.currentTimeMillis(), text, at)
        save(all() + reminder)
        schedule(reminder)
        return reminder
    }

    fun cancel(reminder: Reminder) {
        alarms.cancel(pending(reminder.id))
        save(all().filterNot { it.id == reminder.id })
    }

    /** After a reboot alarms are gone; set them again (overdue ones fire now). */
    fun rescheduleAll() = all().forEach(::schedule)

    /** True if reminders will ring on time; otherwise Android may delay them a few minutes. */
    fun exact(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun schedule(reminder: Reminder) {
        val millis = reminder.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (exact()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(reminder.id))
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(reminder.id))
        }
    }

    internal fun fire(id: Long) {
        val reminder = all().firstOrNull { it.id == id } ?: return
        save(all().filterNot { it.id == id })
        val manager = context.getSystemService(NotificationManager::class.java)
        // A NEW channel is required: Android doesn't allow apps to change the sound or
        // importance of the existing "reminders" channel after the first creation.
        val ring = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
        val channel = NotificationChannel(
            CALL_CHANNEL, "KAIRO reminder calls", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Free call-style reminders with ringtone and Answer / Dismiss"
            enableVibration(true)
            setSound(
                ring,
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
        }
        manager.createNotificationChannel(channel)

        val activityIntent = Intent(context, ReminderCallActivity::class.java)
            .putExtra(ReminderCallActivity.EXTRA_ID, id)
            .putExtra(ReminderCallActivity.EXTRA_TEXT, reminder.text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val showCall = PendingIntent.getActivity(
            context, id.toInt(), activityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val answer = PendingIntent.getActivity(
            context, id.toInt() xor 0x51A0,
            Intent(activityIntent).putExtra(ReminderCallActivity.EXTRA_AUTO_ANSWER, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val dismiss = PendingIntent.getBroadcast(
            context, id.toInt(), Intent(context, ReminderActionReceiver::class.java)
                .setAction(ACTION_DISMISS).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CALL_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${Branding.ASSISTANT_NAME} Calling")
            .setContentText(reminder.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reminder.text))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setContentIntent(showCall)
            .setFullScreenIntent(showCall, true)
            .addAction(0, "Answer", answer)
            .addAction(0, "Dismiss", dismiss)
            .setDeleteIntent(dismiss)
            .setAutoCancel(false)
            .build()
        runCatching { manager.notify(id.toInt(), notification) }
            .onFailure { Diag.w("reminder: call notification blocked") }
        // Android 14+ may downgrade the full-screen intent to a heads-up alert.
        // In that case the user can still tap Answer on the notification.
        Diag.i("reminder: call alert posted")
    }

    private fun save(list: List<Reminder>) = prefs.edit().putString(KEY, ReminderRules.encode(list)).apply()

    private fun pending(id: Long): PendingIntent = PendingIntent.getBroadcast(
        context,
        id.toInt(),
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private companion object {
        const val KEY = "list"
        const val CALL_CHANNEL = "kairo_reminder_calls_v1"
        const val ACTION_DISMISS = "app.shilex.android.reminder.DISMISS"
        const val EXTRA_ID = "id"
    }

    class ReminderActionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_DISMISS) return
            val id = intent.getLongExtra(EXTRA_ID, -1L)
            if (id != -1L) {
                context.getSystemService(NotificationManager::class.java).cancel(id.toInt())
            }
        }
    }

    class ReminderReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Reminders(context).fire(intent.getLongExtra(EXTRA_ID, -1))
        }
    }

    class BootReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders(context).rescheduleAll()
        }
    }
}
