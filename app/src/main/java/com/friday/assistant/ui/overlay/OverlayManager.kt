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
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.friday.assistant.audio.PipelineState
import kotlinx.coroutines.flow.MutableStateFlow

class OverlayManager(
    private val context: Context,
    private val onMicClick: () -> Unit,
    private val onClose: () -> Unit
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

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
    private var myLifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle
        get() = myLifecycleRegistry

    private var mySavedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = mySavedStateRegistryController.savedStateRegistry

    private val myViewModelStore = ViewModelStore()
    override val viewModelStore: ViewModelStore = myViewModelStore

    private var sessionCount = 0

    init {
        myLifecycleRegistry.currentState = Lifecycle.State.INITIALIZED
        mySavedStateRegistryController.performRestore(null)
    }

    private var params: WindowManager.LayoutParams? = null

    fun show() {
        if (isVisible) return

        // Fast path: ComposeView is already attached but hidden — just make it visible again.
        // This avoids the 200-400ms WindowManager.addView() overhead on consecutive commands.
        if (composeView != null) {
            com.friday.assistant.core.FridayLogger.d(TAG, "show() fast path — restoring hidden ComposeView")
            composeView?.visibility = android.view.View.VISIBLE
            isVisible = true
            resetStateFlows()
            return
        }

        com.friday.assistant.core.FridayLogger.i(TAG, "Showing overlay window")

        // Reset state flows to clear any previous responses/transcripts
        pipelineState.value = PipelineState.IDLE
        statusText.value = "Active"
        transcript.value = ""
        assistantResponse.value = ""
        audioAmplitude.value = 0f
        
        try {
            if (myLifecycleRegistry.currentState == Lifecycle.State.DESTROYED) {
                com.friday.assistant.core.FridayLogger.i(TAG, "Recreating LifecycleRegistry and SavedStateRegistry for new session")
                myLifecycleRegistry = LifecycleRegistry(this)
                mySavedStateRegistryController = SavedStateRegistryController.create(this)
                mySavedStateRegistryController.performRestore(null)
            }
            myLifecycleRegistry.currentState = Lifecycle.State.CREATED

            val layoutParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP
                windowAnimations = android.R.style.Animation_Toast
                x = 0
                y = 0
            }
            this.params = layoutParams

            composeView = ComposeView(context).apply {
                // DisposeOnDetachedFromWindow ensures Compose runtime is released when removeView() is called
                // in destroyOverlay() — this is what triggers composition cleanup and prevents memory leaks
                // from lingering recomposition scopes after the overlay is fully torn down.
                setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
                
                // Attach lifecycle owners so Compose works inside a Service overlay
                setViewTreeLifecycleOwner(this@OverlayManager)
                setViewTreeSavedStateRegistryOwner(this@OverlayManager)
                setViewTreeViewModelStoreOwner(this@OverlayManager)
                
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
                        onDrag = { dx, dy ->
                            val view = composeView
                            val p = params
                            if (view != null && p != null) {
                                p.x += dx
                                p.y += dy
                                windowManager.updateViewLayout(view, p)
                            }
                        }
                    )
                }
            }

            windowManager.addView(composeView, layoutParams)
            myLifecycleRegistry.currentState = Lifecycle.State.STARTED
            myLifecycleRegistry.currentState = Lifecycle.State.RESUMED
            isVisible = true
        } catch (e: Throwable) {
            com.friday.assistant.core.FridayLogger.e(TAG, "Failed to add overlay view to WindowManager", e)
        }
    }

    /**
     * Hides the overlay visually without detaching it from WindowManager.
     * The ComposeView and lifecycle remain alive so show() can restore it instantly
     * without the 200-400ms WindowManager.addView() overhead.
     */
    fun hide() {
        if (!isVisible) return
        com.friday.assistant.core.FridayLogger.d(TAG, "Hiding overlay (keeping ComposeView attached)")
        composeView?.visibility = android.view.View.GONE
        isVisible = false
    }

    private fun resetStateFlows() {
        pipelineState.value = PipelineState.IDLE
        statusText.value = "Active"
        transcript.value = ""
        assistantResponse.value = ""
        audioAmplitude.value = 0f
    }

    /**
     * Hides the overlay after command completion. Delegates to hide() to keep ComposeView alive
     * for instant re-show on next wake-word trigger.
     */
    fun dismiss() {
        hide()
    }

    /**
     * Fully tears down the overlay — removes the view from WindowManager and destroys the lifecycle.
     * Only call this on service destroy or explicit permanent close. For auto-dismiss use dismiss()/hide().
     */
    fun destroyOverlay() {
        com.friday.assistant.core.FridayLogger.i(TAG, "Destroying overlay (full teardown)")
        try {
            if (myLifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                myLifecycleRegistry.currentState = Lifecycle.State.STARTED
                myLifecycleRegistry.currentState = Lifecycle.State.CREATED
            }
            composeView?.let { windowManager.removeView(it) }
            composeView = null
            isVisible = false
            myViewModelStore.clear()
            com.friday.assistant.core.FridayLogger.d(TAG, "ViewModelStore cleared, overlay session: ${++sessionCount}")
            if (myLifecycleRegistry.currentState != Lifecycle.State.DESTROYED) {
                myLifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
        } catch (e: Throwable) {
            com.friday.assistant.core.FridayLogger.e(TAG, "Error in destroyOverlay", e)
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
