package net.northbrand.zyrajarvis

import android.Manifest
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Activity
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Zyra — native Android client for the OpenJarvis backend on the NAS.
 *
 * One Activity, three jobs:
 *  1. Chat with Zyra through POST /v1/chat/completions (OpenAI-shaped;
 *     the server applies the Zyra persona itself).
 *  2. Voice in: record, POST /v1/speech/transcribe. Voice out: POST
 *     /v1/speech/synthesize and play the WAV reply.
 *  3. Be a presence, not a chat log: the stage at the top shows Zyra
 *     alive in whatever state the conversation is in (see [Stage]).
 *
 * AUTH (important): the NAS server binds 0.0.0.0, and OpenJarvis refuses
 * a non-loopback bind without OPENJARVIS_API_KEY. Every /v1 request
 * therefore sends "Authorization: Bearer <key>" with the key from
 * Settings. CHANGE-ME: the key must match the OPENJARVIS_API_KEY value
 * in the NAS project's .env; it is typed into the app's Settings field,
 * never hardcoded here.
 *
 * Networking is raw HttpURLConnection on a single background executor;
 * the UI thread only touches views. No third-party libraries.
 */
class MainActivity : Activity() {

    companion object {
        // CHANGE-ME: default backend address (UGREEN NAS, OpenJarvis
        // default port 8000). Editable in the app's Settings row and
        // persisted; this constant is only the first-run value.
        const val DEFAULT_BASE_URL = "http://192.168.1.60:8000"

        // SharedPreferences keys for the two settings fields.
        const val PREFS_NAME = "zyra_settings"
        const val PREF_BASE_URL = "base_url"
        const val PREF_API_KEY = "api_key"

        // Timeouts: connect fast, read slow (cloud chat + TTS take a while).
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 60_000

        // Handsfree end-of-utterance tuning. CHANGE-ME if she cuts you
        // off or waits too long: an utterance ends after SILENCE_MS of
        // quiet following real speech above AMPLITUDE_THRESHOLD
        // (MediaRecorder.getMaxAmplitude scale, roughly 0..32767).
        const val AMPLITUDE_THRESHOLD = 1500
        const val SILENCE_MS = 1200L
        const val NO_SPEECH_RESTART_MS = 15_000L // no speech yet: recycle the recorder
        const val MAX_LISTEN_MS = 30_000L       // hard cap on one utterance

        // Idle this long and Zyra drifts to the sleepy portrait.
        const val SLEEPY_AFTER_MS = 90_000L

        const val REQ_RECORD_AUDIO = 42
    }

    /**
     * The stage state machine. Every state maps to one bundled portrait
     * and one motion loop; [setStage] is the ONLY way the picture or the
     * motion changes, and it is driven by real conversation events:
     *
     *   IDLE      zyra_idle.webp      waiting; slow breathing scale loop
     *   LISTENING zyra_listening.webp mic is recording (PTT or handsfree);
     *                                 quicker pulse
     *   THINKING  zyra_thinking.webp  request in flight (chat or STT);
     *                                 gentle side-to-side sway
     *   SPEAKING  zyra_speaking.webp  TTS audio playing; bounce loop that
     *                                 stands in for speech amplitude
     *   HAPPY     zyra_happy.webp     flash for ~1.2 s when a reply lands,
     *                                 while its audio is being fetched
     *   PLAYFUL   zyra_playful.webp   flash when the stage is tapped, and
     *                                 when handsfree mode switches on
     *   SLEEPY    zyra_sleepy.webp    after SLEEPY_AFTER_MS idle
     *
     * Portraits live in res/drawable-nodpi/zyra_<state>.webp (640x640,
     * generated from Zyra's canonical likeness). To restyle her, replace
     * those files; no code change needed.
     */
    enum class Stage(val drawableRes: Int, val label: String) {
        IDLE(R.drawable.zyra_idle, ""),
        LISTENING(R.drawable.zyra_listening, "Listening…"),
        THINKING(R.drawable.zyra_thinking, "Thinking…"),
        SPEAKING(R.drawable.zyra_speaking, "Speaking…"),
        HAPPY(R.drawable.zyra_happy, ""),
        PLAYFUL(R.drawable.zyra_playful, ""),
        SLEEPY(R.drawable.zyra_sleepy, "")
    }

