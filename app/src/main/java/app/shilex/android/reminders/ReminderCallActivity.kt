package app.shilex.android.reminders

import android.app.NotificationManager
import android.content.Intent
import android.media.AudioAttributes
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        acceptIntent(intent)

        if (reminderId == -1L || reminderText.isBlank()) {
            finish()
            return
        }
        setContent {
            ShilexTheme {
                IncomingReminder(
                    text = reminderText,
                    answered = answered,
                    status = status,
                    onAnswer = ::answer,
                    onDismiss = ::dismiss,
                )
            }
        }
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        acceptIntent(intent)
        if (intent.getBooleanExtra(EXTRA_AUTO_ANSWER, false)) answer()
    }

    private fun acceptIntent(intent: Intent) {
        val newId = intent.getLongExtra(EXTRA_ID, -1)
        val newText = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        if (newId != reminderId && newId != -1L) {
            tts?.stop()
            tts?.shutdown()
            tts = null
            answered = false
            status = "Incoming reminder"
        }
        reminderId = newId
        reminderText = newText
    }

    private fun answer() {
        if (answered || reminderText.isBlank()) return
        answered = true
        status = "Preparing offline voice..."
        cancelNotification()
        // TTS is created ONLY after Answer; background ringing never sends text or audio
        // to Gemini or an external speech provider.
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
        cancelNotification()
        tts?.stop()
        finish()
    }

    private fun cancelNotification() {
        if (reminderId != -1L) {
            getSystemService(NotificationManager::class.java).cancel(reminderId.toInt())
        }
    }

    override fun onDestroy() {
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
