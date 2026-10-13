package me.rerere.asr

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * Splits a streamed reply into speakable sentences.
 *
 * Why: feeding [kotlinx.coroutines.flow.Flow] tokens straight to TTS would make the user
 * wait for the whole reply before hearing anything - the walkie-talkie latency that makes
 * a voice mode feel broken. Flushing on sentence boundaries instead lets the first audio
 * start ~300ms after the LLM's first sentence, while the rest is still generating.
 *
 * **Contract: [flush] must be called when the stream ends.** The splitter deliberately
 * refuses to guess, because both failure modes are asymmetric:
 *
 *  - guessing wrong cuts `3.14` into `3.` + `14`, or an abbreviation in half, and TTS
 *    then reads text the user never said - unrecoverable
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
     * Append a token (usually one [kotlinx.coroutines.flow.Flow] delta) and return any
     * sentences that are now complete. May return empty repeatedly; that is the point.
     */
    fun append(token: String): List<String> {
        if (token.isEmpty()) return emptyList()
        buffer.append(token)
        return scan()
    }

    /** The text held back so far, exposed for the "still transcribing" style of UI. */
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
 * knows nothing about settings, assistants, models or tools - all of which the chat layer
 * already assembles. That keeps the whole loop testable with a stub flow, and keeps a
 * 900-line generation file from leaking into the voice path.
 *
 * VAD and the state machine are pure (see [VoiceActivityDetector], [VoiceModeState]), so
 * what is left here is genuinely just wiring and lifecycle.
 */
class VoiceModeController(
    private val scope: CoroutineScope,
    /** Streams [StreamChunk]-normalised messages; the concrete type comes from the caller. */
    private val onTurn: suspend (utterance: String) -> Flow<List<Chunk>>,
    private val onStateChange: (VoiceModeState) -> Unit,
) {
    private var vad: VoiceActivityDetector? = null
    private var generationJob: Job? = null
    private var state: VoiceModeState = VoiceModeState()

    /** The last transcript, kept so the UI can show what was heard after playback ends. */
    val currentState: VoiceModeState get() = state

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
     * Feed one ASR amplitude frame. Returns true if this frame ended the turn, i.e. the
     * caller should expect [utteranceReady] to follow shortly.
     */
    fun onAmplitude(amplitude: Float): Boolean {
        if (!state.isListening) return false
        val detector = vad ?: return false
        val ended = detector.next(amplitude)
        if (ended) {
            // The utterance text itself arrives via onTranscript; VAD only says *when*.
            // A blank guard here too, since the ASR layer may emit an empty final flush.
            val text = state.transcript
            if (text.isNotBlank()) {
                emit(state.transition(VoiceModeEvent.UtteranceReady(text)))
                runTurn(text)
            } else {
                vad?.reset()
            }
        }
        return ended
    }

    /** Partial or final transcript from the ASR layer. */
    fun onTranscript(text: String) {
        if (state.isListening) {
            emit(state.transition(VoiceModeEvent.PartialTranscript(text)))
        }
    }

    /**
     * Barked-in: the user started speaking over the reply. The caller is responsible for
     * stopping TTS - [onTurn]'s flow is cancelled here so the LLM stops streaming too.
     */
    fun bargeIn() {
        generationJob?.cancel()
        generationJob = null
        if (state.isIdle) return
        emit(state.transition(VoiceModeEvent.UserBargeIn))
    }

    private fun runTurn(utterance: String) {
        generationJob?.cancel()
        generationJob = scope.launch {
            try {
                val splitter = StreamingTextSplitter()
                onTurn(utterance).collect { chunk ->
                    if (!state.isThinking) return@collect
                    when (chunk) {
                        is Chunk.Text -> {
                            val sentences = splitter.append(chunk.text)
                            sentences.forEach { sentence ->
                                // flushCalled=false appends to the TTS chunk queue, so
                                // sentences play back-to-back without a gap.
                                onSpeakSentence(sentence, flushCalled = false)
                            }
                        }

                        is Chunk.Finished -> {
                            splitter.flush().forEach { onSpeakSentence(it, flushCalled = true) }
                            emit(state.transition(VoiceModeEvent.FirstAudioReady))
                        }
                    }
                }
                // Stream ended without a Finished marker - still flush the tail.
                splitter.flush().forEach { onSpeakSentence(it, flushCalled = true) }
                if (state.isThinking) {
                    emit(state.transition(VoiceModeEvent.FirstAudioReady))
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (t: Throwable) {
                emit(state.transition(VoiceModeEvent.Failed(t.message ?: "generation failed")))
            }
        }
    }

    /** Overridden by the UI layer to actually call TTS. Default no-op keeps tests simple. */
    var onSpeakSentence: (sentence: String, flushCalled: Boolean) -> Unit = { _, _ -> }

    /** Overridden by the UI layer when TTS finishes the whole queue. */
    fun onPlaybackFinished() {
        if (state.isSpeaking) emit(state.transition(VoiceModeEvent.PlaybackFinished))
    }

    fun dispose() {
        generationJob?.cancel()
        scope.cancel()
    }
}

/** Minimal provider-agnostic reply surface, so the controller does not import :ai. */
sealed class Chunk {
    data class Text(val text: String) : Chunk()
    data object Finished : Chunk()
}
