package app.shilex.android.memory

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.shilex.android.notify.VoiceConsent
import org.json.JSONObject
import org.json.JSONArray
import java.time.LocalDate

/** Durable local project memory. Does not save unapproved AI suggestions. */
class ProjectMemory(context: Context) : SQLiteOpenHelper(context.applicationContext, "kairo_projects_v2.db", null, 1) {
    data class Project(val title: String, val worker: String, val details: String, val due: String, val status: String, val updated: Long)
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE projects (title TEXT PRIMARY KEY COLLATE NOCASE, worker TEXT NOT NULL, details TEXT NOT NULL, due TEXT NOT NULL, status TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE history (id INTEGER PRIMARY KEY AUTOINCREMENT, project TEXT NOT NULL, note TEXT NOT NULL, at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    @Synchronized fun all(): List<Project> {
        val out = mutableListOf<Project>()
        readableDatabase.rawQuery("SELECT title, worker, details, due, status, updated FROM projects ORDER BY updated DESC LIMIT 3000", null).use { c ->
            while (c.moveToNext()) out += Project(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getLong(5))
        }
        return out
    }
    fun search(query: String): List<Project> {
        val keys = query.trim().lowercase().split(' ').filter { it.length > 1 }
        return all().filter { p ->
            keys.isEmpty() || keys.any { k ->
                (p.title + " " + p.worker + " " + p.details + " " + p.status).lowercase().contains(k)
            }
        }.take(12)
    }
    @Synchronized fun save(data: JSONObject): Project {
        val title = data.optString("project").trim().take(120)
        require(title.isNotEmpty()) { "Missing project" }
        val existing = all().firstOrNull { it.title.equals(title, true) }
        fun field(key: String, old: String) = data.optString(key).trim().take(1200).ifBlank { old }
        val now = System.currentTimeMillis()
        val result = Project(title, field("worker", existing?.worker.orEmpty()),
            field("details", existing?.details.orEmpty()), field("due", existing?.due.orEmpty()),
            field("status", existing?.status ?: "In progress"), now)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val v = ContentValues().apply {
                put("title", result.title); put("worker", result.worker); put("details", result.details)
                put("due", result.due); put("status", result.status); put("updated", now)
            }
            db.insertWithOnConflict("projects", null, v, SQLiteDatabase.CONFLICT_REPLACE)
            val h = ContentValues().apply {
                put("project", result.title)
                put("note", data.optString("note").trim().ifBlank { data.toString() }.take(1600))
                put("at", now)
            }
            db.insertOrThrow("history", null, h)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return result
    }
    fun history(title: String, limit: Int = 8): List<String> {
        val out = mutableListOf<String>()
        readableDatabase.rawQuery("SELECT note FROM history WHERE project=? COLLATE NOCASE ORDER BY at DESC LIMIT " + limit.coerceIn(1, 2000), arrayOf(title)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }
    /** User-initiated, human-readable backup. Contains private business data. */
    fun exportJson(): String {
        val records = JSONArray()
        all().forEach { p ->
            records.put(JSONObject()
                .put("project", p.title).put("worker", p.worker).put("details", p.details)
                .put("due", p.due).put("status", p.status)
                .put("history", JSONArray(history(p.title, 2000))))
        }
        return JSONObject().put("format", "KAIRO_PROJECTS_V2")
            .put("exportedAt", System.currentTimeMillis()).put("projects", records).toString(2)
    }

    /** Import is called only after an explicit on-screen user confirmation. */
    @Synchronized fun importJson(content: String): Int {
        require(content.length <= 2_000_000) { "Backup file exceeds 2 MB" }
        val root = JSONObject(content)
        require(root.optString("format") == "KAIRO_PROJECTS_V2") { "Not a KAIRO Project Memory backup" }
        val list = root.getJSONArray("projects")
        require(list.length() <= 3000) { "Too many projects in backup" }
        val entries = (0 until list.length()).map { i ->
            val p = list.getJSONObject(i)
            require(p.optString("project").isNotBlank() && p.optString("project").length <= 120)
            val due = p.optString("due")
            if (due.isNotBlank()) require(runCatching { LocalDate.parse(due) }.isSuccess) { "Invalid due date" }
            require(!MemoryRules.looksSecret(p.toString())) { "Backup contains prohibited credentials" }
            require((p.optJSONArray("history")?.length() ?: 0) <= 2000) { "Too much history" }
            p
        }
        entries.forEach { p ->
            val title = p.getString("project")
            val historyArray = p.optJSONArray("history") ?: JSONArray()
            val old = (0 until historyArray.length()).map { historyArray.optString(it) }.filter { it.isNotBlank() }
            val already = history(title, 2000).toSet()
            val restore = JSONObject(p.toString()).put("note", "Restored from user-approved backup")
            save(restore)
            val db = writableDatabase
            db.beginTransaction()
            try {
                old.asReversed().filterNot { it in already }.forEach { note ->
                    val h = ContentValues().apply {
                        put("project", title); put("note", note.take(1600)); put("at", System.currentTimeMillis())
                    }
                    db.insertOrThrow("history", null, h)
                }
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        return entries.size
    }

    fun review(): String {
        val today = LocalDate.now()
        val open = all().filter { it.status.lowercase() !in setOf("done", "completed", "finished") }
        if (open.isEmpty()) return "No unfinished projects saved. Ask what work to review today."
        val urgent = open.filter { p -> runCatching { !LocalDate.parse(p.due).isAfter(today.plusDays(1)) }.getOrDefault(false) }
        return "Saved work status (verify with the user): " +
            (urgent + open).distinctBy { it.title.lowercase() }.take(8).joinToString("; ") { p ->
                p.title + ", " + p.status +
                    (if (p.worker.isNotBlank()) ", worker " + p.worker else "") +
                    (if (p.due.isNotBlank()) ", due " + p.due else "") +
                    (if (p.details.isNotBlank()) ", " + p.details.take(90) else "")
            }
    }
}

/** Proposal held in memory; independent consent is required after the proposal. */
object ProjectApproval {
    private data class Pending(val json: String, val at: Long)
    private var pending: Pending? = null
    private var words = ""
    @Synchronized fun propose(data: JSONObject) {
        pending = Pending(data.toString(), System.currentTimeMillis())
        words = ""
    }
    @Synchronized fun userSpoke(text: String) {
        if (pending != null && text.isNotBlank()) words = (words + " " + text).takeLast(350)
    }
    /** Real user confirmation is applied directly, without relying on another AI tool call. */
    @Synchronized fun onAnswer(context: Context, spoken: String): String? {
        if (pending == null) return null
        val text = spoken.lowercase().trim().trimEnd('.', '!', '?', '।').trim()
        if (Regex("^(no|nahi|nahin|cancel|reject|wrong|galat|नहीं)(\\b|$)").containsMatchIn(text)) {
            discard()
            return "User rejected project update; nothing saved. Ask for correction."
        }
        val yes = Regex("^(yes|yeah|yep|haan|han|haa|ji|ok|okay|save|theek hai|thik hai|हाँ|हां|ठीक है)(\\b|$)")
        if (!yes.containsMatchIn(text) || text.length > 65) return null
        words = text
        return confirm(context)
    }
    @Synchronized fun discard() { pending = null; words = "" }
    @Synchronized fun confirm(context: Context): String {
        val candidate = pending ?: return "No project change is waiting for confirmation."
        if (System.currentTimeMillis() - candidate.at > 180_000) {
            discard()
            return "Confirmation timed out. Ask to save the project again."
        }
        if (!VoiceConsent.confirms(words, "", "")) return "Not saved. The user must explicitly say yes, save it, AFTER the proposal."
        discard()
        val saved = ProjectMemory(context).save(JSONObject(candidate.json))
        return "Saved project " + saved.title + ". Worker: " + saved.worker +
            ", due: " + saved.due + ", status: " + saved.status + "."
    }
}
