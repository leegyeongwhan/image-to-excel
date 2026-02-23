package com.imagetoexcel.domain.parser

import kotlin.math.abs

class NameExtractor {

    private val nameWithPhonePattern = Regex("^([A-Za-z][A-Za-z ]+?)\\s+\\d{10,11}")
    private val englishNamePattern = Regex("^[A-Za-z][A-Za-z\\s]{1,30}$")
    private val singleNamePattern = Regex("\\b([A-Z][a-z]{2,15})\\b")

    // 엄격한 Full Name 패턴: "Akhom Narakool" 처럼 각 단어가 대문자+소문자
    private val fullNamePattern = Regex("^[A-Z][a-z]+(?:\\s+[A-Z][a-z]+)+$")

    private val excludeWords = setOf(
        "Intake", "Online", "Image", "Excel", "Error", "Anyeong", "Khaopan",
        "Messenger", "Facebook", "Message", "Send", "Photo", "Forwarded",
        "Bora", "Purlple", "SET", "Sordonway", "Demand", "Transaction"
    )

    fun extract(lines: List<String>, address: String, phoneLineIndex: Int): String {
        // 0순위: 메신저 상단 5줄에서 Full Name (2단어 이상) 최우선
        // "Akhom Narakool" 처럼 각 단어가 대문자로 시작하는 경우
        val topRange = minOf(5, lines.size)
        for (i in 0 until topRange) {
            val line = lines[i]
            if (line == address) continue
            if (fullNamePattern.matches(line)) {
                val words = line.trim().split("\\s+".toRegex())
                if (words.none { it in excludeWords }) {
                    return line.trim()
                }
            }
        }

        // 1순위: 이름+전화번호가 같은 줄에 있는 경우
        for (line in lines) {
            val match = nameWithPhonePattern.find(line)
            if (match != null) {
                val name = match.groupValues[1].trim()
                if (name !in excludeWords) return name
            }
        }

        // 후보 수집
        data class Candidate(val name: String, val lineIndex: Int, val isFullLine: Boolean)
        val candidates = mutableListOf<Candidate>()

        for ((index, line) in lines.withIndex()) {
            if (line == address) continue
            if (index == phoneLineIndex) continue
            if (line.contains("KRW", ignoreCase = true)) continue
            if (isAddressRomanization(line)) continue

            if (englishNamePattern.matches(line)) {
                val name = line.trim()
                val words = name.split("\\s+".toRegex())
                if (words.none { it in excludeWords } && name.toSet().count { !it.isWhitespace() } >= 2) {
                    candidates.add(Candidate(name, index, true))
                }
            } else {
                val match = singleNamePattern.find(line) ?: continue
                val name = match.groupValues[1]
                if (name !in excludeWords) {
                    candidates.add(Candidate(name, index, false))
                }
            }
        }

        if (candidates.isEmpty()) return if (hasForeignScript(lines)) "외국인" else ""

        // 2순위: 전화번호 근처 본문 이름 (±4줄 이내)
        if (phoneLineIndex >= 0) {
            val bodyRange = 4
            val bodyCandidates = candidates
                .filter { abs(it.lineIndex - phoneLineIndex) <= bodyRange }
                .sortedWith(
                    compareBy<Candidate> { abs(it.lineIndex - phoneLineIndex) }
                        .thenByDescending { it.isFullLine }
                )

            if (bodyCandidates.isNotEmpty()) {
                return bodyCandidates.first().name
            }

            // 본문에 이름 없음 → 외국어 콘텐츠여도 상단 5줄에 2단어 이름 후보 있으면 우선
            // 예: "KhmKrich ThongNok", "iLai Chaitawong" (OCR이 첫 글자 잘린 경우 포함)
            val topCandidates = candidates
                .filter { it.lineIndex < 5 && it.isFullLine }
                .filter { it.name.trim().split("\\s+".toRegex()).size >= 2 }
            if (topCandidates.isNotEmpty()) return topCandidates.first().name

            if (hasForeignScript(lines)) return "외국인"

            // 3순위: 메신저 발신자 이름 (본문 밖)
            return candidates.first().name
        }

        // 전화번호 없음 → 상단 후보 우선
        val topCandidates = candidates
            .filter { it.lineIndex < 5 && it.isFullLine }
            .filter { it.name.trim().split("\\s+".toRegex()).size >= 2 }
        if (topCandidates.isNotEmpty()) return topCandidates.first().name

        if (hasForeignScript(lines)) return "외국인"
        return candidates.first().name
    }

    private fun isAddressRomanization(line: String): Boolean {
        val lower = line.lowercase()
        return lower.contains("-gil") || lower.contains("-ro ") ||
                lower.contains("-dong") || lower.contains("beon-gil") ||
                lower.contains("-daero")
    }

    private fun hasForeignScript(lines: List<String>): Boolean {
        return lines.any { line ->
            line.any { char ->
                char in '\u0E00'..'\u0E7F' ||  // Thai
                char in '\u1780'..'\u17FF' ||  // Khmer
                char in '\u1000'..'\u109F' ||  // Myanmar
                char in '\u0E80'..'\u0EFF'     // Lao
            }
        }
    }
}
