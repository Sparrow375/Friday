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
            try {
                // Try DuckDuckGo Instant Answer API first
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://api.duckduckgo.com/?q=$encodedQuery&format=json&no_html=1&skip_disambig=1")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 3000
                conn.readTimeout = 3000
                
                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val reader = BufferedReader(InputStreamReader(conn.inputStream))
                    val response = StringBuilder()
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        response.append(line)
                    }
                    reader.close()
                    
                    val json = JsonParser.parseString(response.toString()).asJsonObject
                    val abstractText = json.get("AbstractText")?.asString ?: ""
                    
                    if (abstractText.isNotEmpty()) {
                        Log.i(TAG, "Instant answer found: $abstractText")
                        return@withContext ToolResult(true, "Search Result: $abstractText")
                    }
                }
                
                // Fallback: Open browser search
                openBrowserSearch(query)
            } catch (e: Exception) {
                Log.w(TAG, "Instant answer lookup failed, falling back to browser search: ${e.message}")
                openBrowserSearch(query)
            }
        }
    }

    private fun openBrowserSearch(query: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = Uri.parse("https://www.google.com/search?q=" + URLEncoder.encode(query, "UTF-8"))
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "I couldn't find an instant answer, so I opened a Google search for '$query' in your browser.")
        } catch (e: Exception) {
            ToolResult(false, "Failed to launch browser: ${e.message}")
        }
    }
}
