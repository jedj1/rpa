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
 *
 * **State lives inside the `flow {}` block, per collection.** That is deliberate and
 * load-bearing: an earlier version kept the emitted-character offset as a file-level `var`,
 * which leaked across every call site — a second voice turn started already "ahead" by
 * whatever the first one emitted, and two concurrent sessions corrupted each other.
 * Collecting the returned Flow a second time must start from zero.
 *
 * The upstream is collected by capturing it in a local `val` first, then calling `.collect`
 * on that. It cannot be written as `this@toVoiceChunks.collect { }` because inside a
 * `flow {}` builder the receiver is the `ProducerScope`, not the source Flow — which is the
 * compile error two earlier versions hit. And a `transform { }` cannot be used either,
 * because its lambda gives no access to a per-collection local.
 */
fun GenerationChunk.toVoiceChunks(): Flow<Chunk> {
    val source = this
    return flow {
        var emittedChars = 0
        source.collect { chunk ->
            if (chunk !is GenerationChunk.Messages) return@collect
            val assistant = chunk.messages.lastOrNull { it.role == MessageRole.ASSISTANT }
                ?: return@collect

            val text = assistant.parts
                .filterIsInstance<UIMessagePart.Text>()
                .joinToString(separator = "") { it.text }

            val delta = when {
                text.length > emittedChars -> text.substring(emittedChars)
                // Rewritten shorter: speak the new text rather than losing it.
                text.isNotEmpty() && text.length < emittedChars -> text
                else -> null
            }
            if (delta != null && delta.isNotEmpty()) emit(Chunk.Text(delta))
            emittedChars = text.length
        }
        emit(Chunk.Finished)
    }
}

/** Convenience: one voice turn's utterance, as a user [UIMessage]. */
fun voiceUtteranceMessage(utterance: String): UIMessage = UIMessage.user(utterance)
