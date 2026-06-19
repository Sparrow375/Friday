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

    private fun playFromSearch(query: String, app: String?): ToolResult {
        return when {
            app?.contains("spotify") == true -> playOnSpotify(query)
            app?.contains("youtube music") == true || app == "yt music" -> playOnYouTubeMusic(query)
            app?.contains("youtube") == true || app == "yt" -> searchOnYouTube(query)
            app?.contains("google") == true -> searchOnGoogle(query)
            else -> playFromSearchDefault(query)
        }
    }

    private fun playOnSpotify(query: String): ToolResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(PKG_SPOTIFY)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            ToolResult(true, "Playing '$query' on Spotify")
        } catch (e: Exception) {
            Log.w(TAG, "Spotify auto-play intent failed, falling back to search URI", e)
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")).apply {
                    setPackage(PKG_SPOTIFY)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                context.startActivity(intent)
                ToolResult(true, "Searching '$query' on Spotify")
            } catch (ex: Exception) {
                Log.w(TAG, "Spotify search deep link failed, falling back", ex)
                playFromSearchDefault(query)
            }
        }
    }

    private fun playOnYouTubeMusic(query: String): ToolResult {
        return try {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH).apply {
                setPackage(PKG_YT_MUSIC)
                putExtra(SearchManager.QUERY, query)
                putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            ToolResult(true, "Playing '$query' on YouTube Music")
        } catch (e: Exception) {
            Log.w(TAG, "YouTube Music auto-play intent failed, falling back to search URL", e)
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=$encoded")).apply {
                    setPackage(PKG_YT_MUSIC)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
                }
                context.startActivity(intent)
                ToolResult(true, "Searching '$query' on YouTube Music")
            } catch (ex: Exception) {
                Log.w(TAG, "YouTube Music deep link failed", ex)
                playFromSearchDefault(query)
            }
        }
    }

    private fun searchOnYouTube(query: String): ToolResult {
        return try {
            val intent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(PKG_YOUTUBE)
                putExtra(SearchManager.QUERY, query)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            }
            context.startActivity(intent)
            ToolResult(true, "Searching '$query' on YouTube")
        } catch (e: Exception) {
            Log.w(TAG, "YouTube search failed", e)
            playFromSearchDefault(query)
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
