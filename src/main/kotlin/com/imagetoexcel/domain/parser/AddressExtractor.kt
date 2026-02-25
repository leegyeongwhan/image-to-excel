package com.imagetoexcel.domain.parser

import com.imagetoexcel.domain.enum.KoreanRegion

class AddressExtractor {

    private val addressSuffixes =
            Regex("[가-힣][시군구읍면동리로길]|[가-힣]\\d+[로길]|\\d+[호층]|번지|아파트|빌라|오피스텔|APT")
    private val roadNamePattern = Regex("[가-힣]{2,}\\s*\\d*(?:번)?[로길](?:$|\\s|,)")

    // 건물번호 패턴: 최대 4자리 (5자리는 우편번호 → 제외)
    // 예: "80" ✓, "10-12" ✓, "428-20" ✓, "<99 103>" 같이 숫자가 여러개여도 마지막 숫자 등 노이즈 허용
    private val numberPattern = Regex("^.*\\b\\d{1,4}(-\\d{1,4})?\\b.*$")

    // 줄 안에 독립적인(공백으로 구분된) 숫자가 있는지 확인
    // "대송4길" → "4"는 도로명 안에 붙어있어 독립숫자 아님 → false
    private val standaloneNumber =
            Regex("(?:^|\\s)[<\\(\\[]?\\s*\\d{1,4}(-\\d{1,4})?\\s*[>\\)\\]]?(?:\\s|$|번지|호|층)")

    // 지번 주소 패턴: "XX동/읍/면/리 xxx번지" - 지역명 없이 잘린 경우 대응
    private val jibunPattern = Regex("[가-힣]{2,}(?:동|읍|면|리)\\s*\\d{1,5}(?:-\\d{1,5})?")

    fun extract(lines: List<String>): String {
        // 0단계: 파란색 건물 번호판 (카메라 앱의 상단 GPS 주소보다 실제 사물 우선)
        // 줄 전체가 "오직 도로명"으로만 끝나는 경우 (예: "대송4길", "원고매로2번길")
        val pureRoadName = Regex("^[a-zA-Z가-힣\\s]*[가-힣]{2,}\\s*\\d*(?:번)?[로길]$")
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (pureRoadName.matches(trimmed) && trimmed.any { it.isKorean() }) {
                // 위로 최대 5줄 거꾸로 탐색 (숫자가 먼저 잡혔을 가능성)
                for (back in 1..5) {
                    val prevIdx = index - back
                    if (prevIdx < 0) break
                    val prevLine = lines[prevIdx].trim()
                    if (prevLine.length < 15 && numberPattern.matches(prevLine)) {
                        // 노이즈를 제거하고 번지수만 추출
                        val match = Regex("\\d{1,4}(-\\d{1,4})?").findAll(prevLine).lastOrNull()
                        if (match != null) return "$trimmed ${match.value}"
                    }
                }
                // 아래로 최대 3줄 탐색
                for (ahead in 1..3) {
                    val nextIdx = index + ahead
                    if (nextIdx >= lines.size) break
                    val nextLine = lines[nextIdx].trim()
                    if (nextLine.isEmpty() || isRomanization(nextLine) || nextLine.contains("우편번호"))
                            continue
                    if (nextLine.length < 15 && numberPattern.matches(nextLine)) {
                        val match = Regex("\\d{1,4}(-\\d{1,4})?").findAll(nextLine).lastOrNull()
                        if (match != null) return "$trimmed ${match.value}"
                    }
                    break
                }
            }
        }

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
            if (line.any { it.isKorean() } &&
                            standaloneNumber.containsMatchIn(line) &&
                            addressSuffixes.containsMatchIn(line)
            ) {
                return line
            }
        }

        // 3단계: 도로명(~로, ~길) + 앞/뒤 줄 번지수 결합
        // 로마자 병기 줄(Daesong 4-gil) 은 건너뛰고 상하 문맥 탐색
        for ((index, line) in lines.withIndex()) {
            if (roadNamePattern.containsMatchIn(line) && line.any { it.isKorean() }) {

                // 앞(위)으로 최대 5줄 거꾸로 탐색 (OCR이 큰 번호를 먼저 읽는 현상 대응)
                for (back in 1..5) {
                    val prevIdx = index - back
                    if (prevIdx < 0) break
                    val prevLine = lines[prevIdx].trim()

                    // 만약 너무 긴 텍스트거나 다른 한글 주소가 아닐 때 독립 숫자만 있다면
                    if (prevLine.length < 15 && numberPattern.matches(prevLine)) {
                        // 노이즈(괄호 등)를 제거하고 숫자-숫자 패턴만 추출해 결합
                        val onlyNumberMatch = Regex("\\d{1,4}(-\\d{1,4})?").find(prevLine)
                        if (onlyNumberMatch != null) {
                            return "$line ${onlyNumberMatch.value}"
                        }
                    }
                }

                // 뒷 줄 최대 3줄 탐색 (로마자 병기 줄 건너뜀)
                for (ahead in 1..3) {
                    val nextIdx = index + ahead
                    if (nextIdx >= lines.size) break
                    val nextLine = lines[nextIdx].trim()
                    if (isRomanization(nextLine)) continue // "Daesong 4-gil" 건너뜀
                    if (nextLine.length < 15 && numberPattern.matches(nextLine)) {
                        val onlyNumberMatch = Regex("\\d{1,4}(-\\d{1,4})?").find(nextLine)
                        if (onlyNumberMatch != null) {
                            return "$line ${onlyNumberMatch.value}" // "대송4길 80"
                        }
                    }
                    break // 번지수도 로마자도 아니면 중단
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
        return lower.contains("-gil") ||
                lower.contains("-ro") ||
                lower.contains("-daero") ||
                lower.contains("beon-gil") ||
                lower.contains("myeon") ||
                lower.contains("heungcheon") ||
                lower.contains("yeoju") ||
                lower.contains("daesong")
    }

    private fun Char.isKorean(): Boolean = this in '\uAC00'..'\uD7A3'
}
