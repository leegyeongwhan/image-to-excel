package com.imagetoexcel.domain.parser

data class PhoneResult(val phone: String, val lineIndex: Int)

class PhoneExtractor {

    private val phonePatterns = listOf(
        Regex("(01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4})"),
        Regex("(01[016789]\\d{7,8})")
    )

    fun extract(lines: List<String>): PhoneResult {
        for ((index, line) in lines.withIndex()) {
            for (pattern in phonePatterns) {
                val match = pattern.find(line) ?: continue
                val digits = match.groupValues[1].replace(Regex("[^0-9]"), "")
                if (digits.length in 10..11) {
                    return PhoneResult(phone = digits, lineIndex = index)
                }
            }
        }
        return PhoneResult(phone = "", lineIndex = -1)
    }
}
