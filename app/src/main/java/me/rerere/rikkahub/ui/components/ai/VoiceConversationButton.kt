package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.rerere.asr.ASRState
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.Mic01
import me.rerere.rikkahub.R

/**
 * Entry point to VCM (Voice Conversation Mode), shown beside the plain mic.
 *
 * It reuses the mic glyph on purpose, and is distinguished from [AsrButton] by tint rather
 * than by shape, because the two are the same action to a user — "let me talk" — with the
 * only difference being whether the loop continues afterwards.
 *
 * Why a second button rather than a mode on the first:
 *
 *  - [AsrButton] is one-shot dictation: it fills the text field, and the user still presses
 *    send by hand.
 *  - This one opens the hands-free loop and never touches the text field.
 *
 * That distinction is worth the screen space because VCM is the only mode that works
 * without server-side voice-activity detection, so on Groq, HuggingFace or a self-hosted
 * endpoint it is not an optional extra — it is the only way to have a spoken conversation.
 *
 * Uses the same [HugeIcons.Mic01] glyph and the existing `asr_button_content_description`
 * string as [AsrButton]. The project does not depend on
 * `androidx.compose.material.icons` — an earlier draft of the voice screen used
 * `Icons.Filled.Close` and failed to compile — and `HugeIcons.AiVoice`, which the first
 * version of this file used, is not a glyph the rest of the app uses, so it was swapped for
 * one that is demonstrably present.
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
            imageVector = HugeIcons.Mic01,
            contentDescription = stringResource(R.string.asr_button_content_description),
            modifier = Modifier
                .size(20.dp)
                .alpha(if (active) 1f else 0.85f),
            tint = if (active) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
    }
}
