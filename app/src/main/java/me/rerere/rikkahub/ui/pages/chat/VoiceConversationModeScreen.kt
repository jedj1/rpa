package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import me.rerere.asr.VoiceModeStatus
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Cancel01
import me.rerere.rikkahub.ui.theme.LocalDarkMode
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Full-screen surface for VCM (Voice Conversation Mode).
 *
 * Deliberately a separate feature from the existing voice mode: this one is a loop that
 * runs hands-free, and it is the only one that works on providers without server-side
 * endpointing (Groq, HuggingFace, any self-hosted OpenAI-compatible ASR), because turn
 * detection here comes from the client-side VAD rather than the server.
 *
 * Visual design is the Gemini / ChatGPT "ink orb" pattern: a slowly breathing blob whose
 * motion and colour track the session state, drifting on the app's own
 * [me.rerere.rikkahub.ui.pages.chat.MeshGradientBackground] palette so it does not look
 * bolted on.
 *
 * Strings here are literals rather than `R.string.*` on purpose. This screen's four state
 * labels plus the close and interrupt hints have no translations to preserve, so there is
 * nothing to localise and no reason to grow a shared ~260KB resource file for them. This
 * mirrors how DashScopeStreamingASRConfiguration already carries its labels inline.
 */

/** What the orb is doing, which drives both its colour and its motion. */
enum class OrbState { Listening, Thinking, Speaking, Failed }

/**
 * The breathing ink orb.
 *
 * Motion reads the wall clock at draw time rather than using
 * `rememberInfiniteTransition`, which would recompute on every display vsync (120fps on a
 * 120Hz screen even while idle). Reading the clock inside the draw lambda means state
 * changes only trigger a redraw, not a recomposition.
 *
 * The blob is a sum of three radial gradients on slightly offset trajectories, which is
 * what gives it the "liquid ink" look — a single circle reads as a loading spinner instead.
 */
@Composable
fun VoiceOrb(
    state: OrbState,
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkMode.current
    val (baseColor, accentColor) = when (state) {
        OrbState.Listening -> if (dark) Color(0xFF4A9FD8) to Color(0xFF3FD6C8) else Color(0xFF2E7BC4) to Color(0xFF1FA8A0)
        OrbState.Thinking -> if (dark) Color(0xFF8A7BD8) to Color(0xFFC58AE0) else Color(0xFF6A5ACD) to Color(0xFF9F7AEA)
        OrbState.Speaking -> if (dark) Color(0xFF3FA96A) to Color(0xFFA8D84A) else Color(0xFF2E9E5B) to Color(0xFF7CB518)
        OrbState.Failed -> if (dark) Color(0xFFD86A6A) to Color(0xFFE08A6A) else Color(0xFFC4453F) to Color(0xFFD2694A)
    }

    // Audio level, smoothed so a single spike does not jerk the orb.
    val level by animateFloatAsState(
        targetValue = (amplitudes.lastOrNull() ?: 0f),
        animationSpec = tween(120),
        label = "orb-level",
    )

    Canvas(modifier = modifier) {
        val t = System.currentTimeMillis() / 1000f
        val w = size.width
        val h = size.height
        val r = minOf(w, h) * 0.5f

        // Breath: a slow pulse, plus a smaller one driven by the live audio level while
        // listening. Idle states breathe slowly; Speaking pulses faster.
        val breathRate = if (state == OrbState.Speaking) 1.6f else 0.9f
        val breath = 1f + sin(t * breathRate * 2f * PI.toFloat()) * 0.05f + level * 0.12f
        val radius = r * 0.42f * breath

        val cx = w / 2f
        val cy = h / 2f

        // Ink blobs: each on its own slow circular drift, so the sum is never static.
        val drift = r * 0.10f
        fun blob(phase: Float, radiusScale: Float, color: Color, alpha: Float) {
            val ox = cx + sin(t * 0.55f + phase) * drift
            val oy = cy + cos(t * 0.7f + phase) * drift
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(color.copy(alpha = alpha), Color.Transparent),
                    center = Offset(ox, oy),
                    radius = radius * radiusScale,
                ),
                radius = radius * radiusScale,
                center = Offset(ox, oy),
            )
        }
        blob(0f, 1.0f, baseColor, 0.85f)
        blob(2.1f, 0.78f, accentColor, 0.65f)
        blob(4.2f, 0.6f, baseColor, 0.45f)

        // A thin ring gives the edge definition a pure gradient lacks.
        drawCircle(
            color = Color.White.copy(alpha = if (dark) 0.20f else 0.30f),
            radius = radius * 1.06f,
            center = Offset(cx, cy),
            style = Stroke(width = r * 0.018f),
        )
    }
}

