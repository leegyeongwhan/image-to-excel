package com.imagetoexcel.domain.parser

import com.imagetoexcel.domain.enum.KoreanRegion

class AddressExtractor {

    private val addressSuffixes = Regex("[가-힣][시군구읍면동리로길]|[가-힣]\\d+[로길]|\\d+[호층]|번지|아파트|빌라|오피스텔|APT")
    private val roadNamePattern = Regex("[가-힣]{2,}\\d*[로길]$|[가-힣]{2,}\\d*[로길]\\s")

    // 건물번호 패턴: 최대 4자리 (5자리는 우편번호 → 제외)
    // 예: "80" ✓, "10-12" ✓, "428-20" ✓ / "44064" ❌ (우편번호)
    private val numberPattern = Regex("^\\d{1,4}(-\\d{1,4})?(번지)?$")

    // 줄 안에 독립적인(공백으로 구분된) 숫자가 있는지 확인
    // "대송4길" → "4"는 도로명 안에 붙어있어 독립숫자 아님 → false
    // "메나리길 123" → " 123" 독립숫자 → true
    private val standaloneNumber = Regex("(?:^|\\s)\\d{1,4}(-\\d{1,4})?(?:\\s|$|번지|호|층)")

    // 지번 주소 패턴: "XX동/읍/면/리 xxx번지" - 지역명 없이 잘린 경우 대응
    private val jibunPattern = Regex("[가-힣]{2,}(?:동|읍|면|리)\\s*\\d{1,5}(?:-\\d{1,5})?")

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
            // OCR이 "107호 01033345885"를 "1075 01033345885" 처럼 읽을 때:
            // 호(号) 문자가 숫자로 오인되어 붙어버린 경우 → 짧은 숫자를 호수로 추출해 주소에 추가
            val lastAddressLine = lines.firstOrNull { it == addressLines.last() }
            val lastIdx = if (lastAddressLine != null) lines.indexOf(lastAddressLine) else -1
            if (lastIdx >= 0 && lastIdx + 1 < lines.size) {
                val nextLine = lines[lastIdx + 1].trim()
                // 패턴: "1075 01033345885" → 앞 1~4자리 숫자 + 공백 + 010 시작 전화번호
                val unitBeforePhone = Regex("^(\\d{1,4})\\s+01[016789]\\d{7,8}$").find(nextLine)
                val hasUnitAlready = addressLines.joinToString("").contains(Regex("[호동층]"))
                if (unitBeforePhone != null && !hasUnitAlready) {
                    val unit = unitBeforePhone.groupValues[1]
                    addressLines.add("${unit}호")
                }
            }
            return addressLines.joinToString(" ").trim()
        }

        // 2단계: 한국어 + 독립 번지수 + 주소 접미사 (한 줄에 도로명+번호 모두 있는 경우)
        // "메나리길 123" → 독립숫자 O → 반환
        // "대송4길" → 독립숫자 X (4는 도로명 안에 붙어있음) → 스킵 → 3단계에서 다음 줄 "80" 결합
        for (line in lines) {
            if (line.any { it.isKorean() }
                && standaloneNumber.containsMatchIn(line)
                && addressSuffixes.containsMatchIn(line)) {
                return line
            }
        }

        // 3단계: 도로명(~로, ~길) + 앞뒤 줄 번지수 결합
        // 로마자 병기 줄(Daesong 4-gil) 은 건너뛰고 최대 3줄 앞을 탐색
        for ((index, line) in lines.withIndex()) {
            if (roadNamePattern.containsMatchIn(line) && line.any { it.isKorean() }) {
                // 앞 줄에 번지수가 있는 경우
                if (index > 0 && numberPattern.matches(lines[index - 1].trim())) {
                    return "$line ${lines[index - 1].trim()}"
                }

                // 뒷 줄 최대 3줄 탐색 (로마자 병기 줄 건너뜀)
                for (ahead in 1..3) {
                    val nextIdx = index + ahead
                    if (nextIdx >= lines.size) break
                    val nextLine = lines[nextIdx].trim()
                    if (isRomanization(nextLine)) continue   // "Daesong 4-gil" 건너뜀
                    if (numberPattern.matches(nextLine)) {
                        return "$line $nextLine"             // "대송4길 80"
                    }
                    break   // 번지수도 로마자도 아니면 중단
                }

                return line
            }
        }

        // 4단계: 지번 주소 패턴 (동/읍/면/리 + 번지) - 지역명 없이 잘린 경우
        for (line in lines) {
            if (jibunPattern.containsMatchIn(line)) {
                return line
            }
        }

        return ""
    }

    /** 영문 로마자 병기 줄 여부 (주소 탐색 시 건너뜀) */
    private fun isRomanization(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("-gil") || lower.contains("-ro") ||
                lower.contains("-daero") || lower.contains("beon-gil") ||
                lower.contains("myeon") || lower.contains("heungcheon") ||
                lower.contains("yeoju") || lower.contains("daesong")
    }

    private fun Char.isKorean(): Boolean = this in '\uAC00'..'\uD7A3'
}
