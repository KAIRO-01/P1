package app.shilex.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.material3.TextButton
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import app.shilex.android.reminders.DailyReview
import app.shilex.android.memory.ProjectMemory
import app.shilex.android.memory.WorkMemory
import app.shilex.android.memory.WorkApproval
import app.shilex.android.reminders.CallRetryManager
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.shilex.android.reminders.Reminders
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.shilex.android.memory.MemoryRules
import app.shilex.android.memory.MemoryStore
import app.shilex.android.wake.WakeWordModel
import org.json.JSONObject

private class Command(val title: String, val examples: String)

private val LOCAL = listOf(
    Command("Volume", "\"Volume 30 percent kar do\" · \"volume badhao\" · \"phone mute karo\""),
    Command("Music", "\"Music pause kar do\" · \"agla gaana\" · \"resume\""),
    Command("Flashlight", "\"Flashlight on karo\" · \"torch band karo\""),
    Command("Open apps", "\"YouTube kholo\" · \"open Calendar\" · \"Camera chalu karo\""),
    Command("Time, date, battery", "\"Kitne baje hai?\" · \"aaj ki date\" · \"kitni battery hai\""),
    Command("Timers and alarms", "\"5 minute ka timer\" · \"subah 6:30 ka alarm\""),
    Command("Stop", "\"Bas\" · \"stop\" · \"never mind\""),
)

private val TASKS = listOf(
    Command(
        "WhatsApp message",
        "\"WhatsApp Rahul ko bolo main 7 baje aaunga\". Asks if several Rahuls match, opens the chat with the message; " +
            "you check it and tap Send. With phone control on, it only says \"sent\" once the message shows in the chat.",
    ),
    Command(
        "Movie tickets",
        "\"Book 4 tickets for Pushpa tomorrow evening\". Opens your ticket app (BookMyShow, District or Paytm) or a web " +
            "search; you pick the show and seats and pay there. It never pays or books for you.",
    ),
    Command("Calls", "\"Call Mom\". Opens the dialer with the number; you tap Call."),
)

private val CLOUD = listOf(
    Command("Songs", "\"Play Kesariya on YouTube Music\""),
    Command("Your screen", "\"What's on my screen?\" · \"reply to this\" (needs phone control)"),
    Command("Questions", "Weather, facts, explanations, anything else"),
    Command("Free call-style reminders", "\"Remind me to call the bank at 5\". KAIRO rings like a call; tap Answer to hear the reminder offline."),
)

/** What can be done: on the phone, with the user's OK, or with Gemini. */
@Composable
fun AutomationsScreen() {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.padding(top = 12.dp)) {
            Text("Automations", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Say \"${WakeWordModel.PHRASE}\", then the command. English, Hindi and Hinglish.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Routines()
        DailyReviewSetup()
        WorkerFiles()
        ProjectMemorySummary()
        ReminderPreview()
        Group("On this phone", "Works without internet and doesn't use your Gemini key.", Accent, "Local", LOCAL)
        Group("Tasks with your OK", "Gemini plans them; you finish anything that sends, calls or pays.", Warning, "Needs you", TASKS)
        Group("With Gemini", "Needs internet; uses your Gemini key.", Warning, "Gemini", CLOUD)
        Spacer(Modifier.height(8.dp))
    }
}

