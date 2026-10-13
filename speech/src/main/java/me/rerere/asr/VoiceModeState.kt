package me.rerere.asr

/**
 * The speaking state of one VCM (Voice Chat Mode) session.
 *
 * The loop is LISTEN → THINK → SPEAK → LISTEN, and every automatic transition is edge-driven
 * so a stuck state is visible in the log rather than silent:
 *
 * ```
 * Idle ──SessionStarted──► Listening ──UtteranceReady──► Thinking ──FirstAudioReady──► Speaking
 *   ▲                          ▲                            │                         │    │
 *   │                          └──────────Recovered─────────┘                         │    │
 *   │                                                    (any state + Failed)         │    │
 *   └──────────────────────SessionEnded────────────────────────────────────────────────┘
 * ```
 *
 * Only [SessionEnded] can end a session from [Failed]; a transient error is recoverable and
 * returns to LISTEN on [VoiceModeEvent.Recovered] rather than tearing the session down.
 */
enum class VoiceModeStatus {
    /** No session. */
    Idle,

    /** Mic is open, VAD is running, partial transcripts may be shown live. */
    Listening,

    /** Utterance sent to the LLM; waiting for the first audio to be ready to speak. */
    Thinking,

    /** The reply is being spoken. The mic stays open so the user can barge in. */
    Speaking,

    /** Transient failure. Returns to [Listening] on [VoiceModeEvent.Recovered]. */
    Failed,
}

/** Everything the controller knows about the current session, in one immutable value. */
data class VoiceModeState(
    val status: VoiceModeStatus = VoiceModeStatus.Idle,
    /** Live partial transcript while [VoiceModeStatus.Listening]; latest full one otherwise. */
    val transcript: String = "",
    /** Present only while [VoiceModeStatus.Failed]. */
    val errorMessage: String? = null,
    /**
     * True once a session is running, i.e. the voice surface should be on screen.
     * Stays true through [VoiceModeStatus.Failed], so a recoverable error does not
     * dismiss the UI the user is looking at.
     */
    val sessionActive: Boolean = false,
) {
    val isIdle: Boolean get() = status == VoiceModeStatus.Idle
    val isListening: Boolean get() = status == VoiceModeStatus.Listening
    val isThinking: Boolean get() = status == VoiceModeStatus.Thinking
    val isSpeaking: Boolean get() = status == VoiceModeStatus.Speaking
    val isFailed: Boolean get() = status == VoiceModeStatus.Failed

    /** The mic is capturing, or should be. Drives the waveform and the "listening" affordance. */
    val isCapturing: Boolean
        get() = sessionActive && (status == VoiceModeStatus.Listening || status == VoiceModeStatus.Thinking)
}

/**
 * Inputs to [VoiceModeState.transition].
 *
 * Kept a sealed hierarchy rather than plain method calls so the transition table is one
 * exhaustive `when` — a new state cannot forget to handle an event, and the whole table is
 * unit-testable off-device with no Android objects involved.
 */
sealed class VoiceModeEvent {
    /** User entered voice mode. */
    data object SessionStarted : VoiceModeEvent()

    /** Partial ASR result, shown live while the user is still talking. */
    data class PartialTranscript(val text: String) : VoiceModeEvent()

    /**
     * VAD declared end of turn. Ignored when [text] is blank, so silence alone never
     * sends an empty utterance to the LLM.
     */
    data class UtteranceReady(val text: String) : VoiceModeEvent()

    /** First chunk of TTS audio is playable; the user can start hearing the reply. */
    data object FirstAudioReady : VoiceModeEvent()

    /** TTS finished the whole queued reply. */
    data object PlaybackFinished : VoiceModeEvent()

    /** User started speaking over the reply. TTS must be stopped by the caller. */
    data object UserBargeIn : VoiceModeEvent()

    /** Transient failure (network, provider, transcription). Recoverable. */
    data class Failed(val message: String) : VoiceModeEvent()

    /** A [Failed] session recovered and should resume listening. */
    data object Recovered : VoiceModeEvent()

    /** User left voice mode, or the session cap elapsed. Only way out of [VoiceModeStatus.Idle]. */
    data object SessionEnded : VoiceModeEvent()
}

/**
 * The single transition function. Pure: no clock, no Android, no coroutines, so the whole
 * table is deterministic and testable on a bare JVM.
 *
 * Events that do not apply to the current state are deliberately ignored (return the same
 * state) rather than throwing: a WebSocket teardown and a VAD edge can easily race, and a
 * late duplicate should not crash a live voice session.
 */
fun VoiceModeState.transition(event: VoiceModeEvent): VoiceModeState = when (event) {
    VoiceModeEvent.SessionStarted -> if (isIdle) {
        VoiceModeState(status = VoiceModeStatus.Listening, sessionActive = true)
    } else {
        this
    }

    is VoiceModeEvent.PartialTranscript -> if (isListening) {
        copy(transcript = event.text)
    } else {
        this
    }

    is VoiceModeEvent.UtteranceReady -> if (isListening) {
        // A blank utterance means the VAD fired on noise or an empty final flush. Drop it
        // but keep the (now empty) transcript so stale text is not shown twice.
        if (event.text.isBlank()) {
            copy(transcript = "")
        } else {
            VoiceModeState(
                status = VoiceModeStatus.Thinking,
                transcript = event.text,
                sessionActive = true,
            )
        }
    } else {
        this
    }

    VoiceModeEvent.FirstAudioReady -> if (isThinking) {
        copy(status = VoiceModeStatus.Speaking)
    } else {
        this
    }

    VoiceModeEvent.PlaybackFinished -> if (isSpeaking) {
        // Loop closes here. The transcript is cleared so the next turn starts clean.
        VoiceModeState(status = VoiceModeStatus.Listening, sessionActive = true)
    } else {
        this
    }

    VoiceModeEvent.UserBargeIn -> if (isIdle) {
        this
    } else {
        VoiceModeState(status = VoiceModeStatus.Listening, sessionActive = true)
    }

    is VoiceModeEvent.Failed -> if (isIdle) {
        this
    } else {
        VoiceModeState(
            status = VoiceModeStatus.Failed,
            transcript = transcript,
            errorMessage = event.message,
            sessionActive = true,
        )
    }

    VoiceModeEvent.Recovered -> if (isFailed) {
        VoiceModeState(status = VoiceModeStatus.Listening, sessionActive = true)
    } else {
        this
    }

    VoiceModeEvent.SessionEnded -> if (isIdle) {
        this
    } else {
        VoiceModeState(status = VoiceModeStatus.Idle)
    }
}
