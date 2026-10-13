package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import me.rerere.asr.ASRStatus
import me.rerere.asr.Chunk
import me.rerere.asr.VoiceModeController
import me.rerere.asr.VoiceModeState
import me.rerere.rikkahub.service.ChatService
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.hooks.CustomAsrState
import me.rerere.rikkahub.ui.hooks.CustomTtsState
import me.rerere.rikkahub.ui.hooks.rememberCustomAsrState
import me.rerere.rikkahub.ui.hooks.rememberCustomTtsState
import org.koin.compose.koinInject
import kotlin.uuid.Uuid

/**
 * One VCM (Voice Conversation Mode) session.
 *
 * This is the only new logic voice conversation mode needed: the VAD, the state machine, the
 * sentence splitter and the screen already existed, but nothing connected them to the app's
 * ASR, TTS and generation.
 *
 * Shaped like `rememberCustomAsrState` / `rememberCustomTtsState` so it reads like the rest
 * of the codebase — a plain class, remembered by a `@Composable` and torn down by
 * `DisposableEffect`.
 *
 * ### The Groq-specific part, which is the whole reason this exists
 * A one-shot HTTP provider (Groq, HuggingFace, vLLM, anything self-hosted behind
 * `/audio/transcriptions`) only produces a transcript when its buffer flushes, and that
 * happens either every `segmentDurationSec` or on `stop()`. Streaming providers with
 * server-side VAD emit `voiceTurn.speechEnded` and never need this.
 *
 * So client VAD has to *stop* the recogniser to obtain the text: [onAmplitude] returning
 * true leads to `asr.stop()`, and the next transcript that arrives is treated as the final
 * utterance. `segmentDurationSec = 0` on the provider is what makes this correct — it turns
 * off the periodic flush so one utterance means exactly one request.
 *
 * Consequence worth being honest about: each utterance costs one HTTP round-trip before the
 * LLM even starts, and there is no live partial transcript on those providers, because the
 * text only exists once the flush lands. The orb still reacts live, since amplitudes come
 * from the microphone rather than from the transcript.
 *
 * ### Known limitation: audio focus
 * `CustomAsrStateImpl` requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`, which tells the
 * platform the mic owns audio. While TTS plays a reply that can cause capture to be ducked or
 * dropped depending on OEM, breaking barge-in and the following turn. That request needs to
 * become shared (or be re-requested) when the session enters SPEAKING — a follow-up, since it
 * means changing `ASR.kt`'s focus request and therefore every ASR consumer.
 */
