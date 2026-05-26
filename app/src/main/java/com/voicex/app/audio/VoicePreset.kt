package com.voicex.app.audio

data class VoicePreset(
    val id: String,
    val name: String,
    val emoji: String,
    val pitchSemitones: Float,
    val speed: Float,
    val reverbMix: Float,
    val echoDuration: Float,
    val bassBoost: Float,
    val volume: Float,
    val robotize: Boolean = false,
    val whisper: Boolean = false
) {
    companion object {
        val ALL = listOf(
            VoicePreset("normal",   "NORMAL",   "👤",  0f,   1.0f, 0.0f, 0.0f, 0.0f, 1.0f),
            VoicePreset("robot",    "ROBOT",    "🤖", -4f,  0.95f, 0.4f, 0.2f, 0.3f, 1.2f, robotize = true),
            VoicePreset("deep",     "DEEP",     "👹", -8f,  0.90f, 0.2f, 0.1f, 0.5f, 1.3f),
            VoicePreset("chipmunk", "CHIPMUNK", "🐿️", 10f,  1.30f, 0.1f, 0.0f, 0.0f, 1.0f),
            VoicePreset("alien",    "ALIEN",    "👽",  6f,  1.10f, 0.6f, 0.3f, 0.2f, 1.1f, robotize = true),
            VoicePreset("ghost",    "GHOST",    "👻",  3f,  0.85f, 0.8f, 0.4f, 0.0f, 0.9f, whisper = true),
            VoicePreset("female",   "GIRL",     "👩",  5f,  1.05f, 0.0f, 0.0f, 0.0f, 1.0f),
            VoicePreset("radio",    "RADIO",    "📻", -1f,  1.00f, 0.1f, 0.0f, 0.2f, 1.15f)
        )

        fun findById(id: String) = ALL.find { it.id == id } ?: ALL[0]
    }
}

enum class AppMode {
    CALL,
    GAME
}
