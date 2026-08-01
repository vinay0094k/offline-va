package com.codvika.voiceassistant

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.MeasureSpec
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.text.HtmlCompat
import com.codvika.llama.LlamaBridge
import com.codvika.whisper.WhisperBridge
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.io.File

class MainActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var whisperCtx = 0L
    private var tts: OfflineTts? = null
    private var ready = false
    private var busy = false
    private var speaking = false
    private var isRecording = false
    private var micDenied = false
    private var speakButton: Button? = null
    @Volatile private var stopRequested = false
    @Volatile private var currentTrack: AudioTrack? = null
    private val recorder = AudioRecorder()

    /** Full transcript of the open chat: role -> content, no system prompt. */
    private val history = ArrayList<Pair<String, String>>()

    /** File id of the open chat in ChatStore; null until the first exchange. */
    private var chatId: String? = null

    private lateinit var statusView: TextView
    private lateinit var statusSpinner: ProgressBar
    private lateinit var badgeCloud: TextView
    private lateinit var messagesView: LinearLayout
    private lateinit var chatScroll: ScrollView
    private lateinit var btnRecord: Button
    private lateinit var recordProgress: ProgressBar
    private var pulseAnimator: ObjectAnimator? = null
    private lateinit var btnFile: Button
    private lateinit var btnNew: Button
    private lateinit var btnChats: Button
    private lateinit var btnSettings: Button
    private lateinit var btnImport: Button
    private lateinit var importProgress: ProgressBar
    private lateinit var recordingOverlay: View
    private lateinit var recordTimer: TextView
    private var recordTimerJob: Job? = null

    private val systemPrompt =
        "You are a helpful voice assistant. Reply in one or two short sentences of " +
        "spoken text. Use **bold** for key words and short dash lists when useful. " +
        "No emoji. /no_think"

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
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (!granted) micDenied()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        statusSpinner = findViewById(R.id.statusSpinner)
        messagesView = findViewById(R.id.messagesView)
        chatScroll = findViewById(R.id.chatScroll)
        btnRecord = findViewById(R.id.btnRecord)
        recordProgress = findViewById(R.id.recordProgress)
        btnFile = findViewById(R.id.btnFile)
        btnNew = findViewById(R.id.btnNew)
        btnChats = findViewById(R.id.btnChats)
        btnSettings = findViewById(R.id.btnSettings)
        btnImport = findViewById(R.id.btnImport)
        badgeCloud = findViewById(R.id.badgeCloud)
        importProgress = findViewById(R.id.importProgress)
        recordingOverlay = findViewById(R.id.recordingOverlay)
        recordTimer = findViewById(R.id.recordTimer)
        updateOnlineBadge()
        setFabEnabled(btnRecord, false)
        setFabEnabled(btnFile, false)

        btnRecord.setOnClickListener { toggleRecord() }
        btnImport.setOnClickListener { if (!busy) pickPack.launch("*/*") }
        btnFile.setOnClickListener { if (ready && !busy) pickAudio.launch("audio/*") }
        btnNew.setOnClickListener { newChat() }
        btnChats.setOnClickListener { showChats() }
        btnSettings.setOnClickListener {
            if (!busy) startActivity(Intent(this, SettingsActivity::class.java))
        }

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

    /** Re-evaluates the online badge after returning from Settings. */
    override fun onResume() {
        super.onResume()
        updateOnlineBadge()
    }

    private fun importPack(uri: Uri) {
        scope.launch {
            busy = true
            btnImport.isEnabled = false
            importProgress.visibility = View.VISIBLE
            importProgress.progress = 0
            try {
                setWorking("Importing model pack…")
                withContext(Dispatchers.IO) {
                    ModelPack.import(this@MainActivity, uri) { fraction ->
                        val percent = (fraction * 100).toInt().coerceIn(0, 100)
                        setWorking("Importing model pack… $percent%")
                        runOnUiThread { importProgress.progress = percent }
                    }
                }
                importProgress.visibility = View.GONE
                btnImport.visibility = View.GONE
                busy = false
                initModels()
            } catch (e: Exception) {
                busy = false
                importProgress.visibility = View.GONE
                btnImport.isEnabled = true
                showError("Import failed: ${e.message}")
            }
        }
    }

    private suspend fun initModels() {
        try {
            withContext(Dispatchers.IO) {
                val base = ModelPack.dir(this@MainActivity)

                setWorking("Loading Whisper…")
                whisperCtx = WhisperBridge.initContext(
                    File(base, "whisper/ggml-base.en-q5_1.bin").absolutePath
                )
                check(whisperCtx != 0L) { "Whisper model failed to load" }

                setWorking("Loading Qwen…")
                val ok = LlamaBridge.loadModel(
                    File(base, "llm/qwen3.5-0.8b-q4_k_m.gguf").absolutePath,
                    numThreads(), 4096
                )
                check(ok) { "Qwen model failed to load" }

                setWorking("Loading TTS voice…")
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
            setFabEnabled(btnRecord, true)
            setFabEnabled(btnFile, true)
            setStatus("Ready — tap 🎙 and speak")
        } catch (e: Exception) {
            showError("Startup failed: ${e.message}")
        }
    }

    private fun toggleRecord() {
        if (!ready || busy) return
        if (!isRecording) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                if (micDenied) micDenied() else askMic.launch(Manifest.permission.RECORD_AUDIO)
                return
            }
            if (!recorder.start()) {
                setStatus("Could not open microphone")
                return
            }
            isRecording = true
            btnRecord.text = "■"
            setRecordingUi(true)
            startRecordTimer()
            setStatus("Listening… tap ■ when done")
        } else {
            val pcm = recorder.stop()
            isRecording = false
            btnRecord.text = "🎙"
            setRecordingUi(false)
            stopRecordTimer()
            scope.launch { runPipeline(pcm) }
        }
    }

    /** Dims the screen and shows a live "● Recording 0:07" chip while the mic is on. */
    private fun startRecordTimer() {
        val start = System.currentTimeMillis()
        recordingOverlay.visibility = View.VISIBLE
        recordTimer.visibility = View.VISIBLE
        recordTimer.text = "● 0:00"
        recordTimerJob = scope.launch {
            while (isActive) {
                val secs = ((System.currentTimeMillis() - start) / 1000).toInt()
                recordTimer.text = "● %d:%02d".format(secs / 60, secs % 60)
                delay(200)
            }
        }
    }

    private fun stopRecordTimer() {
        recordTimerJob?.cancel()
        recordTimerJob = null
        recordingOverlay.visibility = View.GONE
        recordTimer.visibility = View.GONE
    }

    /** Custom oval backgrounds don't get the automatic disabled tint. */
    private fun setFabEnabled(button: Button, enabled: Boolean) {
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.4f
    }

    /** Red background + a slow opacity pulse while the mic is live. */
    private fun setRecordingUi(recording: Boolean) {
        if (recording) {
            btnRecord.background = ContextCompat.getDrawable(this, R.drawable.bg_fab_recording)
            pulseAnimator = ObjectAnimator.ofFloat(btnRecord, "alpha", 1f, 0.45f).apply {
                duration = 600
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        } else {
            pulseAnimator?.cancel()
            pulseAnimator = null
            btnRecord.alpha = 1f
            btnRecord.background = ContextCompat.getDrawable(this, R.drawable.bg_fab_primary)
        }
    }

    /** Dims the mic FAB and shows a spinner over it while transcribing/thinking. */
    private fun setProcessingUi(processing: Boolean) = runOnUiThread {
        recordProgress.visibility = if (processing) View.VISIBLE else View.GONE
        btnRecord.alpha = if (processing) 0.35f else 1f
    }

    private fun handleFile(uri: Uri) {
        scope.launch {
            setWorking("Decoding audio file…")
            busy = true
            var error: String? = null
            val pcm = withContext(Dispatchers.IO) {
                try {
                    AudioDecoder.decodeToMono16k(this@MainActivity, uri)
                } catch (e: Exception) {
                    error = e.message
                    null
                }
            }
            busy = false
            if (pcm == null) {
                showError(error ?: "Could not decode that audio file. Try WAV, MP3, M4A, OGG, or FLAC.")
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
        setProcessingUi(true)
        try {
            setWorking("Transcribing…")
            val text = withContext(Dispatchers.Default) {
                WhisperBridge.transcribe(whisperCtx, pcm, numThreads())
            }.trim()
            if (text.isEmpty() || text.startsWith("[")) {
                setStatus("Heard nothing — try again")
                return
            }
            appendChat("You", text)

            history.add("user" to text)
            // Prompt window stays small; the stored chat keeps everything.
            val msgs = listOf("system" to systemPrompt) + history.takeLast(10)
            val online = Settings.onlineEnabled(this) && Settings.isConfigured(this)
            setWorking(if (online) "Thinking… (cloud)" else "Thinking…")
            val raw = if (online) {
                withContext(Dispatchers.IO) {
                    RemoteLlm.chat(
                        Settings.baseUrl(this@MainActivity),
                        Settings.apiKey(this@MainActivity),
                        Settings.model(this@MainActivity),
                        msgs.map { it.first }.toTypedArray(),
                        msgs.map { it.second }.toTypedArray(),
                        512
                    )
                }
            } else {
                withContext(Dispatchers.Default) {
                    String(
                        LlamaBridge.chat(
                            msgs.map { it.first }.toTypedArray(),
                            msgs.map { it.second }.toTypedArray(),
                            512
                        ),
                        Charsets.UTF_8
                    )
                }
            }
            val reply = cleanReply(raw)
            history.add("assistant" to reply)
            appendChat("Assistant", reply)
            saveChat()
            setStatus("Ready — tap 🔊 to hear the answer")
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        } finally {
            busy = false
            setProcessingUi(false)
        }
    }

    /** Speaks one message; tap the same button again to stop playback. */
    private fun speak(text: String, button: Button) {
        if (!ready) return
        if (speaking) {
            stopSpeaking()
            return
        }
        speaking = true
        speakButton = button
        button.text = "■ Stop"
        scope.launch {
            try {
                setWorking("Speaking…")
                val audio = withContext(Dispatchers.Default) {
                    tts!!.generate(text = markdownToPlain(text).take(500), sid = 0, speed = 1.0f)
                }
                withContext(Dispatchers.IO) { play(audio.samples, audio.sampleRate) }
                setStatus(if (stopRequested) "Stopped" else "Ready")
            } catch (e: Exception) {
                setStatus("TTS error: ${e.message}")
            } finally {
                speaking = false
                stopRequested = false
                speakButton?.setText("🔊 Speak")
                speakButton = null
            }
        }
    }

    /** Requests playback to stop; the running play() loop exits promptly. */
    private fun stopSpeaking() {
        stopRequested = true
        currentTrack?.stop()
    }

    private fun newChat() {
        if (busy) return
        chatId = null
        history.clear()
        messagesView.removeAllViews()
        if (ready) setStatus("New chat — tap 🎙 and speak")
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

    /**
     * Drops thinking tags and tidies whitespace. Newlines are kept (only
     * collapsed runs), so markdown lists and paragraphs render correctly;
     * Qwen may emit <think>…</think> first.
     */
    private fun cleanReply(raw: String): String {
        var s = raw.replace(Regex("(?s)<think>.*?</think>"), "")
        if (s.contains("<think>")) s = s.substringBefore("<think>")
        s = s.replace("\r\n", "\n").replace("\r", "\n")
        s = s.replace(Regex("\\h+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
        return s.ifEmpty { "I do not have an answer for that." }
    }

    private val markdownParser: Parser = Parser.builder().build()
    private val markdownRenderer: HtmlRenderer = HtmlRenderer.builder().build()

    /** Renders markdown (bold, lists, code) to a styled Spanned for bubbles. */
    private fun renderMarkdown(text: String): CharSequence =
        HtmlCompat.fromHtml(
            markdownRenderer.render(markdownParser.parse(text)),
            HtmlCompat.FROM_HTML_MODE_LEGACY
        )

    /** Strips markdown so TTS reads plain text, not `**` and list dashes. */
    private fun markdownToPlain(text: String): String =
        renderMarkdown(text).toString().trim()

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
        currentTrack = track
        stopRequested = false
        track.play()
        var offset = 0
        while (offset < samples.size && !stopRequested) {
            val written = track.write(
                samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING
            )
            if (written <= 0) break
            offset += written
        }
        track.stop()
        track.release()
        currentTrack = null
    }

    /** Sets idle status text; hides the inline working spinner. Safe from any thread. */
    private fun setStatus(text: String) = runOnUiThread {
        statusView.text = text
        statusSpinner.visibility = View.GONE
    }

    /** Sets status text during a long-running stage and shows the inline spinner. */
    private fun setWorking(text: String) = runOnUiThread {
        statusView.text = text
        statusSpinner.visibility = View.VISIBLE
    }

    /** Errors can overflow the two-line status bar, so surface them in a dialog. */
    private fun showError(message: String) = runOnUiThread {
        statusView.text = message
        statusSpinner.visibility = View.GONE
        AlertDialog.Builder(this)
            .setTitle("Error")
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    /** Shown once the user has declined the mic; offers a shortcut to app settings. */
    private fun micDenied() {
        micDenied = true
        AlertDialog.Builder(this)
            .setTitle("Microphone permission needed")
            .setMessage(
                "Recording your voice needs the microphone. " +
                "Open system settings to grant it, then come back."
            )
            .setPositiveButton("Open Settings") { _, _ ->
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            .setNegativeButton("Not now", null)
            .show()
    }

    /** Persistent "☁ online" badge whenever online mode is active and configured. */
    private fun updateOnlineBadge() {
        val online = Settings.onlineEnabled(this) && Settings.isConfigured(this)
        badgeCloud.visibility = if (online) View.VISIBLE else View.GONE
    }

    /**
     * Adds one chat bubble: user bubbles lean right in brand color, assistant
     * bubbles lean left in a neutral tone with a 🔊 Speak action. Any thread.
     * Bubbles are capped by [MaxWidthLinearLayout] so chats stay readable on
     * tablets and in landscape, where they'd otherwise stretch full-width.
     */
    private fun appendChat(who: String, text: String) = runOnUiThread {
        val isUser = who == "You"
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = if (isUser) Gravity.END else Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
        }
        val bubble = MaxWidthLinearLayout(this, dp(360)).apply {
            orientation = LinearLayout.VERTICAL
            background = ContextCompat.getDrawable(
                this@MainActivity,
                if (isUser) R.drawable.bg_bubble_user else R.drawable.bg_bubble_assistant
            )
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        bubble.addView(TextView(this).apply {
            this.text = if (isUser) text else renderMarkdown(text)
            textSize = 15.5f
            setTextColor(
                ContextCompat.getColor(
                    this@MainActivity,
                    if (isUser) R.color.bubble_user_text else R.color.bubble_assistant_text
                )
            )
            setTextIsSelectable(true)
        })
        if (!isUser) {
            bubble.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                this.text = "🔊 Speak"
                textSize = 13f
                isAllCaps = false
                minWidth = 0
                minimumWidth = 0
                minHeight = 0
                minimumHeight = 0
                setPadding(0, dp(6), 0, 0)
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.brand_primary))
                setOnClickListener { speak(text, this) }
            })
        }
        row.addView(bubble)
        messagesView.addView(row)
        chatScroll.post { chatScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun numThreads() =
        Runtime.getRuntime().availableProcessors().coerceIn(2, 6)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        pulseAnimator?.cancel()
        if (isRecording) recorder.stop()
        if (speaking) stopSpeaking()
        if (whisperCtx != 0L) WhisperBridge.freeContext(whisperCtx)
        LlamaBridge.unload()
        tts?.release()
    }
}

/**
 * A LinearLayout that never exceeds [maxWidthPx], so chat bubbles stay
 * readable on tablets and in landscape. Android's View has no max-width
 * property, so the cap is applied during onMeasure instead.
 */
private class MaxWidthLinearLayout(context: Context, private val maxWidthPx: Int) :
    LinearLayout(context) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = MeasureSpec.getSize(widthMeasureSpec)
        if (size > maxWidthPx) {
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(maxWidthPx, MeasureSpec.AT_MOST),
                heightMeasureSpec
            )
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }
}
