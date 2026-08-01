package com.codvika.voiceassistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/** Records 16 kHz mono PCM from the microphone into memory (capped at 2 min). */
class AudioRecorder {

    companion object {
        const val SAMPLE_RATE = 16000
        private const val MAX_SAMPLES = SAMPLE_RATE * 120
    }

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var recording = false
    private val chunks = ArrayList<ShortArray>()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) return false
        val r = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4
        )
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        record = r
        chunks.clear()
        recording = true
        r.startRecording()
        thread = Thread {
            val buf = ShortArray(minBuf)
            var total = 0
            while (recording && total < MAX_SAMPLES) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    chunks.add(buf.copyOf(n))
                    total += n
                }
            }
        }.also { it.start() }
        return true
    }

    fun stop(): FloatArray {
        recording = false
        thread?.join()
        thread = null
        record?.apply {
            try { stop() } catch (_: IllegalStateException) {}
            release()
        }
        record = null

        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var i = 0
        for (chunk in chunks) {
            for (s in chunk) out[i++] = s / 32768f
        }
        chunks.clear()
        return out
    }
}
