package com.codvika.whisper

object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Returns a native context handle, or 0 on failure. */
    external fun initContext(modelPath: String): Long

    /** [samples] must be 16 kHz mono PCM in the range [-1, 1]. */
    external fun transcribe(ctx: Long, samples: FloatArray, nThreads: Int): String

    external fun freeContext(ctx: Long)
}
