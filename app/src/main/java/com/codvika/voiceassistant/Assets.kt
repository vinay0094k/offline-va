package com.codvika.voiceassistant

import android.content.Context
import java.io.File

/**
 * Copies the bundled assets/models tree into filesDir on first launch.
 * whisper.cpp, llama.cpp, and espeak-ng all need real file paths, which
 * assets inside the APK cannot provide.
 */
object Assets {

    private const val VERSION_MARKER = ".models-v1"

    fun copyModels(context: Context) {
        val target = File(context.filesDir, "models")
        val marker = File(target, VERSION_MARKER)
        if (marker.exists()) return
        target.deleteRecursively()
        copyAssetDir(context, "models", target)
        marker.createNewFile()
    }

    private fun copyAssetDir(context: Context, assetPath: String, target: File) {
        val children = context.assets.list(assetPath) ?: return
        if (children.isEmpty()) {
            // A leaf: this asset path is a file.
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output, 1 shl 20) }
            }
        } else {
            target.mkdirs()
            for (child in children) {
                copyAssetDir(context, "$assetPath/$child", File(target, child))
            }
        }
    }
}
