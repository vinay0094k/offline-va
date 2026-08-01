package com.codvika.voiceassistant

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Installs the model pack into filesDir/models. The APK ships without models
 * (keeps it ~30 MB and keeps the no-INTERNET-permission guarantee); the user
 * downloads model-pack-v1.zip separately and imports it once via the system
 * file picker. Native code needs real file paths, hence the unzip to disk.
 */
object ModelPack {

    private const val MARKER = ".models-v1"

    /** Paths that must exist after an import for the pipeline to start. */
    private val REQUIRED = listOf(
        "whisper/ggml-base.en-q5_1.bin",
        "llm/qwen3.5-0.8b-q4_k_m.gguf",
        "tts/en_US-amy-low.onnx",
        "tts/tokens.txt",
        "tts/espeak-ng-data"
    )

    fun dir(context: Context): File = File(context.filesDir, "models")

    fun installed(context: Context): Boolean = File(dir(context), MARKER).exists()

    /**
     * Streams the picked zip into filesDir/models. Throws IOException with a
     * user-readable message on bad input; a failed import leaves no partial
     * install behind. [onProgress] reports a 0..1 fraction.
     */
    fun import(context: Context, uri: Uri, onProgress: (fraction: Float) -> Unit) {
        val target = dir(context)
        target.deleteRecursively()
        target.mkdirs()

        val free = target.usableSpace
        if (free < 900L shl 20) {
            throw IOException("need ~900 MB free, only ${free shr 20} MB available")
        }

        try {
            unzip(context, uri, target, onProgress)
            val missing = REQUIRED.firstOrNull { !File(target, it).exists() }
            if (missing != null) {
                throw IOException("not a model pack (missing $missing)")
            }
            File(target, MARKER).createNewFile()
        } catch (e: Exception) {
            target.deleteRecursively()
            throw e
        }
    }

    private fun unzip(
        context: Context, uri: Uri, target: File, onProgress: (Float) -> Unit
    ) {
        val targetPrefix = target.canonicalPath + File.separator
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("cannot open the picked file")
        pfd.use { fd ->
            // ZipFile reads the central directory, so entry sizes are exact
            // (unlike ZipInputStream, where data-descriptor entries read 0).
            ZipFile(fd.fileDescriptor).use { zip ->
                var total = 0L
                val sizes = zip.entries()
                while (sizes.hasMoreElements()) {
                    val entry = sizes.nextElement()
                    if (!entry.isDirectory) total += entry.size
                }
                if (total <= 0L) throw IOException("empty model pack")

                var written = 0L
                var lastReport = 0L
                val buf = ByteArray(1 shl 20)
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry: ZipEntry = entries.nextElement()
                    // The pack is rooted at models/; tolerate a bare layout too.
                    val name = entry.name.removePrefix("models/")
                    if (name.isEmpty()) continue
                    val out = File(target, name)
                    if (!out.canonicalPath.startsWith(targetPrefix)) {
                        throw IOException("unsafe zip entry: ${entry.name}")
                    }
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        out.outputStream().use { o ->
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                o.write(buf, 0, n)
                                written += n
                                if (written - lastReport >= 32L shl 20) {
                                    lastReport = written
                                    onProgress(written.toFloat() / total)
                                }
                            }
                        }
                    }
                }
                onProgress(1f)
            }
        }
    }
}
