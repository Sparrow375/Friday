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
}
