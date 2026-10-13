package me.rerere.rikkahub.data.ai.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.asr.Chunk

/**
 * Adapts [GenerationChunk] (the app's generation stream) onto [Chunk] (what `:speech`'s
 * [me.rerere.asr.VoiceModeController] consumes).
 *
 * Two things make this more than a passthrough, and both are easy to get wrong:
 *
 * **1. The stream is cumulative, not deltas.** Every [GenerationChunk.Messages] re-emits the
 * *entire* message list, and the last assistant message contains all the text produced so
 * far — so the assistant text reads "He", then "Hello", then "Hello world". Feeding that
 * straight to TTS would speak every prefix again and again. The delta is computed by
 * tracking how many characters were already emitted and slicing the remainder.
 *
 * **2. Only the assistant's reply may be spoken.** During a tool turn the stream re-emits
 * user and tool messages alongside the assistant's, and reading the "last message" blindly
 * would speak the user's own words back to them.
 *
 * The last assistant message is found with [lastOrNull] over the whole list rather than
 * `.last()`, because a completed tool call emits a trailing tool-role message.
 *
 * If the assistant text is ever rewritten *shorter* (context compaction, or a fresh
 * assistant message after a tool call), the new text is emitted in full and the offset
 * resynchronised. Silently dropping it would mean the user never hears what replaced it.
 */
fun GenerationChunk.toVoiceChunks(): Flow<Chunk> = flow {
    var emittedChars = 0
    this@toVoiceChunks.collect { chunk ->
        val messages = (chunk as? GenerationChunk.Messages)?.messages ?: return@collect
        val assistant = messages.lastOrNull { it.role == MessageRole.ASSISTANT } ?: return@collect

        val text = assistant.parts
            .filterIsInstance<UIMessagePart.Text>()
            .joinToString(separator = "") { it.text }

        if (text.length > emittedChars) {
            emit(Chunk.Text(text.substring(emittedChars)))
        } else if (text.isNotEmpty() && text.length < emittedChars) {
            emit(Chunk.Text(text))
        }
        emittedChars = text.length
    }
    emit(Chunk.Finished)
}

/** Convenience: one voice turn's utterance, as a user [UIMessage]. */
fun voiceUtteranceMessage(utterance: String): UIMessage = UIMessage.user(utterance)