/** Live amplitude waveform, drawn as a row of vertical bars around the orb. */
@Composable
fun VoiceWaveform(
    amplitudes: List<Float>,
    modifier: Modifier = Modifier,
) {
    if (amplitudes.isEmpty()) return
    Canvas(modifier = modifier) {
        val barCount = amplitudes.size.coerceAtMost(32)
        val gap = size.width / (barCount * 2f)
        val barWidth = gap * 0.85f
        val maxBarHeight = size.height
        amplitudes.forEachIndexed { index, amp ->
            if (index >= barCount) return@forEachIndexed
            val x = gap + index * gap * 2f
            val barHeight = (maxBarHeight * amp.coerceIn(0.05f, 1f)).coerceAtLeast(maxBarHeight * 0.06f)
            drawLine(
                color = Color.White.copy(alpha = 0.55f),
                start = Offset(x, size.height / 2f - barHeight / 2f),
                end = Offset(x, size.height / 2f + barHeight / 2f),
                strokeWidth = barWidth,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * The VCM screen.
 *
 * [state] is the [me.rerere.asr.VoiceModeState] owned by the session controller; [amplitudes]
 * is the live ASR amplitude ring, used so the orb reacts to the user's voice in real time —
 * that reaction is what makes it feel alive rather than decorative, and it costs nothing
 * because every ASR controller already computes the ring.
 */
@Composable
fun VoiceConversationModeScreen(
    state: me.rerere.asr.VoiceModeState,
    amplitudes: List<Float>,
    onEnd: () -> Unit,
) {
    val orbState = when (state.status) {
        VoiceModeStatus.Listening -> OrbState.Listening
        VoiceModeStatus.Thinking -> OrbState.Thinking
        VoiceModeStatus.Speaking -> OrbState.Speaking
        VoiceModeStatus.Failed -> OrbState.Failed
        VoiceModeStatus.Idle -> return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.surfaceContainerLow,
                        MaterialTheme.colorScheme.surfaceContainer,
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: close button, so leaving is always one tap and obvious.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onEnd, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = HugeIcons.Cancel01,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            // Middle: the orb, with the live waveform around it.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(48.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(300.dp)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    VoiceOrb(
                        state = orbState,
                        amplitudes = if (state.isListening) amplitudes else emptyList(),
                        modifier = Modifier
                            .fillMaxSize()
                            .scale(0.9f),
                    )
                }

                VoiceWaveform(
                    amplitudes = if (state.isListening) amplitudes else emptyList(),
                    modifier = Modifier
                        .width(260.dp)
                        .height(40.dp),
                )
            }

            // Bottom: state text + the transcript the user can watch being heard.
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 32.dp),
            ) {
                AnimatedVisibility(
                    visible = state.transcript.isNotBlank(),
                    enter = fadeIn(tween(180)),
                    exit = fadeOut(tween(180)),
                ) {
                    Text(
                        text = state.transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Text(
                    text = when (state.status) {
                        VoiceModeStatus.Listening -> "Listening…"
                        VoiceModeStatus.Thinking -> "Thinking…"
                        VoiceModeStatus.Speaking -> "Speaking…"
                        VoiceModeStatus.Failed -> state.errorMessage ?: "Voice conversation mode stopped"
                        VoiceModeStatus.Idle -> ""
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                // A persistent hint, because the interrupt-while-speaking gesture is not
                // discoverable otherwise and it is the whole point of the mode.
                if (state.isSpeaking) {
                    Text(
                        text = "Speak any time to interrupt",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
