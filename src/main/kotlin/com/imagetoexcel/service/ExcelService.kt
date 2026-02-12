package com.imagetoexcel.service

import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Service
import java.io.ByteArrayOutputStream

@Service
class ExcelService {

    /**
     * 텍스트 라인들을 엑셀 파일로 변환
     */
    fun createExcel(lines: List<String>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("OCR Result")

        // 헤더 행 생성
        val headerRow = sheet.createRow(0)
        headerRow.createCell(0).setCellValue("No.")
        headerRow.createCell(1).setCellValue("Text")

        // 데이터 행 생성
        lines.forEachIndexed { index, line ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue((index + 1).toDouble())
            row.createCell(1).setCellValue(line)
        }

        // 컬럼 너비 자동 조정
        sheet.autoSizeColumn(0)
        sheet.autoSizeColumn(1)

        // ByteArray로 변환
        return ByteArrayOutputStream().use { outputStream ->
            workbook.write(outputStream)
            workbook.close()
            outputStream.toByteArray()
        }
    }
}
