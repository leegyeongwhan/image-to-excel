package com.imagetoexcel.domain.parser

import com.imagetoexcel.domain.enum.KoreanRegion

class AddressExtractor {

    /** 주소의 끝을 나타내는 일반적인 접미사 패턴 */
    private val addressSuffixes =
            Regex("[가-힣][시군구읍면동리로길]|[가-힣]\\d+[로길]|\\d+[호층]|번지|아파트|빌라|오피스텔|APT")
    /** 도로명 패턴 (예: 지제동삭2로177 처럼 공백 유무 무관하게 도로명과 숫자가 붙은 형태) */
    private val roadNamePattern =
            Regex("[가-힣]{2,}\\s*\\d*(?:번)?[로길](?:\\s*\\d{1,4}(?:-\\d{1,4})?)?(?:$|\\s|,)")
    /** 띄어쓰기 없이 붙어있는 도로명과 건물번호 패턴 */
    private val tightRoadAndNumber = Regex("([가-힣]+(?:로|길))\\s*(\\d{1,4}(?:-\\d{1,4})?)")
    /** 건물번호 패턴 (최대 4자리, 노이즈 허용) */
    private val numberPattern = Regex("^.*\\b\\d{1,4}(-\\d{1,4})?\\b.*$")
    /** 독립적인 숫자로 된 번지수/호수 패턴 (공백으로 구분됨) */
    private val standaloneNumber =
            Regex("(?:^|\\s)[<\\(\\[]?\\s*\\d{1,4}(-\\d{1,4})?\\s*[>\\)\\]]?(?:\\s|$|번지|호|층)")
    /** 지번 주소 패턴 (동/읍/면/리 + 번지수) */
    private val jibunPattern = Regex("[가-힣]{2,}(?:동|읍|면|리)\\s*\\d{1,5}(?:-\\d{1,5})?")
    /** 동/호수 등 상세 주소 관련 패턴 */
    private val unitDetailPattern = Regex("\\d+[호층]|\\d+동\\s*\\d+호|\\d+BL|\\d+블록")
    /** 줄 전체가 오직 도로명으로만 끝나는 패턴 (파란색 건물 번호판) */
    private val pureRoadNamePattern = Regex("^[a-zA-Z가-힣\\s]*[가-힣]{2,}\\s*\\d*(?:번)?[로길]$")

    /** OCR로 인식된 텍스트 줄 목록에서 주소를 추출합니다. 외국어(특히 태국어)가 포함된 블록은 제외하며, 다양한 패턴 매칭 전략을 순차적으로 시도합니다. */
    fun extract(lines: List<String>): String {
        val cleanLines = lines.filter { !containsThaiScript(it) }
        if (cleanLines.isEmpty()) return ""

        extractPureRoadName(cleanLines)?.let {
            return it
        }
        extractTightAddress(cleanLines)?.let {
            return it
        }
        extractStandardAddress(cleanLines)?.let {
            return it
        }
        extractCombinedRoadAddress(cleanLines)?.let {
            return it
        }
        extractJibunAddress(cleanLines)?.let {
            return it
        }

        return ""
    }

