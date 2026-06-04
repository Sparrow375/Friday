package com.friday.assistant.executor

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class RoutineAction(
    val type: String, // "TTS", "VOLUME", "BRIGHTNESS", "TORCH", "WIFI", "BLUETOOTH", "DND", "LAUNCH_APP", "DEEP_LINK_APP", "DELAY"
    val value: String, // Parameter value
    val extraParams: Map<String, String> = emptyMap()
)

class RoutineExecutor(
    private val context: Context,
    private val systemExecutor: SystemExecutor,
    private val ttsCallback: (String) -> Unit
) {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.Main)

    /**
     * Parses the routine actions JSON and executes them in order.
     */
    fun executeRoutine(routineName: String, actionsJson: String) {
        Log.i(TAG, "Executing routine: $routineName")
        
        try {
            val listType = object : TypeToken<List<RoutineAction>>() {}.type
            val actions: List<RoutineAction> = gson.fromJson(actionsJson, listType) ?: emptyList()
            
            scope.launch {
                for (action in actions) {
                    executeAction(action)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing routine $routineName", e)
            ttsCallback("Error executing routine $routineName")
        }
    }

    private suspend fun executeAction(action: RoutineAction) {
        Log.d(TAG, "Running routine action: type=${action.type}, value=${action.value}")
        
        when (action.type.uppercase()) {
            "TTS" -> {
                // Speak out the value
                ttsCallback(action.value)
                // Give a short delay to let TTS start speaking
                delay(3000) 
            }
            "VOLUME" -> {
                systemExecutor.executeVolume(mapOf("action" to "set", "level" to action.value))
            }
            "BRIGHTNESS" -> {
                systemExecutor.executeBrightness(mapOf("action" to "set", "level" to action.value))
            }
            "TORCH" -> {
                systemExecutor.executeTorch(mapOf("state" to action.value))
            }
            "WIFI" -> {
                systemExecutor.executeWifi(mapOf("state" to action.value))
            }
            "BLUETOOTH" -> {
                systemExecutor.executeBluetooth(mapOf("state" to action.value))
            }
            "DND" -> {
                systemExecutor.executeDnd(mapOf("state" to action.value))
            }
            "LAUNCH_APP" -> {
                systemExecutor.executeLaunchApp(action.value, action.extraParams["appName"] ?: "App")
            }
            "DEEP_LINK_APP" -> {
                systemExecutor.executeDeepLink(
                    mapOf(
                        "app" to action.value,
                        "query" to (action.extraParams["query"] ?: ""),
                        "name" to (action.extraParams["name"] ?: "")
                    )
                )
            }
            "DELAY" -> {
                val delayTimeMs = action.value.toLongOrNull() ?: 1000L
                delay(delayTimeMs)
            }
            else -> {
                Log.w(TAG, "Unknown routine action type: ${action.type}")
            }
        }
    }

    companion object {
        private const val TAG = "RoutineExecutor"
    }
}
