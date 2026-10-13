package me.rerere.asr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Splits a streamed reply into speakable sentences.
 *
 * Why: feeding tokens straight to TTS would make the user wait for the whole reply before
 * hearing anything — the walkie-talkie latency that makes a voice mode feel broken.
 * Flushing on sentence boundaries instead lets the first audio start ~300ms after the
 * LLM's first sentence, while the rest is still generating.
 *
 * **Contract: [flush] must be called when the stream ends.** The splitter deliberately
 * refuses to guess, because the two failure modes are asymmetric:
 *
 *  - guessing wrong cuts `3.14` into `3.` + `14`, or an abbreviation in half, and TTS then
 *    reads text the user never said — unrecoverable
 *  - waiting when it could have spoken only delays audio slightly, and [flush] recovers
 *    the tail
 *
 * So a boundary is accepted only when punctuation is followed by whitespace *inside* the
 * buffer. A buffer ending in `.` is ambiguous (the next token may be `5`, completing
 * `2.5`) and is held until more text arrives.
 *
 * Not thread-safe: one instance per collection, driven from a single coroutine.
 */
class StreamingTextSplitter {
    private val buffer = StringBuilder()

    /**
     * Append a token (usually one flow delta) and return any sentences that are now
     * complete. May return empty repeatedly; that is the point.
     */
    fun append(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        buffer.append(token)
        return scan()
    }

    /** The text held back so far, exposed so the UI can show still-accumulating speech. */
    fun pending(): String = buffer.toString()

    /** Emit whatever remains, ending with no trailing boundary. Always safe to call. */
    fun flush(): List<String> {
        val rest = buffer.toString().trim()
        buffer.setLength(0)
        return if (rest.isEmpty()) emptyList() else listOf(rest)
    }

    private fun scan(): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < buffer.length) {
            val c = buffer[i]
            if (c == '\n') {
                val line = buffer.substring(0, i).trim()
                if (line.isNotEmpty()) out.add(line)
                buffer.delete(0, i + 1)
                i = 0
                continue
            }
            if (c == '.' || c == '!' || c == '?') {
                var j = i
                while (j + 1 < buffer.length && buffer[j + 1] == c) j++
                val after = buffer.getOrNull(j + 1)
                if (after == ' ' || after == '\t' || after == '\n') {
                    val sentence = buffer.substring(0, j + 1).trim()
                    if (sentence.isNotEmpty()) out.add(sentence)
                    buffer.delete(0, j + 1)
                    i = 0
                    continue
                }
                if (after == null) {
                    // Buffer ends mid-punctuation-run. Hold: the next token decides.
                    return out
                }
                // Punctuation with no following space, e.g. "3.14" or "e.g." mid-word.
                // Not a boundary; keep scanning past it.
                i = j + 1
            } else {
                i++
            }
        }
        return out
    }
}

/**
 * One VCM session: owns the ASR/TTS/LLM loop and the [VoiceModeState] transition table.
 *
 * The LLM hop is injected as [onTurn] rather than reaching into ChatService, so this class
 * knows nothing about settings, assistants, models or tools — all of which the chat layer
 * already assembles. That keeps the loop testable with a stub flow, and keeps a 900-line
 * generation file from leaking into the voice path.
 *
 * VAD and the state machine are pure (see [VoiceActivityDetector], [VoiceModeState]), so
 * what is left here is genuinely just wiring and lifecycle.
 */
