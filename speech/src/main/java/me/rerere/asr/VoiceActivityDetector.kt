package me.rerere.asr

/**
 * Client-side voice-activity detection, used by VCM (Voice Chat Mode) to decide when the
 * user has finished an utterance so the loop can hand off to the LLM.
 *
 * Why this exists at all: server-side VAD — OpenAI Realtime's `speech_started` /
 * `speech_stopped`, Volcengine's `definite` flag — only exists on *streaming* providers.
 * A plain OpenAI-compatible endpoint (Groq `whisper-large-v3`, HuggingFace Inference,
 * vLLM, faster-whisper-server, or any self-hosted `/audio/transcriptions`) is a one-shot
 * HTTP call with no session and therefore no turn detection. Reading the amplitude ring
 * that every ASR controller already computes gives those providers the same turn-taking
 * behaviour, and works identically for the streaming ones too.
 *
 * Feed it the same values that populate `ASRState.amplitudes` — 0f..1f, dB-scaled by
 * [calculateRmsAmplitude] — one frame at a time, in order.
 *
 * Pure Kotlin: no Android, no coroutines, no clock. Every input is explicit so the
 * behaviour is fully deterministic and unit-testable off-device.
 */
class VoiceActivityDetector(
    /** Frame amplitude above which counts as speech. See [DEFAULT_THRESHOLD]. */
    private val speechThreshold: Float = DEFAULT_THRESHOLD,
    /** Consecutive silent frames required to declare end of turn. See [DEFAULT_HANGOVER]. */
    private val hangoverFrames: Int = DEFAULT_HANGOVER,
    /** Frames of speech before a turn may fire. Guards against a cough or a stray tap. */
    private val minUtteranceFrames: Int = DEFAULT_MIN_UTTERANCE,
) {
    init {
        require(speechThreshold in 0f..1f) { "speechThreshold must be 0..1, was $speechThreshold" }
        require(hangoverFrames >= 0) { "hangoverFrames must be >= 0, was $hangoverFrames" }
        require(minUtteranceFrames >= 0) { "minUtteranceFrames must be >= 0, was $minUtteranceFrames" }
    }

    private var speechFrames = 0
    private var silenceFrames = 0

    /** True once any speech has been seen in the current turn; resets on [reset]. */
    var hasSpeech: Boolean = false
        private set

    /**
     * Feed one amplitude frame.
     *
     * @return true exactly on the frame where the turn ends, i.e. the frame that completes
     *   the hangover after a long-enough utterance. Never true on consecutive frames, so a
     *   caller can treat `true` as a one-shot "send now" edge rather than a level.
     */
    fun next(amplitude: Float): Boolean {
        if (amplitude > speechThreshold) {
            speechFrames++
            silenceFrames = 0
            hasSpeech = true
            return false
        }
        if (!hasSpeech) return false

        silenceFrames++
        return if (silenceFrames >= hangoverFrames) {
            val utterance = speechFrames
            val completed = utterance >= minUtteranceFrames
            reset()
            // A too-short utterance is discarded, but speech has still ended, so the
            // next real utterance starts from a clean slate either way.
            completed
        } else {
            false
        }
    }

    /** Abandon the current turn (barge-in, session stop, provider switch). */
    fun reset() {
        speechFrames = 0
        silenceFrames = 0
        hasSpeech = false
    }

    companion object {
        /**
         * ~0.15 (about −47 dB). Sustained speech sits near 0.5–0.75 and ordinary room noise
         * near 0.02–0.05, so the gap is wide and the default is deliberately conservative.
         * Cheap mics run noisier; expose this in settings rather than guessing per-device.
         */
        const val DEFAULT_THRESHOLD = 0.15f

        /**
         * 7 frames ≈ 700 ms of silence. Matches the range the existing providers already use
         * (`OpenAIRealtime.silenceDurationMs` = 500, Volcengine `end_window_size` clamped to
         * 300..5000) so VCM behaves no differently on a streaming provider than on Groq.
         */
        const val DEFAULT_HANGOVER = 7

        /** 3 frames ≈ 300 ms. A cough or a bump to the phone must not fire a turn. */
        const val DEFAULT_MIN_UTTERANCE = 3

        /**
         * Frames are ~100 ms of audio at any supported sample rate, because every controller
         * reads `AudioRecord` in `sampleRate / 10 * 2` byte chunks. Only relevant if a future
         * controller buffers differently.
         */
        const val FRAME_MS = 100L

        /**
         * Convert a millisecond hangover to the frame count [VoiceActivityDetector] wants.
         * Kept here so callers store a human-friendly setting and never do the arithmetic
         * inline in two places.
         */
        fun framesForHangoverMs(hangoverMs: Int): Int =
            ((hangoverMs / FRAME_MS).toInt()).coerceAtLeast(1)
    }
}
