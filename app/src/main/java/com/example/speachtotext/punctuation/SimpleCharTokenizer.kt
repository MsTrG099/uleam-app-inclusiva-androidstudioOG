package com.example.speachtotext.punctuation

import android.content.Context
import android.util.Log

/**
 * Tokenizador simplificado por caracteres
 * Compatible con modelo punct_cap_seg_47_language
 */
class SimpleCharTokenizer(context: Context) {

    private val charToId = mutableMapOf<Char, Int>()
    private val idToChar = mutableMapOf<Int, Char>()

    companion object {
        private const val TAG = "SimpleCharTokenizer"

        // IDs especiales (estándar SentencePiece)
        const val UNK_ID = 0
        const val BOS_ID = 1  // <s>
        const val EOS_ID = 2  // </s>
        const val PAD_ID = 3  // <pad>

        private const val MAX_LENGTH = 256

        // Offset para caracteres normales
        private const val CHAR_OFFSET = 10
    }

    init {
        buildCharVocab()
    }

    /**
     * Construye vocabulario basado en caracteres
     */
    private fun buildCharVocab() {
        // Caracteres comunes en español y otros idiomas
        val chars = buildString {
            // Letras minúsculas
            append("abcdefghijklmnopqrstuvwxyz")
            // Letras mayúsculas
            append("ABCDEFGHIJKLMNOPQRSTUVWXYZ")
            // Números
            append("0123456789")
            // Puntuación
            append(".,;:!?¿¡-()[]{}\"'")
            // Caracteres especiales español
            append("áéíóúüñÁÉÍÓÚÜÑ")
            // Espacio
            append(" ")
        }

        chars.forEachIndexed { index, char ->
            val id = CHAR_OFFSET + index
            charToId[char] = id
            idToChar[id] = char
        }

        Log.d(TAG, "✅ Vocabulario de ${charToId.size} caracteres creado")
    }

    /**
     * Tokeniza texto
     */
    fun encode(text: String, maxLength: Int = MAX_LENGTH): TokenizedResult {
        val words = text.lowercase().split(Regex("\\s+"))
        val inputIds = mutableListOf<Int>()

        // BOS token
        inputIds.add(BOS_ID)

        // Convertir palabras a IDs
        for ((wordIdx, word) in words.withIndex()) {
            if (inputIds.size >= maxLength - 1) break

            // Agregar espacio entre palabras (excepto primera)
            if (wordIdx > 0 && inputIds.size < maxLength - 1) {
                val spaceId = charToId[' '] ?: UNK_ID
                inputIds.add(spaceId)
            }

            // Agregar caracteres de la palabra
            for (char in word) {
                if (inputIds.size >= maxLength - 1) break
                val charId = charToId[char] ?: UNK_ID
                inputIds.add(charId)
            }
        }

        // EOS token
        inputIds.add(EOS_ID)

        // Attention mask (1 para tokens reales, 0 para padding)
        val attentionMask = MutableList(inputIds.size) { 1 }

        // Padding hasta maxLength
        while (inputIds.size < maxLength) {
            inputIds.add(PAD_ID)
            attentionMask.add(0)
        }

        return TokenizedResult(
            inputIds = inputIds.take(maxLength).toIntArray(),
            attentionMask = attentionMask.take(maxLength).toIntArray(),
            originalWords = words
        )
    }

    /**
     * Decodifica IDs a texto
     */
    fun decode(ids: IntArray): String {
        return ids
            .filter { it !in listOf(UNK_ID, BOS_ID, EOS_ID, PAD_ID) }
            .mapNotNull { idToChar[it] }
            .joinToString("")
            .trim()
    }

    data class TokenizedResult(
        val inputIds: IntArray,
        val attentionMask: IntArray,
        val originalWords: List<String>
    )
}