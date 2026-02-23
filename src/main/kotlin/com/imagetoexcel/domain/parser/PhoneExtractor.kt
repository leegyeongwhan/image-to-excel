package com.imagetoexcel.domain.parser

data class PhoneResult(val phone: String, val lineIndex: Int)

class PhoneExtractor {

    private val phonePatterns = listOf(
        Regex("(01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4})"),
        Regex("(01[016789]\\d{7,8})")
    )

    fun extract(lines: List<String>): PhoneResult {
        // 1단계: 단일 줄에서 전화번호 검색 (가장 일반적인 경우)
        for ((index, line) in lines.withIndex()) {
            val result = matchPhone(line, index)
            if (result != null) return result
        }

        // 2단계: 인접한 두 줄을 합쳐서 검색
        // 예: OCR이 "010 8610" / "0102" 로 줄을 나눈 경우
        for (i in 0 until lines.size - 1) {
            val merged = "${lines[i]} ${lines[i + 1]}"
            val result = matchPhone(merged, i)
            if (result != null) return result
        }

        return PhoneResult(phone = "", lineIndex = -1)
    }

    private fun matchPhone(text: String, lineIndex: Int): PhoneResult? {
        for (pattern in phonePatterns) {
            val match = pattern.find(text) ?: continue
            val digits = match.groupValues[1].replace(Regex("[^0-9]"), "")
            if (digits.length in 10..11) {
                return PhoneResult(phone = digits, lineIndex = lineIndex)
            }
        }
        return null
    }
}
