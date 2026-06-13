package com.friday.assistant.automation

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import com.friday.assistant.ui.FridayService

object UIAutomator {
    private const val TAG = "UIAutomator"

    fun tapElement(id: Int): Boolean {
        val cachedNode = ScreenReader.getCachedElement(id) ?: run {
            Log.e(TAG, "tapElement: Node with ID $id not found in cache")
            return false
        }
        
        // If the cached node is clickable, click it directly
        if (cachedNode.isClickable) {
            val success = cachedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "tapElement ID $id clicked directly: $success")
            return success
        }
        
        // Otherwise search up the parent tree for a clickable node
        var current: AccessibilityNodeInfo? = cachedNode.parent
        while (current != null) {
            if (current.isClickable) {
                val success = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                Log.d(TAG, "tapElement ID $id (clicked parent: ${current.className}): $success")
                current.recycle()
                return success
            }
            val parent = current.parent
            current.recycle()
            current = parent
        }
        
        Log.e(TAG, "tapElement: No clickable parent found for ID $id, attempting click on original node")
        return cachedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    fun typeText(id: Int, text: String): Boolean {
        val cachedNode = ScreenReader.getCachedElement(id) ?: run {
            Log.e(TAG, "typeText: Node with ID $id not found in cache")
            return false
        }
        
        if (!cachedNode.isEditable) {
            Log.w(TAG, "typeText: Node with ID $id is not marked as editable, attempting set text anyway")
        }
        
        // Request focus first
        cachedNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        
        val arguments = Bundle()
        arguments.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        val success = cachedNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
        Log.d(TAG, "typeText ID $id: $success")
        return success
    }

    fun scroll(direction: String): Boolean {
        val service = FridayService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        
        val scrollableNode = findScrollableNode(root)
        if (scrollableNode == null) {
            Log.w(TAG, "scroll: No scrollable view found in active window")
            root.recycle()
            return false
        }
        
        val action = if (direction.lowercase() == "backward" || direction.lowercase() == "up") {
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        } else {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        
        val success = scrollableNode.performAction(action)
        Log.d(TAG, "scroll $direction on ${scrollableNode.className}: $success")
        
        scrollableNode.recycle()
        root.recycle()
        return success
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) {
            return AccessibilityNodeInfo.obtain(node)
        }
        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = findScrollableNode(child)
            child.recycle()
            if (found != null) {
                return found
            }
        }
        return null
    }

    fun performGlobalAction(actionId: Int): Boolean {
        val service = FridayService.instance ?: return false
        val success = service.performGlobalAction(actionId)
        Log.d(TAG, "performGlobalAction $actionId: $success")
        return success
    }
}
