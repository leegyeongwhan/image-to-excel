package com.imagetoexcel.service

import net.sourceforge.tess4j.Tesseract
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.File

@Service
class OcrService(
    private val tesseract: Tesseract
) {
    /**
     * 이미지에서 텍스트 추출 (태국어 + 영어)
     */
    fun extractText(file: MultipartFile): String {
        val tempFile = File.createTempFile("ocr_", ".${file.originalFilename?.substringAfterLast('.') ?: "png"}")
        try {
            file.transferTo(tempFile)
            return tesseract.doOCR(tempFile)
        } finally {
            tempFile.delete()
        }
    }

    /**
     * 추출된 텍스트를 라인별로 파싱
     */
    fun parseLines(text: String): List<String> {
        return text.lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
    }
}
