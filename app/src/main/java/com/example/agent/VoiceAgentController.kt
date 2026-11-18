package com.example.agent

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Voice AI controller for the Floating Editor Agent & Agent Briefing:
 * - Uses Android TextToSpeech (`speakAndAwait`) so the AI announces what it will do BEFORE acting.
 * - Uses Android SpeechRecognizer (`startListening`) with RECORD_AUDIO check for hands-free spoken commands.
 */
class VoiceAgentController(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var recognizer: SpeechRecognizer? = null

    private val _isListening = MutableStateFlow(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _lastTranscript = MutableStateFlow("")
    val lastTranscript: StateFlow<String> = _lastTranscript.asStateFlow()

    private val _lastSpokenText = MutableStateFlow("")
    val lastSpokenText: StateFlow<String> = _lastSpokenText.asStateFlow()

    private val _voiceError = MutableStateFlow<String?>(null)
    val voiceError: StateFlow<String?> = _voiceError.asStateFlow()

    private val pendingUtterances = mutableMapOf<String, CompletableDeferred<Unit>>()
    private var ttsInitDeferred: CompletableDeferred<Boolean>? = null

    fun initTts() {
        if (tts != null) return
        val initDef = CompletableDeferred<Boolean>()
        ttsInitDeferred = initDef
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    ttsReady = true
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            _isSpeaking.value = true
                        }
                        override fun onDone(utteranceId: String?) {
                            _isSpeaking.value = false
                            pendingUtterances.remove(utteranceId)?.complete(Unit)
                        }
                        @Deprecated("Deprecated in API 21")
                        override fun onError(utteranceId: String?) {
                            _isSpeaking.value = false
                            pendingUtterances.remove(utteranceId)?.complete(Unit)
                        }
                        override fun onError(utteranceId: String?, errorCode: Int) {
                            _isSpeaking.value = false
                            pendingUtterances.remove(utteranceId)?.complete(Unit)
                        }
                    })
                    initDef.complete(true)
                } else {
                    initDef.complete(false)
                }
            }
        }.onFailure {
            initDef.complete(false)
        }
    }

    suspend fun speakAndAwait(text: String) {
        val clean = text.replace(Regex("[*`#_~]+"), " ").replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return
        _lastSpokenText.value = clean
        if (!ttsReady) {
            initTts()
            withTimeoutOrNull(2_000L) { ttsInitDeferred?.await() }
        }
        val engine = tts
        if (engine == null || !ttsReady) {
            return
        }
        val uid = "utt_${System.currentTimeMillis()}"
        val def = CompletableDeferred<Unit>()
        pendingUtterances[uid] = def
        mainHandler.post {
            runCatching {
                engine.speak(clean.take(3800), TextToSpeech.QUEUE_FLUSH, null, uid)
            }.onFailure {
                pendingUtterances.remove(uid)?.complete(Unit)
            }
        }
        withTimeoutOrNull(45_000L) { def.await() }
        _isSpeaking.value = false
    }

    fun stopSpeaking() {
        mainHandler.post {
            runCatching { tts?.stop() }
            pendingUtterances.values.toList().forEach { it.complete(Unit) }
            pendingUtterances.clear()
            _isSpeaking.value = false
        }
    }

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun startListening(onCommandRecognized: (String) -> Unit) {
        _voiceError.value = null
        if (!hasMicPermission()) {
            _voiceError.value = "RECORD_AUDIO permission required for Mic voice commands."
            return
        }
        mainHandler.post {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                _voiceError.value = "Android SpeechRecognizer service is not installed on this device/emulator."
                _isListening.value = false
                return@post
            }
            stopListeningInternal()
            val sr = SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            recognizer = sr
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    _isListening.value = true
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    _isListening.value = false
                }
                override fun onError(error: Int) {
                    _isListening.value = false
                    _voiceError.value = "Mic error code #$error — tap Mic to retry."
                }
                override fun onResults(results: Bundle?) {
                    _isListening.value = false
                    val spoken = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                    if (spoken.isNotBlank()) {
                        _lastTranscript.value = spoken
                        onCommandRecognized(spoken)
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                    if (partial.isNotBlank()) _lastTranscript.value = partial
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            }
            sr.startListening(intent)
            _isListening.value = true
        }
    }

    fun stopListening() {
        mainHandler.post { stopListeningInternal() }
    }

    private fun stopListeningInternal() {
        runCatching { recognizer?.stopListening() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        _isListening.value = false
    }

    fun release() {
        stopListeningInternal()
        mainHandler.post {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ttsReady = false
        }
    }
}
