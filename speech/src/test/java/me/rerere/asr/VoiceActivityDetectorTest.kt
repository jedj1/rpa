package me.rerere.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the VCM turn detector. Deliberately synthetic: amplitudes are exact values
 * (speech = 0.6f, silence = 0.0f) so the assertions read as "this many speech frames, this
 * many silence frames", not as fuzzy signal processing.
 */
class VoiceActivityDetectorTest {

    // --- the Phase 1 gate ----------------------------------------------------------------

    @Test
    fun `a speech burst followed by silence fires exactly one turn`() {
        val vad = VoiceActivityDetector()
        var turns = 0
        repeat(10) { assertFalse("speech must not fire", vad.next(0.6f)) }
        repeat(8) { if (vad.next(0.0f)) turns++ }
        assertEquals(1, turns)
    }

    @Test
    fun `a cough alone fires no turn`() {
        // One loud frame then quiet: below minUtteranceFrames, so it is discarded rather
        // than sent to the LLM as an empty utterance.
        val vad = VoiceActivityDetector()
        assertFalse(vad.next(0.9f))
        repeat(10) { assertFalse(vad.next(0.0f)) }
    }

    // --- hangover behaviour --------------------------------------------------------------

    @Test
    fun `the turn fires on the frame that completes the hangover`() {
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { assertFalse(vad.next(0.6f)) }
        assertFalse(vad.next(0.0f))   // silence 1
        assertFalse(vad.next(0.0f))   // silence 2
        assertTrue(vad.next(0.0f))    // silence 3 → fires
    }

    @Test
    fun `speech during the hangover restarts the count`() {
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        replayHangover(vad) { assertFalse(it) }
        assertTrue(vad.next(0.6f))    // user resumes speaking
        assertFalse(vad.next(0.0f))   // hangover starts over
        assertFalse(vad.next(0.0f))
        assertFalse(vad.next(0.0f))
        assertTrue(vad.next(0.0f))    // and only completes after three more
    }

    private fun replayHangover(vad: VoiceActivityDetector, assertion: (Boolean) -> Unit) {
        repeat(3) { assertion(vad.next(0.0f)) }
    }

    @Test
    fun `consecutive silence does not fire repeatedly`() {
        // next() must be an edge, not a level. Firing every frame would send the same
        // utterance to the LLM over and over.
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        var firings = 0
        repeat(20) { if (vad.next(0.0f)) firings++ }
        assertEquals(1, firings)
    }

    @Test
    fun `a new utterance after a turn fires a second turn`() {
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        repeat(3) { vad.next(0.0f) }
        repeat(5) { vad.next(0.6f) }
        assertTrue("second utterance must be detectable", hasTurn(vad))
    }

    private fun hasTurn(vad: VoiceActivityDetector): Boolean {
        var fired = false
        repeat(5) { if (vad.next(0.0f)) fired = true }
        return fired
    }

    // --- minUtteranceFrames --------------------------------------------------------------

    @Test
    fun `an utterance at exactly the minimum still fires`() {
        val vad = VoiceActivityDetector(hangoverFrames = 2, minUtteranceFrames = 3)
        repeat(3) { vad.next(0.6f) }
        assertTrue(vad.next(0.0f))
        assertTrue(vad.next(0.0f))
    }

    @Test
    fun `an utterance one frame under the minimum is discarded`() {
        val vad = VoiceActivityDetector(hangoverFrames = 2, minUtteranceFrames = 3)
        repeat(2) { vad.next(0.6f) }
        assertFalse(vad.next(0.0f))
        assertFalse(vad.next(0.0f))
    }

    @Test
    fun `a discarded utterance does not poison the next one`() {
        // The rejected cough must leave the detector clean, or the real utterance that
        // follows would start already inside a hangover.
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 4)
        vad.next(0.9f)               // cough
        repeat(4) { assertFalse(vad.next(0.0f)) }
        val secondVad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 4)
        repeat(5) { assertFalse(secondVad.next(0.6f)) }
        assertTrue(secondVad.next(0.0f))
        assertTrue(secondVad.next(0.0f))
        assertTrue(secondVad.next(0.0f))
    }

    // --- threshold semantics -------------------------------------------------------------

    @Test
    fun `silence before any speech never fires`() {
        val vad = VoiceActivityDetector()
        repeat(50) { assertFalse(vad.next(0.0f)) }
    }

    @Test
    fun `threshold is exclusive`() {
        // Exactly at the threshold is NOT speech, so hangoverFrames must be 3 of strictly
        // greater values. 1.0f..(1.0f - 0.15f) is the boundary region.
        val vad = VoiceActivityDetector(speechThreshold = 0.5f, hangoverFrames = 1, minUtteranceFrames = 1)
        assertFalse("at threshold is not speech", vad.next(0.5f))
        assertFalse("still no speech seen", vad.next(0.5f))
        assertTrue(vad.next(0.6f))   // above → fires after one silent frame
    }

    @Test
    fun `ambient noise floor does not trip detection`() {
        val vad = VoiceActivityDetector()
        // A typical quiet-room floor, well under the 0.15 default.
        repeat(30) { assertFalse("noise floor must not be speech", vad.next(0.05f)) }
    }

    // --- reset ----------------------------------------------------------------------------

    @Test
    fun `reset abandons a turn in progress`() {
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        vad.reset()
        // The pending speech is gone, so silence now starts a brand-new waiting period
        // rather than completing the abandoned turn.
        repeat(5) { assertFalse("reset must clear the pending turn", vad.next(0.0f)) }
    }

    @Test
    fun `reset is the barging-in path`() {
        // Mirrors VCM's interruption: the user starts talking while the LLM is answering,
        // so the detector is reset and the new utterance is tracked from scratch.
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        vad.reset()
        repeat(4) { vad.next(0.6f) }
        assertTrue(vad.next(0.0f))
        assertTrue(vad.next(0.0f))
        assertTrue(vad.next(0.0f))
    }

    // --- configuration guards -------------------------------------------------------------

    @Test(expected = IllegalArgumentException::class)
    fun `threshold above one is rejected`() {
        VoiceActivityDetector(speechThreshold = 1.5f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative threshold is rejected`() {
        VoiceActivityDetector(speechThreshold = -0.1f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative hangover is rejected`() {
        VoiceActivityDetector(hangoverFrames = -1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `negative minimum utterance is rejected`() {
        VoiceActivityDetector(minUtteranceFrames = -1)
    }

    // --- ms → frame helper ----------------------------------------------------------------

    @Test
    fun `framesForHangoverMs converts a human setting`() {
        assertEquals(7, VoiceActivityDetector.framesForHangoverMs(700))
        assertEquals(5, VoiceActivityDetector.framesForHangoverMs(500))
        assertEquals(3, VoiceActivityDetector.framesForHangoverMs(300))
    }

    @Test
    fun `framesForHangoverMs never returns zero`() {
        // A zero hangover would fire on the very first silent frame, i.e. on every natural
        // pause between words, so the helper must clamp rather than let that through.
        assertEquals(1, VoiceActivityDetector.framesForHangoverMs(0))
        assertEquals(1, VoiceActivityDetector.framesForHangoverMs(-100))
    }

    @Test
    fun `the default hangover resolves to roughly seven hundred milliseconds`() {
        assertEquals(
            VoiceActivityDetector.framesForHangoverMs(700),
            VoiceActivityDetector.DEFAULT_HANGOVER,
        )
    }
}
