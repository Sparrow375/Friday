package com.friday.assistant.audio

enum class PipelineState {
    IDLE,        // Monitoring for wake-word
    LISTENING,   // Actively recording user command
    PROCESSING,  // Transcribing speech
    THINKING,    // Running NLU / LLM and executing tools
    SPEAKING     // Playing voice response via TTS
}