    // --- Views (bound in onCreate) ---
    private lateinit var baseUrlInput: EditText
    private lateinit var apiKeyInput: EditText
    private lateinit var stageFrame: FrameLayout
    private lateinit var stageImageA: ImageView
    private lateinit var stageImageB: ImageView
    private lateinit var stateLabel: TextView
    private lateinit var chatScroll: ScrollView
    private lateinit var chatContainer: LinearLayout
    private lateinit var messageInput: EditText
    private lateinit var talkButton: Button
    private lateinit var handsfreeButton: Button

    // --- Runtime state ---
    private val mainHandler = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor() // serializes chat -> TTS
    private val history = mutableListOf<Pair<String, String>>() // (role, content)
    private var stage: Stage = Stage.IDLE
    private var frontImage: ImageView? = null  // the currently visible stage ImageView
    private var backImage: ImageView? = null   // the hidden one, used for crossfades
    private var stageMotion: AnimatorSet? = null
    private var sleepyRunnable: Runnable? = null

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var pttRecording = false

    private var player: MediaPlayer? = null

    private var handsfreeOn = false
    private var hfHeardSpeech = false
    private var hfListenStartedAt = 0L
    private var hfLastSpeechAt = 0L
    private var hfPoll: Runnable? = null

    private var pendingVoiceAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Edge-to-edge (targetSdk 35): consume the system-bar insets as
        // root padding so nothing renders under the status/nav bars.
        val root = findViewById<View>(R.id.rootLayout)
        root.setOnApplyWindowInsetsListener { v, insets ->
            v.setPadding(
                insets.systemWindowInsetLeft,
                insets.systemWindowInsetTop,
                insets.systemWindowInsetRight,
                insets.systemWindowInsetBottom
            )
            insets
        }

        baseUrlInput = findViewById(R.id.baseUrlInput)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        stageFrame = findViewById(R.id.stageFrame)
        stageImageA = findViewById(R.id.stageImageA)
        stageImageB = findViewById(R.id.stageImageB)
        stateLabel = findViewById(R.id.stateLabel)
        chatScroll = findViewById(R.id.chatScroll)
        chatContainer = findViewById(R.id.chatContainer)
        messageInput = findViewById(R.id.messageInput)
        talkButton = findViewById(R.id.talkButton)
        handsfreeButton = findViewById(R.id.handsfreeButton)

