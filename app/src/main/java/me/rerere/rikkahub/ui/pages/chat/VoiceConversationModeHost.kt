package me.rerere.rikkahub.ui.pages.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import me.rerere.asr.VoiceModeStatus
import me.rerere.rikkahub.ui.components.ui.permission.PermissionRecordAudio
import me.rerere.rikkahub.ui.components.ui.permission.rememberPermissionState
import me.rerere.rikkahub.ui.components.ui.permission.PermissionManager
import kotlin.uuid.Uuid

/**
 * Hosts the VCM (Voice Conversation Mode) surface over the chat page.
 *
 * Thin on purpose: [VoiceConversationSession] owns the loop and the state machine, and
 * [VoiceConversationModeScreen] owns the visuals. This exists only to bind them to a
 * composable lifetime and to gate on the microphone permission, so the session never opens
 * into an immediately-failed state.
 *
 * `rememberVoiceConversationSession` already stops the session in its `onDispose`, so
 * leaving the composition (back press, navigating away, configuration change that drops the
 * host) tears the session down and releases the recogniser and TTS.
 */
@Composable
fun VoiceConversationModeHost(
    conversationId: Uuid,
    onDismiss: () -> Unit,
) {
    val permission = rememberPermissionState(PermissionRecordAudio)
    PermissionManager(permissionState = permission)

    if (!permission.allRequiredPermissionsGranted) {
        // Nothing has been started yet, so simply asking and closing is safe: the session
        // will be created (and started) on the next composition once the grant lands.
        permission.requestPermissions()
        onDismiss()
        return
    }

    val session = rememberVoiceConversationSession(
        conversationId = conversationId,
        onDismiss = onDismiss,
    )

    // Starting from a composable effect rather than from the session's constructor keeps
    // the ASR/TTS handles (which are themselves remembered) established before the
    // recogniser begins.
    androidx.compose.runtime.LaunchedEffect(session) {
        session.start()
    }

    val asr = me.rerere.rikkahub.ui.context.LocalASRState.current
    val asrState by asr.state.collectAsStateWithLifecycle()

    VoiceConversationModeScreen(
        state = session.state,
        amplitudes = asrState.amplitudes,
        onEnd = {
            session.stop()
            onDismiss()
        },
    )

    // Idle means the session ended itself (end of turn with no further speech, or a stop);
    // the host should not sit on screen showing nothing.
    if (session.state.status == VoiceModeStatus.Idle) {
        onDismiss()
    }
}
