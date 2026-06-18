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

    /**
     * Asks the accessibility service to find and click a Play/Resume button in the
     * currently-focused app. Used as a Tier-3 media fallback.
     */
    fun triggerInAppPlay(): Boolean {
        val svc = service ?: return false
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        svc.postInAppPlay { ok ->
            result.set(ok)
            latch.countDown()
        }
        latch.await(3, TimeUnit.SECONDS)
        return result.get()
    }

    /**
     * After a WhatsApp chat is opened via deep link, waits for WhatsApp to foreground
     * then taps the send button autonomously. Blocks for up to 7 seconds.
     */
    fun performWhatsAppSend(): Boolean {
        val svc = service ?: return false
        val result = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        svc.postWhatsAppSend(timeoutMs = 4000L) { ok ->
            result.set(ok)
            latch.countDown()
        }
        latch.await(5, TimeUnit.SECONDS)
        return result.get()
    }
}
