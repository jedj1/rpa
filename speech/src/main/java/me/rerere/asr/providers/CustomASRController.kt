package me.rerere.asr.providers

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.asr.ASRController
import me.rerere.asr.ASRProviderSetting
import me.rerere.asr.ASRState
import me.rerere.asr.ASRStatus
import me.rerere.asr.appendAmplitude
import me.rerere.asr.calculateRmsAmplitude
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Collections

private const val TAG = "CustomASR"

// Same 6MB guard as Step/MiMo: a single oversized segment risks transport failure
// and re-sends audio the server already billed on a previous segment.
private const val MAX_SEGMENT_BYTES = 6 * 1024 * 1024

// 16kHz/16bit/mono -> 3200 bytes = 100ms. Shorter fragments get a 400 from most
// OpenAI-compatible endpoints, so drop them rather than burning a request.
private const val MIN_SEGMENT_BYTES = 3200

private const val MAX_RETRY = 3

/**
 * User-supplied OpenAI-compatible ASR provider.
 *
 * Talks to `POST {baseUrl}/audio/transcriptions` with the standard multipart form
 * (`file` + `model` + optional `language` + `response_format`), i.e. the shape served
 * by Groq, HuggingFace Inference, vLLM, LocalAI, faster-whisper-server and
 * whisper.cpp's own server. That single shape is what makes this one class cover
 * "bring your own key" for a large fraction of free/self-hosted endpoints.
 *
 * The recording, chunking, retry/backoff and amplitude plumbing mirrors
 * [StepASRController] so behaviour stays consistent across the HTTP-based providers.
 */
