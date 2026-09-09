package com.friday.assistant.tools.media

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.KeyEvent
import com.friday.assistant.tools.Tool
import com.friday.assistant.tools.ToolResult
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.URLEncoder
import com.friday.assistant.automation.AutomationBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MediaControlTool(private val context: Context) : Tool {

    companion object {
        private const val TAG = "MediaControlTool"
        private const val PKG_SPOTIFY = "com.spotify.music"
        private const val PKG_YOUTUBE = "com.google.android.youtube"
        private const val PKG_YT_MUSIC = "com.google.android.apps.youtube.music"
    }

    override val name: String = "media_control"

    override val description: String = """
        Controls media playback (play, pause, next, previous) or plays specific songs/artists 
        on Spotify, YouTube, or the default system media app.
    """.trimIndent()

    override val parameters: JsonObject = JsonParser.parseString("""
        {
          "type": "object",
          "properties": {
            "action": {
              "type": "string",
              "enum": ["play", "pause", "next", "previous", "play_search"],
              "description": "The media control action to perform"
            },
            "query": {
              "type": "string",
              "description": "The song name, artist, or genre to search and play (only used for 'play_search' action)"
            },
            "app": {
              "type": "string",
              "description": "Target app: spotify, youtube, youtube music, or default"
            }
          },
          "required": ["action"]
        }
    """).asJsonObject

    override suspend fun execute(args: JsonObject): ToolResult {
        val action = args.get("action")?.asString ?: return ToolResult(false, "Missing required parameter: action")
        val app = args.get("app")?.asString?.lowercase()

        return when (action) {
            "play" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY)
            "pause" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
            "next" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "previous" -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "play_search" -> {
                val query = args.get("query")?.asString
                    ?: return ToolResult(false, "Missing parameter 'query' for action 'play_search'")
                playFromSearch(query, app)
            }
            else -> ToolResult(false, "Unknown media action: $action")
        }
    }

    private fun sendMediaKey(keycode: Int): ToolResult {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val eventTime = SystemClock.uptimeMillis()

        val downEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keycode, 0)
        audioManager.dispatchMediaKeyEvent(downEvent)

        val upEvent = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keycode, 0)
        audioManager.dispatchMediaKeyEvent(upEvent)

        val actionName = when (keycode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> "Play"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "Pause"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "Next Track"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Previous Track"
            else -> "Media Key"
        }
        return ToolResult(true, "Sent media command: $actionName")
    }

    private suspend fun playFromSearch(query: String, app: String?): ToolResult {
        return when {
            app == "youtube" || app == "yt" -> searchOnYouTube(query)
            app?.contains("google") == true -> searchOnGoogle(query)
            else -> playOnYouTubeMusic(query)
        }
    }

    private suspend fun scrapeTopYouTubeVideoId(query: String): String? {
        return withContext(Dispatchers.IO) {
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = java.net.URL("https://www.youtube.com/results?search_query=$encoded")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 5000
                conn.readTimeout = 5000
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"
                )
                conn.setRequestProperty("Accept-Language", "en-US,en;q=0.9")

                if (conn.responseCode == java.net.HttpURLConnection.HTTP_OK) {
                    val reader = java.io.BufferedReader(java.io.InputStreamReader(conn.inputStream))
                    val sb = java.lang.StringBuilder()
                    var line: String?
                    var bytesRead = 0
                    val videoRendererRegex = Regex("\"videoRenderer\":\\{\"videoId\":\"([a-zA-Z0-9_-]{11})\"")
                    var foundVid: String? = null

                    while (reader.readLine().also { line = it } != null && bytesRead < 2097152) {
                        val currentLine = line ?: break
                        bytesRead += currentLine.length
                        sb.append(currentLine)

                        val match = videoRendererRegex.find(currentLine)
                        if (match != null) {
                            foundVid = match.groupValues[1]
                            Log.i(TAG, "Scraped top YouTube videoId via stream: $foundVid for query '$query'")
                            break
                        }
                    }
                    reader.close()

                    if (foundVid != null) {
                        return@withContext foundVid
                    }

                    // Fallback to searching accumulated buffer
                    val html = sb.toString()
                    val vrMatch = videoRendererRegex.find(html)
                    if (vrMatch != null) {
                        val vid = vrMatch.groupValues[1]
                        Log.i(TAG, "Scraped top YouTube videoId from buffer: $vid for query '$query'")
                        return@withContext vid
                    }

                    val watchRegex = Regex("/watch\\?v=([a-zA-Z0-9_-]{11})")
                    val watchMatch = watchRegex.find(html)
                    if (watchMatch != null) {
                        val vid = watchMatch.groupValues[1]
                        Log.i(TAG, "Scraped top YouTube videoId from watch link: $vid for query '$query'")
                        return@withContext vid
                    }
                }
                null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to scrape top YouTube video ID: ${e.message}")
                null
            }
        }
    }

    private suspend fun scrapeTopYouTubeVideoUrl(query: String): String? {
        val vid = scrapeTopYouTubeVideoId(query) ?: return null
        return "https://www.youtube.com/watch?v=$vid&autoplay=1"
    }

    private suspend fun playOnSpotify(query: String): ToolResult {
        // Spotify removed - redirect to YouTube Music web for ad-free listening in browser
        Log.i(TAG, "playOnSpotify redirected to YouTube Music web for query='$query'")
        return playOnYouTubeMusic(query)
    }

    private suspend fun playOnYouTubeMusic(query: String): ToolResult {
        return try {
            val foundVid = scrapeTopYouTubeVideoId(query)
            val encoded = URLEncoder.encode(query, "UTF-8")
            val targetUri = if (!foundVid.isNullOrBlank()) {
                Log.i(TAG, "Opening direct YouTube Music track: $foundVid for '$query'")
                Uri.parse("https://music.youtube.com/watch?v=$foundVid")
            } else {
                Log.i(TAG, "Opening YouTube Music search for '$query'")
                Uri.parse("https://music.youtube.com/search?q=$encoded")
            }

            val browserIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                val bravePackage = "com.brave.browser"
                val isBraveInstalled = try {
                    context.packageManager.getPackageInfo(bravePackage, 0) != null
                } catch (_: Exception) {
                    false
                }
                if (isBraveInstalled) {
                    setPackage(bravePackage)
                }
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(browserIntent)
            ToolResult(true, "Playing '$query' on YouTube Music")
        } catch (e: Exception) {
            Log.e(TAG, "YouTube Music search failed", e)
            ToolResult(false, "Failed to open YouTube Music: ${e.message}")
        }
    }

    private suspend fun searchOnYouTube(query: String): ToolResult {
        return try {
            // Attempt to scrape top video result for direct browser autoplay
            val directVideoUrl = scrapeTopYouTubeVideoUrl(query)
            val targetUri = if (!directVideoUrl.isNullOrBlank()) {
                Log.i(TAG, "Opening direct scraped video URL with autoplay: $directVideoUrl")
                Uri.parse(directVideoUrl)
            } else {
                val encoded = URLEncoder.encode(query, "UTF-8")
                Log.i(TAG, "Falling back to search results URL for '$query'")
                Uri.parse("https://www.youtube.com/results?search_query=$encoded")
            }

            val genericIntent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(genericIntent)

            // If accessibility service is ready and fallback search page was opened, trigger UI automator
            if (directVideoUrl.isNullOrBlank() && AutomationBridge.isReady()) {
                Thread {
                    try { Thread.sleep(400) } catch (_: Exception) {}
                    Log.d(TAG, "Triggering YouTube auto-play accessibility helper")
                    val autoPlayed = AutomationBridge.triggerYouTubeAutoPlay(query)
                    Log.d(TAG, "YouTube auto-play accessibility helper returned: $autoPlayed")
                }.start()
            }

            ToolResult(true, "Playing '$query' on YouTube")
        } catch (e: Exception) {
            Log.e(TAG, "YouTube search failed", e)
            ToolResult(false, "Failed to search YouTube: ${e.message}")
        }
    }

    private fun searchOnGoogle(query: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Searching Google for '$query'")
        } catch (e: Exception) {
            ToolResult(false, "Failed to search Google: ${e.message}")
        }
    }

    private fun playFromSearchDefault(query: String): ToolResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            ToolResult(true, "Playing '$query'")
        } catch (e: Exception) {
            Log.w(TAG, "MEDIA_PLAY_FROM_SEARCH failed, trying in-app play via Accessibility", e)
            // Tier-3: accessibility service clicks the Play button in the currently-active app
            if (com.friday.assistant.automation.AutomationBridge.isReady()) {
                val ok = com.friday.assistant.automation.AutomationBridge.triggerInAppPlay()
                if (ok) return ToolResult(true, "Triggered playback in the active app")
            }
            ToolResult(false, "Failed to start media playback: ${e.message}")
        }
    }
}
