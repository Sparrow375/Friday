package com.friday.assistant.automation

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Thread-safe bridge between tools and [FridayAccessibilityService].
 */
object AutomationBridge {

    @Volatile
    private var service: FridayAccessibilityService? = null

    fun bind(service: FridayAccessibilityService) {
        this.service = service
    }

    fun unbind() {
        service = null
    }

    fun isReady(): Boolean = service != null

    fun takeScreenshot(): Boolean {
        val svc = service ?: return false
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        svc.postTakeScreenshot { ok ->
            result.set(ok)
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    fun toggleQuickSetting(label: String, enable: Boolean): Boolean {
        val svc = service ?: return false
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        svc.postToggleQuickSetting(label, enable) { ok ->
            result.set(ok)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result.get()
    }
}
