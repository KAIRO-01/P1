package app.shilex.android.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import app.shilex.android.Diag
import app.shilex.android.local.OfflineSpeech
import org.json.JSONObject
import org.vosk.Recognizer

/**
 * Optional offline wake-word fallback while a custom ONNX classifier is not installed.
 * It uses the Vosk speech model already bundled for local commands. No audio is uploaded.
 * Unlike a trained wake-word classifier, continuous speech recognition may use more battery
 * and phone-specific pronunciation accuracy must be tested.
 */
class VoskWakeListener(private val context: Context, private val onWake: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start(): Boolean {
        if (!VoiceProfile.exists(context)) return false
        if (running) return true
        if (!OfflineSpeech.get(context).available()) return false
        running = true
        thread = Thread(::listen, "wake-vosk").apply { start() }
        return true
    }

    fun stop() {
        running = false
    }

    @SuppressLint("MissingPermission")
    private fun listen() {
        // The model loads on this worker thread, not Android's main thread.
        try {
            val model = OfflineSpeech.get(context).model()
            if (!running) return
            Recognizer(model, SAMPLE_RATE.toFloat(), GRAMMAR).use { recognizer ->
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                )
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, SAMPLE_RATE * 2),
                )
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    Diag.w("wake: offline microphone unavailable")
                    return
                }
                val chunk = ShortArray(SAMPLE_RATE / 10)
                // Keep only the last ~3 seconds for the speaker check; no recording is stored.
                val recent = ArrayDeque<ShortArray>()
                val profile = VoiceProfile.load(context) ?: return
                var consecutive = 0
                try {
                    record.startRecording()
                    Diag.i("wake: offline listener ready for Hey Kairo")
                    while (running) {
                        val size = record.read(chunk, 0, chunk.size)
                        if (size <= 0) break
                        recent.addLast(chunk.copyOf(size))
                        if (recent.size > 30) recent.removeFirst()
                        val finished = recognizer.acceptWaveForm(chunk, size)
                        val text = extract(if (finished) recognizer.result else recognizer.partialResult)
                        consecutive = if (MATCH.containsMatchIn(text)) consecutive + 1 else 0
                        if ((finished && consecutive > 0) || consecutive >= 2) {
                            if (!matchesEnrolledVoice(profile, recent)) {
                                // Don't allow an unverified voice to trigger the assistant.
                                Diag.i("wake: offline phrase rejected by voice profile")
                                consecutive = 0
                                recognizer.reset()
                                continue
                            }
                            running = false
                            Diag.i("wake: offline phrase matched")
                            main.post(onWake)
                            break
                        }
                    }
                } finally {
                    runCatching { record.stop() }
                    record.release()
                }
            }
        } catch (error: Exception) {
            Diag.w("wake: offline recognizer failed (${error.javaClass.simpleName})")
        } finally {
            running = false
        }
    }

    private fun matchesEnrolledVoice(profile: VoiceProfile, recent: ArrayDeque<ShortArray>): Boolean {
        if (profile.templates.firstOrNull()?.size != SpeakerFingerprint.VECTOR_SIZE) return false
        val audio = ShortArray(recent.sumOf { it.size })
        var index = 0
        recent.forEach { frame ->
            frame.copyInto(audio, index)
            index += frame.size
        }
        val features = SpeakerFingerprint.extract(audio) ?: return false
        return profile.similarity(features) >= profile.threshold
    }

    private fun extract(json: String): String =
        runCatching {
            val result = JSONObject(json)
            result.optString("partial", result.optString("text", ""))
        }.getOrDefault("")

    companion object {
        private const val SAMPLE_RATE = 16_000
        // Cairo is a common speech-recognition spelling of the pronounced name Kairo.
        private const val GRAMMAR =
            "[\"hey cairo\", \"hey kairo\", \"hey kyro\", \"hey kero\", \"[unk]\"]"
        private val MATCH = Regex(
            """\bhey\s+(?:cairo|kairo|kyro|kero)\b""", RegexOption.IGNORE_CASE,
        )

        fun available(context: Context): Boolean = OfflineSpeech.get(context).available()
    }
}
