package com.imagetoexcel.domain.parser

import com.imagetoexcel.domain.enum.KoreanRegion

class AddressExtractor {

    private val addressSuffixes = Regex("[\uAC00-\uD7A3][시군구읍면동리로길]|\\d+[호층]|번지|아파트|빌라|오피스텔|APT")
    private val roadNamePattern = Regex("[\uAC00-\uD7A3]{2,}[로길]$|[\uAC00-\uD7A3]{2,}[로길]\\s")
    private val numberPattern = Regex("^\\d{1,5}(-\\d{1,5})?(번지)?$")

    fun extract(lines: List<String>): String {
        // 1단계: 지역 키워드 기반 여러 줄 결합
        val addressLines = mutableListOf<String>()
        var foundAddress = false

        for (line in lines) {
            val hasKeyword = KoreanRegion.containsAny(line)
            val hasSuffix = addressSuffixes.containsMatchIn(line)
            val hasKorean = line.any { it.isKorean() }

            if (hasKeyword) {
                addressLines.add(line)
                foundAddress = true
            } else if (foundAddress && hasKorean && hasSuffix) {
                addressLines.add(line)
            } else if (foundAddress) {
                break
            }
        }

        if (addressLines.isNotEmpty()) {
            return addressLines.joinToString(" ").trim()
        }

        // 2단계: 한국어 + 숫자 + 주소 접미사가 있는 단일 줄
        for (line in lines) {
            if (line.any { it.isKorean() } && line.any { it.isDigit() } && addressSuffixes.containsMatchIn(line)) {
                return line
            }
        }

        // 3단계: 도로명(~로, ~길) 패턴 찾고 앞뒤 줄의 번지수 결합
        for ((index, line) in lines.withIndex()) {
            if (roadNamePattern.containsMatchIn(line) && line.any { it.isKorean() }) {
                val parts = mutableListOf<String>()

                if (index > 0 && numberPattern.matches(lines[index - 1].trim())) {
                    parts.add(line)
                    parts.add(lines[index - 1].trim())
                    return parts.joinToString(" ").trim()
                }

                if (index < lines.size - 1 && numberPattern.matches(lines[index + 1].trim())) {
                    parts.add(line)
                    parts.add(lines[index + 1].trim())
                    return parts.joinToString(" ").trim()
                }

                return line
            }
        }

        return ""
    }

    private fun Char.isKorean(): Boolean = this in '\uAC00'..'\uD7A3'
}