        // Settings: prefill from SharedPreferences (defaults on first run).
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        baseUrlInput.setText(prefs.getString(PREF_BASE_URL, DEFAULT_BASE_URL))
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, ""))
        findViewById<Button>(R.id.saveSettingsButton).setOnClickListener {
            prefs.edit()
                .putString(PREF_BASE_URL, baseUrlInput.text.toString().trim())
                .putString(PREF_API_KEY, apiKeyInput.text.toString().trim())
                .apply()
            Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
        }

        // Stage starts on the idle portrait; tapping her gets a playful flash.
        frontImage = stageImageA
        backImage = stageImageB
        stageImageA.setImageResource(Stage.IDLE.drawableRes)
        stageFrame.setOnClickListener {
            if (stage == Stage.IDLE || stage == Stage.SLEEPY) flashStage(Stage.PLAYFUL, 1500)
        }
        setStage(Stage.IDLE)

        findViewById<Button>(R.id.sendButton).setOnClickListener {
            val text = messageInput.text.toString().trim()
            if (text.isNotEmpty()) {
                messageInput.text.clear()
                sendChat(text)
            }
        }
        talkButton.setOnClickListener { onTalkTapped() }
        handsfreeButton.setOnClickListener { onHandsfreeTapped() }
    }

    override fun onDestroy() {
        stopRecorder()
        player?.release()
        net.shutdownNow()
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------
    // Settings helpers
    // ------------------------------------------------------------------

    private fun baseUrl(): String =
        (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL)
            .trim().removeSuffix("/")

    private fun apiKey(): String =
        (getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getString(PREF_API_KEY, "") ?: "").trim()

    // ------------------------------------------------------------------
    // Stage: crossfade + per-state motion
    // ------------------------------------------------------------------

    private fun setStage(newStage: Stage) {
        // Any state change cancels a pending drift off to sleep.
        sleepyRunnable?.let { mainHandler.removeCallbacks(it) }
        sleepyRunnable = null

        if (newStage != stage) {
            stage = newStage
            // Crossfade: load the new portrait into the hidden ImageView,
            // fade the pair, then swap which one is "front".
            val front = frontImage ?: return
            val back = backImage ?: return
            back.setImageResource(newStage.drawableRes)
            back.animate().alpha(1f).setDuration(300).start()
            front.animate().alpha(0f).setDuration(300).start()
            frontImage = back
            backImage = front
        }
        stateLabel.text = newStage.label
        startMotion(newStage)

        if (newStage == Stage.IDLE && !handsfreeOn) {
            val r = Runnable { if (stage == Stage.IDLE && !handsfreeOn) setStage(Stage.SLEEPY) }
            sleepyRunnable = r
            mainHandler.postDelayed(r, SLEEPY_AFTER_MS)
        }
    }

    /** Show a brief expression, then fall back to idle (used for HAPPY/PLAYFUL). */
    private fun flashStage(flash: Stage, durationMs: Long) {
        setStage(flash)
        mainHandler.postDelayed({ if (stage == flash) setStage(Stage.IDLE) }, durationMs)
    }

    /** Gentle continuous motion, one loop per state, run on the stage frame. */
    private fun startMotion(forStage: Stage) {
        stageMotion?.cancel()
        stageFrame.animate().cancel()
        stageFrame.scaleX = 1f
        stageFrame.scaleY = 1f
        stageFrame.rotation = 0f
        stageFrame.translationY = 0f

        fun loop(prop: String, from: Float, to: Float, durationMs: Long): ObjectAnimator =
            ObjectAnimator.ofFloat(stageFrame, prop, from, to).apply {
                duration = durationMs
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
            }

        val set = AnimatorSet()
        when (forStage) {
            Stage.IDLE, Stage.SLEEPY -> // breathing
                set.playTogether(loop("scaleX", 1f, 1.03f, 2400), loop("scaleY", 1f, 1.03f, 2400))
            Stage.LISTENING -> // attentive pulse
                set.playTogether(loop("scaleX", 1f, 1.06f, 650), loop("scaleY", 1f, 1.06f, 650))
            Stage.THINKING -> // sway
                set.playTogether(loop("rotation", -2f, 2f, 1600))
            Stage.SPEAKING ->
                // Bounce stands in for live speech amplitude; a real
                // Visualizer hookup would need another permission for
                // very little gain. shortcut: fixed bounce, upgrade to
                // Visualizer-driven scale if it ever feels lifeless.
                set.playTogether(loop("translationY", 0f, -10f, 300), loop("scaleY", 1f, 1.04f, 300))
            Stage.HAPPY, Stage.PLAYFUL -> { // one cheerful pop, no loop
                val pop = ObjectAnimator.ofFloat(stageFrame, "scaleX", 1f, 1.08f, 1f).apply { duration = 600 }
                val popY = ObjectAnimator.ofFloat(stageFrame, "scaleY", 1f, 1.08f, 1f).apply { duration = 600 }
                set.playTogether(pop, popY)
            }
        }
        stageMotion = set
        set.start()
    }

    // ------------------------------------------------------------------
    // Chat bubbles
    // ------------------------------------------------------------------

    private fun bubble(text: String, who: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 15f
        tv.setPadding(28, 18, 28, 18)
        val bg = GradientDrawable()
        bg.cornerRadius = 36f
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        lp.bottomMargin = 14
        when (who) {
            "user" -> {
                bg.setColor(Color.parseColor("#C9A227")) // Northbrand gold
                tv.setTextColor(Color.parseColor("#14161C"))
                lp.gravity = Gravity.END
            }
            "error" -> {
                bg.setColor(Color.parseColor("#1E222C"))
                tv.setTextColor(Color.parseColor("#E08A8A"))
                lp.gravity = Gravity.START
            }
            else -> { // assistant
                bg.setColor(Color.parseColor("#1E222C"))
                tv.setTextColor(Color.parseColor("#F2EFE6"))
                lp.gravity = Gravity.START
            }
        }
        tv.background = bg
        tv.layoutParams = lp
        chatContainer.addView(tv)
        chatScroll.post { chatScroll.fullScroll(View.FOCUS_DOWN) }
    }

    /** The one plain error message for any backend failure. */
    private fun errorBubble(httpCode: Int?) {
        val url = baseUrl()
        val msg = if (httpCode == 401)
            "Could not reach Zyra at $url (HTTP 401). Check the backend API key in Settings and that the NAS backend is running."
        else
            "Could not reach Zyra at $url. Check the address and that the NAS backend is running."
        bubble(msg, "error")
    }

    // ------------------------------------------------------------------
    // Networking. All calls carry the Bearer key: the NAS bind is
    // non-loopback, so OpenJarvis requires it on every /v1 route
    // (only /health is open). Header is added here, in one place.
    // ------------------------------------------------------------------

    private class HttpError(val code: Int) : Exception("HTTP $code")

    private fun openAuthed(path: String): HttpURLConnection {
        val conn = URL(baseUrl() + path).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        val key = apiKey()
        if (key.isNotEmpty()) conn.setRequestProperty("Authorization", "Bearer $key")
        return conn
    }

    /** POST /v1/chat/completions with the full history; returns the reply text. */
    private fun postChat(): String {
        val messages = JSONArray()
        for ((role, content) in history) {
            messages.put(JSONObject().put("role", role).put("content", content))
        }
        val body = JSONObject()
            .put("model", "") // server picks its configured model
            .put("stream", false)
            .put("messages", messages)

        val conn = openAuthed("/v1/chat/completions")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) throw HttpError(conn.responseCode)
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        // Verified shape (server/models.py ChatCompletionResponse):
        // choices[0].message.content. Parsed defensively anyway.
        val choices = json.optJSONArray("choices") ?: throw IOException("No choices in reply")
        val message = choices.optJSONObject(0)?.optJSONObject("message")
            ?: throw IOException("No message in reply")
        return message.optString("content", "").ifBlank { throw IOException("Empty reply") }
    }

    /** POST /v1/speech/transcribe (multipart, field "file"); returns the transcript. */
    private fun postTranscribe(audio: File): String {
        val boundary = "zyra" + System.currentTimeMillis()
        val conn = openAuthed("/v1/speech/transcribe")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        conn.doOutput = true
        conn.outputStream.use { out ->
            // The backend detects the audio format from the filename
            // extension, so the .m4a name here is load-bearing.
            out.write("--$boundary\r\n".toByteArray())
            out.write("Content-Disposition: form-data; name=\"file\"; filename=\"speech.m4a\"\r\n".toByteArray())
            out.write("Content-Type: audio/mp4\r\n\r\n".toByteArray())
            audio.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$boundary--\r\n".toByteArray())
        }
        if (conn.responseCode != 200) throw HttpError(conn.responseCode)
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        return json.optString("text", "")
    }

    /** POST /v1/speech/synthesize; returns raw WAV bytes (server picks the voice). */
    private fun postSynthesize(text: String): ByteArray {
        val body = JSONObject().put("text", text)
        val conn = openAuthed("/v1/speech/synthesize")
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        if (conn.responseCode != 200) throw HttpError(conn.responseCode)
        return conn.inputStream.use { it.readBytes() }
    }

    // ------------------------------------------------------------------
    // The conversation turn: user text -> reply bubble -> spoken reply.
    // Runs for typed messages, push-to-talk transcripts, and handsfree
    // utterances alike. On completion it either reopens the handsfree
    // mic or settles back to idle.
    // ------------------------------------------------------------------

    private fun sendChat(userText: String) {
        bubble(userText, "user")
        history.add("user" to userText)
        setStage(Stage.THINKING)
        net.execute {
            try {
                val reply = postChat()
                mainHandler.post {
                    bubble(reply, "assistant")
                    history.add("assistant" to reply)
                    // Happy flash while the reply's audio is fetched.
                    setStage(Stage.HAPPY)
                }
                // TTS is best-effort: a synthesis failure never eats the reply.
                val audio = try { postSynthesize(reply) } catch (e: Exception) { null }
                mainHandler.post { if (audio != null) playSpeech(audio) else afterSpeech() }
            } catch (e: HttpError) {
                mainHandler.post { onTurnFailed(e.code) }
            } catch (e: Exception) {
                mainHandler.post { onTurnFailed(null) }
            }
        }
    }

    private fun onTurnFailed(httpCode: Int?) {
        errorBubble(httpCode)
        // A dead backend would loop forever in handsfree; stop instead.
        if (handsfreeOn) setHandsfree(false)
        setStage(Stage.IDLE)
    }

    private fun playSpeech(wav: ByteArray) {
        val file = File(cacheDir, "reply.wav")
        file.writeBytes(wav)
        player?.release()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener { afterSpeech() }
            setOnErrorListener { _, _, _ -> afterSpeech(); true }
            prepare()
            start()
        }
        setStage(Stage.SPEAKING)
    }

    /** Half-duplex hinge: the mic only ever reopens AFTER playback ends. */
    private fun afterSpeech() {
        player?.release()
        player = null
        if (handsfreeOn) startHandsfreeListen() else setStage(Stage.IDLE)
    }

    // ------------------------------------------------------------------
    // Recording (shared by push-to-talk and handsfree)
    // ------------------------------------------------------------------

    private fun startRecorder(fileName: String): Boolean {
        stopRecorder()
        val file = File(cacheDir, fileName)
        recordFile = file
        return try {
            @Suppress("DEPRECATION") // no-context constructor still works on all supported APIs
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            true
        } catch (e: Exception) {
            recorder = null
            false
        }
    }

    private fun stopRecorder() {
        try { recorder?.stop() } catch (e: Exception) { /* too-short recordings throw; the file is discarded anyway */ }
        recorder?.release()
        recorder = null
    }

    private fun ensureAudioPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingVoiceAction = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_RECORD_AUDIO) {
            val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (granted) pendingVoiceAction?.invoke()
            else Toast.makeText(this, "Microphone permission is needed for voice", Toast.LENGTH_LONG).show()
            pendingVoiceAction = null
        }
    }

    // --- Push-to-talk: tap Talk to start, tap Stop to send. ---

    private fun onTalkTapped() {
        if (pttRecording) {
            stopPtt()
            return
        }
        if (handsfreeOn) setHandsfree(false) // PTT takes the mic over
        ensureAudioPermission {
            if (startRecorder("ptt.m4a")) {
                pttRecording = true
                talkButton.text = "Stop"
                setStage(Stage.LISTENING)
            }
        }
    }

    private fun stopPtt() {
        pttRecording = false
        talkButton.text = "Talk"
        stopRecorder()
        val file = recordFile ?: return
        setStage(Stage.THINKING)
        net.execute {
            try {
                val text = postTranscribe(file).trim()
                mainHandler.post { if (text.isNotEmpty()) sendChat(text) else setStage(Stage.IDLE) }
            } catch (e: HttpError) {
                mainHandler.post { onTurnFailed(e.code) }
            } catch (e: Exception) {
                mainHandler.post { onTurnFailed(null) }
            }
        }
    }

    // ------------------------------------------------------------------
    // Handsfree mode: listen -> transcribe -> chat -> speak -> listen…
    // Half-duplex by construction: the recorder is released before any
    // network call and only recreated in afterSpeech(), so Zyra can
    // never hear (and transcribe) herself.
    // Exit paths: tap the Handsfree button again, say a stop phrase
    // (see STOP_PHRASES), or any backend error (onTurnFailed).
    // ------------------------------------------------------------------

    private fun onHandsfreeTapped() {
        if (handsfreeOn) {
            setHandsfree(false)
            setStage(Stage.IDLE)
            return
        }
        ensureAudioPermission {
            setHandsfree(true)
            flashStage(Stage.PLAYFUL, 900)
            mainHandler.postDelayed({ if (handsfreeOn) startHandsfreeListen() }, 950)
        }
    }

    private fun setHandsfree(on: Boolean) {
        handsfreeOn = on
        handsfreeButton.text = if (on) "Stop HF" else "Handsfree"
        if (!on) {
            hfPoll?.let { mainHandler.removeCallbacks(it) }
            hfPoll = null
            if (!pttRecording) stopRecorder()
        }
    }

    private fun startHandsfreeListen() {
        if (!handsfreeOn) return
        if (!startRecorder("handsfree.m4a")) { setHandsfree(false); setStage(Stage.IDLE); return }
        setStage(Stage.LISTENING)
        hfHeardSpeech = false
        hfListenStartedAt = System.currentTimeMillis()
        hfLastSpeechAt = hfListenStartedAt

        // End-of-utterance detection: poll the recorder's peak amplitude.
        // Speech above the threshold restarts the silence clock; SILENCE_MS
        // of quiet after speech ends the utterance. No speech at all for
        // NO_SPEECH_RESTART_MS recycles the recorder quietly; MAX_LISTEN_MS
        // caps a single utterance no matter what.
        val poll = object : Runnable {
            override fun run() {
                if (!handsfreeOn || recorder == null) return
                val now = System.currentTimeMillis()
                val amp = try { recorder?.maxAmplitude ?: 0 } catch (e: Exception) { 0 }
                if (amp > AMPLITUDE_THRESHOLD) {
                    hfHeardSpeech = true
                    hfLastSpeechAt = now
                }
                when {
                    hfHeardSpeech && now - hfLastSpeechAt > SILENCE_MS -> finishHandsfreeUtterance()
                    now - hfListenStartedAt > MAX_LISTEN_MS -> finishHandsfreeUtterance()
                    !hfHeardSpeech && now - hfListenStartedAt > NO_SPEECH_RESTART_MS -> {
                        stopRecorder()
                        startHandsfreeListen()
                    }
                    else -> mainHandler.postDelayed(this, 150)
                }
            }
        }
        hfPoll = poll
        mainHandler.postDelayed(poll, 150)
    }

    private fun finishHandsfreeUtterance() {
        hfPoll?.let { mainHandler.removeCallbacks(it) }
        hfPoll = null
        stopRecorder() // mic closed BEFORE any network call: half-duplex
        val file = recordFile ?: run { if (handsfreeOn) startHandsfreeListen(); return }
        if (!hfHeardSpeech) { if (handsfreeOn) startHandsfreeListen(); return }
        setStage(Stage.THINKING)
        net.execute {
            try {
                val text = postTranscribe(file).trim()
                mainHandler.post {
                    when {
                        text.isEmpty() -> if (handsfreeOn) startHandsfreeListen()
                        isStopPhrase(text) -> {
                            bubble(text, "user")
                            setHandsfree(false)
                            bubble("Handsfree off.", "assistant")
                            setStage(Stage.IDLE)
                        }
                        else -> sendChat(text)
                    }
                }
            } catch (e: HttpError) {
                mainHandler.post { onTurnFailed(e.code) }
            } catch (e: Exception) {
                mainHandler.post { onTurnFailed(null) }
            }
        }
    }

    /** Spoken exit phrases for handsfree mode. CHANGE-ME: add phrasings freely. */
    private fun isStopPhrase(text: String): Boolean {
        val t = text.lowercase().trim().trimEnd('.', '!', '?')
        return t == "stop" || t.startsWith("stop ") || t == "goodbye" ||
            t == "that's all" || t == "that is all"
    }
}
