package app.shilex.android.ui

import android.Manifest
import android.app.NotificationManager
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.location.LocationManager
import androidx.core.app.NotificationManagerCompat
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.Branding
import app.shilex.android.assistant.AssistantViewModel
import app.shilex.android.data.Settings as AppSettings
import app.shilex.android.memory.MemoryStore
import app.shilex.android.notify.NotificationWatch
import app.shilex.android.orb.FloatingOrb
import app.shilex.android.reminders.Reminders
import app.shilex.android.wake.WakeWordModel
import app.shilex.android.sheets.OrderSheet
import app.shilex.android.sheets.SheetsAuth
import app.shilex.android.sheets.SheetWatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

@Composable
fun SettingsScreen(assistant: AssistantViewModel) {
    val context = LocalContext.current
    val wakeOn by assistant.wakeWord.collectAsStateWithLifecycle()
    var localOn by remember { mutableStateOf(assistant.localCommands) }
    var networkOn by remember { mutableStateOf(assistant.networkSpeech) }
    var engine by remember { mutableStateOf(assistant.speechEngine) }
    var batteryExempt by remember { mutableStateOf(assistant.batteryExempt) }
    var microphoneOn by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    var gpsOn by remember { mutableStateOf(false) }
    var mapsLocationOn by remember { mutableStateOf(false) }
    var notificationsOn by remember { mutableStateOf(false) }
    val refreshPermissions = {
        microphoneOn = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val location = context.getSystemService(LocationManager::class.java)
        gpsOn = location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        mapsLocationOn = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).any {
            context.packageManager.checkPermission(it, "com.google.android.apps.maps") == PackageManager.PERMISSION_GRANTED
        }
        notificationsOn = NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
    LifecycleResumeEffect(Unit) {
        batteryExempt = assistant.batteryExempt
        refreshPermissions()
        engine = assistant.speechEngine
        onPauseOrDispose { }
    }
    val open = { action: String -> context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 12.dp))

        Section("Gemini") {
            val liveSettings = remember { AppSettings(context) }
            var liveWebOn by remember { mutableStateOf(liveSettings.liveWebSearch) }
            SwitchRow(
                "Live Internet Search (Google)",
                "Research current rates, products, news and latest facts inside KAIRO voice. " +
                    "Uses your Gemini Google Search quota and MAY COST MONEY if your Google API project has billing enabled. " +
                    "KAIRO cannot inspect your API billing. Turn on only if you accept the charges. " +
                    "Restart your voice session after changing this.",
                liveWebOn,
            ) {
                liveWebOn = it
                liveSettings.liveWebSearch = it
            }
            InfoRow("API key", "Your own key, encrypted with Android Keystore on this phone. Used only for requests the phone can't handle. Gemini usage counts against your key's limits.") {
                TextButton(onClick = assistant::forgetKey) { Text("Replace") }
            }
            InfoRow("Default assistant", "Long-press Home or swipe from a corner to talk.") {
                TextButton(onClick = { open(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS) }) { Text("Open") }
            }
        }

        Section("Conversation") {
            var talkOn by remember { mutableStateOf(assistant.talkOver) }
            SwitchRow(
                "Talk over ${Branding.ASSISTANT_NAME}",
                "Speak while it answers to cut it off, also on the loudspeaker. An echo canceller on this phone removes its own voice first. Turn off if it cuts itself off.",
                talkOn,
            ) {
                talkOn = it
                assistant.talkOver = it
            }
            var waveOn by remember { mutableStateOf(assistant.waveToStop) }
            SwitchRow(
                "Wave to interrupt",
                "Wave a hand over the top of the phone while ${Branding.ASSISTANT_NAME} speaks to cut it off. With headphones you can also just talk over it.",
                waveOn,
            ) {
                waveOn = it
                assistant.waveToStop = it
            }
        }

        Section("Floating orb") {
            val prefs = remember { AppSettings(context) }
            var orbOn by remember { mutableStateOf(prefs.floatingOrb && FloatingOrb.allowed(context)) }
            LifecycleResumeEffect(Unit) {
                orbOn = prefs.floatingOrb && FloatingOrb.allowed(context)
                onPauseOrDispose { }
            }
            SwitchRow(
                "Use ${Branding.ASSISTANT_NAME} over any app",
                "A small orb floats over your apps. Tap to talk, double-tap to type, drag to move. " +
                    "Needs \"Display over other apps\"; it hides while ${Branding.ASSISTANT_NAME} is open.",
                orbOn,
            ) { on ->
                prefs.floatingOrb = on
                orbOn = on && FloatingOrb.allowed(context)
                if (on && !FloatingOrb.allowed(context)) {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
                FloatingOrb.sync(context)
            }
        }

        Section("Notifications") {
            var notifyOn by remember { mutableStateOf(NotificationWatch.allowed(context)) }
            LifecycleResumeEffect(Unit) {
                notifyOn = NotificationWatch.allowed(context)
                onPauseOrDispose { }
            }
            InfoRow(
                "What did I miss?",
                (if (notifyOn) "On. " else "Off. ") +
                    "Ask \"what did I miss?\" and hear messages from people first. Notifications stay in memory on this " +
                    "phone; Gemini gets a short summary only when you ask, with codes hidden.",
            ) {
                TextButton(onClick = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text(if (notifyOn) "Change" else "Turn on") }
            }
        }

        Section("Call-style reminders · free") {
            val manager = context.getSystemService(NotificationManager::class.java)
            var notificationsAllowed by remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED,
                )
            }
            var callsAllowed by remember {
                mutableStateOf(
                    Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent(),
                )
            }
            var exactAlarmsAllowed by remember {
                mutableStateOf(Reminders(context).exact())
            }
            val requestNotifications = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission(),
            ) { notificationsAllowed = it }
            LifecycleResumeEffect(Unit) {
                notificationsAllowed = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                callsAllowed = Build.VERSION.SDK_INT < 34 || manager.canUseFullScreenIntent()
                exactAlarmsAllowed = Reminders(context).exact()
                onPauseOrDispose { }
            }

            InfoRow(
                "Reminder ringtone and Answer / Dismiss",
                "Call-style notification, then offline voice only after Answer. No paid API, login, " +
                    "mobile network call or Gemini usage when the reminder rings.",
            ) {
                TextButton(onClick = {
                    if (notificationsAllowed) {
                        Reminders(context).testCall()
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }) { Text(if (notificationsAllowed) "Test call" else "Allow") }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                InfoRow(
                    "Exact reminder time",
                    if (exactAlarmsAllowed) "Allowed. KAIRO can schedule reminders for the specified time."
                    else "Not allowed. Android may delay the reminder until later.",
                ) {
                    if (!exactAlarmsAllowed) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }.onFailure {
                                context.startActivity(
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }) { Text("Allow") }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= 34) {
                InfoRow(
                    "Full-screen incoming call",
                    if (callsAllowed) "Allowed. The call screen can open over the lock screen."
                    else "Not allowed. The Answer / Dismiss notification still works; " +
                        "Android may prevent automatic full-screen display.",
                ) {
                    TextButton(onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT,
                            Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { context.startActivity(intent) }.onFailure {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    }) { Text(if (callsAllowed) "Review" else "Allow") }
                }
            }
            InfoRow(
                "Offline speech",
                "Uses an offline voice already installed on your Android phone. " +
                    "If one isn't installed, the reminder stays readable instead of using internet speech.",
            )
        }

        Section("Wake word") {
            if (assistant.wakeWordAvailable) {
                var voiceReady by remember { mutableStateOf(assistant.voiceSaved) }
                SwitchRow(
                    "Say \"${WakeWordModel.PHRASE}\"",
                    "Requires your five voice samples. Speaker filtering is approximate and is NOT 100% biometric security.",
                    wakeOn,
                    enabled = voiceReady,
                ) { assistant.setWakeWord(it) }
                if (!voiceReady) InfoRow("Enroll your voice first", "Record five clear Hey Kairo samples before enabling hands-free wake.")
                VoiceSetupRow(
                    saved = voiceReady,
                    record = assistant::recordVoiceSample,
                    onSave = { samples -> assistant.saveVoice(samples); voiceReady = true },
                    onForget = { assistant.forgetVoice(); voiceReady = false; assistant.setWakeWord(false) },
                )
            } else {
                InfoRow("Not in this build", "The wake word ships once ${Branding.ASSISTANT_NAME} has its own model.")
            }
        }


        Section("Google Sheets - customer orders") {
            val sheetSettings = remember { AppSettings(context) }
            var sheetLink by remember { mutableStateOf(sheetSettings.orderSheetUrl) }
            var sheetStatus by remember { mutableStateOf(
                if (sheetLink.isBlank() && !OrderSheet.hasImport(context)) "Not connected."
                else "Saved source ready for testing."
            ) }
            val sheetScope = rememberCoroutineScope()
            var watchChanges by remember { mutableStateOf(sheetSettings.watchSheetChanges) }
            var googleConnected by remember { mutableStateOf(SheetsAuth.authorized(context)) }
            val googleConsent = rememberLauncherForActivityResult(
                ActivityResultContracts.StartIntentSenderForResult(),
            ) { response ->
                if (response.resultCode == Activity.RESULT_OK) {
                    val ok = runCatching { SheetsAuth.complete(context, response.data) }.getOrDefault(false)
                    googleConnected = ok
                    sheetStatus = if (ok) "Google Account connected read-only. Tap Test." else "Google did not grant Sheets access."
                } else sheetStatus = "Google authorization cancelled."
            }
            val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) sheetScope.launch {
                    sheetStatus = "Importing your private CSV..."
                    sheetStatus = try {
                        val count = withContext(Dispatchers.IO) {
                            val stream = context.contentResolver.openInputStream(uri)
                                ?: error("Could not open the selected CSV.")
                            OrderSheet.importCsv(context, stream)
                        }
                        "Imported $count orders privately. Voice queries now work from this CSV until you import a newer export."
                    } catch (e: Exception) { "Import failed: ${e.message}" }
                }
            }
            Text(
                "Paste a Google Sheet link. For private Sheets, tap Connect Google Account to authorize READ-ONLY access. " +
                    "Do not make customer data public. Google Sheets API and Android OAuth client configuration may be required.",
                style = MaterialTheme.typography.bodySmall,
            )
            InfoRow("Google Account", if (googleConnected) "Google Sheets read-only access granted." else "Not connected for private Sheets.") {
                TextButton(onClick = {
                    sheetStatus = "Opening Google authorization..."
                    SheetsAuth.connect(context,
                        resolve = { googleConsent.launch(it) },
                        connected = { googleConnected = true; sheetStatus = "Google Account connected. Tap Test." },
                        failed = { sheetStatus = it },
                    )
                }) { Text(if (googleConnected) "Reconnect" else "Connect") }
            }
            if (googleConnected) {
                InfoRow("Disconnect Google", "Revoke KAIRO's Sheets permission, without deleting the spreadsheet.") {
                    TextButton(onClick = {
                        SheetsAuth.disconnect(context) { success ->
                            googleConnected = SheetsAuth.authorized(context)
                            sheetStatus = if (success) "Google permission revoked." else "Could not revoke Google permission; try again."
                        }
                    }) { Text("Disconnect") }
                }
            }
            OutlinedTextField(
                value = sheetLink,
                onValueChange = { sheetLink = it },
                label = { Text("Google Sheet link") },
                placeholder = { Text("https://docs.google.com/spreadsheets/d/...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            SwitchRow(
                "Notify me when Google Sheet orders change",
                "Checks a readable LIVE Sheet about every 30 minutes. Android may delay checks. " +
                    "Works with a connected live Sheet; Google may request authorization again. An imported CSV cannot update itself. " +
                    "Allow Android notifications to receive alerts.",
                watchChanges,
                enabled = OrderSheet.parseLink(sheetLink) != null,
            ) { enable ->
                sheetSettings.orderSheetUrl = sheetLink.trim()
                if (SheetWatch.configure(context, enable)) {
                    watchChanges = enable
                    sheetStatus = if (enable) "Change alerts scheduled; first successful check sets a baseline." else "Change alerts turned off."
                } else {
                    sheetStatus = "Android could not schedule Sheet monitoring."
                }
            }
            InfoRow("Last background check", SheetWatch.lastUpdate(context))
            InfoRow("Order data", sheetStatus) {
                TextButton(onClick = {
                    if (sheetLink.isBlank()) {
                        sheetSettings.orderSheetUrl = ""
                        sheetStatus = if (OrderSheet.hasImport(context)) "Sheet link cleared; private CSV remains saved." else "Sheet link cleared."
                    } else if (OrderSheet.parseLink(sheetLink) == null) {
                        sheetStatus = "Invalid link. Copy the complete link from Google Sheets."
                    } else {
                        sheetSettings.orderSheetUrl = sheetLink.trim()
                        sheetStatus = "Saved. Tap Test to check whether the Sheet is readable."
                    }
                }) { Text("Save link") }
            }
            InfoRow("Test connection", "Check the linked Sheet or the saved private CSV.") {
                TextButton(onClick = {
                    if (sheetLink.isNotBlank() && OrderSheet.parseLink(sheetLink) != null) {
                        sheetSettings.orderSheetUrl = sheetLink.trim()
                    }
                    sheetScope.launch {
                        sheetStatus = "Checking the source..."
                        sheetStatus = try {
                            val snap = withContext(Dispatchers.IO) {
                                OrderSheet.load(context, OkHttpClient())
                            }
                            "Connected: ${snap.orders.size} orders from ${snap.source}. Say 'KAIRO, which mandirs are due in four days?'"
                        } catch (e: Exception) { "Cannot read source: ${e.message}" }
                    }
                }) { Text("Test") }
            }
            InfoRow("Import private CSV", "Read private order data without making the Sheet public. Re-import for changes.") {
                TextButton(onClick = { csvPicker.launch(arrayOf("text/comma-separated-values", "text/csv", "text/plain", "application/vnd.ms-excel")) }) {
                    Text("Import CSV")
                }
            }
        }

        Section("Local commands") {
            SwitchRow(
                "Handle simple commands on the phone",
                "Volume, music, flashlight, apps, time, timers and alarms run here, without Gemini.",
                localOn,
            ) {
                localOn = it
                assistant.localCommands = it
            }
            SwitchRow(
                "Use the phone's speech service",
                "Better Hinglish, but sends what you say after the wake word to that service (usually Google) and needs internet. Off: speech stays on this phone.",
                networkOn,
                enabled = localOn,
            ) {
                networkOn = it
                assistant.networkSpeech = it
                engine = assistant.speechEngine
            }
            InfoRow("Speech heard by", engineText(engine))
        }

        Section("Learning") {
            var learnOn by remember { mutableStateOf(assistant.learning) }
            var learned by remember { mutableStateOf(assistant.learnedSummary()) }
            SwitchRow(
                "Learn from how I use ${Branding.ASSISTANT_NAME}",
                "Kept only on this phone: which commands you use and when, and your own phrases once Gemini has understood them. Never contacts.",
                learnOn,
            ) {
                learnOn = it
                assistant.learning = it
            }
            InfoRow("What it knows", learned) {
                TextButton(onClick = {
                    assistant.forgetLearned()
                    learned = assistant.learnedSummary()
                }) { Text("Forget") }
            }
        }

        Section("Permissions") {
            ControlCard(Modifier.padding(vertical = 8.dp), showWhenOn = true)
            InfoRow("Background running", if (batteryExempt) "Allowed. The phone won't stop the wake word." else "The phone may stop the wake word to save battery. Allow ${Branding.ASSISTANT_NAME} under Battery → Unrestricted.") {
                TextButton(onClick = { open(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }) { Text("Open") }
            }
        }

        Section("Permissions status") {
            InfoRow("Microphone: " + if (microphoneOn) "ON" else "OFF", "Required to hear your voice.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
            InfoRow("Device GPS: " + if (gpsOn) "ON" else "OFF", "Google Maps must be able to determine your current location.") {
                TextButton(onClick = { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Review") }
            }
            InfoRow("Google Maps location: " + if (mapsLocationOn) "ON" else "OFF", "Maps, not KAIRO, reads your GPS location for navigation.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:com.google.android.apps.maps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
            InfoRow("Notifications: " + if (notificationsOn) "ON" else "OFF", "For reminders and Android alerts.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
            InfoRow("Notification reader: " + if (NotificationWatch.allowed(context)) "ON" else "OFF", "Needed for 'what did I miss?' messages.") {
                TextButton(onClick = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("Review") }
            }
            InfoRow("Floating overlay: " + if (FloatingOrb.allowed(context)) "ON" else "OFF", "Required if you want the KAIRO orb over other apps.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
            InfoRow("Exact reminders: " + if (Reminders(context).exact()) "ON" else "OFF", "Android controls exact alarm permission.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
        }

        Section("Quick permission setup") {
            InfoRow("Microphone", "Required for KAIRO voice commands. Android will ask you to allow it.") {
                val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                TextButton(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                        microphone.launch(Manifest.permission.RECORD_AUDIO)
                    else context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Review") }
            }
            InfoRow("Current location / GPS", "Google Maps uses your live location for navigation. Enable device location and grant Maps location permission.") {
                TextButton(onClick = { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) }) { Text("Open location") }
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:com.google.android.apps.maps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Maps permission") }
            }
            InfoRow("Notification access", "Allow KAIRO to read supported incoming notification previews.") {
                TextButton(onClick = { open(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) }) { Text("Allow") }
            }
            InfoRow("Accessibility / phone control", "Enable KAIRO's Accessibility service manually in Android Settings.") {
                TextButton(onClick = { open(Settings.ACTION_ACCESSIBILITY_SETTINGS) }) { Text("Open") }
            }
            InfoRow("Truecaller", "Open Truecaller's Android app settings. KAIRO cannot turn on another app's protected features automatically.") {
                TextButton(onClick = {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:com.truecaller")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text("Open") }
            }
        }

        Section("Privacy") {
            InfoRow(
                "What leaves the phone",
                "Wake-word audio stays on this phone. When you ask about Google Sheet orders, relevant order details " +
                    "are sent to Gemini for the spoken answer. The CSV and stored link remain in app-private storage. " +
                    "Diagnostic logs keep timings and states, not spoken words.",
            )
            InfoRow("Standby", "Between requests only the wake word runs; Gemini is disconnected.")
            val memory = remember { MemoryStore(context) }
            var notes by remember { mutableStateOf(memory.notes().size) }
            LifecycleResumeEffect(Unit) {
                notes = memory.notes().size
                onPauseOrDispose { }
            }
            InfoRow(
                "What ${Branding.ASSISTANT_NAME} remembers",
                "$notes note(s) you asked it to remember, kept on this phone only. Gemini sees one only when you ask about it.",
            ) {
                if (notes > 0) TextButton(onClick = { memory.clearNotes().also { notes = 0 } }) { Text("Forget all") }
            }
        }

        Section("About") {
            InfoRow(Branding.ASSISTANT_NAME, "Version ${versionName(context)}")
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun engineText(engine: String?) = when (engine) {
    "on-device" -> "The phone's on-device recogniser. No internet needed."
    "offline" -> "Offline model on this phone (Vosk). No internet needed; best with English words."
    "network" -> "The phone's speech service (usually Google). Needs internet."
    else -> "Nothing local is available, so Gemini hears every request."
}

private fun versionName(context: android.content.Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
