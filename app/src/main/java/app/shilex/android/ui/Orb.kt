package app.shilex.android.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import app.shilex.android.assistant.Phase
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

private val SoftCyan = Color(0xFF73B9C7)
private val MutedCyan = Color(0xFF356D7C)
private val Rim = Color(0xFF94CED6)
private val Inner = Color(0xFF060F14)

/**
 * KAIRO's audio-reactive orbital display.
 *
 * No canned loud-speaking animation: waveform height comes from the actual PCM level fed
 * into [level] by VoiceSession for microphone input AND Gemini speaker output.
 * Quiet audio makes tiny waves; loud audio expands the rings significantly.
 * This is a visual effect (not a biometric voice recogniser).
 */
@Composable
fun Orb(phase: Phase, modifier: Modifier = Modifier, level: Float = 0f) {
    val raw = when (phase) {
        Phase.Listening, Phase.Speaking -> level.coerceIn(0f, 1f)
        Phase.Off, Phase.Connecting -> 0f
    }
    val voice by animateFloatAsState(raw, animationSpec = tween(95), label = "live audio volume")
    val clock = if (phase == Phase.Off) {
        0f
    } else {
        rememberInfiniteTransition(label = "kairo orb rotation").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(3_200, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
            ),
            label = "rotation",
        ).value
    }

    Canvas(modifier) {
        val c = center
        val unit = size.minDimension
        val disc = unit * 0.205f
        val outer = unit * 0.288f
        val radians = clock * (2f * PI.toFloat())
        val waveHeight = unit * 0.142f * voice
        val stroke = max(1.1f, 1.4f * density)

        // Black background stays untouched. Soft light is local to the orb only.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    MutedCyan.copy(alpha = 0.12f + voice * 0.12f),
                    SoftCyan.copy(alpha = 0.045f + voice * 0.09f),
                    Color.Transparent,
                ),
                center = c,
                radius = outer * 1.55f,
            ),
            radius = outer * 1.55f,
            center = c,
        )

        // Structural rings: subdued even at high audio volume.
        drawCircle(color = MutedCyan.copy(alpha = 0.5f), radius = outer * 0.96f,
            center = c, style = Stroke(width = stroke))
        drawCircle(color = SoftCyan.copy(alpha = 0.20f), radius = outer * 1.08f,
            center = c, style = Stroke(width = stroke))
        drawCircle(color = SoftCyan.copy(alpha = 0.38f), radius = disc * 1.18f,
            center = c, style = Stroke(width = stroke))
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Color(0xFF132B34), Inner, Color.Black),
                center = Offset(c.x - disc * 0.16f, c.y - disc * 0.20f),
                radius = disc * 1.55f,
            ),
            center = c,
            radius = disc,
        )
        drawCircle(color = Rim.copy(alpha = 0.64f + voice * 0.28f), radius = disc,
            center = c, style = Stroke(width = stroke * (1.2f + voice)))
        drawCircle(color = MutedCyan.copy(alpha = 0.4f), radius = disc * 0.75f,
            center = c, style = Stroke(width = stroke))

        // Eight segmented HUD arcs, so the orb resembles a futuristic assistant rather
        // than the old multi-colour liquid ball. Rotating only during an active session.
        for (i in 0 until 8) {
            val segment = i * 45f
            val turn = if (phase == Phase.Off) 0f else clock * 16f
            val radius = outer * if (i % 2 == 0) 0.90f else 1.04f
            drawArc(
                color = if (i % 3 == 0) Rim.copy(alpha = 0.73f) else MutedCyan.copy(alpha = 0.48f),
                startAngle = segment + turn + 5f,
                sweepAngle = if (i % 2 == 0) 19f else 25f,
                useCenter = false,
                topLeft = Offset(c.x - radius, c.y - radius),
                size = Size(radius * 2, radius * 2),
                style = Stroke(width = stroke * (1.8f + 0.6f * voice)),
            )
        }

        // Actual audio waves: the radial peaks increase smoothly with measured volume.
        // Two rings react at different speeds but are BOTH driven by [voice] amplitude.
        if (phase == Phase.Listening || phase == Phase.Speaking) {
            for (track in 0..1) {
                val wave = Path()
                val ring = outer + track * (unit * 0.030f)
                val strength = waveHeight * (if (track == 0) 1f else 0.68f)
                for (i in 0..POINTS) {
                    val angle = i * 2f * PI.toFloat() / POINTS
                    val signal = 0.50f +
                        0.29f * sin(12f * angle - radians * (if (track == 0) 3f else -2f)) +
                        0.14f * sin(21f * angle + radians * 4f) +
                        0.07f * sin(6f * angle - radians)
                    val radius = ring + strength * signal.coerceIn(0f, 1f)
                    val x = c.x + radius * cos(angle)
                    val y = c.y + radius * sin(angle)
                    if (i == 0) wave.moveTo(x, y) else wave.lineTo(x, y)
                }
                wave.close()
                drawPath(
                    path = wave,
                    color = if (track == 0) Rim.copy(alpha = 0.62f + voice * 0.30f)
                        else SoftCyan.copy(alpha = 0.27f + voice * 0.33f),
                    style = Stroke(width = stroke * (1.1f + voice * 0.85f)),
                )
            }
        }

        // Quiet focal point, no bright screen-sized flash.
        drawCircle(color = MutedCyan.copy(alpha = 0.40f), radius = disc * 0.15f, center = c)
        drawCircle(color = Rim.copy(alpha = 0.76f + 0.16f * voice), radius = disc * 0.045f, center = c)
    }
}

private const val POINTS = 144
