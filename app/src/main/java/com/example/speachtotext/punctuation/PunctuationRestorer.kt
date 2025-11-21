package com.example.speachtotext.punctuation

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Clase principal para restaurar puntuación usando modelo ONNX
 * VERSIÓN SIMPLIFICADA: Compatible con el nuevo OnnxPunctuationModel
 */
class PunctuationRestorer(private val context: Context) {

    private lateinit var tokenizer: SimpleCharTokenizer
    private lateinit var model: OnnxPunctuationModel
    private var isInitialized = false

    companion object {
        private const val TAG = "PunctuationRestorer"

        @Volatile
        private var instance: PunctuationRestorer? = null

        fun getInstance(context: Context): PunctuationRestorer {
            return instance ?: synchronized(this) {
                instance ?: PunctuationRestorer(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    /**
     * Inicializa el modelo (llamar al inicio de la app)
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (isInitialized) {
            Log.d(TAG, "Ya está inicializado")
            return@withContext true
        }

        try {
            Log.d(TAG, "Inicializando PunctuationRestorer...")

            // Cargar tokenizer
            tokenizer = SimpleCharTokenizer(context)

            // Cargar modelo ONNX
            model = OnnxPunctuationModel(context)
            val modelLoaded = model.initialize()

            if (!modelLoaded) {
                Log.e(TAG, "❌ Error cargando modelo ONNX")
                return@withContext false
            }

            isInitialized = true
            Log.d(TAG, "✅ PunctuationRestorer inicializado")
            true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en inicialización", e)
            false
        }
    }

    /**
     * Agrega puntuación a un texto
     */
    suspend fun addPunctuation(text: String): String = withContext(Dispatchers.Default) {
        if (!isInitialized) {
            Log.w(TAG, "Modelo no inicializado, retornando texto original")
            return@withContext text
        }

        if (text.isBlank()) {
            return@withContext text
        }

        try {
            Log.d(TAG, "Procesando: $text")

            // 1. Tokenizar el texto
            val tokenized = tokenizer.encode(text, maxLength = 128)

            // 2. Predecir puntuación (retorna IntArray simple)
            val predictions = model.predict(
                tokenized.inputIds,
                tokenized.attentionMask
            )

            // 3. Reconstruir texto con puntuación
            val result = reconstructText(
                tokenized.originalWords,
                predictions
            )

            Log.d(TAG, "Resultado: $result")
            return@withContext result

        } catch (e: Exception) {
            Log.e(TAG, "Error agregando puntuación", e)
            return@withContext text // Retornar original en caso de error
        }
    }

    /**
     * Reconstruye el texto con la puntuación predicha
     * ✅ SIMPLIFICADO: Usa solo el IntArray de predicciones
     */
    private fun reconstructText(words: List<String>, predictions: IntArray): String {
        if (words.isEmpty()) return ""

        val result = StringBuilder()
        var shouldCapitalize = true // Capitalizar primera palabra

        for (i in words.indices) {
            // Agregar espacio antes de la palabra (excepto al inicio)
            if (i > 0) {
                result.append(" ")
            }

            // Capitalizar si es necesario
            val word = if (shouldCapitalize) {
                words[i].replaceFirstChar { it.uppercase() }
            } else {
                words[i]
            }
            result.append(word)
            shouldCapitalize = false

            // Obtener predicción de puntuación para esta palabra
            // +1 porque predictions tiene [CLS] al inicio
            val predictionIndex = predictions.getOrNull(i + 1) ?: 0
            val punctuation = OnnxPunctuationModel.PUNCTUATION_LABELS.getOrNull(predictionIndex)

            // Agregar puntuación si no es "O" (sin puntuación)
            if (punctuation != null && punctuation != "O") {
                result.append(punctuation)

                // Si es fin de oración, capitalizar siguiente palabra
                if (punctuation in listOf(".", "?", "!")) {
                    shouldCapitalize = true
                }
            }
        }

        // Agregar punto final si no hay puntuación al final
        val lastChar = result.lastOrNull()
        if (lastChar != null && lastChar !in listOf('.', '?', '!', ',', ';', ':', '-')) {
            result.append(".")
        }

        return result.toString()
    }

    /**
     * Procesa texto largo dividiéndolo en chunks
     */
    suspend fun addPunctuationToLongText(text: String, chunkSize: Int = 100): String {
        val words = text.split(Regex("\\s+"))

        if (words.size <= chunkSize) {
            return addPunctuation(text)
        }

        val chunks = words.chunked(chunkSize)
        val processedChunks = mutableListOf<String>()

        for (chunk in chunks) {
            val chunkText = chunk.joinToString(" ")
            val processed = addPunctuation(chunkText)
            processedChunks.add(processed)
        }

        return processedChunks.joinToString(" ")
    }

    /**
     * Libera recursos
     */
    fun close() {
        if (isInitialized) {
            model.close()
            isInitialized = false
            instance = null
            Log.d(TAG, "Recursos liberados")
        }
    }
}