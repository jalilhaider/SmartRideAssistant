package com.smartride.assistant.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.smartride.assistant.data.OcrLine
import kotlinx.coroutines.tasks.await

/** On-device OCR (Google ML Kit, bundled Latin model). Frames never leave the phone. */
class OcrEngine {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun recognise(bitmap: Bitmap): List<OcrLine> {
        val result = recognizer.process(InputImage.fromBitmap(bitmap, 0)).await()
        return result.textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val r = line.boundingBox ?: return@mapNotNull null
                OcrLine(line.text, r.left, r.top, r.right, r.bottom, line.confidence)
            }
    }

    fun close() = recognizer.close()
}
