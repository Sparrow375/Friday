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
            // Tier 1: Google Featured Snippet / Knowledge Panel scraping
            scrapeGoogleAnswer(query)?.let { return@withContext ToolResult(true, it) }

            // Tier 2: DuckDuckGo Instant Answer API (good for calculators, conversions, quick facts)
            searchDuckDuckGoInstant(query)?.let { return@withContext ToolResult(true, it) }

            // Tier 3: DuckDuckGo Lite search snippet (recipes, general questions, definitions)
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
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        val noCitations = noHtml.replace(Regex("\\[[0-9a-zA-Z_\\s-]+\\]"), "")
        val normalized = noCitations.replace(Regex("\\s+"), " ").trim()
        val sentences = normalized.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return if (sentences.size > 3) {
            "${sentences[0]} ${sentences[1]} ${sentences[2]}"
        } else {
            normalized
        }
    }

    /**
     * Scrapes Google search results page for featured snippets, knowledge panels,
     * and organic snippets. This provides AI Overview-quality answers for general
     * knowledge queries.
     */
    private fun scrapeGoogleAnswer(query: String): String? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://www.google.com/search?q=$encodedQuery&hl=en")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.instanceFollowRedirects = true
            // Mobile User-Agent for cleaner/simpler HTML
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; SM-S926B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")

            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                var bytesRead = 0
                // Read up to 512KB — featured snippets typically appear in the first 100-300KB
                while (reader.readLine().also { line = it } != null && bytesRead < 524288) {
                    sb.append(line).append("\n")
                    bytesRead += (line?.length ?: 0)
                }
                reader.close()
                conn.disconnect()

                val html = sb.toString()

                // Check for CAPTCHA / consent page
                if (html.contains("detected unusual traffic") || html.contains("consent.google.com")) {
                    Log.d(TAG, "Google returned CAPTCHA/consent page, skipping")
                    return null
                }

                // Strategy 1: Featured snippet (class="hgKElc" or "IZ6rdc")
                var match = Regex("""class="hgKElc"[^>]*>(.*?)</(?:span|div)""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    val text = cleanAnswerText(match.groupValues[1])
                    if (text.length > 15) {
                        Log.d(TAG, "Google Featured Snippet found for '$query'")
                        return text
                    }
                }

                // Strategy 2: Knowledge panel description (class="kno-rdesc")
                match = Regex("""class="kno-rdesc"[^>]*>.*?<span[^>]*>(.*?)</span>""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    val text = cleanAnswerText(match.groupValues[1])
                    if (text.length > 15) {
                        Log.d(TAG, "Google Knowledge Panel found for '$query'")
                        return text
                    }
                }

                // Strategy 3: Calculator / converter / direct answer (data-tts="answers", class="qv3Wpe", or "Z0LcW")
                match = Regex("""class="(?:qv3Wpe|Z0LcW|XcVN5d)"[^>]*>(.*?)</""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    val text = cleanAnswerText(match.groupValues[1])
                    if (text.length > 2) {
                        Log.d(TAG, "Google Direct Answer found for '$query'")
                        return text
                    }
                }

                // Strategy 4: "About this result" / knowledge fact box ("wDYxhc")
                match = Regex("""data-attrid="[^"]*"[^>]*class="[^"]*wDYxhc[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    val text = cleanAnswerText(match.groupValues[1])
                    if (text.length > 20 && !text.contains("People also ask")) {
                        Log.d(TAG, "Google Knowledge Fact found for '$query'")
                        return text
                    }
                }

                // Strategy 5: BNeawe text snippets (Google's mobile search result CSS class)
                val bneaweMatches = Regex("""class="BNeawe[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
                for (bMatch in bneaweMatches) {
                    val text = cleanAnswerText(bMatch.groupValues[1])
                    // Skip short text, URLs, navigation elements, and dates
                    if (text.length > 40
                        && !text.startsWith("http")
                        && !text.contains("Google")
                        && !text.contains("Sign in")
                        && !text.contains("Search tools")
                        && !text.matches(Regex("^[A-Z][a-z]{2} \\d{1,2}, \\d{4}.*"))) {
                        Log.d(TAG, "Google BNeawe Snippet found for '$query'")
                        return text
                    }
                }

                // Strategy 6: Generic organic result snippet (class="VwiC3b" or "lEBKkf")
                match = Regex("""class="(?:VwiC3b|lEBKkf)[^"]*"[^>]*>(.*?)</(?:span|div)""", RegexOption.DOT_MATCHES_ALL).find(html)
                if (match != null) {
                    val text = cleanAnswerText(match.groupValues[1])
                    if (text.length > 30) {
                        Log.d(TAG, "Google Organic Snippet found for '$query'")
                        return text
                    }
                }
            } else {
                Log.d(TAG, "Google search returned HTTP ${conn.responseCode}")
                conn.disconnect()
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "Google scraping failed: ${e.message}")
            null
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

                // DDG "Answer" field is great for calculators, conversions, and quick facts
                val ans = json.get("Answer")?.asString?.trim() ?: ""
                if (ans.isNotEmpty()) return cleanAnswerText(ans)

                // DDG "AbstractText" provides brief definitions (only use if substantial)
                val abstractText = json.get("AbstractText")?.asString?.trim() ?: ""
                if (abstractText.length > 30) return cleanAnswerText(abstractText)
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "DDG Instant lookup failed: ${e.message}")
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
                val reader = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                val sb = StringBuilder()
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    sb.append(line).append("\n")
                }
                reader.close()

                val html = sb.toString()
                val snippets = Regex("""<td class=['"]result-snippet['"]>(.*?)</td>""", RegexOption.DOT_MATCHES_ALL).findAll(html)
                for (match in snippets) {
                    val snippet = cleanAnswerText(match.groupValues[1])
                    if (snippet.length > 20 && !snippet.startsWith("http")) {
                        Log.d(TAG, "DDG Lite snippet found for '$query'")
                        return snippet
                    }
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
