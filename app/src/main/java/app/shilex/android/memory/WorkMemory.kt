package app.shilex.android.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.shilex.android.reminders.Reminders
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.json.JSONObject

/** Permanent worker files, with each worker's tasks linked to their customer order. */
class WorkMemory(private val context: Context) :
    SQLiteOpenHelper(context.applicationContext, "kairo_work_v1.db", null, 1) {

    data class Task(
        val id: Long, val worker: String, val order: String, val work: String,
        val due: String, val status: String, val note: String, val reminderId: Long
    )
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE workers (name TEXT PRIMARY KEY COLLATE NOCASE, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE assignments (id INTEGER PRIMARY KEY AUTOINCREMENT, worker TEXT NOT NULL, order_name TEXT NOT NULL, work TEXT NOT NULL, due TEXT NOT NULL DEFAULT '', status TEXT NOT NULL DEFAULT 'Pending', note TEXT NOT NULL DEFAULT '', reminder_id INTEGER NOT NULL DEFAULT 0, updated INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX assignments_worker ON assignments(worker COLLATE NOCASE)")
        db.execSQL("CREATE INDEX assignments_order ON assignments(order_name COLLATE NOCASE)")
        db.execSQL("CREATE TABLE changes (id INTEGER PRIMARY KEY AUTOINCREMENT, assignment_id INTEGER NOT NULL, description TEXT NOT NULL, at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun workers(): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT name FROM workers ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out += c.getString(0)
        }
        return out
    }
    @Synchronized fun tasks(): List<Task> {
        val rows = mutableListOf<Task>()
        readableDatabase.rawQuery(
            "SELECT id, worker, order_name, work, due, status, note, reminder_id FROM assignments ORDER BY updated DESC LIMIT 4000", null
        ).use { c ->
            while (c.moveToNext()) rows += Task(c.getLong(0), c.getString(1), c.getString(2),
                c.getString(3), c.getString(4), c.getString(5), c.getString(6), c.getLong(7))
        }
        return rows
    }
    fun find(query: String): List<Task> {
        val words = query.trim().lowercase().split(Regex("\\s+")).filter { it.length > 1 }
        return tasks().filter { t ->
            words.isEmpty() || words.all { part ->
                (t.worker + " " + t.order + " " + t.work + " " + t.status + " " + t.note)
                    .lowercase().contains(part)
            }
        }.take(30)
    }
    fun summary(): String {
        val open = tasks().filterNot { done(it.status) }
        if (open.isEmpty()) return "No confirmed pending worker assignments yet."
        val today = LocalDate.now()
        val ordered = open.sortedWith(compareBy<Task> {
            val date = runCatching { LocalDate.parse(it.due) }.getOrNull()
            if (date == null) 2 else if (!date.isAfter(today)) 0 else 1
        }.thenBy { it.due })
        return ordered.take(15).joinToString("; ") { t ->
            t.worker + ": " + t.work + " (" + t.order + "), status " + t.status +
                if (t.due.isNotBlank()) ", due " + t.due else ", due date not specified"
        }
    }

    /** One approved batch updates every worker and order atomically. */
    @Synchronized fun commit(proposal: JSONObject): String {
        val list = proposal.getJSONArray("assignments")
        require(list.length() in 1..20) { "1 to 20 assignments required" }
        val before = tasks()
        val db = writableDatabase
        val changed = mutableListOf<Pair<Long, Task?>>()
        db.beginTransaction()
        try {
            for (i in 0 until list.length()) {
                val job = list.getJSONObject(i)
                val worker = job.optString("worker").trim().take(80)
                val order = job.optString("order").trim().take(120).ifBlank { "General work" }
                val work = job.optString("task").trim().take(280)
                require(worker.isNotBlank() && work.isNotBlank()) { "Worker and task required" }
                val taskId = job.optLong("task_id", 0L)
                val old = if (taskId > 0) before.firstOrNull { it.id == taskId }
                    ?: error("Task " + taskId + " not found")
                    else before.firstOrNull { it.order.equals(order, true) && it.work.equals(work, true) }
                val due = job.optString("due").trim().ifBlank { old?.due.orEmpty() }
                if (due.isNotBlank()) LocalDate.parse(due)
                val status = job.optString("status").trim().take(40).ifBlank { old?.status ?: "Pending" }
                val note = job.optString("note").trim().take(900).ifBlank { old?.note.orEmpty() }
                db.insertWithOnConflict("workers", null, ContentValues().apply {
                    put("name", worker); put("updated", System.currentTimeMillis())
                }, SQLiteDatabase.CONFLICT_REPLACE)
                val v = ContentValues().apply {
                    put("worker", worker); put("order_name", order); put("work", work)
                    put("due", due); put("status", status); put("note", note)
                    put("updated", System.currentTimeMillis())
                    put("reminder_id", old?.reminderId ?: 0L)
                }
                val id = if (old != null) {
                    db.update("assignments", v, "id=?", arrayOf(old.id.toString()))
                    old.id
                } else db.insertOrThrow("assignments", null, v)
                db.insertOrThrow("changes", null, ContentValues().apply {
                    put("assignment_id", id)
                    put("description", (worker + " / " + order + " / " + work + " / " + due + " / " + status + " / " + note).take(1400))
                    put("at", System.currentTimeMillis())
                })
                changed += id to old
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        val reminders = Reminders(context)
        for ((id, old) in changed) {
            val fresh = tasks().firstOrNull { it.id == id } ?: continue
            if (old != null && old.worker == fresh.worker && old.work == fresh.work &&
                old.due == fresh.due && old.status == fresh.status) continue
            if (old != null && old.reminderId != 0L) {
                reminders.all().firstOrNull { it.id == old.reminderId }?.let(reminders::cancel)
            }
            val whenDue = runCatching { LocalDate.parse(fresh.due).atTime(LocalTime.of(10, 0)) }.getOrNull()
            val next = if (whenDue != null && whenDue.isAfter(LocalDateTime.now()) && !done(fresh.status)) {
                runCatching {
                    reminders.add("Work follow-up: " + fresh.worker + " — " + fresh.work +
                        " (" + fresh.order + "). Confirm progress.", whenDue)
                }.getOrNull()
            } else null
            db.execSQL("UPDATE assignments SET reminder_id=? WHERE id=?", arrayOf(next?.id ?: 0L, id))
        }
        return changed.size.toString() + " worker assignments saved/updated. " + summary()
    }

    companion object {
        fun done(status: String): Boolean = status.trim().lowercase() in
            setOf("done", "completed", "finished", "complete", "cancelled", "canceled")
    }
}

/** Consent is recognized from the user's next real voice/text turn, not from an AI tool call. */
object WorkApproval {
    private data class Pending(val raw: String, val since: Long)
    private var waiting: Pending? = null
    @Synchronized fun offer(data: JSONObject) {
        waiting = Pending(data.toString(), System.currentTimeMillis())
    }
    @Synchronized fun discard(): Boolean {
        val had = waiting != null
        waiting = null
        return had
    }
    @Synchronized fun pending(): Boolean = waiting != null
    @Synchronized fun preview(): String? {
        val p = waiting ?: return null
        return runCatching {
            val list = JSONObject(p.raw).getJSONArray("assignments")
            (0 until list.length()).joinToString("; ") { i ->
                val one = list.getJSONObject(i)
                one.optString("worker") + ": " + one.optString("task") +
                    " (" + one.optString("order") + ")" +
                    if (one.optString("due").isNotBlank()) ", due " + one.optString("due") else ""
            }
        }.getOrNull()
    }
    /** The user can explicitly confirm by tapping when audio transcription misses their Yes. */
    @Synchronized fun approveTap(context: Context): String {
        val p = waiting ?: return "No worker tasks pending approval."
        if (System.currentTimeMillis() - p.since > 180_000L) {
            waiting = null
            return "Approval expired. Ask KAIRO to propose the tasks again."
        }
        waiting = null
        return runCatching { WorkMemory(context).commit(JSONObject(p.raw)) }
            .getOrElse { "Could not save: " + (it.message ?: "unknown error") }
    }


    @Synchronized fun spoken(context: Context, words: String): String? {
        val p = waiting ?: return null
        if (System.currentTimeMillis() - p.since > 180_000L) {
            waiting = null
            return "Pending assignment approval timed out. Ask again to save."
        }
        val text = words.lowercase().trim().trimEnd('.', '!', '?', '।').trim()
        val no = Regex("^(no|nahi|nahin|na|cancel|reject|mat karo|galat hai|wrong|नहीं|मत)(\\b|$)")
        if (no.containsMatchIn(text)) {
            waiting = null
            return "User rejected the worker changes. Nothing saved. Ask for correction."
        }
        val yes = Regex("^(yes|yeah|yep|haan|han|haa|ji|ok|okay|theek hai|thik hai|save|save karo|save kar do|kar do|हाँ|हां|ठीक है)(\\b|$)", RegexOption.IGNORE_CASE)
        if (!yes.containsMatchIn(text) || text.length > 65) return null
        waiting = null
        return runCatching { "User confirmed. " + WorkMemory(context).commit(JSONObject(p.raw)) }
            .getOrElse { "Saving failed: " + (it.message ?: "unknown error") }
    }
}