    /** 파란색 건물 번호판 등 오직 도로명으로만 이루어진 줄을 찾아 앞뒤로 번지수를 탐색하여 추출합니다. */
    private fun extractPureRoadName(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            val trimmed = line.trim()
            if (pureRoadNamePattern.matches(trimmed) && trimmed.any { it.isKorean() }) {
                findNumberNear(index, lines, 1..3, forward = true)?.let {
                    return "$trimmed $it"
                }
                findNumberNear(index, lines, 1..5, forward = false)?.let {
                    return "$trimmed $it"
                }
            }
        }
        return null
    }

    /** 지역명, 도로명, 건물번호 사이에 띄어쓰기가 없는 주소를 추출합니다. 필요시 다음 줄에 있는 상세주소(동/호수)를 결합합니다. */
    private fun extractTightAddress(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            if (KoreanRegion.containsAny(line) && tightRoadAndNumber.containsMatchIn(line)) {
                val result = StringBuilder(line)

                for (ahead in 1..3) {
                    val nextIdx = index + ahead
                    if (nextIdx >= lines.size) break

                    val nextLine = lines[nextIdx].trim()
                    if (nextLine.isEmpty() ||
                                    containsThaiScript(nextLine) ||
                                    isCurrencyOrPriceLine(nextLine)
                    )
                            break

                    if (unitDetailPattern.containsMatchIn(nextLine)) {
                        result.append(" ").append(nextLine)
                    } else {
                        break
                    }
                }
                return result.toString()
            }
        }
        return null
    }

    /** 지역 키워드 기반으로 일반적인 여러 줄 주소를 추출하거나 독립된 번지수가 포함된 짧은 줄을 처리합니다. */
    private fun extractStandardAddress(lines: List<String>): String? {
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
            recoverUnitNumber(lines, addressLines)
            return addressLines.joinToString(" ").trim()
        }

        for (line in lines) {
            if (line.any { it.isKorean() } &&
                            standaloneNumber.containsMatchIn(line) &&
                            addressSuffixes.containsMatchIn(line)
            ) {
                return line
            }
        }
        return null
    }

    /** 도로명(~로, ~길) 줄을 기준으로 위아래 줄에서 번지수를 찾아 결합합니다. */
    private fun extractCombinedRoadAddress(lines: List<String>): String? {
        for ((index, line) in lines.withIndex()) {
            if (roadNamePattern.containsMatchIn(line) && line.any { it.isKorean() }) {
                findNumberNear(index, lines, 1..3, forward = true, returnFullMatch = false)?.let {
                    return "$line $it"
                }
                findNumberNear(index, lines, 1..5, forward = false, returnFullMatch = false)?.let {
                    return "$line $it"
                }
                return line
            }
        }
        return null
    }

    /** 지번 주소(동/읍/면/리 + 번지) 패턴을 추출합니다. */
    private fun extractJibunAddress(lines: List<String>): String? {
        return lines.firstOrNull { jibunPattern.containsMatchIn(it) }
    }

    /**
     * 특정 줄(인덱스) 주변에서 건물 번호를 탐색합니다.
     * @param index 기준 줄 인덱스
     * @param lines 전체 텍스트 줄
     * @param range 탐색 범위 (몇 줄 앞/뒤까지 볼지)
     * @param forward 순방향 탐색 여부 (true면 아래로, false면 위로)
     */
    private fun findNumberNear(
            index: Int,
            lines: List<String>,
            range: IntRange,
            forward: Boolean,
            returnFullMatch: Boolean = true
    ): String? {
        for (step in range) {
            val targetIdx = if (forward) index + step else index - step
            if (targetIdx !in lines.indices) break

            val line = lines[targetIdx].trim()
            if (line.isEmpty() ||
                            isRomanization(line) ||
                            line.contains("우편번호") ||
                            isCurrencyOrPriceLine(line)
            )
                    continue

            if (line.length < 15 && numberPattern.matches(line)) {
                val match = Regex("\\d{1,4}(-\\d{1,4})?").findAll(line).lastOrNull()
                if (match != null) {
                    return if (returnFullMatch && !forward && line.any { it.isKorean() }) line
                    else match.value
                }
            }

            // For forward tracking, if we hit a valid unrelated string block, we assume no number
            // exists.
            if (forward) break
        }
        return null
    }

    /** Recovers unit numbers (e.g., Room 107) mistakenly attached to phone numbers by OCR. */
    private fun recoverUnitNumber(lines: List<String>, addressLines: MutableList<String>) {
        val lastAddressLine = lines.firstOrNull { it == addressLines.last() }
        val lastIdx = if (lastAddressLine != null) lines.indexOf(lastAddressLine) else -1

        if (lastIdx >= 0 && lastIdx + 1 < lines.size) {
            val nextLine = lines[lastIdx + 1].trim()
            val unitBeforePhone = Regex("^(\\d{1,4})\\s+01[016789]\\d{7,8}$").find(nextLine)
            val hasUnitAlready = addressLines.joinToString("").contains(Regex("[호동층]"))

            if (unitBeforePhone != null && !hasUnitAlready) {
                val rawUnit = unitBeforePhone.groupValues[1]
                val unit = if (rawUnit.length >= 2) rawUnit.dropLast(1) else rawUnit
                addressLines.add("${unit}호")
            }
        }
    }

    private fun isCurrencyOrPriceLine(line: String): Boolean {
        val lower = line.lowercase().trim()
        return lower.contains("krw") ||
                lower.contains("원") ||
                lower.contains("usd") ||
                lower.contains("₩") ||
                Regex("\\d{1,3}(,\\d{3})+").containsMatchIn(line)
    }

    private fun isRomanization(line: String): Boolean {
        val lower = line.lowercase()
        return listOf(
                        "-gil",
                        "-ro",
                        "-daero",
                        "beon-gil",
                        "myeon",
                        "heungcheon",
                        "yeoju",
                        "daesong"
                )
                .any { lower.contains(it) }
    }

    private fun containsThaiScript(text: String): Boolean = text.any { it in '\u0E00'..'\u0E7F' }

    private fun Char.isKorean(): Boolean = this in '\uAC00'..'\uD7A3'
}