/** The user's routines: say the name to run them; made by voice ("make a routine called …"). */
@Composable
private fun Routines() {
    val context = LocalContext.current
    val store = remember { MemoryStore(context) }
    var routines by remember { mutableStateOf(store.routines()) }
    LifecycleResumeEffect(Unit) {
        routines = store.routines()
        onPauseOrDispose { }
    }
    Section(null) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Routines", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Text(
            "Say a routine's name to run its steps. Make one by voice: \"make a routine called good night: alarm at 6:30, what's on tomorrow\".",
            style = MaterialTheme.typography.bodySmall,
            color = Muted,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
        routines.forEach { r ->
            InfoRow(r.name, r.steps.joinToString(" · ")) {
                if (r !== MemoryRules.GOOD_MORNING) {
                    TextButton(onClick = { store.deleteRoutine(r.name).also { routines = store.routines() } }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun Group(title: String, detail: String, color: Color, badge: String, commands: List<Command>) {
    Section(null) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Pill(badge, color)
        }
        Text(detail, style = MaterialTheme.typography.bodySmall, color = Muted, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        commands.forEach { InfoRow(it.title, it.examples) }
    }
}

/** One-tap, offline end-to-end test of the Answer-to-speak call reminder. */
@Composable
private fun ReminderPreview() {
    val context = LocalContext.current
    val reminders = remember(context) { Reminders(context) }
    var note by remember { mutableStateOf("") }

    fun testIfAllowed() {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) {
            note = "KAIRO app notifications are switched off. Open KAIRO notification settings below."
            return
        }
        reminders.testCall()
        note = "Test reminder triggered. If the call screen does not open, check the KAIRO Calling notification and tap Answer."
    }

    val requestNotifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) testIfAllowed()
        else note = "Allow KAIRO app notifications (not Device & app notification access). Open settings below."
    }

    Section(null) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("KAIRO reminder calls", style = MaterialTheme.typography.titleLarge)
            Text(
                "Free local ringtone. After Answer, KAIRO uses Gemini for a two-way AI conversation when connected; without it, the reminder is read offline. No SIM call.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Button(onClick = {
                val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                if (!permitted) {
                    // Ask Android directly. Device & app notification access is a separate permission.
                    requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    testIfAllowed()
                }
            }) { Text("Test reminder call") }
            OutlinedButton(onClick = {
                CallRetryManager(context).stopAll()
                note = "All pending missed-call retries stopped."
            }) { Text("Stop all missed-call retries") }
            OutlinedButton(onClick = {
                // Some manufacturers hide the runtime permission prompt after a denial.
                // This opens the actual app notification page, not notification-listener access.
                val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }.onFailure {
                    note = "Open Android Settings > Apps > KAIRO > Notifications, then allow notifications."
                }
            }) { Text("Open KAIRO notification settings") }
            if (!reminders.exact()) {
                Text(
                    "For accurate scheduled reminders, allow Alarms & reminders access in Android Settings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted,
                )
            }
            if (note.isNotEmpty()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** User chooses the time before any automatic daily review call is created. */
@Composable
private fun DailyReviewSetup() {
    val context = LocalContext.current
    val review = remember(context) { DailyReview(context) }
    var time by remember { mutableStateOf(review.timeLabel()) }
    var active by remember { mutableStateOf(review.enabled) }
    var note by remember { mutableStateOf("") }
    Section(null) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Daily AI work-review call", style = MaterialTheme.typography.titleLarge)
            Text(
                "Opt-in. Select a daily time; KAIRO calls with saved pending projects. Answer to discuss work using two-way AI.",
                style = MaterialTheme.typography.bodySmall,
                color = Muted,
            )
            Text(if (active) "Enabled at " + review.timeLabel() else "Daily calls OFF until you select and save a time",
                color = if (active) Accent else Muted)
            OutlinedTextField(
                value = time,
                onValueChange = { time = it.take(5) },
                label = { Text("Daily time (24-hour HH:mm)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    val parts = time.trim().split(":")
                    val hour = parts.getOrNull(0)?.toIntOrNull()
                    val minute = parts.getOrNull(1)?.toIntOrNull()
                    if (parts.size != 2 || hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
                        note = "Enter time like 09:00 or 17:30."
                    } else {
                        review.enable(hour, minute)
                        active = true
                        note = "Daily review scheduled at " + review.timeLabel() +
                            ". Keep KAIRO notifications enabled. Exact timing may require Alarms & reminders access."
                    }
                }) { Text("Save daily time") }
                if (active) {
                    OutlinedButton(onClick = {
                        review.disable()
                        active = false
                        note = "Daily review calls disabled."
                    }) { Text("Turn off") }
                }
            }
            if (note.isNotBlank()) Text(note, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProjectMemorySummary() {
    val context = LocalContext.current
    val store = remember(context) { ProjectMemory(context) }
    var projects by remember { mutableStateOf(store.all()) }
    var notice by remember { mutableStateOf("") }
    var backupToImport by remember { mutableStateOf<String?>(null) }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Android could not open the chosen file")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(store.exportJson()) }
            }.onSuccess { notice = "Project backup saved. Keep this private file safe." }
                .onFailure { notice = "Backup export failed: " + (it.message ?: "Unknown error") }
        }
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri)
                    ?: error("Android could not read the selected file")
                val text = input.bufferedReader(Charsets.UTF_8).use { it.readText() }
                require(text.length <= 2_000_000) { "File is too large" }
                val parsed = JSONObject(text)
                require(parsed.optString("format") == "KAIRO_PROJECTS_V2") { "This is not a KAIRO project backup" }
                text to parsed.getJSONArray("projects").length()
            }.onSuccess { (raw, count) ->
                backupToImport = raw
                notice = "Ready to restore " + count + " projects. Review below and confirm import; existing names may be updated."
            }.onFailure { notice = "Cannot import: " + (it.message ?: "Invalid file") }
        }
    }

    LifecycleResumeEffect(Unit) {
        projects = store.all()
        onPauseOrDispose {}
    }
    Section(null) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Project Memory V2", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { projects = store.all() }) { Text("Refresh") }
            }
            Text("Saved only after explicit project confirmation. Data stays on this phone.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (projects.isEmpty()) {
                Text("No confirmed projects yet. Tell KAIRO about a project and say yes when it asks to save.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            projects.take(12).forEach { p ->
                InfoRow(p.title, listOfNotNull(
                    p.worker.takeIf { it.isNotBlank() }?.let { "Worker: " + it },
                    p.due.takeIf { it.isNotBlank() }?.let { "Due: " + it },
                    "Status: " + p.status,
                ).joinToString(" · "))
            }
            Text("Backup includes project records and confirmed update history, not passwords or Gemini settings. Export before uninstalling a test APK.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { exportPicker.launch("kairo-projects-v2.json") }) { Text("Export backup") }
                OutlinedButton(onClick = { importPicker.launch(arrayOf("application/json", "text/plain")) }) { Text("Import backup") }
            }
            if (backupToImport != null) {
                Text("Import may overwrite project records with matching names. Do you approve?",
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val raw = backupToImport
                        if (raw != null) {
                            runCatching { store.importJson(raw) }
                                .onSuccess { count ->
                                    projects = store.all()
                                    notice = "Restored " + count + " projects."
                                    backupToImport = null
                                }
                                .onFailure { notice = "Import failed: " + (it.message ?: "Unknown error") }
                        }
                    }) { Text("Confirm import") }
                    OutlinedButton(onClick = {
                        backupToImport = null
                        notice = "Import cancelled. Nothing changed."
                    }) { Text("Cancel") }
                }
            }
            if (notice.isNotBlank()) Text(notice, style = MaterialTheme.typography.bodySmall)
        }
    }
}


