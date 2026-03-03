package com.imagetoexcel.service

import com.imagetoexcel.domain.enum.KoreanRegion
import org.springframework.stereotype.Component

/**
 * 주소 문자열의 파싱, 정규화, 분리를 담당합니다.
 * API 호출 없이 순수 문자열 변환만 수행합니다.
 */
@Component
class AddressParser {

    private val detailUnitPattern = Regex("\\d+호|\\d+동\\s*\\d|\\d+층")
    private val roadNamePattern = Regex("([가-힣]+\\d*[로길])\\s*(\\d{1,4}(?:-\\d{1,4})?)")

    // ========================
    // 주소 판별
    // ========================

    /** 상세주소(건물명 + 동/호수)가 포함되어 있는지 확인합니다. */
    fun isDetailedAddress(address: String): Boolean {
        return KoreanRegion.containsAny(address) && detailUnitPattern.containsMatchIn(address)
    }

    // ========================
    // 주소 정규화 (원본 → 검색 가능한 형태)
    // ========================

    /** OCR이 자주 틀리는 행정구역 약칭을 정식 명칭으로 변환합니다. (예: "전북도" → "전북특별자치도") */
    fun normalizeRegionName(address: String): String {
        return address
                .replace(Regex("전북도(?!특별)"), "전북특별자치도 ")
                .replace("전라북도 ", "전북특별자치도 ")
                .replace(Regex("전남도(?!라)"), "전라남도 ")
                .replace(Regex("경남도(?!상)"), "경상남도 ")
                .replace(Regex("경북도(?!상)"), "경상북도 ")
                .replace(Regex("충북도(?!청)"), "충청북도 ")
                .replace(Regex("충남도(?!청)"), "충청남도 ")
                .replace("강원도 ", "강원특별자치도 ")
                .trim()
    }

    /** 도로명과 건물번호 사이 띄어쓰기를 정규화합니다. (예: "삭2로177" → "삭2로 177") */
    fun normalizeRoadSpacing(address: String): String {
        return address.replace(Regex("([가-힣](?:로|길))(\\d)"), "$1 $2")
    }

    /** 검색용으로 주소에서 노이즈를 제거합니다. (괄호 내용, 층수, 번지 등) */
    fun cleanForSearch(address: String): String {
        return address
                .replace(Regex("\\([^)]*\\)"), "")              // 괄호 내용 제거
                .replace(Regex("\\s+\\d+[Ff]\\b"), "")          // 층수 (3F 등)
                .replace(Regex("\\s+[Bb]\\d+\\b"), "")          // 지하층 (B1 등)
                .replace(Regex("\\d+층"), "")                    // N층
                .replace("번지", "")                              // 번지 제거
                .replace(Regex("([동리])([0-9])"), "$1 $2")      // 사창동492 → 사창동 492
                .replace(Regex("\\s+"), " ")                     // 중복 공백 제거
                .trim()
    }

    /** 뒤쪽 건물번호를 제거합니다. (예: "대청로 59번길 15-1" → "대청로 59번길") */
    fun removeTrailingNumber(address: String): String {
        return address.replace(Regex("\\s+\\d+(-\\d+)?\\s*$"), "").trim()
    }

    // ========================
    // 주소 분리/추출
    // ========================

    /** 주소에서 도로명 + 건물번호만 추출합니다. (예: "함평군 해보면 창서길 52-200" → "창서길 52-200") */
    fun extractRoadNameQuery(address: String): String? {
        val match = roadNamePattern.find(address) ?: return null
        var road = match.groupValues[1]
        val number = match.groupValues[2]

        // 행정구역 접미사(시/군/구/면/읍) 뒤의 실제 도로명만 추출
        val adminSplit = Regex(".*[시군구면읍]").find(road)
        if (adminSplit != null && adminSplit.range.last + 1 < road.length) {
            val afterAdmin = road.substring(adminSplit.range.last + 1)
            if (afterAdmin.length >= 2) road = afterAdmin
        }

        return "$road $number"
    }

