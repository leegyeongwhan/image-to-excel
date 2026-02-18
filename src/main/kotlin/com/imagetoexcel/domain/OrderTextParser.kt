package com.imagetoexcel.domain

import com.imagetoexcel.domain.enum.KoreanRegion
import com.imagetoexcel.dto.OrderData

class OrderTextParser {

    private val addressSuffixes = Regex("[시군구읍면동리로길번지호층아파트빌라오피스텔]|APT")
    private val roadNamePattern = Regex("[\uAC00-\uD7A3]{2,}[로길]$|[\uAC00-\uD7A3]{2,}[로길]\\s")
    private val numberPattern = Regex("^\\d{1,5}(-\\d{1,5})?(번지)?$")

    private val phonePatterns = listOf(
        Regex("(01[016789][-\\s]?\\d{3,4}[-\\s]?\\d{4})"),
        Regex("(01[016789]\\d{7,8})")
    )

    private val nameWithPhonePattern = Regex("([A-Za-z][A-Za-z\\s]+)\\s*\\d{10,11}")
    private val englishNamePattern = Regex("^[A-Za-z][A-Za-z\\s]{1,30}$")
    private val singleNamePattern = Regex("\\b([A-Z][a-z]{2,15})\\b")
    private val excludeWords = setOf("Intake", "Online", "Image", "Excel", "Error", "Anyeong", "Khaopan")

    fun parse(text: String): OrderData {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }

        val address = extractKoreanAddress(lines)
        val phone = extractPhone(lines)
        val name = extractName(lines, address)

        val finalAddress = address.ifBlank {
            lines.filter { line -> line.any { it.isKorean() } }
                .joinToString(" ")
                .ifBlank { "[인식 실패] 원문: ${lines.take(5).joinToString(" | ")}" }
        }

        return OrderData(name = name, address = finalAddress, phone = phone)
    }

    private fun extractKoreanAddress(lines: List<String>): String {
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

        for (line in lines) {
            if (line.any { it.isKorean() } && line.any { it.isDigit() } && addressSuffixes.containsMatchIn(line)) {
                return line
            }
        }

        // 3단계: 도로명(~로, ~길) 패턴 찾고 앞뒤 줄의 번지수 결합
        for ((index, line) in lines.withIndex()) {
            if (roadNamePattern.containsMatchIn(line) && line.any { it.isKorean() }) {
                val parts = mutableListOf<String>()

                // 앞 줄이 번지수면 결합 (예: "764-28" + "서리등로" → "서리등로 764-28")
                if (index > 0 && numberPattern.matches(lines[index - 1].trim())) {
                    parts.add(line)
                    parts.add(lines[index - 1].trim())
                    return parts.joinToString(" ").trim()
                }

                // 뒷 줄이 번지수면 결합 (예: "서리등로" + "764-28")
                if (index < lines.size - 1 && numberPattern.matches(lines[index + 1].trim())) {
                    parts.add(line)
                    parts.add(lines[index + 1].trim())
                    return parts.joinToString(" ").trim()
                }

                // 번지수 없이 도로명만 있는 경우
                return line
            }
        }

        return ""
    }

    private fun extractPhone(lines: List<String>): String {
        for (line in lines) {
            for (pattern in phonePatterns) {
                val match = pattern.find(line) ?: continue
                val digits = match.groupValues[1].replace(Regex("[^0-9]"), "")
                if (digits.length in 10..11) {
                    return digits
                }
            }
        }
        return ""
    }

    private fun extractName(lines: List<String>, address: String): String {
        for (line in lines) {
            val match = nameWithPhonePattern.find(line)
            if (match != null) return match.groupValues[1].trim()
        }

        for (line in lines) {
            if (line == address) continue
            if (englishNamePattern.matches(line) && !line.contains("KRW", ignoreCase = true)) {
                return line.trim()
            }
        }

        for (line in lines) {
            if (line == address || line.contains("KRW", ignoreCase = true)) continue
            val match = singleNamePattern.find(line) ?: continue
            val candidate = match.groupValues[1]
            if (candidate !in excludeWords) return candidate
        }

        return ""
    }

    private fun Char.isKorean(): Boolean = this in '\uAC00'..'\uD7A3'
}
