    onStartVoiceMode: (() -> Unit)? = null,
    voiceState: VoiceSessionState = VoiceSessionState(),
    onStopVoiceMode: () -> Unit = {},
    /** Opens VCM (Voice Conversation Mode) — the hands-free loop. */
    onStartVoiceConversation: (() -> Unit)? = null,
    /** True while VCM is on screen, so its button can show as active. */
    voiceConversationActive: Boolean = false,
) {