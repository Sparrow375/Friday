package com.friday.assistant.intelligence.nlu

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class WordpieceTokenizer(private val vocab: Map<String, Int>) {

    companion object {
        fun loadFromAssets(context: Context, assetPath: String): WordpieceTokenizer {
            val vocab = mutableMapOf<String, Int>()
            try {
                context.assets.open(assetPath).use { inputStream ->
                    BufferedReader(InputStreamReader(inputStream)).use { reader ->
                        var line: String?
                        var index = 0
                        while (reader.readLine().also { line = it } != null) {
                            val word = line!!.trim()
                            if (word.isNotEmpty()) {
                                vocab[word] = index
                            }
                            index++
                        }
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("WordpieceTokenizer", "Failed to load vocab from assets", e)
            }
            return WordpieceTokenizer(vocab)
        }
    }

    fun tokenize(text: String): List<Int> {
        val tokens = mutableListOf<Int>()
        // Normalize text and split by word boundaries (spaces and basic punctuation)
        val cleanText = text.lowercase()
            .replace(Regex("([^a-z0-9#])"), " $1 ")
            .trim()
        val words = cleanText.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val unkId = vocab["[UNK]"] ?: 100

        for (word in words) {
            var start = 0
            val len = word.length
            while (start < len) {
                var end = len
                var matchedId = -1
                while (start < end) {
                    var subWord = word.substring(start, end)
                    if (start > 0) {
                        subWord = "##$subWord"
                    }
                    if (vocab.containsKey(subWord)) {
                        matchedId = vocab[subWord]!!
                        break
                    }
                    end--
                }
                if (matchedId == -1) {
                    tokens.add(unkId)
                    break
                }
                tokens.add(matchedId)
                start = end
            }
        }
        return tokens
    }
    
    fun getVocabSize(): Int = vocab.size
}
