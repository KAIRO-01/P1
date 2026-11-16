package app.shilex.android.tools

import app.shilex.android.memory.MemoryRules
import app.shilex.android.memory.ProjectApproval
import app.shilex.android.memory.WorkApproval
import app.shilex.android.memory.WorkMemory
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Single voice confirmation for a batch of assignments, one file per worker. */
object WorkTools {
    fun all(): List<PhoneTool> = listOf(Propose, Search, Board, Discard)

    private object Propose : PhoneTool {
        override val name = "work_propose"
        override fun declaration(): JSONObject {
            val fields = JSONObject()
                .put("worker", JSONObject().put("type", "string").put("description", "Person assigned; e.g. Anjali, Vijay, Mangat"))
                .put("order", JSONObject().put("type", "string").put("description", "Client or order linked to this job; same order name for all workers on same order"))
                .put("task", JSONObject().put("type", "string").put("description", "Specific responsibility: order taking, manufacturing, polishing, etc."))
                .put("task_id", JSONObject().put("type", "integer").put("description", "Existing task number from worker_tasks when editing or reassigning"))
                .put("due", JSONObject().put("type", "string").put("description", "YYYY-MM-DD date, if actually known"))
                .put("due_in_days", JSONObject().put("type", "integer").put("description", "Relative deadline e.g. 3 days, only if user explicitly said"))
                .put("status", JSONObject().put("type", "string").put("description", "Pending, In progress, Done, etc."))
                .put("note", JSONObject().put("type", "string").put("description", "Extra worker or order information"))
            val one = JSONObject().put("type", "object").put("properties", fields)
                .put("required", JSONArray(listOf("worker", "task")))
            val values = JSONObject().put("type", "array").put("items", one)
                .put("description", "1 to 20 assignments. Group all described workers into ONE confirmation.")
            return Schema.function(
                name,
                "Extract important worker and customer-order tasks discussed in conversation. Stage MULTIPLE worker changes together; NO DATABASE WRITE yet. Ask just ONE yes/no. If changing an existing task, first call worker_tasks and use its task_id.",
                Schema.Param("assignments", values, true),
            )
        }
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val input = args.optJSONArray("assignments") ?: return Results.failed("missing", "Tell me the workers and their tasks.")
            if (input.length() !in 1..20) return Results.failed("limit", "Please provide between 1 and 20 tasks.")
            val prepared = JSONArray()
            val summary = mutableListOf<String>()
            for (i in 0 until input.length()) {
                val raw = input.optJSONObject(i) ?: return Results.failed("invalid", "One assignment is not an object.")
                val worker = raw.optString("worker").trim().take(80)
                val work = raw.optString("task").trim().take(280)
                if (worker.isBlank() || work.isBlank()) return Results.failed("incomplete", "Please tell me the worker name and task.")
                var date = raw.optString("due").trim()
                if (date.isBlank() && raw.optInt("due_in_days", 0) > 0) {
                    val count = raw.optInt("due_in_days")
                    if (count !in 1..365) return Results.failed("date", "Ask for a valid deadline.")
                    date = LocalDate.now().plusDays(count.toLong()).toString()
                }
                if (date.isNotBlank() && runCatching { LocalDate.parse(date) }.isFailure)
                    return Results.failed("date", "Deadline must be a real YYYY-MM-DD date.")
                val order = raw.optString("order").trim().take(120).ifBlank { "General work" }
                val status = raw.optString("status").trim().take(40)
                val note = raw.optString("note").trim().take(900)
                if (MemoryRules.looksSecret(worker + " " + work + " " + order + " " + note))
                    return Results.failed("secret", "Sensitive passwords or card numbers cannot be saved.")
                val v = JSONObject().put("worker", worker).put("task", work)
                    .put("order", order).put("due", date).put("status", status)
                    .put("note", note)
                if (raw.optLong("task_id", 0) > 0) v.put("task_id", raw.optLong("task_id"))
                prepared.put(v)
                summary += worker + " → " + work + " (" + order + ")" +
                    if (date.isNotBlank()) " due " + date else ""
            }
            ProjectApproval.discard()
            WorkApproval.offer(JSONObject().put("assignments", prepared))
            return Results.done("STAGED BUT NOT SAVED: " + summary.joinToString("; ") +
                ". Ask ONCE: 'Kya in sab tasks ko save kar doon?' WAIT for the user to say yes, then the phone saves automatically. Do not call another tool to confirm.")
        }
    }

    private object Search : PhoneTool {
        override val name = "worker_tasks"
        override fun declaration() = Schema.function(name,
            "Lookup saved workers, tasks and customer orders. Call FIRST before editing someone's assignment, and use returned task ID for reassignment.",
            Schema.string("query", "Worker name, client name, order, or task; blank shows all recent work"),
        )
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val db = WorkMemory(host.context)
            val tasks = db.find(args.text("query"))
            return if (tasks.isEmpty()) Results.done("No approved worker tasks found. Ask user to describe and approve a new assignment.")
                else Results.done(tasks.joinToString("\n") { t ->
                    "Task #"+t.id + ": "+t.worker + " — " + t.work + "; order " + t.order +
                        "; due " + t.due.ifBlank { "unknown" } + "; status " + t.status + "; note " + t.note
                }.take(5500))
        }
    }

    private object Board : PhoneTool {
        override val name = "work_board"
        override fun declaration() = Schema.function(name,
            "Read today's pending work and each worker's assignments from approved local memory. Use for daily review calls and planning next tasks.")
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject =
            Results.done(WorkMemory(host.context).summary())
    }

    private object Discard : PhoneTool {
        override val name = "work_discard"
        override fun declaration() = Schema.function(name,
            "Discard staged worker changes when user rejects them or requests corrections.")
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val cancelled = WorkApproval.discard()
            return Results.done(if (cancelled) "Proposed tasks discarded, nothing saved."
                else "No pending worker changes.")
        }
    }
}
