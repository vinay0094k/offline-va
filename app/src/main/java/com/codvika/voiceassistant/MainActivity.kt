package com.codvika.voiceassistant

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codvika.llama.LlamaBridge
import com.codvika.whisper.WhisperBridge
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var whisperCtx = 0L
    private var tts: OfflineTts? = null
    private var ready = false
    private var busy = false
    private var speaking = false
    private var isRecording = false
    private val recorder = AudioRecorder()

    /** Full transcript of the open chat: role -> content, no system prompt. */
    private val history = ArrayList<Pair<String, String>>()

    /** File id of the open chat in ChatStore; null until the first exchange. */
    private var chatId: String? = null

    private lateinit var statusView: TextView
    private lateinit var messagesView: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var btnRecord: Button
    private lateinit var btnFile: Button
    private lateinit var btnNew: Button
    private lateinit var btnChats: Button
    private lateinit var btnImport: Button

    private val systemPrompt =
        "You are a helpful voice assistant. Reply in one or two short sentences of " +
        "plain spoken text. No markdown, no emoji, no lists. /no_think"

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) handleFile(uri)
        }

    // "*/*" because download managers often mislabel the pack's mime type.
    private val pickPack =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importPack(uri)
        }

    private val askMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        messagesView = findViewById(R.id.messagesView)
        chatScroll = findViewById(R.id.chatScroll)
        btnRecord = findViewById(R.id.btnRecord)
        btnFile = findViewById(R.id.btnFile)
        btnNew = findViewById(R.id.btnNew)
        btnChats = findViewById(R.id.btnChats)
        btnImport = findViewById(R.id.btnImport)

        btnRecord.setOnClickListener { toggleRecord() }
        btnImport.setOnClickListener { if (!busy) pickPack.launch("*/*") }
        btnFile.setOnClickListener { if (ready && !busy) pickAudio.launch("audio/*") }
        btnNew.setOnClickListener { newChat() }
        btnChats.setOnClickListener { showChats() }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        scope.launch {
            if (ModelPack.installed(this@MainActivity)) {
                initModels()
            } else {
                btnImport.visibility = View.VISIBLE
                setStatus(
                    "Models not installed — download model-pack-v1.zip " +
                    "(see README), copy it to this phone, then tap Import"
                )
            }
        }
    }

    private fun importPack(uri: Uri) {
        scope.launch {
            busy = true
            btnImport.isEnabled = false
            try {
                setStatus("Importing model pack…")
                withContext(Dispatchers.IO) {
                    ModelPack.import(this@MainActivity, uri) { mb ->
                        setStatus("Importing model pack… $mb MB")
                    }
                }
                btnImport.visibility = View.GONE
                busy = false
                initModels()
            } catch (e: Exception) {
                busy = false
                btnImport.isEnabled = true
                setStatus("Import failed: ${e.message}")
            }
        }
    }

    private suspend fun initModels() {
        try {
            withContext(Dispatchers.IO) {
                val base = ModelPack.dir(this@MainActivity)

                setStatus("Loading Whisper…")
                whisperCtx = WhisperBridge.initContext(
                    File(base, "whisper/ggml-base.en-q5_1.bin").absolutePath
                )
                check(whisperCtx != 0L) { "Whisper model failed to load" }

                setStatus("Loading Qwen…")
                val ok = LlamaBridge.loadModel(
                    File(base, "llm/qwen3.5-0.8b-q4_k_m.gguf").absolutePath,
                    numThreads(), 4096
                )
                check(ok) { "Qwen model failed to load" }

                setStatus("Loading TTS voice…")
                val ttsDir = File(base, "tts")
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = File(ttsDir, "en_US-amy-low.onnx").absolutePath,
                                tokens = File(ttsDir, "tokens.txt").absolutePath,
                                dataDir = File(ttsDir, "espeak-ng-data").absolutePath
                            ),
                            numThreads = 2
                        )
                    )
                )
            }
            ready = true
            btnRecord.isEnabled = true
            btnFile.isEnabled = true
            setStatus("Ready — tap Record and speak")
        } catch (e: Exception) {
            setStatus("Startup failed: ${e.message}")
        }
    }

    private fun toggleRecord() {
        if (!ready || busy) return
        if (!isRecording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                askMic.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            if (!recorder.start()) {
                setStatus("Could not open microphone")
                return
            }
            isRecording = true
            btnRecord.text = "■ Stop"
            setStatus("Listening… tap Stop when done")
        } else {
            val pcm = recorder.stop()
            isRecording = false
            btnRecord.text = "🎙 Record"
            scope.launch { runPipeline(pcm) }
        }
    }

    private fun handleFile(uri: Uri) {
        scope.launch {
            setStatus("Decoding audio file…")
            busy = true
            val pcm = withContext(Dispatchers.IO) {
                try {
                    AudioDecoder.decodeToMono16k(this@MainActivity, uri)
                } catch (e: Exception) {
                    null
                }
            }
            busy = false
            if (pcm == null) {
                setStatus("Could not decode that file")
            } else {
                runPipeline(pcm)
            }
        }
    }

    private suspend fun runPipeline(pcm: FloatArray) {
        if (busy) return
        if (pcm.size < 8000) { // < 0.5 s
            setStatus("Audio too short — try again")
            return
        }
        busy = true
        try {
            setStatus("Transcribing…")
            val text = withContext(Dispatchers.Default) {
                WhisperBridge.transcribe(whisperCtx, pcm, numThreads())
            }.trim()
            if (text.isEmpty() || text.startsWith("[")) {
                setStatus("Heard nothing — try again")
                return
            }
            appendChat("You", text)

            setStatus("Thinking…")
            history.add("user" to text)
            // Prompt window stays small; the stored chat keeps everything.
            val msgs = listOf("system" to systemPrompt) + history.takeLast(10)
            val raw = withContext(Dispatchers.Default) {
                String(
                    LlamaBridge.chat(
                        msgs.map { it.first }.toTypedArray(),
                        msgs.map { it.second }.toTypedArray(),
                        512
                    ),
                    Charsets.UTF_8
                )
            }
            val reply = cleanReply(raw)
            history.add("assistant" to reply)
            appendChat("Assistant", reply)
            saveChat()
            setStatus("Ready — tap 🔊 to hear the answer")
        } catch (e: Exception) {
            setStatus("Error: ${e.message}")
        } finally {
            busy = false
        }
    }

    /** Speaks one message; triggered by the 🔊 button, never automatically. */
    private fun speak(text: String) {
        if (!ready || speaking) return
        speaking = true
        scope.launch {
            try {
                setStatus("Speaking…")
                val audio = withContext(Dispatchers.Default) {
                    tts!!.generate(text = text.take(500), sid = 0, speed = 1.0f)
                }
                withContext(Dispatchers.IO) { play(audio.samples, audio.sampleRate) }
                setStatus("Ready")
            } catch (e: Exception) {
                setStatus("TTS error: ${e.message}")
            } finally {
                speaking = false
            }
        }
    }

    private fun newChat() {
        if (busy) return
        chatId = null
        history.clear()
        messagesView.removeAllViews()
        if (ready) setStatus("New chat — tap Record and speak")
    }

    private fun showChats() {
        if (busy) return
        val chats = ChatStore.list(this)
        if (chats.isEmpty()) {
            setStatus("No saved chats yet")
            return
        }
        val labels = chats.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Chats (saved on this phone only)")
            .setItems(labels) { _, i -> openChat(chats[i].id) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun openChat(id: String) {
        val msgs = ChatStore.load(this, id) ?: run {
            setStatus("Could not open that chat")
            return
        }
        chatId = id
        history.clear()
        history.addAll(msgs)
        messagesView.removeAllViews()
        for ((role, content) in msgs) {
            appendChat(if (role == "user") "You" else "Assistant", content)
        }
    }

    private suspend fun saveChat() {
        val id = chatId ?: System.currentTimeMillis().toString().also { chatId = it }
        val title = history.firstOrNull { it.first == "user" }
            ?.second?.take(40) ?: "Chat"
        val snapshot = history.toList()
        withContext(Dispatchers.IO) {
            ChatStore.save(this@MainActivity, id, title, snapshot)
        }
    }

    /** Drops thinking tags and trims; Qwen may emit <think>…</think> first. */
    private fun cleanReply(raw: String): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        if (s.contains("<think>")) s = s.substringBefore("<think>")
        s = s.replace(Regex("\\s+"), " ").trim()
        return s.ifEmpty { "I do not have an answer for that." }
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(
                AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
                ) * 2
            )
            .build()
        track.play()
        var offset = 0
        while (offset < samples.size) {
            val written = track.write(
                samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING
            )
            if (written <= 0) break
            offset += written
        }
        track.stop()
        track.release()
    }

    /** Safe from any thread. */
    private fun setStatus(text: String) = runOnUiThread {
        statusView.text = text
    }

    /** Adds one message row; assistant rows get a 🔊 button. Any thread. */
    private fun appendChat(who: String, text: String) = runOnUiThread {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(12))
        }
        row.addView(TextView(this).apply {
            this.text = "$who: $text"
            textSize = 16f
            setTextIsSelectable(true)
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        if (who == "Assistant") {
            row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = "🔊"
                minWidth = 0
                minimumWidth = 0
                setPadding(dp(8), 0, dp(8), 0)
                setOnClickListener { speak(text) }
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ))
        }
        messagesView.addView(row)
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun numThreads() =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        if (isRecording) recorder.stop()
        if (whisperCtx != 0L) WhisperBridge.freeContext(whisperCtx)
        LlamaBridge.unload()
        tts?.release()
    }
}
