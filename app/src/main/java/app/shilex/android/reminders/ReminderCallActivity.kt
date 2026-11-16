package app.shilex.android.reminders

import android.Manifest
import android.app.KeyguardManager
import android.content.pm.PackageManager
import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.shilex.android.Branding
import app.shilex.android.assistant.Phase
import app.shilex.android.assistant.VoiceSession
import app.shilex.android.memory.ProjectMemory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.shilex.android.ui.ShilexTheme
import java.util.Locale

/**
 * A local reminder displayed like an incoming call. It does NOT place a phone call
 * or connect to a paid service. No audio is spoken until the user taps Answer.
 * The selected Android TTS voice must be available offline; network voices are rejected.
 */
class ReminderCallActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var reminderId = -1L
    private var reminderText by mutableStateOf("")
    private var answered by mutableStateOf(false)
    private var status by mutableStateOf("Incoming reminder")
    private var tts: TextToSpeech? = null
    private var usingAi by mutableStateOf(false)
    private var unlocked by mutableStateOf(false)
    private var sessionStartedHere = false
    private var ringtone: Ringtone? = null
    private val startRing = Runnable {
        if (answered || isFinishing || isDestroyed) return@Runnable
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri)?.apply {
                isLooping = true
                play()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        acceptIntent(intent)

        if (reminderId == -1L || reminderText.isBlank()) {
            finish()
            return
        }
        setContent {
            val live by VoiceSession.get(applicationContext).state.collectAsStateWithLifecycle()
            val label = if (usingAi) when (live.phase) {
                Phase.Off -> live.problem ?: "AI disconnected. End call or try again."
                Phase.Connecting -> "Connecting AI voice..."
                Phase.Listening -> "Listening — ask your question"
                Phase.Speaking -> "KAIRO is speaking..."
            } else status
            ShilexTheme {
                IncomingReminder(
                    text = if (unlocked) reminderText else "Unlock to view reminder details",
                    answered = answered,
                    status = label,
                    onAnswer = ::answer,
                    onDismiss = ::dismiss,
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer()
        else main.postDelayed(startRing, 1_200L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
        unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer()
        else main.postDelayed(startRing, 1_200L)
    }

    private fun acceptIntent(intent: Intent) {
        val newId = intent.getLongExtra(EXTRA_ID, -1)
        val newText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (newId != reminderId && newId != -1L) {
            stopRinging()
            if (sessionStartedHere) VoiceSession.get(applicationContext).stop("next reminder")
            sessionStartedHere = false
            usingAi = false
            tts?.stop()
            tts?.shutdown()
            tts = null
            answered = false
            status = "Incoming reminder"
        }
        reminderId = newId
        reminderText = newText
    }

    override fun onResume() {
        super.onResume()
        unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
    }

    private fun answer() {
        if (answered || reminderText.isBlank()) return
        // Do not read business details or open a live microphone on a locked phone.
        val lock = getSystemService(KeyguardManager::class.java)
        if (lock.isKeyguardLocked) {
            status = "Unlock your phone to answer privately."
            lock.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { runOnUiThread { unlocked = true; answer() } }
                override fun onDismissCancelled() { status = "Unlock cancelled. Tap Answer to retry." }
                override fun onDismissError() { status = "Unlock phone, then tap Answer." }
            })
            return
        }
        unlocked = true
        answered = true
        stopRinging()
        cancelNotification()

        val session = VoiceSession.get(applicationContext)
        val micAllowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (session.state.value.hasKey && micAllowed) {
            if (session.active) session.stop("switching to reminder call")
            usingAi = true
            sessionStartedHere = true
            status = "Connecting AI voice..."
            val memory = ProjectMemory(applicationContext)
            val context = when {
                reminderText.startsWith("Daily work review", ignoreCase = true) -> memory.review()
                reminderText.contains("test reminder", ignoreCase = true) -> "This is a test call; no project is selected."
                else -> {
                    val match = memory.search(reminderText).firstOrNull {
                        reminderText.contains(it.title, ignoreCase = true)
                    }
                    if (match == null) "No verified project selected. Use projects_search when asked about work."
                    else "Approved project: " + match.title + ", worker " + match.worker +
                        ", due " + match.due + ", status " + match.status + ", details " + match.details
                }
            }
            session.start(
                "I just ANSWERED a KAIRO reminder call. Reminder topic: " + reminderText +
                    ". Verified local information: " + context +
                    ". Begin by briefly telling me the reminder, then LISTEN to my questions " +
                    "and keep a natural TWO-WAY conversation about this topic. " +
                    "For work facts use projects_search or work_review. " +
                    "Before changing any project memory ask for approval via project_propose. " +
                    "Do not claim a task is finished unless I confirmed it."
            )
            return
        }

        // Offline fallback preserves the original Answer-to-speak functionality if
        // the Gemini key, network or microphone is not available.
        status = "Offline reminder (two-way AI needs Gemini key and microphone access)"
        tts = TextToSpeech(applicationContext) { result ->
            main.post {
                if (isFinishing || isDestroyed) return@post
                if (result != TextToSpeech.SUCCESS) {
                    status = "Offline speech isn't available on this phone."
                    return@post
                }
                speakOffline()
            }
        }
    }

    private fun speakOffline() {
        val engine = tts ?: return
        // Voice selection is local-only. Some phones ship a network TTS default, which
        // must not be used for this free/offline reminder.
        val wantsHindi = reminderText.any { it.code in 0x0900..0x097F }
        val language = if (wantsHindi) "hi" else "en"
        val voices = runCatching { engine.voices.orEmpty() }.getOrDefault(emptySet())
            .filter { !it.isNetworkConnectionRequired && it.locale.language == language }
        val chosen = voices.firstOrNull { it.locale.country == "IN" }
            ?: voices.firstOrNull { it.locale.country == "US" }
            ?: voices.firstOrNull()
        if (chosen == null || engine.setVoice(chosen) == TextToSpeech.ERROR) {
            status = "No offline $language voice installed. You can read the reminder here."
            return
        }

        engine.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                runOnUiThread { status = "KAIRO is speaking..." }
            }
            override fun onDone(utteranceId: String?) {
                runOnUiThread { status = "Reminder delivered" }
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                runOnUiThread { status = "Could not speak. Reminder text is shown above." }
            }
        })

        val reply = "Reminder from ${Branding.ASSISTANT_NAME}. ${reminderText.take(1_000)}"
        if (engine.speak(reply, TextToSpeech.QUEUE_FLUSH, null, "kairo-reminder") == TextToSpeech.ERROR) {
            status = "Could not speak. Reminder text is shown above."
        }
    }

    private fun dismiss() {
        if (sessionStartedHere) VoiceSession.get(applicationContext).stop("reminder call ended")
        sessionStartedHere = false
        usingAi = false
        stopRinging()
        cancelNotification()
        tts?.stop()
        finish()
    }

    private fun stopRinging() {
        main.removeCallbacks(startRing)
        runCatching { ringtone?.stop() }
        ringtone = null
    }

    private fun cancelNotification() {
        if (reminderId != -1L) {
            getSystemService(NotificationManager::class.java).cancel(reminderId.toInt())
        }
    }

    override fun onDestroy() {
        if (sessionStartedHere && !isChangingConfigurations) {
            VoiceSession.get(applicationContext).stop("reminder activity closed")
        }
        sessionStartedHere = false
        stopRinging()
        tts?.stop()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_ID = "kairo_reminder_id"
        const val EXTRA_TEXT = "kairo_reminder_text"
        const val EXTRA_AUTO_ANSWER = "kairo_reminder_auto_answer"
    }
}

