package com.codvika.voiceassistant

import android.content.Context
import android.net.Uri
import java.io.BufferedInputStream
import java.io.File
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.ZipInputStream

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

        // Total uncompressed size, read from the central directory so the
        // progress bar is accurate. Android's ZipFile has no FileDescriptor
        // constructor, so the directory is parsed from the seekable file.
        val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            ?: throw IOException("cannot open the picked file")
        val total = pfd.use { totalUncompressedSize(it.fileDescriptor) }
        if (total <= 0L) throw IOException("empty model pack")

        val buf = ByteArray(1 shl 20)
        var written = 0L
        var lastReport = 0L
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw IOException("cannot open the picked file")
        ZipInputStream(BufferedInputStream(raw)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
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
                out.outputStream().use { o ->
                    while (true) {
                        val n = zip.read(buf)
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

    /**
     * Sums uncompressed entry sizes by reading the end-of-central-directory
     * record and each central-directory entry header. Android's RandomAccessFile
     * has no FileDescriptor constructor, so a FileInputStream channel is used
     * for seeking.
     */
    private fun totalUncompressedSize(fd: FileDescriptor): Long {
        FileInputStream(fd).use { fis ->
            val ch = fis.channel
            val len = ch.size()
            // The EOCD sits within the last 64 KB (max comment) + its 22 bytes.
            val scanStart = (len - (64L * 1024 + 22)).coerceAtLeast(0)
            val scanLen = (len - scanStart).toInt()
            val buf = ByteBuffer.allocate(scanLen)
            ch.position(scanStart)
            while (buf.hasRemaining() && ch.read(buf) >= 0) {
            }
            val bytes = buf.array()

            var eocd = -1
            for (i in bytes.size - 22 downTo 0) {
                if (bytes[i] == 0x50.toByte() && bytes[i + 1] == 0x4b.toByte() &&
                    bytes[i + 2] == 0x05.toByte() && bytes[i + 3] == 0x06.toByte()
                ) {
                    eocd = i
                    break
                }
            }
            if (eocd < 0) throw IOException("not a valid zip (no central directory)")

            // EOCD fields: total entries (2 bytes) at +10, CD offset (4) at +16.
            val totalEntries = le16(bytes, eocd + 10)
            val cdOffset = le32(bytes, eocd + 16)

            ch.position(cdOffset)
            val head = ByteBuffer.allocate(46)
            var total = 0L
            for (i in 0 until totalEntries) {
                head.clear()
                while (head.hasRemaining()) {
                    if (ch.read(head) < 0) throw IOException("corrupt zip central directory")
                }
                val h = head.array()
                if (h[0] != 0x50.toByte() || h[1] != 0x4b.toByte() ||
                    h[2] != 0x01.toByte() || h[3] != 0x02.toByte()
                ) {
                    throw IOException("corrupt zip central directory")
                }
                // Central-directory header: uncompressed size (4) at +24,
                // then name/extra/comment lengths (2 each) at +28/+30/+32.
                total += le32(h, 24)
                ch.position(ch.position() + le16(h, 28) + le16(h, 30) + le16(h, 32))
            }
            return total
        }
    }

    private fun le16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or
            ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or
            ((b[off + 3].toLong() and 0xFF) shl 24)
}
