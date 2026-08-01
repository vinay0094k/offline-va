package com.codvika.voiceassistant

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder

/**
 * Decodes any audio file Android can read (wav/mp3/m4a/ogg/flac…) to the
 * 16 kHz mono float PCM whisper.cpp expects.
 */
object AudioDecoder {

    private const val TARGET_RATE = 16000
    private const val MAX_INPUT_MINUTES = 10

    fun decodeToMono16k(context: Context, uri: Uri): FloatArray? {
        val extractor = MediaExtractor()
        extractor.setDataSource(context, uri, null)

        var trackIndex = -1
        var format: MediaFormat? = null
        for (i in 0 until extractor.trackCount) {
            val f = extractor.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                trackIndex = i
                format = f
                break
            }
        }
        if (trackIndex < 0 || format == null) {
            extractor.release()
            return null
        }
        extractor.selectTrack(trackIndex)

        var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val mime = format.getString(MediaFormat.KEY_MIME)!!

        val codec = MediaCodec.createDecoderByType(mime)
        codec.configure(format, null, null, 0)
        codec.start()

        val pcmChunks = ArrayList<ShortArray>()
        val info = MediaCodec.BufferInfo()
        var sawInputEOS = false
        var sawOutputEOS = false
        val maxSamples = TARGET_RATE.toLong() * 60 * MAX_INPUT_MINUTES * channels
        var totalShorts = 0L

        while (!sawOutputEOS && totalShorts < maxSamples) {
            if (!sawInputEOS) {
                val inIdx = codec.dequeueInputBuffer(10_000)
                if (inIdx >= 0) {
                    val inBuf = codec.getInputBuffer(inIdx)!!
                    val n = extractor.readSampleData(inBuf, 0)
                    if (n < 0) {
                        codec.queueInputBuffer(
                            inIdx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        sawInputEOS = true
                    } else {
                        codec.queueInputBuffer(inIdx, 0, n, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val outIdx = codec.dequeueOutputBuffer(info, 10_000)
            when {
                outIdx >= 0 -> {
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        sawOutputEOS = true
                    }
                    if (info.size > 0) {
                        val outBuf = codec.getOutputBuffer(outIdx)!!
                        outBuf.order(ByteOrder.nativeOrder())
                        outBuf.position(info.offset)
                        val shorts = ShortArray(info.size / 2)
                        outBuf.asShortBuffer().get(shorts)
                        pcmChunks.add(shorts)
                        totalShorts += shorts.size
                    }
                    codec.releaseOutputBuffer(outIdx, false)
                }
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        }
        codec.stop()
        codec.release()
        extractor.release()

        if (pcmChunks.isEmpty()) return null

        // Interleaved shorts -> mono floats.
        val frames = (pcmChunks.sumOf { it.size.toLong() } / channels).toInt()
        val mono = FloatArray(frames)
        var frame = 0
        var chIdx = 0
        var acc = 0f
        for (chunk in pcmChunks) {
            for (s in chunk) {
                acc += s / 32768f
                chIdx++
                if (chIdx == channels) {
                    if (frame < frames) mono[frame++] = acc / channels
                    acc = 0f
                    chIdx = 0
                }
            }
        }

        if (sampleRate == TARGET_RATE) return mono

        // Linear resample to 16 kHz.
        val outLen = (mono.size.toLong() * TARGET_RATE / sampleRate).toInt()
        if (outLen == 0) return null
        val out = FloatArray(outLen)
        val ratio = (mono.size - 1).toDouble() / (outLen - 1).coerceAtLeast(1)
        for (i in out.indices) {
            val pos = i * ratio
            val i0 = pos.toInt()
            val i1 = (i0 + 1).coerceAtMost(mono.size - 1)
            val frac = (pos - i0).toFloat()
            out[i] = mono[i0] * (1 - frac) + mono[i1] * frac
        }
        return out
    }
}
