package com.bookreader.app.ai

import android.content.Context
import android.content.SharedPreferences
import com.bookreader.app.BuildConfig

/**
 * Resolves Anthropic (Claude) and OpenAI API keys.
 * Priority: SharedPreferences (entered via Settings UI) > BuildConfig (from secrets.properties).
 * Keys are optional — the app degrades gracefully without them.
 */
class ApiKeyManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("api_keys", Context.MODE_PRIVATE)

    var anthropicApiKey: String?
        get() = prefs.getString(KEY_ANTHROPIC, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_ANTHROPIC, value?.trim()).apply()

    var openaiApiKey: String?
        get() = prefs.getString(KEY_OPENAI, null)?.takeIf { it.isNotBlank() }
            ?: BuildConfig.OPENAI_API_KEY.takeIf { it.isNotBlank() }
        set(value) = prefs.edit().putString(KEY_OPENAI, value?.trim()).apply()

    val hasClaudeKey: Boolean get() = anthropicApiKey != null
    val hasOpenAIKey: Boolean get() = openaiApiKey != null

    companion object {
        private const val KEY_ANTHROPIC = "anthropic_api_key"
        private const val KEY_OPENAI = "openai_api_key"
    }
}
