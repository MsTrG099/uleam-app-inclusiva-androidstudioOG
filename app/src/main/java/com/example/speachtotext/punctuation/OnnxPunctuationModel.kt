package com.example.speachtotext.punctuation

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.LongBuffer

/**
 * Carga y ejecuta el modelo ONNX de puntuación
 * VERSIÓN CORREGIDA: Solo envía input_ids (sin attention_mask)
 */
class OnnxPunctuationModel(private val context: Context) {

    private lateinit var environment: OrtEnvironment
    private lateinit var session: OrtSession

    companion object {
        private const val TAG = "OnnxPunctuationModel"
        private const val MODEL_FILENAME = "punctuation/model.onnx"

        // Labels del modelo (orden según entrenamiento)
        val PUNCTUATION_LABELS = arrayOf(
            "O",      // Sin puntuación
            ".",      // Punto
            ",",      // Coma
            "?",      // Interrogación
            "!",      // Exclamación
            ":",      // Dos puntos
            ";",      // Punto y coma
            "-"       // Guión
        )
    }

    /**
     * Inicializa el modelo ONNX
     */
    fun initialize(): Boolean {
        return try {
            Log.d(TAG, "Inicializando modelo ONNX...")

            // Crear environment
            environment = OrtEnvironment.getEnvironment()

            // Copiar modelo desde assets a directorio interno
            val modelFile = copyAssetToFile()

            // Crear sesión con el modelo
            val sessionOptions = OrtSession.SessionOptions()
            sessionOptions.setIntraOpNumThreads(4) // Usar 4 threads
            session = environment.createSession(modelFile.absolutePath, sessionOptions)

            Log.d(TAG, "✅ Modelo ONNX cargado exitosamente")
            Log.d(TAG, "📥 Inputs: ${session.inputNames}")
            Log.d(TAG, "📤 Outputs: ${session.outputNames}")

            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error inicializando modelo ONNX", e)
            false
        }
    }

    /**
     * Copia el modelo desde assets a almacenamiento interno
     */
    private fun copyAssetToFile(): File {
        val outputFile = File(context.filesDir, "punctuation_model.onnx")

        if (outputFile.exists()) {
            Log.d(TAG, "Modelo ya existe en cache")
            return outputFile
        }

        Log.d(TAG, "Copiando modelo desde assets...")
        context.assets.open(MODEL_FILENAME).use { input ->
            FileOutputStream(outputFile).use { output ->
                input.copyTo(output)
            }
        }

        Log.d(TAG, "Modelo copiado a: ${outputFile.absolutePath}")
        return outputFile
    }

    /**
     * Predice la puntuación para los tokens
     * ✅ CORREGIDO: Solo envía input_ids (el modelo no necesita attention_mask)
     */
    fun predict(inputIds: IntArray, attentionMask: IntArray): IntArray {
        try {
            // ✅ Solo convertir input_ids a tensor
            val inputIdsTensor = createTensor(inputIds)

            // ✅ Crear mapa de inputs con SOLO input_ids
            val inputs = mapOf(
                "input_ids" to inputIdsTensor
            )

            // Ejecutar inferencia
            val results = session.run(inputs)

            // ✅ El modelo retorna 4 outputs: [pre_preds, post_preds, cap_preds, seg_preds]
            // Usamos post_preds (índice 1) para la puntuación
            val output = results[1].value as Array<LongArray>

            // Cerrar tensor
            inputIdsTensor.close()
            results.close()

            // Convertir predicciones a array de enteros
            val predictions = output[0].map { it.toInt() }.toIntArray()

            Log.d(TAG, "✅ Predicción exitosa: ${predictions.size} tokens")
            return predictions

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en predicción", e)
            return IntArray(inputIds.size) { 0 } // Retornar sin puntuación
        }
    }

    /**
     * Crea un tensor ONNX desde un array de enteros
     */
    private fun createTensor(data: IntArray): OnnxTensor {
        val shape = longArrayOf(1, data.size.toLong())
        val buffer = LongBuffer.allocate(data.size)

        data.forEach { buffer.put(it.toLong()) }
        buffer.rewind()

        return OnnxTensor.createTensor(environment, buffer, shape)
    }

    /**
     * Libera recursos del modelo
     */
    fun close() {
        try {
            session.close()
            environment.close()
            Log.d(TAG, "Modelo ONNX cerrado")
        } catch (e: Exception) {
            Log.e(TAG, "Error cerrando modelo", e)
        }
    }
}