    /**
     * 전체 주소에서 행정구역을 단계별로 줄인 검색 후보를 생성합니다.
     * 예: "전라남도 함평군 해보면 창서길 52-200" →
     *   ["함평군 해보면 창서길 52-200", "함평군 창서길 52-200", "해보면 창서길 52-200"]
     */
    fun extractSubAdminQueries(address: String): List<String> {
        val roadMatch = roadNamePattern.find(address) ?: return emptyList()
        val roadPart = address.substring(roadMatch.range.first).trim()
        val beforeRoad = address.substring(0, roadMatch.range.first).trim()
        val adminUnits = beforeRoad.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (adminUnits.size < 2) return emptyList()

        return buildList {
            // 도(道) 제거: "함평군 해보면 창서길 52-200"
            add(adminUnits.drop(1).joinToString(" ") + " " + roadPart)

            // 시/군/구 + 도로명만: "함평군 창서길 52-200"
            val cityCounty = adminUnits.find { it.endsWith("시") || it.endsWith("군") || it.endsWith("구") }
            if (cityCounty != null) add("$cityCounty $roadPart")

            // 면/읍/동 + 도로명만: "해보면 창서길 52-200"
            val subDistrict = adminUnits.find { it.endsWith("면") || it.endsWith("읍") || it.endsWith("동") }
            if (subDistrict != null && subDistrict != cityCounty) add("$subDistrict $roadPart")
        }.distinct().filter { it != address }
    }

    /** 주소에서 검색용 핵심 주소와 접미(상호명/동호수)를 쉼표 기준으로 분리합니다. */
    fun extractSuffix(address: String): Pair<String, String> {
        if (!address.contains(",")) return Pair(address, "")

        val beforeComma = address.substringBefore(",").trim()
        val afterComma = address.substringAfter(",").trim()
        val numberMatch = Regex("^(\\d+(?:-\\d+)?)\\s*(.*)$").find(afterComma)

        return if (numberMatch != null) {
            val number = numberMatch.groupValues[1]
            val suffix = numberMatch.groupValues[2].trim()
            val core = if (number.isNotBlank()) "$beforeComma $number" else beforeComma
            Pair(core, suffix)
        } else {
            Pair(beforeComma, afterComma)
        }
    }

    // ========================
    // 상세 주소 (건물명 + 동/호수) 처리
    // ========================

    /** 주소에서 기본 주소와 상세 정보(건물명, 동/호수)를 분리합니다. */
    fun splitBuildingDetail(address: String): Pair<String, String> {
        // 1) 건물명으로 분리 (아파트, 빌라 등)
        val buildingStart = Regex("[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)")
        buildingStart.find(address)?.let {
            val base = address.substring(0, it.range.first).trim()
            val detail = address.substring(it.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }

        // 2) 도로명+건물번호 뒤의 동/호수로 분리
        roadNamePattern.find(address)?.let {
            val afterRoadNumber = it.range.last + 1
            if (afterRoadNumber < address.length) {
                val remaining = address.substring(afterRoadNumber).trim()
                if (remaining.isNotBlank() && detailUnitPattern.containsMatchIn(remaining)) {
                    val base = address.substring(0, afterRoadNumber).trim()
                    return Pair(base, remaining)
                }
            }
        }

        // 3) 끝부분의 동/호수로 분리
        Regex("\\s+\\d+동\\s+\\d+호|\\s+\\d+호\\s*$").find(address)?.let {
            val base = address.substring(0, it.range.first).trim()
            val detail = address.substring(it.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }

        return Pair(address, "")
    }

    /** API 결과(도로명주소)에 상세 정보(동/호수)를 결합합니다. */
    fun combineWithDetail(roadAddr: String, detail: String): String {
        if (detail.isBlank()) return roadAddr

        val unitInfo = detail
                .replace(Regex("^[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)\\s*"), "")
                .trim()
        val parenMatch = Regex("\\(([^)]+)\\)\\s*$").find(roadAddr)

        if (parenMatch != null && unitInfo.isNotBlank()) {
            val basePart = roadAddr.substring(0, parenMatch.range.first)
            val buildingName = parenMatch.groupValues[1]
            return "$basePart($buildingName $unitInfo)"
        }

        return "$roadAddr ($detail)"
    }
}
