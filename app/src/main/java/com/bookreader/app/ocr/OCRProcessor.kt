package com.bookreader.app.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OCRProcessor {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun extractText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val text = buildReadableText(visionText)
                    continuation.resume(text)
                }
                .addOnFailureListener { exception ->
                    continuation.resumeWithException(exception)
                }
            continuation.invokeOnCancellation { recognizer.close() }
        }
    }

    /**
     * Reconstructs readable text from ML Kit's block/line structure,
     * preserving paragraph breaks but merging hyphenated line-breaks.
     */
    private fun buildReadableText(visionText: com.google.mlkit.vision.text.Text): String {
        val sb = StringBuilder()
        for (block in visionText.textBlocks) {
            val blockText = block.lines.joinToString(" ") { line ->
                // De-hyphenate line breaks (e.g. "exam-\nple" -> "example")
                val lineText = line.text.trim()
                if (lineText.endsWith("-")) lineText.dropLast(1) else lineText
            }
            sb.append(blockText.trim())
            sb.append("\n\n") // paragraph separator
        }
        return sb.toString().trim()
    }

    fun release() {
        recognizer.close()
    }
}
