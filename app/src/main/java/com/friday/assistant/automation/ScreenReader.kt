package com.friday.assistant.automation

import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.friday.assistant.ui.FridayService

object ScreenReader {
    private const val TAG = "ScreenReader"
    private val elementCache = mutableMapOf<Int, AccessibilityNodeInfo>()
    private var nextElementId = 1

    @Synchronized
    fun getCachedElement(id: Int): AccessibilityNodeInfo? {
        return elementCache[id]
    }

    @Synchronized
    fun findCachedElementId(predicate: (AccessibilityNodeInfo) -> Boolean): Int? {
        return elementCache.entries.firstOrNull { predicate(it.value) }?.key
    }

    @Synchronized
    fun clearCache() {
        elementCache.values.forEach {
            try {
                it.recycle()
            } catch (e: Exception) {
                // Ignore already recycled node exceptions
            }
        }
        elementCache.clear()
        nextElementId = 1
    }

    @Synchronized
    fun readScreen(): String {
        val service = FridayService.instance ?: return "Error: Accessibility service not active."
        val root = service.rootInActiveWindow
            ?: return "Error: No active window found. Ensure the app is open on the screen and unlock the phone."
        
        clearCache()
        
        val sb = StringBuilder("Current Screen Content:\n")
        try {
            traverse(root, sb, 0)
        } catch (e: Exception) {
            Log.e(TAG, "Error traversing accessibility tree", e)
            return "Error scanning screen: ${e.message}"
        } finally {
            try {
                root.recycle()
            } catch (e: Exception) { }
        }
        return sb.toString()
    }

    private fun traverse(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int) {
        if (node == null) return
        
        val className = node.className?.toString() ?: ""
        val text = node.text?.toString() ?: ""
        val desc = node.contentDescription?.toString() ?: ""
        val resourceId = node.viewIdResourceName ?: ""
        val isClickable = node.isClickable
        val isEditable = node.isEditable
        
        var elementIdStr = ""
        if (isClickable || isEditable) {
            val elementId = nextElementId++
            elementCache[elementId] = AccessibilityNodeInfo.obtain(node)
            elementIdStr = "[$elementId]"
        }
        
        // Output readable item info if it contains text or is interactive
        if (text.isNotEmpty() || desc.isNotEmpty() || elementIdStr.isNotEmpty() || resourceId.isNotEmpty()) {
            val indent = "  ".repeat(depth.coerceAtMost(6)) // Limit indent to avoid huge wrap lines
            val info = java.lang.StringBuilder(indent)
            if (elementIdStr.isNotEmpty()) info.append("$elementIdStr ")
            
            val shortClass = className.substringAfterLast('.')
            info.append(shortClass)
            
            if (resourceId.isNotEmpty()) {
                info.append(" (id: ${resourceId.substringAfterLast('/')})")
            }
            if (text.isNotEmpty()) {
                info.append(" text: \"$text\"")
            }
            if (desc.isNotEmpty()) {
                info.append(" desc: \"$desc\"")
            }
            if (isClickable && !isEditable) {
                info.append(" [Clickable]")
            }
            if (isEditable) {
                info.append(" [Editable]")
            }
            sb.append(info.toString()).append("\n")
        }
        
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            traverse(child, sb, depth + 1)
            try {
                child.recycle()
            } catch (e: Exception) {}
        }
    }
}
