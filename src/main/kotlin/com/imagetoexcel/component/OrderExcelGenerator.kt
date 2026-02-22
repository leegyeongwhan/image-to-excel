package com.imagetoexcel.component

import com.imagetoexcel.dto.OrderData
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream

@Component
class OrderExcelGenerator {

    fun createExcel(orders: List<OrderData>): ByteArray {
        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet("주문 목록")

        val headerStyle = workbook.createCellStyle().apply {
            val font = workbook.createFont().apply { bold = true }
            setFont(font)
        }

        val headerRow = sheet.createRow(0)
        mapOf(0 to "이름", 2 to "주소", 3 to "전화번호").forEach { (col, header) ->
            headerRow.createCell(col).apply {
                setCellValue(header)
                cellStyle = headerStyle
            }
        }

        orders.forEachIndexed { index, order ->
            val row = sheet.createRow(index + 1)
            row.createCell(0).setCellValue(order.name)
            row.createCell(1).setCellValue("")
            row.createCell(2).setCellValue(order.address)
            row.createCell(3).setCellValue(order.phone ?: "")
        }

        sheet.autoSizeColumn(0)
        sheet.autoSizeColumn(2)
        sheet.autoSizeColumn(3)

        return ByteArrayOutputStream().use { outputStream ->
            workbook.write(outputStream)
            workbook.close()
            outputStream.toByteArray()
        }
    }
}
