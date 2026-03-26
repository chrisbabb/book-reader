package com.bookreader.app.state

import android.content.Context
import android.content.SharedPreferences

class ReadingStateManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("book_reader_state", Context.MODE_PRIVATE)

    var pageNumber: Int
        get() = prefs.getInt(KEY_PAGE_NUMBER, 1)
        set(value) = prefs.edit().putInt(KEY_PAGE_NUMBER, value).apply()

    var lastSentenceIndex: Int
        get() = prefs.getInt(KEY_SENTENCE_INDEX, 0)
        set(value) = prefs.edit().putInt(KEY_SENTENCE_INDEX, value).apply()

    var pagesInCurrentSpread: Int
        get() = prefs.getInt(KEY_PAGES_IN_SPREAD, 0)
        set(value) = prefs.edit().putInt(KEY_PAGES_IN_SPREAD, value).apply()

    fun incrementPage() {
        pageNumber++
    }

    fun resetSpread() {
        pagesInCurrentSpread = 0
    }

    companion object {
        private const val KEY_PAGE_NUMBER = "page_number"
        private const val KEY_SENTENCE_INDEX = "sentence_index"
        private const val KEY_PAGES_IN_SPREAD = "pages_in_spread"
    }
}
