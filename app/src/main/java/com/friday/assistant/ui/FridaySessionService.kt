package com.friday.assistant.ui

import android.service.voice.VoiceInteractionSessionService
import android.service.voice.VoiceInteractionSession
import android.os.Bundle
import android.content.Context

class FridaySessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return FridaySession(this)
    }
}

class FridaySession(context: Context) : VoiceInteractionSession(context) {
    override fun onCreateContentView(): android.view.View {
        return android.view.View(context)
    }

    override fun onShow(args: Bundle?, flags: Int) {
        super.onShow(args, flags)
        com.friday.assistant.core.FridayLogger.i("FridaySession", "Assistant invoked via Android System Gesture (Power Hold / Home Swipe)")
        FridayService.triggerGestureActivation()
        hide()
    }
}