@Composable
private fun IncomingReminder(
    text: String,
    answered: Boolean,
    status: String,
    onAnswer: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(Modifier.fillMaxSize().background(Color.Black).padding(28.dp)) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(112.dp).background(Color(0xFF11242C), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("K", style = MaterialTheme.typography.displaySmall, color = Color(0xFF85BCC8))
                }
                Spacer(Modifier.height(20.dp))
                Text("KAIRO Calling", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                Spacer(Modifier.height(10.dp))
                Text(status, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF8AA4AD))
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("YOUR REMINDER", style = MaterialTheme.typography.labelMedium, color = Color(0xFF85BCC8))
                Spacer(Modifier.height(14.dp))
                Text(
                    text,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color(0xFFE6F0F2),
                    textAlign = TextAlign.Center,
                )
            }

            if (!answered) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.size(76.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8A3439)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text("✕", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                        Spacer(Modifier.height(8.dp))
                        Text("Dismiss", color = Color.White)
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = onAnswer,
                            modifier = Modifier.size(76.dp),
                            shape = CircleShape,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF267E60)),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                        ) { Text("✓", color = Color.White, style = MaterialTheme.typography.headlineSmall) }
                        Spacer(Modifier.height(8.dp))
                        Text("Answer", color = Color.White)
                    }
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text("Done / Close", color = Color(0xFF85BCC8))
                }
            }
        }
    }
}
