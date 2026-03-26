package com.bookreader.app.ui

import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bookreader.app.ai.ApiKeyManager
import com.bookreader.app.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var apiKeyManager: ApiKeyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "AI Settings"

        apiKeyManager = ApiKeyManager(this)

        // Populate existing keys (masked)
        binding.claudeKeyInput.setText(apiKeyManager.anthropicApiKey ?: "")
        binding.openaiKeyInput.setText(apiKeyManager.openaiApiKey ?: "")

        binding.toggleClaudeVisibility.setOnClickListener {
            toggleVisibility(binding.claudeKeyInput)
        }
        binding.toggleOpenaiVisibility.setOnClickListener {
            toggleVisibility(binding.openaiKeyInput)
        }

        binding.saveButton.setOnClickListener {
            val claudeKey = binding.claudeKeyInput.text?.toString()?.trim()
            val openaiKey = binding.openaiKeyInput.text?.toString()?.trim()

            apiKeyManager.anthropicApiKey = claudeKey?.takeIf { it.isNotEmpty() }
            apiKeyManager.openaiApiKey = openaiKey?.takeIf { it.isNotEmpty() }

            val msg = buildString {
                if (!claudeKey.isNullOrEmpty()) append("Claude key saved. ")
                if (!openaiKey.isNullOrEmpty()) append("OpenAI key saved. ")
                if (claudeKey.isNullOrEmpty() && openaiKey.isNullOrEmpty()) append("Keys cleared.")
            }
            Toast.makeText(this, msg.ifBlank { "Settings saved." }, Toast.LENGTH_SHORT).show()
            finish()
        }

        binding.clearButton.setOnClickListener {
            binding.claudeKeyInput.setText("")
            binding.openaiKeyInput.setText("")
            apiKeyManager.anthropicApiKey = null
            apiKeyManager.openaiApiKey = null
            Toast.makeText(this, "API keys cleared.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleVisibility(editText: android.widget.EditText) {
        val isPassword = editText.inputType and InputType.TYPE_TEXT_VARIATION_PASSWORD != 0
        if (isPassword) {
            editText.inputType = InputType.TYPE_CLASS_TEXT
        } else {
            editText.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        editText.setSelection(editText.text?.length ?: 0)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
