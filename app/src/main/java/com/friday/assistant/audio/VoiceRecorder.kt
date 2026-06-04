package com.friday.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class VoiceRecorder(private val callback: AudioCallback) {

    interface AudioCallback {
        fun onAudioBuffer(buffer: ShortArray)
        fun onError(message: String)
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat) * 2

    @SuppressLint("MissingPermission")
    fun startRecording() {
        if (isRecording) return
        
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                sampleRate,
                channelConfig,
                audioFormat,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                callback.onError("Failed to initialize AudioRecord")
                return
            }

            audioRecord?.startRecording()
            isRecording = true
            
            recordingJob = scope.launch {
                val buffer = ShortArray(1024)
                while (isRecording) {
                    val readResult = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                    if (readResult > 0) {
                        // Stream a copy of the buffer
                        callback.onAudioBuffer(buffer.copyOf(readResult))
                    } else if (readResult < 0) {
                        Log.e(TAG, "AudioRecord read error: $readResult")
                        callback.onError("Read error: $readResult")
                        break
                    }
                }
            }
            Log.i(TAG, "Recording started.")
        } catch (e: SecurityException) {
            Log.e(TAG, "Recording permission missing", e)
            callback.onError("Audio record permission missing: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording", e)
            callback.onError("Error starting recording: ${e.message}")
        }
    }

    fun stopRecording() {
        if (!isRecording) return
        isRecording = false
        
        recordingJob?.cancel()
        recordingJob = null
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            Log.i(TAG, "Recording stopped.")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }

    companion object {
        private const val TAG = "VoiceRecorder"
    }
}
