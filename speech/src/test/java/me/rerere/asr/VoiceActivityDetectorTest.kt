    @Test
    fun `speech during the hangover restarts the count`() {
        val vad = VoiceActivityDetector(hangoverFrames = 3, minUtteranceFrames = 1)
        repeat(5) { vad.next(0.6f) }
        // Two silent frames — deliberately one short of completing the hangover.
        assertFalse(vad.next(0.0f))
        assertFalse(vad.next(0.0f))
        assertTrue(vad.next(0.6f))    // user resumes speaking mid-pause
        assertFalse(vad.next(0.0f))   // the hangover restarts from scratch
        assertFalse(vad.next(0.0f))
        assertFalse(vad.next(0.0f))
        assertTrue(vad.next(0.0f))    // and only completes three frames later
    }
