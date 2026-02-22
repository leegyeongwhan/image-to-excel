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

        return OrderData(name = name, address = address, phone = phoneResult.phone)
    }
}
