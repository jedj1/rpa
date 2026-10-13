package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.AiVoice
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.rikkahub.R

/**
 * The entry point to VCM (Voice Conversation Mode), shown beside the plain mic.
 *
 * It looks like the mic on purpose — voice is voice — but it is a different feature:
 *
 *  - [AsrButton] is one-shot dictation. It fills the text field. The user taps, talks, taps
 *    again, then sends by hand.
 *  - This button opens the hands-free loop. It never touches the text field.
 *
 * Keeping both reachable matters, because they solve different problems and the second one
 * is the only one that works without server-side voice-activity detection (Groq,
 * HuggingFace, self-hosted).
 *
 * Active state is shown by the same mic glyph swapping for the voice glyph, which is the
 * convention the rest of the input row already uses for toggles.
 */
@Composable
fun VoiceConversationButton(
    state: ASRState,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier.size(40.dp),
    ) {
        Icon(
            imageVector = if (active) HugeIcons.AiVoice else HugeIcons.Mic01,
            contentDescription = stringResource(R.string.voice_conversation_title),
            modifier = Modifier.size(20.dp),
        )
    }
}
