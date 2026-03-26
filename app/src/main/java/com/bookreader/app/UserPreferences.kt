package com.bookreader.app

import android.content.Context

class UserPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)

    var ttsVoice: String
        get() = prefs.getString(KEY_TTS_VOICE, DEFAULT_VOICE) ?: DEFAULT_VOICE
        set(value) { prefs.edit().putString(KEY_TTS_VOICE, value).apply() }

    companion object {
        private const val KEY_TTS_VOICE = "tts_voice"
        const val DEFAULT_VOICE = "nova"

        /** OpenAI voice id → human-readable label */
        val VOICES = listOf(
            "alloy"   to "Alloy — Neutral, balanced",
            "echo"    to "Echo — Warm, male",
            "fable"   to "Fable — Expressive, British",
            "onyx"    to "Onyx — Deep, authoritative",
            "nova"    to "Nova — Natural, female (default)",
            "shimmer" to "Shimmer — Clear, female"
        )
    }
}
