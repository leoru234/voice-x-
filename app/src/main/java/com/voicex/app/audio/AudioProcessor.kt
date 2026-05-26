package com.voicex.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.*
import kotlin.math.*

class AudioProcessor {

    companion object {
        const val SAMPLE_RATE = 44100
        const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        const val BUFFER_SIZE_FACTOR = 2
    }

    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var noiseSuppressor: NoiseSuppressor? = null
    private var echoCanceler: AcousticEchoCanceler? = null
    private var agc: AutomaticGainControl? = null
    private var isProcessing = false
    private var processingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile var pitchSemitones: Float = 0f
    @Volatile var volumeGain: Float = 1.0f
    @Volatile var reverbMix: Float = 0f
    @Volatile var echoDuration: Float = 0f
    @Volatile var bassBoost: Float = 0f
    @Volatile var whisperMode: Boolean = false
    @Volatile var noiseCancel: Boolean = false
    @Volatile var robotize: Boolean = false

    var onLevelUpdate: ((Float) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    private val bufferSize by lazy {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
        maxOf(minBuf, 2048) * BUFFER_SIZE_FACTOR
    }

    private val reverbBuffer = FloatArray(SAMPLE_RATE / 2)
    private var reverbPos = 0
    private val echoBuffer = FloatArray(SAMPLE_RATE)
    private var echoPos = 0
    private var bassFilterState = 0f

    fun start(): Boolean {
        if (isProcessing) return true
        try {
            val minBufIn = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
            val minBufOut = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, CHANNEL_IN, ENCODING,
                maxOf(minBufIn, bufferSize)
            )

            audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_OUT)
                        .setEncoding(ENCODING)
                        .build()
                )
                .setBufferSizeInBytes(maxOf(minBufOut, bufferSize))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            val sessionId = audioRecord!!.audioSessionId
            if (NoiseSuppressor.isAvailable() && noiseCancel) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)
                noiseSuppressor?.enabled = true
            }
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)
                echoCanceler?.enabled = true
            }
            if (AutomaticGainControl.isAvailable()) {
                agc = AutomaticGainControl.create(sessionId)
                agc?.enabled = true
            }

            audioRecord!!.startRecording()
            audioTrack!!.play()
            isProcessing = true

            processingJob = scope.launch(Dispatchers.Default) {
                processLoop()
            }
            return true
        } catch (e: Exception) {
            onError?.invoke("Audio start failed: ${e.message}")
            cleanup()
            return false
        }
    }

    fun stop() {
        isProcessing = false
        processingJob?.cancel()
        cleanup()
    }

    private suspend fun processLoop() {
        val inputBuf = ShortArray(bufferSize / 2)
        val floatBuf = FloatArray(bufferSize / 2)
        val outputBuf = ShortArray(bufferSize / 2)

        while (isProcessing && currentCoroutineContext().isActive) {
            val read = audioRecord?.read(inputBuf, 0, inputBuf.size) ?: break
            if (read <= 0) continue
            for (i in 0 until read) floatBuf[i] = inputBuf[i] / 32768f
            val level = calculateRMS(floatBuf, read)
            withContext(Dispatchers.Main) { onLevelUpdate?.invoke(level) }
            val processed = applyEffectsChain(floatBuf, read)
            for (i in processed.indices) {
                outputBuf[i] = (processed[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
            }
            audioTrack?.write(outputBuf, 0, processed.size)
        }
    }

    private fun applyEffectsChain(input: FloatArray, size: Int): FloatArray {
        var buf = input.copyOf(size)
        if (abs(pitchSemitones) > 0.1f) buf = pitchShift(buf, pitchSemitones)
        if (robotize) buf = applyRobotize(buf)
        if (whisperMode) buf = applyWhisper(buf)
        if (bassBoost > 0.01f) buf = applyBassBoost(buf, bassBoost)
        if (reverbMix > 0.01f) buf = applyReverb(buf, reverbMix)
        if (echoDuration > 0.01f) buf = applyEcho(buf, echoDuration)
        if (abs(volumeGain - 1f) > 0.01f) for (i in buf.indices) buf[i] *= volumeGain
        return buf
    }

    private fun pitchShift(input: FloatArray, semitones: Float): FloatArray {
        val ratio = 2f.pow(semitones / 12f)
        val output = FloatArray(input.size)
        for (i in output.indices) {
            val srcPos = i * ratio
            val srcIdx = srcPos.toInt()
            val frac = srcPos - srcIdx
            val s0 = if (srcIdx < input.size) input[srcIdx] else 0f
            val s1 = if (srcIdx + 1 < input.size) input[srcIdx + 1] else 0f
            output[i] = s0 + frac * (s1 - s0)
        }
        return output
    }

    private fun applyRobotize(input: FloatArray): FloatArray {
        val freq = 80f
        val output = FloatArray(input.size)
        for (i in input.indices) {
            val t = i.toFloat() / SAMPLE_RATE
            val carrier = sin(2f * PI.toFloat() * freq * t)
            output[i] = input[i] * carrier
        }
        return output
    }

    private fun applyWhisper(input: FloatArray): FloatArray {
        val output = FloatArray(input.size)
        for (i in input.indices) {
            val noise = (Math.random() * 2 - 1).toFloat() * 0.3f
            output[i] = input[i] * 0.6f + noise * abs(input[i]) * 2f
        }
        return output
    }

    private fun applyBassBoost(input: FloatArray, amount: Float): FloatArray {
        val output = FloatArray(input.size)
        val alpha = 0.1f + (1f - amount) * 0.4f
        for (i in input.indices) {
            bassFilterState = bassFilterState + alpha * (input[i] - bassFilterState)
            output[i] = input[i] + bassFilterState * amount * 2f
        }
        return output
    }

    private fun applyReverb(input: FloatArray, mix: Float): FloatArray {
        val output = FloatArray(input.size)
        val delaySamples = (SAMPLE_RATE * 80 / 1000)
        val feedback = 0.4f * mix
        for (i in input.indices) {
            val delayedIdx = (reverbPos - delaySamples + reverbBuffer.size) % reverbBuffer.size
            val delayed = reverbBuffer[delayedIdx]
            val wet = input[i] + delayed * feedback
            reverbBuffer[reverbPos] = wet
            reverbPos = (reverbPos + 1) % reverbBuffer.size
            output[i] = input[i] * (1f - mix * 0.5f) + delayed * mix
        }
        return output
    }

    private fun applyEcho(input: FloatArray, duration: Float): FloatArray {
        val output = FloatArray(input.size)
        val delaySamples = (SAMPLE_RATE * duration * 0.4f).toInt().coerceAtMost(echoBuffer.size - 1)
        for (i in input.indices) {
            val delayedIdx = (echoPos - delaySamples + echoBuffer.size) % echoBuffer.size
            val echo = echoBuffer[delayedIdx]
            output[i] = input[i] + echo * 0.4f
            echoBuffer[echoPos] = input[i]
            echoPos = (echoPos + 1) % echoBuffer.size
        }
        return output
    }

    private fun calculateRMS(buf: FloatArray, size: Int): Float {
        var sum = 0f
        for (i in 0 until size) sum += buf[i] * buf[i]
        return sqrt(sum / size).coerceIn(0f, 1f)
    }

    private fun cleanup() {
        noiseSuppressor?.release()
        echoCanceler?.release()
        agc?.release()
        audioRecord?.stop()
        audioRecord?.release()
        audioTrack?.stop()
        audioTrack?.release()
        audioRecord = null
        audioTrack = null
        noiseSuppressor = null
        echoCanceler = null
        agc = null
        reverbBuffer.fill(0f)
        echoBuffer.fill(0f)
        reverbPos = 0
        echoPos = 0
        bassFilterState = 0f
    }
}
