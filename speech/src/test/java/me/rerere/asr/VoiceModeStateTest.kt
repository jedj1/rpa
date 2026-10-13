package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exhaustive coverage of the VCM transition table. Everything here is a pure call, so each
 * test is one state, one event, one expected state — no coroutines, no Android, no clock.
 */
class VoiceModeStateTest {

    private val idle = VoiceModeState()

    // --- session lifecycle ----------------------------------------------------------------

    @Test
    fun `starting from idle opens the session and begins listening`() {
        val next = idle.transition(VoiceModeEvent.SessionStarted)
        assertTrue(next.isListening)
        assertTrue(next.sessionActive)
    }

    @Test
    fun `a second start is ignored`() {
        // Guard against a double-tap opening two sessions and leaking the mic.
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        assertTrue(listening.isListening)
        assertEquals(listening, listening.transition(VoiceModeEvent.SessionStarted))
    }

    @Test
    fun `session can be ended from every active state`() {
        val thinking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("hello"))
        val speaking = thinking.transition(VoiceModeEvent.FirstAudioReady)
        val failed = speaking.transition(VoiceModeEvent.Failed("boom"))

        listOf(
            idle.transition(VoiceModeEvent.SessionStarted),
            thinking,
            speaking,
            failed,
        ).forEach { state ->
            val ended = state.transition(VoiceModeEvent.SessionEnded)
            assertTrue("SessionEnded must return to Idle", ended.isIdle)
            assertFalse("a dead session must not stay active", ended.sessionActive)
        }
    }

    @Test
    fun `ending an already-idle session is a no-op`() {
        assertEquals(idle, idle.transition(VoiceModeEvent.SessionEnded))
    }

    // --- the happy path -------------------------------------------------------------------

    @Test
    fun `partial transcripts replace each other while listening`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        val partial = listening.transition(VoiceModeEvent.PartialTranscript("hel"))
        assertEquals("hel", partial.transcript)
        assertEquals(
            "hello",
            partial.transition(VoiceModeEvent.PartialTranscript("hello")).transcript,
        )
    }

    @Test
    fun `an utterance moves to thinking and carries its text`() {
        val thinking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("what is the time"))
        assertTrue(thinking.isThinking)
        assertEquals("what is the time", thinking.transcript)
        assertTrue(thinking.sessionActive)
    }

    @Test
    fun `first audio moves to speaking`() {
        val thinking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("hi"))
        val speaking = thinking.transition(VoiceModeEvent.FirstAudioReady)
        assertTrue(speaking.isSpeaking)
        assertEquals("hi", speaking.transcript)
    }

    @Test
    fun `finished playback loops back to listening`() {
        // This is the edge that closes the hands-free loop.
        val listening = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("hi"))
            .transition(VoiceModeEvent.FirstAudioReady)
            .transition(VoiceModeEvent.PlaybackFinished)
        assertTrue(listening.isListening)
        assertTrue(listening.sessionActive)
        assertEquals("stale text must not carry into the next turn", "", listening.transcript)
    }

    @Test
    fun `the loop can run a second turn`() {
        var state = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("first"))
            .transition(VoiceModeEvent.FirstAudioReady)
            .transition(VoiceModeEvent.PlaybackFinished)
        state = state.transition(VoiceModeEvent.UtteranceReady("second"))
        assertTrue(state.isThinking)
        assertEquals("second", state.transcript)
    }

    // --- interruption ---------------------------------------------------------------------

    @Test
    fun `barging in mid-reply returns to listening`() {
        // The single most important interaction: talking over the answer stops it and
        // pivots straight back to capture.
        val speaking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("answer this"))
            .transition(VoiceModeEvent.FirstAudioReady)
        val barged = speaking.transition(VoiceModeEvent.UserBargeIn)
        assertTrue(barged.isListening)
        assertTrue("the session must survive a barge-in", barged.sessionActive)
    }

    @Test
    fun `barging in mid-thought also returns to listening`() {
        val thinking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("thinking"))
        assertTrue(thinking.transition(VoiceModeEvent.UserBargeIn).isListening)
    }

    @Test
    fun `the utterance that follows a barge-in is still captured`() {
        val speaking = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.UtteranceReady("stop"))
            .transition(VoiceModeEvent.FirstAudioReady)
        val next = speaking
            .transition(VoiceModeEvent.UserBargeIn)
            .transition(VoiceModeEvent.UtteranceReady("do this instead"))
        assertTrue(next.isThinking)
        assertEquals("do this instead", next.transcript)
    }

    @Test
    fun `a barge-in from idle is ignored`() {
        assertEquals(idle, idle.transition(VoiceModeEvent.UserBargeIn))
    }

    // --- blank-utterance guard ------------------------------------------------------------

    @Test
    fun `a blank utterance is dropped instead of sent`() {
        // Silence-only VAD firing must not produce an empty turn for the LLM.
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        val next = listening.transition(VoiceModeEvent.UtteranceReady("   "))
        assertTrue(next.isListening)
        assertEquals("stale transcript must be cleared", "", next.transcript)
    }

    @Test
    fun `an utterance made only of whitespace is still blank`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        assertTrue(
            listening.transition(VoiceModeEvent.UtteranceReady("\n\t ")).isListening,
        )
    }

    // --- failure and recovery -------------------------------------------------------------

    @Test
    fun `a failure from any active state is recoverable`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        val thinking = listening.transition(VoiceModeEvent.UtteranceReady("x"))
        val speaking = thinking.transition(VoiceModeEvent.FirstAudioReady)

        listOf(listening, thinking, speaking).forEach { state ->
            val failed = state.transition(VoiceModeEvent.Failed("network"))
            assertTrue(failed.isFailed)
            assertEquals("network", failed.errorMessage)
            assertTrue("a transient error must not dismiss the surface", failed.sessionActive)
        }
    }

    @Test
    fun `recovering from failure returns to listening`() {
        val failed = idle
            .transition(VoiceModeEvent.SessionStarted)
            .transition(VoiceModeEvent.Failed("network"))
        val recovered = failed.transition(VoiceModeEvent.Recovered)
        assertTrue(recovered.isListening)
        assertNull("the error must not linger", recovered.errorMessage)
        assertTrue(recovered.sessionActive)
    }

    @Test
    fun `a failure from idle is ignored`() {
        assertEquals("there is no session to fail", idle, idle.transition(VoiceModeEvent.Failed("x")))
    }

    @Test
    fun `recovering when not failed is a no-op`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        assertEquals(listening, listening.transition(VoiceModeEvent.Recovered))
    }

    // --- wrong-state events are ignored ---------------------------------------------------

    @Test
    fun `events that do not apply to the current state leave it unchanged`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        val thinking = listening.transition(VoiceModeEvent.UtteranceReady("x"))
        val speaking = thinking.transition(VoiceModeEvent.FirstAudioReady)

        // A late duplicate, or a WebSocket teardown racing a VAD edge, must be harmless.
        assertEquals(listening, listening.transition(VoiceModeEvent.FirstAudioReady))
        assertEquals(listening, listening.transition(VoiceModeEvent.PlaybackFinished))
        assertEquals(listening, listening.transition(VoiceModeEvent.SessionStarted))
        assertEquals(speaking, speaking.transition(VoiceModeEvent.PartialTranscript("late")))
        assertEquals(speaking, speaking.transition(VoiceModeEvent.FirstAudioReady))
        assertEquals(thinking, thinking.transition(VoiceModeEvent.PlaybackFinished))
        assertEquals(idle, idle.transition(VoiceModeEvent.SessionEnded))
    }

    // --- derived state --------------------------------------------------------------------

    @Test
    fun `isCapturing covers listening and thinking only`() {
        val listening = idle.transition(VoiceModeEvent.SessionStarted)
        val thinking = listening.transition(VoiceModeEvent.UtteranceReady("x"))
        val speaking = thinking.transition(VoiceModeEvent.FirstAudioReady)

        assertTrue("mic open while listening", listening.isCapturing)
        assertTrue("mic stays open while the LLM is working", thinking.isCapturing)
        // While speaking, openness is a barge-in affordance, not normal capture; the
        // controller decides, so the flag stays false here to avoid implying it.
        assertFalse(speaking.isCapturing)

        val failed = speaking.transition(VoiceModeEvent.Failed("x"))
        assertFalse(failed.isCapturing)
        assertFalse(idle.isCapturing)
    }

    @Test
    fun `status flags match the constructor state`() {
        assertTrue(idle.isIdle)
        assertFalse(idle.isListening || idle.isThinking || idle.isSpeaking || idle.isFailed)
    }
}
