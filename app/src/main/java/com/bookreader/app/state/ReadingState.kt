package com.bookreader.app.state

data class ReadingState(
    val pageNumber: Int = 0,
    val fullText: String = "",
    val sentences: List<String> = emptyList(),
    val paragraphs: List<String> = emptyList(),
    val currentSentenceIndex: Int = 0,
    val pagesReadInCurrentSpread: Int = 0, // 0, 1, 2 — resets after turn-page announcement
    val status: ReadingStatus = ReadingStatus.IDLE
)

enum class ReadingStatus {
    IDLE,
    CAPTURING,
    PROCESSING,
    READING,
    PAUSED,
    WAITING_FOR_CONTINUE
}
