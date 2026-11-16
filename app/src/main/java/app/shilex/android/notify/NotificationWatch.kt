package app.shilex.android.notify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import app.shilex.android.Diag

/**
 * Keeps the last notifications in memory so "what did I miss?" can be answered. Nothing is
 * stored on disk or logged (the log gets counts only), and nothing leaves the phone until the
 * user asks; then [Triage] sorts them and the model gets only its summary.
 */
class NotificationWatch : NotificationListenerService() {
    override fun onListenerConnected() {
        connected = true
        Diag.i("notifications: listener connected")
        runCatching { activeNotifications?.forEach(::keep) }
    }

    override fun onListenerDisconnected() {
        connected = false
        Diag.w("notifications: listener disconnected")
        runCatching { requestRebind(ComponentName(this, NotificationWatch::class.java)) }
    }

    override fun onDestroy() {
        connected = false
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        keep(sbn)?.let { MessageWatch.posted(this, it) }
    }

    /** Remembers [sbn] and returns it as JARVIS keeps it, or null when it isn't kept. */
    private fun keep(sbn: StatusBarNotification): Seen? {
        val n = sbn.notification ?: return null
        if (sbn.isOngoing || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val extras = n.extras
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val group = extras.getBoolean(NotificationCompat.EXTRA_IS_GROUP_CONVERSATION) ||
            extras.getCharSequence(NotificationCompat.EXTRA_CONVERSATION_TITLE) != null
        // Different social apps use MessagingStyle, expanded text, or multi-line extras.
        // Read only content that Android actually exposed through the notification.
        val messages = runCatching {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES).orEmpty().mapNotNull {
                (it as? Bundle)?.getCharSequence("text")?.toString()?.trim()?.takeIf(String::isNotEmpty)
            }
        }.getOrDefault(emptyList())
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES).orEmpty()
            .map { it.toString().trim() }.filter { it.isNotBlank() }
        val mainText = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        val extracted = when {
            messages.isNotEmpty() -> messages.takeLast(3).joinToString(" | ")
            mainText.isNotBlank() -> mainText
            lines.isNotEmpty() -> lines.takeLast(3).joinToString(" | ")
            else -> ""
        }
        val seen = Seen(
            packageName = sbn.packageName,
            app = label,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty(),
            text = extracted.take(850),
            atMillis = sbn.postTime,
            group = group,
            category = n.category.orEmpty(),
            notificationKey = sbn.key,
        ).let { s ->
            // Only people's own chats can be answered hands-free, never groups.
            if (group) s else s.copy(replyId = Replies.register(sbn.key, s.title.ifBlank { label }, label, sbn.packageName, n, sbn.postTime))
        }
        synchronized(recent) {
            recent.removeAll { it.atMillis < System.currentTimeMillis() - KEEP_MS || it.notificationKey == sbn.key }
            recent.add(seen)
            while (recent.size > MAX_KEPT) recent.removeAt(0)
        }
        return seen
    }

    companion object {
        @Volatile var connected: Boolean = false
            private set
        private val recent = mutableListOf<Seen>()
        private const val MAX_KEPT = 60
        private const val KEEP_MS = 12 * 60 * 60 * 1000L

        /** The kept notifications since [sinceMillis], oldest first. */
        fun since(sinceMillis: Long): List<Seen> = synchronized(recent) { recent.filter { it.atMillis >= sinceMillis } }

        fun clear() = synchronized(recent) { recent.clear() }

        /** Whether the user gave JARVIS notification access in Android settings. */
        fun allowed(context: Context): Boolean {
            val me = ComponentName(context, NotificationWatch::class.java).flattenToString()
            return Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().split(':').contains(me)
        }
    }
}
