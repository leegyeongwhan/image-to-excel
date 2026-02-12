package com.imagetoexcel.controller

import com.imagetoexcel.service.ExcelService
import com.imagetoexcel.service.OcrService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.multipart.MultipartFile

@Controller
class UploadController(
    private val ocrService: OcrService,
    private val excelService: ExcelService
) {

    @GetMapping("/")
    fun index(): String {
        return "index"
    }

    @PostMapping("/upload")
    fun upload(
        @RequestParam("file") file: MultipartFile,
        model: Model
    ): String {
        if (file.isEmpty) {
            model.addAttribute("error", "파일을 선택해주세요.")
            return "index"
        }

        try {
            val extractedText = ocrService.extractText(file)
            val lines = ocrService.parseLines(extractedText)

            model.addAttribute("lines", lines)
            model.addAttribute("rawText", extractedText)
            model.addAttribute("success", true)
        } catch (e: Exception) {
            model.addAttribute("error", "OCR 처리 중 오류가 발생했습니다: ${e.message}")
        }

        return "index"
    }

    @PostMapping("/download")
    fun download(@RequestParam("lines") lines: List<String>): ResponseEntity<ByteArray> {
        val excelBytes = excelService.createExcel(lines)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=ocr_result.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excelBytes)
    }
}