class VoiceConversationSession(
    private val asr: CustomAsrState,
    private val tts: CustomTtsState,
    private val chatService: ChatService,
    private val conversationId: Uuid,
    private val onExit: () -> Unit,
) {
    var state: VoiceModeState by mutableStateOf(VoiceModeState())
        private set

    private var scope: CoroutineScope? = null
    private var controller: VoiceModeController? = null

    /** True when the recogniser is live and the session should be on screen. */
    val isActive: Boolean get() = state.sessionActive

    fun start() {
        if (isActive) return
        val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = sessionScope

        val created = VoiceModeController(
            scope = sessionScope,
            onTurn = ::runTurn,
            onStateChange = { state = it },
        )
        controller = created

        // Sentences go straight to the TTS queue; flushCalled=false appends so consecutive
        // sentences play back-to-back rather than each restarting the queue.
        created.onSpeakSentence = { sentence, flushCalled ->
            tts.speak(sentence, flushCalled)
        }

        asr.start { transcript ->
            if (awaitingFinalTranscript) {
                awaitingFinalTranscript = false
                asrRestartPending = true
                created.utteranceReady(transcript)
            } else {
                created.onTranscript(transcript)
            }
        }
        created.start()
    }

    /** Set while waiting for the recogniser to flush its final transcript. */
    private var awaitingFinalTranscript = false

    /** Set once a final transcript has been consumed, so the mic can be reopened. */
    private var asrRestartPending = false

    fun stop() {
        awaitingFinalTranscript = false
        asrRestartPending = false
        asr.stop()
        tts.stop()
        controller?.stop()
        controller = null
        scope?.cancel()
        scope = null
    }

    /**
     * Forward one recogniser amplitude frame.
     *
     * While LISTENING this is turn detection. While SPEAKING it is barge-in — the mic is
     * expected to stay open so the user can interrupt, and the check lives here rather than
     * in [VoiceModeController] because that one ignores amplitudes unless it is listening.
     */
    fun onAmplitude(amplitude: Float) {
        val c = controller ?: return
        if (state.isSpeaking) {
            if (amplitude > BARGE_IN_THRESHOLD) {
                tts.stop()
                c.bargeIn()
            }
            return
        }
        if (c.onAmplitude(amplitude)) {
            awaitingFinalTranscript = true
            asr.stop()
        }
    }

    /** Reopen the mic after a final transcript has been consumed. */
    fun resumeListening() {
        if (!asrRestartPending) return
        asrRestartPending = false
        asr.start { transcript ->
            val c = controller ?: return@start
            if (awaitingFinalTranscript) {
                awaitingFinalTranscript = false
                c.utteranceReady(transcript)
            } else {
                c.onTranscript(transcript)
            }
        }
    }

    fun onPlaybackFinished() {
        controller?.onPlaybackFinished()
    }

    /**
     * The LLM hop.
     *
     * Reuses `ChatService.enqueueVoiceMessage` — the same call the existing voice mode
     * makes — so conversation history, the tool-approval guards and the "voice owns playback"
     * semantics are inherited instead of reimplemented. It returns the reply as one string
     * once generation completes.
     *
     * That means the reply is not sentence-streamed, so the first audio starts when the whole
     * answer is ready. Upgrading to `generateInternal(...)` + `voiceChunksOf(...)` would give
     * first audio ~300ms after the first sentence, but it means assembling settings,
     * assistant, model, messages and transformers, and bypassing the message queue. That is a
     * larger change than this first wiring, and it is recorded in the VCM plan rather than
     * silently skipped.
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

    companion object {
        /**
         * Speaking at all, as opposed to the ~0.15 VAD threshold: this is an interruption
         * gesture, so it wants to be sensitive. Matches what [VoiceModeController] treats as
         * speech.
         */
        const val BARGE_IN_THRESHOLD = 0.15f
    }
}

/**
 * Remembers one VCM session for the lifetime of the composition.
 *
 * @param conversationId the conversation the spoken turns belong to
 * @param onDismiss called when the session cannot continue (no ASR provider configured, or
 *   the recogniser errors), so the caller can close the voice surface.
 */
@Composable
fun rememberVoiceConversationSession(
    conversationId: Uuid,
    onDismiss: () -> Unit,
): VoiceConversationSession {
    val asr = rememberCustomAsrState()
    val tts = rememberCustomTtsState()
    val chatService = koinInject<ChatService>()

    var dismissed by remember { mutableStateOf(false) }
    fun dismissOnce() {
        if (!dismissed) {
            dismissed = true
            onDismiss()
        }
    }

    val session = remember(conversationId) {
        VoiceConversationSession(
            asr = asr,
            tts = tts,
            chatService = chatService,
            conversationId = conversationId,
            onExit = ::dismissOnce,
        )
    }

    // Amplitudes are observed by sampling the state's last frame, because ASRState carries
    // the ring as a value rather than a flow of frames. snapshotFlow re-emits only when the
    // ring actually changes, so this does not spin.
    val asrStateValue by asr.state.collectAsStateWithLifecycle()
    LaunchedEffect(session) {
        snapshotFlow { asrStateValue.amplitudes.lastOrNull() ?: 0f }
            .collect { amplitude -> session.onAmplitude(amplitude) }
    }

    // TTS playback draining is what closes the loop and reopens the mic.
    val isSpeaking by tts.isSpeaking.collectAsStateWithLifecycle()
    LaunchedEffect(session, isSpeaking) {
        if (!isSpeaking && session.state.isSpeaking) {
            session.onPlaybackFinished()
            session.resumeListening()
        }
    }

    // A provider error must end the session rather than leaving the user on a dead surface.
    LaunchedEffect(session, asrStateValue.status) {
        if (asrStateValue.status == ASRStatus.Error && session.isActive) {
            session.stop()
            dismissOnce()
        }
    }

    DisposableEffect(session) {
        onDispose {
            session.stop()
        }
    }

    return session
}

/**
 * Requests the microphone permission before VCM starts, so the session does not open into
 * an immediately-failed state.
 *
 * Returns the permission state; the caller decides whether to start. Mirrors how
 * `ChatInput` gates its ASR button.
 */
@Composable
fun rememberVoiceConversationPermission() = rememberPermissionState(PermissionRecordAudio)
