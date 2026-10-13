package me.rerere.rikkahub.ui.pages.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.asr.ASRStatus
import me.rerere.asr.Chunk
import me.rerere.asr.VoiceModeController
import me.rerere.asr.VoiceModeState
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * Everything one VCM (Voice Conversation Mode) session needs, in one place.
 *
 * This is the *only* new logic needed to make voice conversation mode run: the VAD, the
 * state machine, the sentence splitter and the screen already exist. What was missing was
 * something to wire them to the app's ASR, TTS and generation.
 *
 * The shape follows `rememberCustomAsrState` / `rememberCustomTtsState` so it reads like the
 * rest of the codebase: a `@Composable` returning a state holder, with `DisposableEffect`
 * for teardown.
 *
 * ### The Groq-specific part
 * A one-shot HTTP provider (Groq, HuggingFace, vLLM, anything self-hosted behind
 * `/audio/transcriptions`) only produces a transcript when its buffer is flushed, which
 * happens either every `segmentDurationSec` or on `stop()`. So client VAD has to *stop*
 * the recogniser to get the text: [onAmplitude] returning true leads to `asr.stop()`, and
 * the final transcript is taken from whatever arrives after that.
 *
 * Consequence worth knowing: each utterance costs one HTTP round-trip before the LLM even
 * starts, and there is no live partial transcript on those providers — the sentence appears
 * all at once when the flush lands. The orb still reacts live, because amplitudes come from
 * the microphone rather than from the transcript.
 *
 * ### Known limitation: audio focus
 * `rememberCustomAsrState` requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, which the
 * platform interprets as "the mic owns audio". While TTS plays a reply, an exclusive
 * request can cause the mic capture to be ducked or dropped depending on OEM, which would
 * break both barge-in and the next turn. This is filed as a follow-up: the request needs to
 * become a shared one (or be re-requested) when the session enters SPEAKING. Until then,
 * expect barge-in to work on some devices and not others, and treat any "not hearing the
 * user mid-reply" report as this and not the VAD.
 */
class VoiceConversationSession(
    private val appContext: Context,
    private val chatService: ChatService,
    private val conversationId: Uuid,
    private val asr: me.rerere.rikkahub.ui.hooks.CustomAsrState,
    private val tts: me.rerere.rikkahub.ui.hooks.CustomTtsState,
    private val onExit: () -> Unit,
) {
    var state: VoiceModeState by mutableStateOf(VoiceModeState())
        private set

    private var controller: VoiceModeController? = null
    private var awaitingFinalTranscript = false
    private var sessionScope: kotlinx.coroutines.CoroutineScope? = null

    /** True once the recogniser is running and the session is live. */
    val isActive: Boolean get() = state.sessionActive

    fun start() {
        if (isActive) return
        val scope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main,
        )
        sessionScope = scope

        val created = VoiceModeController(
            scope = scope,
            onTurn = ::runTurn,
            onStateChange = { state = it },
        )
        controller = created

        created.onSpeakSentence = { sentence, flushCalled ->
            tts.speak(sentence, flushCalled)
        }

        // ASR transcripts feed both the live partial text and the final utterance.
        asr.start { transcript ->
            if (awaitingFinalTranscript) {
                awaitingFinalTranscript = false
                created.utteranceReady(transcript)
            } else {
                created.onTranscript(transcript)
            }
        }
        created.start()
    }

    fun stop() {
        awaitingFinalTranscript = false
        asr.stop()
        tts.stop()
        controller?.stop()
        controller = null
        sessionScope?.cancel()
        sessionScope = null
    }

    /**
     * One amplitude frame from the recogniser. Drives turn detection while listening and
     * barge-in while the reply is playing.
     */
    fun onAmplitude(amplitude: Float) {
        val c = controller ?: return
        if (state.isSpeaking) {
            // Mic is expected open during playback so the user can interrupt. The check is
            // here rather than in VoiceModeController because that one deliberately ignores
            // amplitudes unless it is in Listening.
            if (amplitude > 0.15f) {
                tts.stop()
                c.bargeIn()
            }
            return
        }
        if (c.onAmplitude(amplitude)) {
            // Turn ended. A one-shot provider only emits its transcript on stop(), so
            // flush now and wait for the text to arrive through the asr callback.
            awaitingFinalTranscript = true
            asr.stop()
        }
    }

    /** Mirrors TTS state so the loop can close when the queue drains. */
    fun onPlaybackFinished() {
        controller?.onPlaybackFinished()
    }

    /**
     * The LLM hop.
     *
     * Uses `ChatService.enqueueVoiceMessage`, the same path the existing voice mode uses,
     * so the conversation history, the tool-approval guards and the "voice owns playback"
     * semantics are all reused rather than reimplemented. It returns the reply as one
     * string once generation completes.
     *
     * That means the reply is not sentence-streamed: the first audio starts when the whole
     * answer is ready. Upgrading to `generateInternal(...)` + `voiceChunksOf(...)` would
     * give first audio ~300ms after the first sentence, but requires assembling settings,
     * assistant, model, messages and transformers, and bypasses the message queue — a
     * larger change than this first wiring.
     */
    private fun runTurn(utterance: String): Flow<Chunk> = flow {
        val reply = try {
            chatService.enqueueVoiceMessage(conversationId, utterance).await()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (t: Throwable) {
            emit(Chunk.Finished)
            return@flow
        }
        val text = reply.orEmpty()
        if (text.isNotBlank()) emit(Chunk.Text(text))
        emit(Chunk.Finished)
    }
}

/**
 * Remembers one VCM session for the lifetime of the composition.
 *
 * @param conversationId the conversation the spoken turns belong to
 * @param onDismiss called when the user closes the voice surface, so the caller can
 *   navigate away. The session itself is stopped here regardless.
 */
@Composable
fun rememberVoiceConversationSession(
    conversationId: Uuid,
    onDismiss: () -> Unit,
): VoiceConversationSession {
    val context = LocalContext.current
    val chatService = koinInject<ChatService>()
    val asr = rememberCustomAsrState()
    val tts = rememberCustomTtsState()

    val session = remember(conversationId) {
        VoiceConversationSession(
            appContext = context.applicationContext,
            chatService = chatService,
            conversationId = conversationId,
            asr = asr,
            tts = tts,
            onExit = onDismiss,
        )
    }

    val scope = rememberCoroutineScope()

    // Amplitudes come from the recogniser, not from a VAD of our own, so they are
    // collected here and forwarded. One collection per session.
    LaunchedEffect(session) {
        asr.state.collectAsStateWithLifecycle().value.let { asrState ->
            asrState.amplitudes.lastOrNull()?.let { session.onAmplitude(it) }
        }
    }

    // TTS playback state decides when the loop re-opens the mic.
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    LaunchedEffect(session, isSpeaking) {
        if (!isSpeaking && session.state.isSpeaking) session.onPlaybackFinished()
    }

    // The recogniser's own status still matters: a provider error has to surface.
    val asrStateValue by asr.state.collectAsStateWithLifecycle()
    LaunchedEffect(session, asrStateValue.status) {
        if (asrStateValue.status == ASRStatus.Error && session.isActive) {
            session.stop()
            onDismiss()
        }
    }

    DisposableEffect(session) {
        onDispose {
            scope.launch { session.stop() }
        }
    }

    return session
}
