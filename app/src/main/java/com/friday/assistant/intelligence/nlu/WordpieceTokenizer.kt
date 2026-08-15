package com.friday.assistant.intelligence.nlu

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

class WordpieceTokenizer(private val vocab: Map<String, Int>) {

    private val idToToken: Map<Int, String> = vocab.entries.associate { it.value to it.key }

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
        return tokenizeWithTokens(text).first
    }

    fun tokenizeWithTokens(text: String): Pair<List<Int>, List<String>> {
        val tokenIds = mutableListOf<Int>()
        val tokenStrings = mutableListOf<String>()

        val cleanText = text.lowercase()
            .replace(Regex("([^a-z0-9#])"), " $1 ")
            .trim()
        val words = cleanText.split(Regex("\\s+")).filter { it.isNotEmpty() }

        val unkId = vocab["[UNK]"] ?: 100
        val unkStr = "[UNK]"

        for (word in words) {
            var start = 0
            val len = word.length
            while (start < len) {
                var end = len
                var matchedId = -1
                var matchedStr = ""
                while (start < end) {
                    var subWord = word.substring(start, end)
                    if (start > 0) {
                        subWord = "##$subWord"
                    }
                    if (vocab.containsKey(subWord)) {
                        matchedId = vocab[subWord]!!
                        matchedStr = subWord
                        break
                    }
                    end--
                }
                if (matchedId == -1) {
                    tokenIds.add(unkId)
                    tokenStrings.add(unkStr)
                    break
                }
                tokenIds.add(matchedId)
                tokenStrings.add(matchedStr)
                start = end
            }
        }
        return Pair(tokenIds, tokenStrings)
    }

    fun convertTokensToString(tokens: List<String>): String {
        val sb = StringBuilder()
        for (tok in tokens) {
            if (tok.startsWith("##")) {
                sb.append(tok.substring(2))
            } else {
                if (sb.isNotEmpty()) {
                    sb.append(" ")
                }
                sb.append(tok)
            }
        }
        return sb.toString().trim()
    }

    fun getVocabSize(): Int = vocab.size
}