/** A real, searchable master record per worker; one order can have many workers. */
@Composable
private fun WorkerFiles() {
    val context = LocalContext.current
    val store = remember(context) { WorkMemory(context) }
    var workers by remember { mutableStateOf(store.workers()) }
    var jobs by remember { mutableStateOf(store.tasks()) }
    var pending by remember { mutableStateOf(WorkApproval.preview()) }
    var message by remember { mutableStateOf("") }
    var backupToRestore by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        workers = store.workers()
        jobs = store.tasks()
        pending = WorkApproval.preview()
    }

    val export = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri ->
        if (uri != null) {
            runCatching {
                val output = context.contentResolver.openOutputStream(uri)
                    ?: error("Could not open export file")
                output.bufferedWriter().use { it.write(store.exportJson()) }
            }.onSuccess { message = "Worker files exported. Keep this backup private." }
                .onFailure { message = "Export failed: " + (it.message ?: "unknown error") }
        }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                val input = context.contentResolver.openInputStream(uri) ?: error("Cannot read backup")
                val data = input.bufferedReader().use { it.readText() }
                require(data.length <= 2_000_000)
                val root = JSONObject(data)
                require(root.optString("format") == "KAIRO_WORKERS_V1") { "Wrong backup format" }
                data to root.getJSONArray("assignments").length()
            }.onSuccess { (raw, count) ->
                backupToRestore = raw
                message = "Restore " + count + " saved worker tasks? Confirm below. Existing matching tasks may change."
            }.onFailure { message = "Invalid backup: " + (it.message ?: "unknown error") }
        }
    }

    LifecycleResumeEffect(Unit) {
        refresh()
        onPauseOrDispose {}
    }
    Section(null) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Worker Master Files", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { refresh() }) { Text("Refresh") }
            }
            Text("One permanent file per worker, with assignments linked to the customer's order. KAIRO proposes changes; you say YES once to save all.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            if (pending != null) {
                Text("WAITING FOR CONFIRMATION: " + pending,
                    style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        message = WorkApproval.approveTap(context)
                        refresh()
                    }) { Text("Yes, save tasks") }
                    OutlinedButton(onClick = {
                        WorkApproval.discard()
                        message = "Proposal rejected. Nothing saved."
                        refresh()
                    }) { Text("No, correct") }
                }
            }
            if (workers.isEmpty()) {
                Text("No worker tasks saved yet. Example: Anjali will take the order, Vijay will make the mandir, Mangat will polish it.",
                    style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            workers.take(40).forEach { worker ->
                val assigned = jobs.filter { it.worker.equals(worker, true) }
                InfoRow(worker + " (" + assigned.size + " tasks)",
                    assigned.take(5).joinToString(" · ") {
                        "#" + it.id + " " + it.work + " / " + it.order +
                            if (it.due.isNotBlank()) " / due " + it.due else ""
                    }.ifBlank { "No assignments" })
            }
            Text("Worker files have their own backup, separate from older Project Memory V2 notes.",
                style = MaterialTheme.typography.bodySmall, color = Muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { export.launch("kairo-worker-files.json") }) { Text("Export worker files") }
                OutlinedButton(onClick = { import.launch(arrayOf("application/json", "text/plain")) }) { Text("Import worker files") }
            }
            if (backupToRestore != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val raw = backupToRestore
                        if (raw != null) {
                            runCatching { store.importJson(raw) }
                                .onSuccess { message = "Restored " + it + " worker tasks."; backupToRestore = null; refresh() }
                                .onFailure { message = "Restore failed: " + (it.message ?: "unknown error") }
                        }
                    }) { Text("Confirm restore") }
                    OutlinedButton(onClick = { backupToRestore = null; message = "Restore cancelled." }) {
                        Text("Cancel")
                    }
                }
            }
            if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        }
    }
}
