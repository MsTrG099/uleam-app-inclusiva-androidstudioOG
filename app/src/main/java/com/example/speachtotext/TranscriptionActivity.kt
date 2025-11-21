package com.example.speachtotext

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope  // ✅ NUEVO
import com.example.speachtotext.database.TranscriptionDatabase
import com.example.speachtotext.punctuation.PunctuationRestorer  // ✅ NUEVO
import kotlinx.coroutines.launch  // ✅ NUEVO
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.SpeechService
import org.vosk.android.StorageService
import java.io.IOException

class TranscriptionActivity : AppCompatActivity() {

    // Views
    private lateinit var btnBack: ImageButton
    private lateinit var tvModeIndicator: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnToggleMode: Button
    private lateinit var tvResult: TextView
    private lateinit var btnRecord: CardView
    private lateinit var microphoneBackground: FrameLayout
    private lateinit var tvRecordLabel: TextView
    private lateinit var btnClear: Button
    private lateinit var btnSettings: ImageButton

    // Vosk (Offline)
    private var voskModel: Model? = null
    private var voskSpeechService: SpeechService? = null

    // Google SpeechRecognizer (Online)
    private var googleSpeechRecognizer: SpeechRecognizer? = null

    private var isListening = false
    private var isOnlineMode = false
    private var isManualMode = false

    // Helper de configuración
    private lateinit var audioSettings: AudioSettingsHelper

    // Para efectos de audio
    private var automaticGainControl: android.media.audiofx.AutomaticGainControl? = null
    private var noiseSuppressor: android.media.audiofx.NoiseSuppressor? = null
    private var echoCanceler: android.media.audiofx.AcousticEchoCanceler? = null

    // Para vibración
    private lateinit var vibrator: Vibrator

    // Para el historial
    private lateinit var database: TranscriptionDatabase
    private var recordingStartTime: Long = 0

    // ✅ NUEVO: Para puntuación automática
    private lateinit var punctuationRestorer: PunctuationRestorer

    companion object {
        private const val TAG = "TranscriptionActivity"
        private const val REQUEST_RECORD_AUDIO = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcription)

        audioSettings = AudioSettingsHelper(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        database = TranscriptionDatabase(this)

        // ✅ NUEVO: Inicializar modelo de puntuación
        punctuationRestorer = PunctuationRestorer.getInstance(this)

        initViews()
        setupListeners()
        checkPermissions()
        checkConnectionAndSetMode()
        initVoskModel()

        audioSettings.logCurrentSettings(TAG)
    }

    override fun onResume() {
        super.onResume()
        audioSettings.logCurrentSettings(TAG)
        if (!isManualMode && !isListening) {
            checkConnectionAndSetMode()
        }
    }

    private fun initViews() {
        btnBack = findViewById(R.id.btnBack)
        tvModeIndicator = findViewById(R.id.tvModeIndicator)
        tvStatus = findViewById(R.id.tvStatus)
        btnToggleMode = findViewById(R.id.btnToggleMode)
        tvResult = findViewById(R.id.tvResult)
        btnRecord = findViewById(R.id.btnRecord)
        microphoneBackground = findViewById(R.id.microphoneBackground)
        tvRecordLabel = findViewById(R.id.tvRecordLabel)
        btnClear = findViewById(R.id.btnClear)
        btnSettings = findViewById(R.id.btnSettings)
    }

