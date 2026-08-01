package com.codvika.llama

object LlamaBridge {
    init {
        System.loadLibrary("llama_jni")
    }

    external fun loadModel(modelPath: String, nThreads: Int, nCtx: Int): Boolean

    /**
     * Runs one chat completion over the full conversation. [roles] and
     * [contents] are parallel arrays ("system"/"user"/"assistant").
     * Returns the assistant reply as UTF-8 bytes.
     */
    external fun chat(roles: Array<String>, contents: Array<String>, maxTokens: Int): ByteArray

    external fun unload()
}