class VoiceModeController(
    private val scope: CoroutineScope,
    /**
     * Runs one utterance and streams the reply back. The [Flow] is cancelled when the user
     * barges in, so implementations must be cancellation-aware (a plain `flow {}` is).
     */
    private val onTurn: suspend (utterance: String) -> Flow<Chunk>,
    private val onStateChange: (VoiceModeState) -> Unit,
) {
    private var vad: VoiceActivityDetector? = null
    private var generationJob: Job? = null
    private var state: VoiceModeState = VoiceModeState()

    /** Latest state, for UI that reads without collecting. */
    val currentState: VoiceModeState get() = state

    /**
     * Called with each sentence to speak. [flushCalled] should be forwarded to TTS: false
     * appends to the existing chunk queue so sentences play back-to-back, true marks the
     * end of the reply.
     */
    var onSpeakSentence: (sentence: String, flushCalled: Boolean) -> Unit = { _, _ -> }

    private fun emit(next: VoiceModeState) {
        state = next
        onStateChange(next)
    }

    /** Enter voice mode. Idempotent: a second call is ignored, so no double session opens. */
    fun start() {
        if (!state.isIdle) return
        vad = VoiceActivityDetector()
        emit(state.transition(VoiceModeEvent.SessionStarted))
    }

    /** Leave voice mode, cancelling any in-flight generation. */
    fun stop() {
        generationJob?.cancel()
        generationJob = null
        vad = null
        emit(state.transition(VoiceModeEvent.SessionEnded))
    }

    /**
     * Feed one ASR amplitude frame.
     *
     * @return true if this frame ended the turn, so the caller should call [utteranceReady]
     *   with the final transcript. The text is deliberately *not* read from [currentState]:
     *   partial ASR and VAD are two independent async sources, and deriving the utterance
     *   from whichever happened to win the race is how a turn gets sent half-finished.
     */
    fun onAmplitude(amplitude: Float): Boolean {
        if (!state.isListening) return false
        val detector = vad ?: return false
        val ended = detector.next(amplitude)
        if (ended && state.transcript.isBlank()) {
            // VAD fired on noise or an empty final flush. Discard and keep listening
            // rather than sending an empty turn.
            detector.reset()
        }
        return ended
    }

    /** Partial transcript from the ASR layer, shown live while the user is talking. */
    fun onTranscript(text: String) {
        if (state.isListening) {
            emit(state.transition(VoiceModeEvent.PartialTranscript(text)))
        }
    }

    /**
     * The ASR layer's final transcript. Blank text is dropped by the state machine, so it is
     * safe to call with whatever the provider produced, including an empty string.
     */
    fun utteranceReady(text: String) {
        if (!state.isListening) return
        emit(state.transition(VoiceModeEvent.UtteranceReady(text)))
        if (state.isThinking) runTurn(text)
    }

    /**
     * Barge-in: the user started speaking over the reply. The caller stops TTS; the
     * generation flow is cancelled here so the LLM stops streaming too.
     */
    fun bargeIn() {
        generationJob?.cancel()
        generationJob = null
        emit(state.transition(VoiceModeEvent.UserBargeIn))
    }

    private fun runTurn(utterance: String) {
        generationJob?.cancel()
        generationJob = scope.launch {
            try {
                val splitter = StreamingTextSplitter()
                var firstAudioAnnounced = false
                // Fires on the first sentence actually queued to TTS, not on stream end:
                // that is the moment the user can start hearing the reply.
                val announceFirstAudio = {
                    if (!firstAudioAnnounced) {
                        firstAudioAnnounced = true
                        emit(state.transition(VoiceModeEvent.FirstAudioReady))
                    }
                }

                onTurn(utterance).collect { chunk ->
                    if (!state.isThinking) return@collect
                    when (chunk) {
                        is Chunk.Text -> splitter.append(chunk.text).forEach { sentence ->
                            onSpeakSentence(sentence, false)
                            announceFirstAudio()
                        }

                        is Chunk.Finished -> splitter.flush().forEach { sentence ->
                            onSpeakSentence(sentence, true)
                            announceFirstAudio()
                        }
                    }
                }

                // Stream ended without a Finished marker (or it was the last thing) — the
                // tail still has to be flushed and announced, or the state stays in
                // Thinking forever.
                splitter.flush().forEach { sentence ->
                    onSpeakSentence(sentence, true)
                    announceFirstAudio()
                }
                if (state.isThinking) announceFirstAudio()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                emit(state.transition(VoiceModeEvent.Failed(t.message ?: "generation failed")))
            }
        }
    }

    /** Called by the UI when TTS drains the queue for the whole reply. */
    fun onPlaybackFinished() {
        emit(state.transition(VoiceModeEvent.PlaybackFinished))
    }

    fun dispose() {
        generationJob?.cancel()
        scope.cancel()
    }
}

/**
 * Minimal provider-agnostic reply surface, so the controller in `:speech` does not have to
 * depend on `:ai`. The adapter that maps `GenerationChunk` onto this lives in `:app`, where
 * `:ai` is already a dependency.
 */
sealed class Chunk {
    data class Text(val text: String) : Chunk()
    data object Finished : Chunk()
}
