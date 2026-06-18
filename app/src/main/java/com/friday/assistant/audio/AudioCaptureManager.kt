package com.friday.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

class AudioCaptureManager(private val context: Context) {
    companion object {
        private const val TAG = "AudioCaptureManager"
        const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val BUFFER_SIZE_FACTOR = 2
    }

    interface AudioFrameListener {
        // IMPORTANT: pcmData is a shared buffer reused each frame. Copy contents before any async use.
        fun onAudioFrame(pcmData: ShortArray, length: Int)
    }

    private val listeners = CopyOnWriteArrayList<AudioFrameListener>()
    private var audioRecord: AudioRecord? = null
    private var acousticEchoCanceler: android.media.audiofx.AcousticEchoCanceler? = null
    private var captureThread: Thread? = null
    @Volatile
    private var isRecording = false

    fun registerListener(listener: AudioFrameListener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
        }
    }

    fun unregisterListener(listener: AudioFrameListener) {
        listeners.remove(listener)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun startCapture(): Boolean {
        if (isRecording) return true

        val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            Log.e(TAG, "Invalid buffer size computed")
            return false
        }
        val bufferSize = minBufferSize * BUFFER_SIZE_FACTOR

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }

            // Enable AcousticEchoCanceler if available
            val sessionId = audioRecord?.audioSessionId
            if (sessionId != null && android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                try {
                    acousticEchoCanceler = android.media.audiofx.AcousticEchoCanceler.create(sessionId).apply {
                        enabled = true
                    }
                    Log.i(TAG, "AcousticEchoCanceler enabled on session ID: $sessionId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to initialize AcousticEchoCanceler", e)
                }
            }

            audioRecord?.startRecording()
            isRecording = true

            val frameSize = SAMPLE_RATE / 10 // 100ms frames (1600 samples)
            captureThread = Thread({
                val buffer = ShortArray(frameSize)
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, frameSize) ?: -1
                    if (readResult > 0) {
                        // Pass shared buffer directly — avoids per-frame allocation (10x/sec GC pressure reduction).
                        // Listeners must copy data before any async use.
                        notifyListeners(buffer, readResult)
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord read error: $readResult")
                        break
                    }
                }
            }, "FridayAudioCaptureThread").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Log.i(TAG, "Audio capture started successfully")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Error starting audio capture", e)
            stopCapture()
            return false
        }
    }

    @Synchronized
    fun stopCapture() {
        if (!isRecording) return
        isRecording = false
        
        try {
            captureThread?.join(1000)
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted waiting for capture thread to finish", e)
        }
        captureThread = null

        try {
            audioRecord?.stop()
            acousticEchoCanceler?.release()
            acousticEchoCanceler = null
            audioRecord?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioRecord", e)
        }
        audioRecord = null
        Log.i(TAG, "Audio capture stopped")
    }

    private fun notifyListeners(pcmData: ShortArray, length: Int) {
        for (listener in listeners) {
            try {
                listener.onAudioFrame(pcmData, length)
            } catch (e: Exception) {
                Log.e(TAG, "Error notifying audio listener", e)
            }
        }
    }

    fun isActive(): Boolean = isRecording
}