class CustomASRController(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val provider: ASRProviderSetting.Custom,
) : ASRController {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(ASRState(isAvailable = true))
    override val state: StateFlow<ASRState> = _state.asStateFlow()

    private var recorderJob: Job? = null
    private var audioRecord: AudioRecord? = null
    private var onTranscriptChange: ((String) -> Unit)? = null
    private var flushJob: Job? = null

    private val bufferLock = Any()
    private var currentBuffer = ByteArrayOutputStream()
    private var segmentStartElapsedMs = 0L
    private val completedTranscripts = Collections.synchronizedList(mutableListOf<String>())

    override fun start(onTranscriptChange: (String) -> Unit) {
        if (state.value.isRecording) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            setError("Microphone permission is required")
            return
        }

        this.onTranscriptChange = onTranscriptChange
        synchronized(bufferLock) {
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
        completedTranscripts.clear()
        flushJob = null

        _state.update { ASRState(status = ASRStatus.Listening, isAvailable = true) }
        startRecorder()
    }

    override fun stop() {
        recorderJob?.cancel()
        releaseRecorder()
        _state.update { it.copy(status = ASRStatus.Stopping) }

        scope.launch(Dispatchers.IO) {
            try {
                flushJob?.join()
                flushSegment()
            } catch (e: Exception) {
                Log.e(TAG, "Final flush failed", e)
                setError(e.message ?: "Custom ASR final flush failed")
            } finally {
                _state.update { it.copy(status = ASRStatus.Idle) }
            }
        }
    }

    override fun dispose() {
        recorderJob?.cancel()
        flushJob?.cancel()
        releaseRecorder()
        scope.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun startRecorder() {
        recorderJob?.cancel()
        recorderJob = scope.launch(Dispatchers.IO) {
            val sampleRate = provider.sampleRate
            val minBufferSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            val bufferSize = minBufferSize
                .coerceAtLeast(sampleRate / 10 * 2)
                .coerceAtLeast(4096)

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 2,
            )
            audioRecord = recorder

            try {
                recorder.startRecording()
                val buffer = ByteArray(bufferSize)
                val segmentMs = provider.segmentDurationSec.coerceAtLeast(0) * 1000L
                while (isActive) {
                    val read = recorder.read(buffer, 0, buffer.size)
                    if (read > 0) {
                        val amplitude = calculateRmsAmplitude(buffer, read)
                        _state.update { it.copy(amplitudes = it.amplitudes.appendAmplitude(amplitude)) }

                        val shouldFlush = synchronized(bufferLock) {
                            currentBuffer.write(buffer, 0, read)
                            if (segmentMs <= 0) {
                                currentBuffer.size() >= MAX_SEGMENT_BYTES
                            } else {
                                val elapsed = SystemClock.elapsedRealtime() - segmentStartElapsedMs
                                currentBuffer.size() >= MAX_SEGMENT_BYTES || elapsed >= segmentMs
                            }
                        }

                        if (shouldFlush) triggerFlush()
                    } else if (read < 0) {
                        throw IllegalStateException("AudioRecord read error: $read")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio recording failed", e)
                setError(e.message ?: "Audio recording failed")
            } finally {
                releaseRecorder()
            }
        }
    }

    private fun triggerFlush() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            runCatching { flushSegment() }
                .onFailure { Log.e(TAG, "Segment flush failed", it) }
        }
    }

    /**
     * Copy the buffered PCM out under the lock and reset immediately, so the recording
     * loop never waits on the network. PCM is wrapped in a WAV header because the
     * OpenAI-compatible `file` part is decoded by filename/extension, and raw PCM
     * without a container is rejected by most servers.
     */
    private suspend fun flushSegment() {
        val pcmBytes = synchronized(bufferLock) {
            if (currentBuffer.size() == 0) return
            val bytes = currentBuffer.toByteArray()
            currentBuffer = ByteArrayOutputStream()
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
            bytes
        }

        if (pcmBytes.size < MIN_SEGMENT_BYTES) {
            Log.d(TAG, "Skip flush: PCM too short (${pcmBytes.size} bytes)")
            return
        }

        val wav = pcmToWav(pcmBytes, provider.sampleRate)
        val request = buildRequest(wav)
        val text = executeWithRetry(request).trim()

        if (text.isNotEmpty()) {
            completedTranscripts.add(text)
            publishTranscript()
        }
    }

    private fun buildRequest(wav: ByteArray): Request {
        val base = provider.baseUrl.trim().trimEnd('/')
        // Accept both ".../v1" and a bare origin; normalise so neither yields a
        // double slash or a missing path segment.
        val url = if (base.endsWith("/v1")) "$base/audio/transcriptions" else "$base/v1/audio/transcriptions"

        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "file",
                "audio.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("model", provider.model)
            .apply {
                if (provider.language.isNotBlank()) {
                    addFormDataPart("language", provider.language)
                }
            }
            // Ask for plain text: many gateways return JSON by default and some
            // OpenAI-compatible servers ignore response_format entirely. Plain text
            // keeps parsing trivial either way, because parseTranscript() below
            // falls back to treating the body as raw text.
            .addFormDataPart("response_format", "text")
            .build()

        return Request.Builder()
            .url(url)
            .apply {
                // Bearer by default; api-key is a common alternative (Azure-style
                // gateways). Sending both when non-blank is harmless and maximises
                // the set of servers that work with no extra config.
                if (provider.apiKey.isNotBlank()) {
                    addHeader("Authorization", "Bearer ${provider.apiKey}")
                    addHeader("api-key", provider.apiKey)
                }
            }
            .addHeader("Accept", "application/json, text/plain;q=0.9")
            .post(body)
            .build()
    }

    private suspend fun executeWithRetry(request: Request): String {
        var lastError: IOException? = null
        for (attempt in 1..MAX_RETRY) {
            try {
                return withContext(Dispatchers.IO) {
                    httpClient.newCall(request).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            throw IOException(
                                "Custom ASR HTTP ${resp.code}: ${resp.body.string()}",
                            )
                        }
                        parseTranscript(resp.body.string())
                    }
                }
            } catch (e: IOException) {
                lastError = e
                Log.w(TAG, "flushSegment attempt $attempt/$MAX_RETRY failed: ${e.message}")
                if (attempt < MAX_RETRY) {
                    kotlinx.coroutines.delay(300L * attempt)
                }
            }
        }
        throw lastError ?: IOException("Custom ASR request failed")
    }

    /** Accepts `{"text":"..."}` and falls back to the raw body for text/plain replies. */
    private fun parseTranscript(body: String): String {
        if (body.isBlank()) return ""
        val trimmed = body.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching {
            val json = org.json.JSONObject(trimmed)
            json.optString("text").ifBlank {
                json.optString("transcript").ifBlank { trimmed }
            }
        }.getOrDefault(trimmed)
    }

    /** Minimal 44-byte RIFF/WAVE header around 16-bit little-endian mono PCM. */
    private fun pcmToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val totalDataLen = pcm.size + 36
        return ByteArrayOutputStream(pcm.size + 44).apply {
            write("RIFF".toByteArray())
            writeLe32(totalDataLen)
            write("WAVE".toByteArray())
            write("fmt ".toByteArray())
            writeLe32(16) // PCM fmt chunk size
            writeLe16(1) // audio format = PCM
            writeLe16(channels)
            writeLe32(sampleRate)
            writeLe32(byteRate)
            writeLe16(channels * bitsPerSample / 8) // block align
            writeLe16(bitsPerSample)
            write("data".toByteArray())
            writeLe32(pcm.size)
            write(pcm)
        }.toByteArray()
    }

    private fun ByteArrayOutputStream.writeLe32(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun ByteArrayOutputStream.writeLe16(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }

    private fun publishTranscript() {
        val transcript = completedTranscripts
            .filter { it.isNotBlank() }
            .joinToString(" ")
        _state.update { it.copy(transcript = transcript, errorMessage = null) }
        scope.launch { onTranscriptChange?.invoke(transcript) }
    }

    private fun setError(message: String) {
        _state.update { it.copy(status = ASRStatus.Error, errorMessage = message) }
    }

    private fun releaseRecorder() {
        recorderJob = null
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
    }
}
