package com.bookreader.app.voice

sealed class VoiceCommand {
    object Pause : VoiceCommand()
    object Resume : VoiceCommand()
    object Stop : VoiceCommand()
    object PreviousSentence : VoiceCommand()
    object ReadParagraph : VoiceCommand()
    object ReadPage : VoiceCommand()
    object Continue : VoiceCommand()
    object Capture : VoiceCommand()
    data class LastNWords(val count: Int) : VoiceCommand()

    companion object {
        fun parse(spokenText: String): VoiceCommand? {
            val text = spokenText.lowercase().trim()

            return when {
                // Capture commands
                text.contains("capture") ||
                text.contains("take picture") ||
                text.contains("take photo") ||
                text.contains("scan page") ||
                text.contains("scan the page") -> Capture

                // Stop reading (but keep app running)
                text == "stop reading" ||
                text.contains("stop reading") -> Pause

                // Full stop
                text == "stop" ||
                (text.contains("stop") && !text.contains("reading")) -> Stop

                // Pause
                text.contains("pause") -> Pause

                // Resume
                text.contains("resume") ||
                text.contains("keep reading") ||
                text.contains("continue reading") ||
                text.contains("start reading") -> Resume

                // Continue to next page
                text == "yes" ||
                text == "continue" ||
                text == "next page" ||
                text == "turn page" ||
                text.contains("next page") ||
                text.contains("turn the page") -> Continue

                // Previous sentence
                text.contains("previous sentence") ||
                text.contains("last sentence") ||
                text.contains("repeat sentence") ||
                text.contains("go back") -> PreviousSentence

                // Last N words — e.g. "last 5 words", "last ten words"
                Regex("last (\\d+|one|two|three|four|five|six|seven|eight|nine|ten) words?").containsMatchIn(text) -> {
                    val match = Regex("last (\\d+|one|two|three|four|five|six|seven|eight|nine|ten) words?").find(text)
                    val wordStr = match?.groupValues?.get(1) ?: "5"
                    LastNWords(wordStrToInt(wordStr))
                }

                // Paragraph
                text.contains("paragraph") ||
                text.contains("read the paragraph") ||
                text.contains("current paragraph") -> ReadParagraph

                // Page
                text.contains("read page") ||
                text.contains("read the page") ||
                text.contains("read again") ||
                text.contains("repeat page") ||
                text.contains("start over") -> ReadPage

                else -> null
            }
        }

        private fun wordStrToInt(word: String): Int {
            return when (word.lowercase()) {
                "one" -> 1
                "two" -> 2
                "three" -> 3
                "four" -> 4
                "five" -> 5
                "six" -> 6
                "seven" -> 7
                "eight" -> 8
                "nine" -> 9
                "ten" -> 10
                else -> word.toIntOrNull() ?: 5
            }
        }
    }
}
