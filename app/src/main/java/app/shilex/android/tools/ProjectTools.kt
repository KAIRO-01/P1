package app.shilex.android.tools

import app.shilex.android.memory.MemoryRules
import app.shilex.android.memory.ProjectApproval
import app.shilex.android.memory.ProjectMemory
import app.shilex.android.memory.WorkMemory
import org.json.JSONObject
import java.time.LocalDate

/** User-controlled local records for workshop, solar, carpenter and client work. */
object ProjectTools {
    fun all(): List<PhoneTool> = listOf(Propose, Confirm, Discard, Search, Review)

    private object Propose : PhoneTool {
        override val name = "project_propose"
        override fun declaration() = Schema.function(name,
            "Propose a new or updated project, worker, task, due date or status. DOES NOT SAVE. Read back the proposed changes and ask the user for a NEW explicit confirmation before project_confirm.",
            Schema.string("project", "Unique job / project name", true),
            Schema.string("worker", "Assigned worker, only if user told you"),
            Schema.string("details", "Description of work / pending task"),
            Schema.string("due", "Expected completion date YYYY-MM-DD, if known"),
            Schema.string("status", "Work status"),
            Schema.string("note", "Reason, instruction or update for history"),
        )
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val name = args.text("project").take(120)
            if (name.isBlank()) return Results.failed("missing_name", "Ask for project name.")
            val due = args.text("due")
            if (due.isNotBlank() && runCatching { LocalDate.parse(due) }.isFailure)
                return Results.failed("bad_date", "Ask for calendar date YYYY-MM-DD, not a guess.")
            if (MemoryRules.looksSecret(args.toString()))
                return Results.failed("secret", "No passwords, PINs, OTPs or card numbers in project memory.")
            ProjectApproval.propose(args)
            return Results.done("NOT SAVED. Ask exactly: Save project " + name +
                "? Worker: " + args.text("worker").ifBlank { "unchanged" } +
                ". Details: " + args.text("details").ifBlank { "unchanged" } +
                ". Due: " + due.ifBlank { "unchanged" } +
                ". Status: " + args.text("status").ifBlank { "unchanged" } +
                ". Wait for their NEXT answer before calling project_confirm.")
        }
    }

    private object Confirm : PhoneTool {
        override val name = "project_confirm"
        override fun declaration() = Schema.function(name,
            "Commit a pending project proposal ONLY AFTER the user replies yes/save in a separate spoken turn. The phone checks actual user speech independently.")
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val message = ProjectApproval.confirm(host.context)
            return if (message.startsWith("Saved project")) Results.done(message)
                else Results.failed("unconfirmed", message)
        }
    }

    private object Discard : PhoneTool {
        override val name = "project_discard"
        override fun declaration() = Schema.function(name,
            "Discard a pending project update when the user says no or asks for a correction.")
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            ProjectApproval.discard()
            return Results.done("Project update discarded; nothing saved.")
        }
    }

    private object Search : PhoneTool {
        override val name = "projects_search"
        override fun declaration() = Schema.function(name,
            "Read verified saved project details, workers, updates and deadlines. Call before answering workshop or solar questions. Never invent values.",
            Schema.string("query", "Client, project, carpenter or topic; empty = recent projects"),
        )
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject {
            val db = ProjectMemory(host.context)
            val projects = db.search(args.text("query"))
            return if (projects.isEmpty()) Results.done("No matching project saved. Ask for the details.")
                else Results.done(projects.joinToString("\n") { p ->
                    p.title + ": worker " + p.worker + ", due " + p.due + ", status " + p.status +
                        ", details " + p.details + ", history " + db.history(p.title).take(3).joinToString(" | ")
                }.take(4300))
        }
    }

    private object Review : PhoneTool {
        override val name = "work_review"
        override fun declaration() = Schema.function(name,
            "Report approved local project memory for daily work review. Check deadlines, people and assignments; never claim an unverified project finished.")
        override suspend fun run(args: JSONObject, host: ToolHost): JSONObject =
            Results.done(WorkMemory(host.context).summary() + "\nOlder projects: " + ProjectMemory(host.context).review())
    }
}
