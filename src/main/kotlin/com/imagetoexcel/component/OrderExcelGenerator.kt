package com.imagetoexcel.component

import com.imagetoexcel.dto.OrderData
import org.apache.poi.ss.usermodel.VerticalAlignment
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class OrderExcelGenerator {

    fun createExcel(orders: List<OrderData>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("주문 목록")

        // --- 참조 엑셀 (로젠-어이) 서식 기준 ---

        // 기본 행 높이
        sheet.defaultRowHeightInPoints = 21.75f

        // 컬럼 너비 (1/256 character width 단위)
        sheet.setColumnWidth(0, (12.5 * 256).toInt())   // A: 이름
        sheet.setColumnWidth(1, (4.625 * 256).toInt())   // B: 빈칸 (스페이서)
        sheet.setColumnWidth(2, (54.25 * 256).toInt())   // C: 주소
        sheet.setColumnWidth(3, (13.375 * 256).toInt())  // D: 전화번호

        // 폰트: 맑은 고딕 11pt, 볼드 없음
        val font = workbook.createFont()
        font.fontName = "맑은 고딕"
        font.fontHeightInPoints = 11
        font.bold = false

        // 셀 스타일: 세로 가운데 정렬
        val style = workbook.createCellStyle()
        style.setFont(font)
        style.verticalAlignment = VerticalAlignment.CENTER

        // 헤더 (볼드 없음, 참조 엑셀과 동일)
        val headerRow = sheet.createRow(0)
        val headers = mapOf(0 to "이름", 2 to "주소", 3 to "전화번호")
        for ((col, header) in headers) {
            val cell = headerRow.createCell(col)
            cell.setCellValue(header)
            cell.cellStyle = style
        }

        // 데이터
        orders.forEachIndexed { index, order ->
            val row = sheet.createRow(index + 1)

            val nameCell = row.createCell(0)
            nameCell.setCellValue(order.name)
            nameCell.cellStyle = style

            val spacerCell = row.createCell(1)
            spacerCell.setCellValue("")
            spacerCell.cellStyle = style

            val addrCell = row.createCell(2)
            addrCell.setCellValue(order.address)
            addrCell.cellStyle = style

            val phoneCell = row.createCell(3)
            phoneCell.setCellValue(order.phone ?: "")
            phoneCell.cellStyle = style
        }

        return ByteArrayOutputStream().use { outputStream ->
            workbook.write(outputStream)
            workbook.close()
            outputStream.toByteArray()
        }
    }
}
