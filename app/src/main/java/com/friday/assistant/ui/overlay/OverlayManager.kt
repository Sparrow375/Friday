package com.friday.assistant.ui.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryOwner
import com.friday.assistant.audio.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow

class OverlayManager(
    private val context: Context,
    private val onMicClick: () -> Unit,
    onClose: () -> Unit,
    onTextSubmit: (String) -> Unit
) : LifecycleOwner, SavedStateRegistryOwner {

    companion object {
        private const val TAG = "OverlayManager"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var composeView: ComposeView? = null
    private var isVisible = false

    // State bindings for Compose UI updates
    val pipelineState = MutableStateFlow(PipelineState.IDLE)
    val statusText = MutableStateFlow("Idle")
    val transcript = MutableStateFlow("")
    val assistantResponse = MutableStateFlow("")
    val audioAmplitude = MutableStateFlow(0f)

    // Lifecycle requirements for Compose View
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistry.create(this)
    override val savedStateRegistry: SavedStateRegistry = savedStateRegistryController.savedStateRegistry

    init {
        lifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        savedStateRegistryController.performRestore(null)
    }

    fun show() {
        if (isVisible) return
        Log.i(TAG, "Showing overlay window")
        
        try {
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM
                windowAnimations = android.R.style.Animation_InputMethod // Smooth slide-up animation
            }

            composeView = ComposeView(context).apply {
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                
                // Attach lifecycle owners so Compose works inside a Service overlay
                setViewTreeLifecycleOwner(this@OverlayManager)
                setViewTreeSavedStateRegistryOwner(this@OverlayManager)
                
                setContent {
                    val state by pipelineState.collectAsState()
                    val status by statusText.collectAsState()
                    val trans by transcript.collectAsState()
                    val resp by assistantResponse.collectAsState()
                    val amp by audioAmplitude.collectAsState()

                    FridayOverlayContent(
                        pipelineState = state,
                        statusText = status,
                        transcript = trans,
                        assistantResponse = resp,
                        audioAmplitude = amp,
                        onClose = {
                            onClose()
                        },
                        onMicClick = onMicClick,
                        onTextSubmit = onTextSubmit
                    )
                }
            }

            windowManager.addView(composeView, params)
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            isVisible = true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view to WindowManager", e)
        }
    }

    fun dismiss() {
        if (!isVisible) return
        Log.i(TAG, "Dismissing overlay window")
        try {
            lifecycleRegistry.currentState = Lifecycle.State.STARTED
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            
            composeView?.let {
                windowManager.removeView(it)
            }
            composeView = null
            isVisible = false
            
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view from WindowManager", e)
        }
    }

    fun updateAmplitude(amp: Float) {
        audioAmplitude.value = amp
    }

    fun updateState(state: PipelineState, text: String, trans: String = "", resp: String = "") {
        pipelineState.value = state
        statusText.value = text
        transcript.value = trans
        assistantResponse.value = resp
    }
}
