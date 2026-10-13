                onTurn(utterance).collect { chunk ->
                    if (!state.isThinking) return@collect
                    when (chunk) {
                        is Chunk.Text -> splitter.append(chunk.text).forEach { sentence ->
                            onSpeakSentence(sentence, false)
                            announceFirstAudio()
                        }

                        is Chunk.Finished -> splitter.flush().forEach { sentence ->
                            onSpeakSentence(sentence, true)
                            announceFirstAudio()
                        }
                    }
                }

                // Stream ended without a Finished marker (or it was the last thing) — the
                // tail still has to be flushed and announced, or the state stays in
                // Thinking forever.
                splitter.flush().forEach { sentence ->
                    onSpeakSentence(sentence, true)
                    announceFirstAudio()
                }
                if (state.isThinking) announceFirstAudio()
