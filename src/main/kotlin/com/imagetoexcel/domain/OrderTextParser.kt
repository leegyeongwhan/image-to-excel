package com.imagetoexcel.domain

import com.imagetoexcel.domain.parser.AddressExtractor
import com.imagetoexcel.domain.parser.NameExtractor
import com.imagetoexcel.domain.parser.PhoneExtractor
import com.imagetoexcel.dto.OrderData

class OrderTextParser {

    private val phoneExtractor = PhoneExtractor()
    private val addressExtractor = AddressExtractor()
    private val nameExtractor = NameExtractor()

    fun parse(text: String): OrderData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val phoneResult = phoneExtractor.extract(lines)
        val address = addressExtractor.extract(lines)
        val name = nameExtractor.extract(lines, address, phoneResult.lineIndex)

        val finalAddress = address.ifBlank {
            lines.filter { line -> line.any { it in '\uAC00'..'\uD7A3' } }
                .joinToString(" ")
                .ifBlank { "외국인" }
        }

        return OrderData(name = name, address = finalAddress, phone = phoneResult.phone)
    }
}
