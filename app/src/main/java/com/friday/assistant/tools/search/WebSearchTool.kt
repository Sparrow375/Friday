package com.friday.assistant.tools.search

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class WebSearchTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "WebSearchTool"
    }

    override val name: String = "web_search"

    override val description: String = """
        Performs a web search to look up facts, weather, news, or general knowledge. 
        Tries to retrieve a direct answer, or falls back to opening the search in a browser.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "query": {
              "type": "string",
              "description": "The search query to look up"
            }
          },
          "required": ["query"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.get("query")?.asString ?: return ToolResult(false, "Missing required parameter: query")
        
        return withContext(Dispatchers.IO) {
            // Tier 1: DuckDuckGo Instant Answer API
            searchDuckDuckGoInstant(query)?.let { return@withContext ToolResult(true, it) }

            // Tier 2: Wikipedia Search & Summary API
            searchWikipediaSummary(query)?.let { return@withContext ToolResult(true, it) }

            // Tier 3: DuckDuckGo Lite Organic Search Snippet
            searchDuckDuckGoLite(query)?.let { return@withContext ToolResult(true, it) }

            // Tier 4: Fallback to opening browser search
            openBrowserSearch(query)
        }
    }

    private fun cleanAnswerText(raw: String): String {
        val noHtml = raw.replace(Regex("<[^>]+>"), "")
            .replace("&quot;", "\"")
            .replace("&amp;", "&")
            .replace("&#39;", "'")
            .replace("&nbsp;", " ")
        val noCitations = noHtml.replace(Regex("\\[[0-9a-zA-Z_\\s-]+\\]"), "")
        val normalized = noCitations.replace(Regex("\\s+"), " ").trim()
        val sentences = normalized.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return if (sentences.size > 2) {
            "${sentences[0]} ${sentences[1]}"
        } else {
            normalized
        }
    }

    private fun searchDuckDuckGoInstant(query: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 3000
            conn.readTimeout = 3000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val json = JsonParser.parseString(sb.toString()).asJsonObject
                val ans = json.get("Answer")?.asString?.trim() ?: ""
                if (ans.isNotEmpty()) return cleanAnswerText(ans)

                val abstractText = json.get("AbstractText")?.asString?.trim() ?: ""
                if (abstractText.isNotEmpty()) return cleanAnswerText(abstractText)

                val related = json.getAsJsonArray("RelatedTopics")
                if (related != null && related.size() > 0 && related[0].isJsonObject) {
                    val topicTxt = related[0].asJsonObject.get("Text")?.asString?.trim() ?: ""
                    if (topicTxt.length > 20) return cleanAnswerText(topicTxt)
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "DDG Instant lookup failed: ${e.message}")
            null
        }
    }

    private fun searchWikipediaSummary(query: String): String? {
        return try {
            val cleanQuery = query.replace(Regex("(?i)^(?:what is|who is|where is|when is|how is|define|tell me about)\\s+"), "").trim()
            if (cleanQuery.isEmpty()) return null

            // 1. Search Wikipedia for best page title
            val searchEncoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val searchUrl = URL("https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=$searchEncoded&utf8=&format=json&srlimit=1")
            val searchConn = searchUrl.openConnection() as HttpURLConnection
            searchConn.requestMethod = "GET"
            searchConn.connectTimeout = 3000
            searchConn.readTimeout = 3000
            searchConn.setRequestProperty("User-Agent", "FridayAssistant/1.0 (contact@friday.ai)")

            var pageTitle: String? = null
            var snippetFallback: String? = null

            if (searchConn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(searchConn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val json = JsonParser.parseString(sb.toString()).asJsonObject
                val searchArr = json.getAsJsonObject("query")?.getAsJsonArray("search")
                if (searchArr != null && searchArr.size() > 0) {
                    val first = searchArr[0].asJsonObject
                    pageTitle = first.get("title")?.asString
                    snippetFallback = first.get("snippet")?.asString
                }
            }

            if (!pageTitle.isNullOrBlank()) {
                val titleEncoded = URLEncoder.encode(pageTitle, "UTF-8")
                val summaryUrl = URL("https://en.wikipedia.org/api/rest_v1/page/summary/$titleEncoded")
                val sumConn = summaryUrl.openConnection() as HttpURLConnection
                sumConn.requestMethod = "GET"
                sumConn.connectTimeout = 3000
                sumConn.readTimeout = 3000
                sumConn.setRequestProperty("User-Agent", "FridayAssistant/1.0 (contact@friday.ai)")

                if (sumConn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(sumConn.inputStream))
                    val sb = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        sb.append(line)
                    }
                    reader.close()

                    val sumJson = JsonParser.parseString(sb.toString()).asJsonObject
                    val extract = sumJson.get("extract")?.asString?.trim() ?: ""
                    if (extract.isNotEmpty()) {
                        return cleanAnswerText(extract)
                    }
                }
            }

            if (!snippetFallback.isNullOrBlank()) {
                val clean = cleanAnswerText(snippetFallback)
                if (clean.length > 20) return clean
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Wikipedia summary lookup failed: ${e.message}")
            null
        }
    }

    private fun searchDuckDuckGoLite(query: String): String? {
        return try {
            val url = URL("https://lite.duckduckgo.com/lite/")
            val postData = "q=" + URLEncoder.encode(query, "UTF-8")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 3500
            conn.readTimeout = 3500
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

            val os = conn.outputStream
            os.write(postData.toByteArray(Charsets.UTF_8))
            os.flush()
            os.close()

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line)
                }
                reader.close()

                val html = sb.toString()
                val snippetRegex = Regex("<td class=[\"']result-snippet[\"']>(.*?)</td>")
                val match = snippetRegex.find(html)
                if (match != null) {
                    val snippet = cleanAnswerText(match.groupValues[1])
                    if (snippet.length > 15) return snippet
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "DDG Lite lookup failed: ${e.message}")
            null
        }
    }

    private fun openBrowserSearch(query: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "I've searched Google for '$query'.")
        } catch (e: Exception) {
            ToolResult(false, "Failed to launch browser: ${e.message}")
        }
    }
}