    private fun setupListeners() {
        btnBack.setOnClickListener { finish() }

        btnRecord.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                tvResult.text = ""
                startListening()
            }
        }

        btnClear.setOnClickListener {
            tvResult.text = ""
        }

        btnToggleMode.setOnClickListener {
            stopListening()
            toggleMode()
        }

        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_RECORD_AUDIO
            )
        }
    }

    private fun isInternetAvailable(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun checkConnectionAndSetMode() {
        if (!isManualMode && audioSettings.isAutoModeSwitchEnabled()) {
            val hasInternet = isInternetAvailable()
            isOnlineMode = hasInternet && audioSettings.shouldPreferOnline()
            updateModeUI()

            if (isOnlineMode) {
                Toast.makeText(this, "✓ Conectado - Modo Online", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Modo Online activado automáticamente")
            } else {
                Toast.makeText(this, "✗ Sin conexión - Modo Offline", Toast.LENGTH_SHORT).show()
                Log.d(TAG, "Modo Offline activado automáticamente")
            }
        } else if (isManualMode) {
            updateModeUI()
        }
    }

    private fun toggleMode() {
        isManualMode = true
        isOnlineMode = !isOnlineMode
        updateModeUI()

        val modeText = if (isOnlineMode) "Online" else "Offline"
        Toast.makeText(this, "Modo cambiado a: $modeText", Toast.LENGTH_SHORT).show()
        Log.d(TAG, "Modo manual activado: $modeText")
    }

    private fun updateModeUI() {
        if (isOnlineMode) {
            tvModeIndicator.text = "🌐 Online"
            tvRecordLabel.text = "Grabar"
        } else {
            tvModeIndicator.text = "📡 Offline"
            tvRecordLabel.text = "Grabar"
        }

        // Actualizar estado
        if (!isListening) {
            tvStatus.text = "● Listo"
        }
    }

    private fun initVoskModel() {
        tvStatus.text = "● Cargando..."
        Log.d(TAG, "Iniciando carga del modelo Vosk...")

        Thread {
            try {
                StorageService.unpack(
                    this,
                    "model-es",
                    "model",
                    { loadedModel: Model ->
                        this.voskModel = loadedModel
                        runOnUiThread {
                            updateModeUI()
                            Toast.makeText(
                                this,
                                "Modelo Vosk cargado",
                                Toast.LENGTH_SHORT
                            ).show()
                            Log.d(TAG, "Modelo Vosk cargado exitosamente")
                        }
                    },
                    { exception: IOException ->
                        runOnUiThread {
                            tvStatus.text = "● Error"
                            val errorMsg = "Error: ${exception.message}"
                            Toast.makeText(this, errorMsg, Toast.LENGTH_LONG).show()
                            Log.e(TAG, "Error cargando modelo Vosk", exception)
                        }
                    }
                )
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "● Error"
                    Toast.makeText(
                        this,
                        "Error crítico: ${e.localizedMessage}",
                        Toast.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Error crítico al inicializar modelo", e)
                }
            }
        }.start()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permiso de micrófono requerido", Toast.LENGTH_SHORT).show()
            checkPermissions()
            return
        }

        releaseAudioEffects()
        recordingStartTime = System.currentTimeMillis()
        Log.d(TAG, "Inicio de grabación: $recordingStartTime")

        if (isOnlineMode) {
            startGoogleSpeechRecognition()
        } else {
            startVoskRecognition()
        }
    }

    private fun startVoskRecognition() {
        if (voskModel == null) {
            Toast.makeText(this, "El modelo Vosk aún no está cargado", Toast.LENGTH_SHORT).show()
            Log.w(TAG, "Intento de grabar sin modelo cargado")
            return
        }

        try {
            val sampleRate = audioSettings.getSampleRate().toFloat()
            Log.d(TAG, "Iniciando Vosk con Sample Rate: $sampleRate Hz")

            val recognizer = Recognizer(voskModel, sampleRate)
            voskSpeechService = SpeechService(recognizer, sampleRate)
            voskSpeechService?.startListening(voskRecognitionListener)

            isListening = true
            updateRecordButtonState()
            tvStatus.text = "● Escuchando"
            Log.d(TAG, "Reconocimiento Vosk iniciado")

            vibrateIfEnabled()
            playSoundIfEnabled()

        } catch (e: IOException) {
            val errorMsg = "Error al iniciar: ${e.message}"
            Toast.makeText(this, errorMsg, Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error al iniciar reconocimiento Vosk", e)
        }
    }

    private fun startGoogleSpeechRecognition() {
        if (!isInternetAvailable() && !isManualMode) {
            Toast.makeText(this, "Sin conexión. Cambiando a modo Offline...", Toast.LENGTH_SHORT).show()
            isOnlineMode = false
            updateModeUI()
            startVoskRecognition()
            return
        }

        try {
            Log.d(TAG, "Iniciando reconocimiento Google...")

            googleSpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            googleSpeechRecognizer?.setRecognitionListener(googleRecognitionListener)

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, audioSettings.getLanguageCode())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, audioSettings.isPartialResultsEnabled())
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, audioSettings.getMaxResults())
            }

            googleSpeechRecognizer?.startListening(intent)

            isListening = true
            updateRecordButtonState()
            tvStatus.text = "● Escuchando"
            Log.d(TAG, "Reconocimiento Google iniciado")

            vibrateIfEnabled()
            playSoundIfEnabled()

        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            Log.e(TAG, "Error al iniciar reconocimiento Google", e)
        }
    }

    private fun stopListening() {
        Log.d(TAG, "Deteniendo reconocimiento...")

        voskSpeechService?.stop()
        voskSpeechService?.shutdown()
        voskSpeechService = null

        googleSpeechRecognizer?.stopListening()
        googleSpeechRecognizer?.destroy()
        googleSpeechRecognizer = null

        releaseAudioEffects()

        isListening = false
        updateRecordButtonState()
        tvStatus.text = "● Listo"

        vibrateIfEnabled()
        playSoundIfEnabled()

        saveTranscriptionToHistory()
    }

    private fun saveTranscriptionToHistory() {
        val text = tvResult.text.toString().trim()

        if (text.isBlank() || text == "El texto aparecerá aquí...") {
            Log.d(TAG, "No se guarda en historial: texto vacío")
            return
        }

        val durationMillis = System.currentTimeMillis() - recordingStartTime
        val durationSeconds = durationMillis / 1000
        val mode = if (isOnlineMode) "online" else "offline"
        val language = audioSettings.getLanguageCode()
        val sampleRate = audioSettings.getSampleRate()

        try {
            val id = database.insertTranscription(
                text = text,
                mode = mode,
                durationSeconds = durationSeconds,
                language = language,
                sampleRate = sampleRate.toFloat()
            )

            Log.d(TAG, "✓ Transcripción guardada [ID: $id, Modo: $mode, Duración: ${durationSeconds}s]")
            Toast.makeText(this, "✓ Guardado en historial", Toast.LENGTH_SHORT).show()

        } catch (e: Exception) {
            Log.e(TAG, "✗ Error al guardar en historial", e)
            Toast.makeText(this, "Error al guardar", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordButtonState() {
        if (isListening) {
            tvRecordLabel.text = "Detener"
            microphoneBackground.setBackgroundResource(R.drawable.bg_button_microphone_recording)

            // Iniciar animación de pulso
            val pulseAnimation = AnimationUtils.loadAnimation(this, android.R.anim.fade_in)
            pulseAnimation.duration = 1000
            pulseAnimation.repeatCount = -1
            pulseAnimation.repeatMode = android.view.animation.Animation.REVERSE
            btnRecord.startAnimation(pulseAnimation)
        } else {
            tvRecordLabel.text = "Grabar"
            microphoneBackground.setBackgroundResource(R.drawable.bg_button_microphone)
            btnRecord.clearAnimation()
        }
    }

    private fun vibrateIfEnabled() {
        if (audioSettings.isVibrationEnabled()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (vibrator.hasVibrator()) {
                    vibrator.vibrate(
                        VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                    )
                }
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(100)
            }
        }
    }

    private fun playSoundIfEnabled() {
        if (audioSettings.isSoundFeedbackEnabled()) {
            val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
            toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            toneGen.release()
        }
    }

    private fun releaseAudioEffects() {
        automaticGainControl?.release()
        automaticGainControl = null
        noiseSuppressor?.release()
        noiseSuppressor = null
        echoCanceler?.release()
        echoCanceler = null
    }

    // ===== LISTENERS =====

    private val voskRecognitionListener = object : org.vosk.android.RecognitionListener {
        override fun onResult(hypothesis: String?) {
            Log.d(TAG, "Vosk onResult: $hypothesis")
            if (!hypothesis.isNullOrEmpty()) {
                try {
                    val text = hypothesis
                        .substringAfter("\"text\" : \"")
                        .substringBefore("\"")

                    if (text.isNotEmpty()) {
                        // ✅ NUEVO: Aplicar puntuación con modelo ONNX
                        lifecycleScope.launch {
                            val punctuatedText = punctuationRestorer.addPunctuation(text)

                            runOnUiThread {
                                val currentText = tvResult.text.toString()
                                tvResult.text = if (currentText.isEmpty() ||
                                    currentText == "El texto aparecerá aquí...") {
                                    punctuatedText
                                } else {
                                    "$currentText $punctuatedText"
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando resultado Vosk", e)
                }
            }
        }

        override fun onFinalResult(hypothesis: String?) {
            Log.d(TAG, "Vosk onFinalResult: $hypothesis")
            onResult(hypothesis)
            runOnUiThread {
                tvStatus.text = "● Listo"
            }
        }

        override fun onPartialResult(hypothesis: String?) {
            // NO mostrar texto aquí, solo logging
            if (!hypothesis.isNullOrEmpty()) {
                try {
                    val text = hypothesis
                        .substringAfter("\"partial\" : \"")
                        .substringBefore("\"")

                    if (text.isNotEmpty()) {
                        Log.d(TAG, "Partial: $text")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error parseando resultado parcial", e)
                }
            }
        }

        override fun onError(exception: Exception?) {
            Log.e(TAG, "Error en reconocimiento Vosk", exception)
            runOnUiThread {
                tvStatus.text = "● Error"
                Toast.makeText(
                    this@TranscriptionActivity,
                    "Error: ${exception?.message}",
                    Toast.LENGTH_SHORT
                ).show()
                isListening = false
                updateRecordButtonState()
            }
        }

        override fun onTimeout() {
            Log.d(TAG, "Timeout en reconocimiento Vosk")
            runOnUiThread {
                tvStatus.text = "● Listo"
                stopListening()
            }
        }
    }

    private val googleRecognitionListener = object : RecognitionListener {

        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Google: Ready for speech")
            runOnUiThread {
                tvStatus.text = "● Listo"
            }
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Google: Beginning of speech")
            runOnUiThread {
                tvStatus.text = "● Escuchando"
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "Google: End of speech")
            runOnUiThread {
                tvStatus.text = "● Procesando"
            }
        }

        override fun onError(error: Int) {
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "Error de audio"
                SpeechRecognizer.ERROR_CLIENT -> "Error del cliente"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permisos insuficientes"
                SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Timeout de red"
                SpeechRecognizer.ERROR_NO_MATCH -> "No se reconoció voz"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Reconocedor ocupado"
                SpeechRecognizer.ERROR_SERVER -> "Error del servidor"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Timeout de voz"
                else -> "Error desconocido: $error"
            }

            Log.e(TAG, "Google error: $errorMessage")
            runOnUiThread {
                tvStatus.text = "● Error"
                Toast.makeText(
                    this@TranscriptionActivity,
                    errorMessage,
                    Toast.LENGTH_SHORT
                ).show()
            }

            if (isListening) {
                Log.d(TAG, "Reintentando escucha tras error...")
                googleSpeechRecognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, audioSettings.getLanguageCode())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, audioSettings.isPartialResultsEnabled())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, audioSettings.getMaxResults())
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, Long.MAX_VALUE)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, Long.MAX_VALUE)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, Long.MAX_VALUE)
                }
                googleSpeechRecognizer?.startListening(intent)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                Log.d(TAG, "Google onResults: $text")

                // ✅ NUEVO: Aplicar puntuación con modelo ONNX
                lifecycleScope.launch {
                    val punctuatedText = punctuationRestorer.addPunctuation(text)

                    runOnUiThread {
                        val currentText = tvResult.text.toString()
                        tvResult.text = if (currentText.isEmpty() ||
                            currentText == "El texto aparecerá aquí...") {
                            punctuatedText
                        } else {
                            "$currentText $punctuatedText"
                        }
                        tvStatus.text = "● Listo"
                    }
                }
            }

            if (isListening) {
                Log.d(TAG, "Reiniciando escucha continua...")
                googleSpeechRecognizer?.cancel()
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, audioSettings.getLanguageCode())
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, audioSettings.isPartialResultsEnabled())
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, audioSettings.getMaxResults())
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, Long.MAX_VALUE)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, Long.MAX_VALUE)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, Long.MAX_VALUE)
                }
                googleSpeechRecognizer?.startListening(intent)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            // NO mostrar texto aquí, solo logging
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Log.d(TAG, "Google partial: ${matches[0]}")
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RECORD_AUDIO) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso concedido", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Permiso denegado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Destruyendo actividad...")
        voskSpeechService?.stop()
        voskSpeechService?.shutdown()
        googleSpeechRecognizer?.destroy()
        releaseAudioEffects()
    }
}