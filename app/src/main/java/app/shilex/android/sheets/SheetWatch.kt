package app.shilex.android.sheets

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.shilex.android.MainActivity
import app.shilex.android.R
import app.shilex.android.data.Settings
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Optional, read-only background polling. Android controls the schedule; no instant
 * delivery guarantee. Runs ONLY for live link-readable Sheets, never imported CSV.
 */
object SheetWatch {
    private const val JOB_ID = 84036
    private const val PREFS = "kairo_sheet_watch"
    private const val SNAPSHOT = "row_fingerprints"
    private const val SUMMARY = "latest_change"
    private const val CHANGED_AT = "changed_at"
    private const val CHECKED_AT = "checked_at"
    private const val FORMAT_VERSION = "fingerprint_version"

    fun configure(context: Context, enabled: Boolean): Boolean {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        scheduler.cancel(JOB_ID)
        if (enabled) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, SheetWatchService::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPeriodic(30 * 60 * 1000L)
                .setPersisted(true)
                .build()
            if (scheduler.schedule(job) != JobScheduler.RESULT_SUCCESS) return false
        }
        Settings(context).watchSheetChanges = enabled
        return true
    }

    /** Called on the JobService worker thread after a successful live fetch. */
    fun record(context: Context, orders: List<OrderSheet.Order>): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val latest = fingerprints(orders)
        // Switching from one tab to all months must not generate false change alerts.
        val oldJson = if (prefs.getInt(FORMAT_VERSION, 0) == 2) prefs.getString(SNAPSHOT, null) else null
        val previous = runCatching { oldJson?.let(::JSONObject) }.getOrNull()
        val updates = if (previous == null) emptyList() else buildList {
            latest.forEach { (key, value) -> if (!previous.has(key) || previous.optString(key) != value) add(key.substringBefore("#")) }
            val iterator = previous.keys()
            while (iterator.hasNext()) {
                val key = iterator.next()
                if (key !in latest) add(key.substringBefore("#") + " (removed)")
            }
        }
        val now = System.currentTimeMillis()
        val next = JSONObject()
        latest.forEach { (key, value) -> next.put(key, value) }
        val edit = prefs.edit().putInt(FORMAT_VERSION, 2).putString(SNAPSHOT, next.toString()).putLong(CHECKED_AT, now)
        if (updates.isNotEmpty()) {
            val names = updates.distinct().take(6).joinToString("; ")
            edit.putString(SUMMARY, "${updates.size} order records changed: $names.")
                .putLong(CHANGED_AT, now)
        }
        edit.apply()
        return updates.size
    }

    fun lastUpdate(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val checked = prefs.getLong(CHECKED_AT, 0)
        if (checked == 0L) return "No live Sheet check has completed yet. Turn on Sheet change alerts in Settings."
        val checkedText = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a")
            .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(checked))
        val summary = prefs.getString(SUMMARY, "").orEmpty()
        return "Last successful live Sheet check: $checkedText. " +
            if (summary.isBlank()) "No changes recorded after the initial baseline." else summary
    }

    private fun fingerprints(orders: List<OrderSheet.Order>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val counts = mutableMapOf<String, Int>()
        orders.forEach { row ->
            val base = (row.tab.ifBlank { "CSV" } + " / " + row.customer.ifBlank { "Unnamed customer" } + ": " +
                row.mandir.ifBlank { "Mandir" }).take(120)
            val n = counts.getOrDefault(base, 0) + 1
            counts[base] = n
            result["$base#$n"] = sha256(row.toString())
        }
        return result
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}

class SheetWatchService : JobService() {
    private val main = Handler(Looper.getMainLooper())

    override fun onStartJob(params: JobParameters): Boolean {
        Thread({
            try {
                if (Settings(this).watchSheetChanges) {
                    val snapshot = OrderSheet.load(this, OkHttpClient())
                    if (snapshot.live) {
                        val changes = SheetWatch.record(this, snapshot.orders)
                        if (changes > 0) notifyChanges(changes)
                    }
                }
            } catch (_: Exception) {
                // A revoked link, offline device, or CSV fallback must not produce fake alerts.
            } finally {
                main.post { jobFinished(params, false) }
            }
        }, "kairo-sheet-watch").apply { isDaemon = true; start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    private fun notifyChanges(count: Int) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NotificationManager::class.java)
        val channel = "kairo_order_sheet"
        manager.createNotificationChannel(NotificationChannel(channel, "Order sheet changes", NotificationManager.IMPORTANCE_DEFAULT))
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 84036, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("KAIRO: Google Sheet updated")
            .setContentText("$count order record(s) changed. Ask KAIRO: what changed in my sheet?")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "$count order record(s) changed. Open KAIRO and ask for changes or upcoming deliveries."
            ))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(84036, notification) }
    }
}
