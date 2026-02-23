package com.imagetoexcel.service

import com.imagetoexcel.domain.enum.KoreanRegion
import com.imagetoexcel.infrastructure.JusoApiClient
import com.imagetoexcel.infrastructure.NaverGeocodingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

@Service
class AddressEnrichService(
    private val jusoApiClient: JusoApiClient,
    private val naverGeocodingClient: NaverGeocodingClient
) {

    private val detailUnitPattern = Regex("\\d+호|\\d+동\\s*\\d|\\d+층")

    fun enrich(address: String): String {
        // 1. 스킵: 빈값 / 외국인 / 인식 실패
        if (address.isBlank() || address == "외국인" || address.startsWith("[인식 실패]")) {
            return address
        }

        // 2. 스킵: 상세주소 (지역명 + 동/호수 → 이미 완전한 주소)
        if (isDetailedAddress(address)) {
            logger.info { "상세 주소 감지, 보정 생략: \"$address\"" }
            return address
        }

        // OCR에서 자주 오인하는 도/시 약칭 정규화 (Juso 검색 성공률 향상)
        // 예: "전북도" → "전북특별자치도", "경남도" → "경상남도"
        val normalizedAddress = normalizeRegionName(address)

        // 3. Juso API 검색 (primary) - 정규화된 주소로 검색
        val jusoResult = jusoApiClient.search(normalizedAddress, page = 1, countPerPage = 1)
        if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
            val enriched = jusoResult.addresses.first().roadAddr
            logger.info { "주소 자동 보정 (Juso): \"$address\" → \"$enriched\"" }
            return enriched
        }

        // 3-1. 도로명만으로 재시도: 끝의 건물번호만 제거 (도로명 안의 숫자는 보존)
        // "황등3길 10-12" → "황등3길" (O) / "황등3길"의 '3'은 건드리지 않음
        val noTrailingNum = normalizedAddress.replace(Regex("\\s+\\d+(-\\d+)?\\s*$"), "").trim()
        if (noTrailingNum != normalizedAddress && noTrailingNum.isNotBlank()) {
            val fallback = jusoApiClient.search(noTrailingNum, page = 1, countPerPage = 1)
            if (fallback.totalCount > 0 && fallback.addresses.isNotEmpty()) {
                val enriched = fallback.addresses.first().roadAddr
                logger.info { "주소 자동 보정 (Juso 도로명): \"$address\" → \"$enriched\"" }
                return enriched
            }
        }

        // 3-2. 상호명/건물명 분리 후 재시도
        // "경상남도 창녕군 유어면 대대리, 737-2 다온농장" → 검색: "경상남도 창녕군 유어면 대대리 737-2", 접미: "다온농장"
        // 최종 출력에는 접미(상호명, 동호수)를 다시 붙임
        val (strippedAddress, suffix) = extractSuffix(normalizedAddress) // Use normalizedAddress here
        if (strippedAddress != normalizedAddress && strippedAddress.isNotBlank()) { // Compare with normalizedAddress
            val stripped = jusoApiClient.search(strippedAddress, page = 1, countPerPage = 1)
            if (stripped.totalCount > 0 && stripped.addresses.isNotEmpty()) {
                val normalizedRoad = stripped.addresses.first().roadAddr
                // 상호명/동호수를 정규화된 주소 뒤에 다시 붙임
                val enriched = if (suffix.isNotBlank()) "$normalizedRoad $suffix" else normalizedRoad
                logger.info { "주소 자동 보정 (Juso 상호명 분리): \"$address\" → \"$enriched\"" }
                return enriched
            }
        }

        // 4. 네이버 Geocoding API (fallback)
        // 상호명이 포함된 주소는 Naver도 못 찾을 수 있으므로 strippedAddress 로도 시도
        val naverResult = naverGeocodingClient.geocode(normalizedAddress)
            ?: if (strippedAddress != normalizedAddress) naverGeocodingClient.geocode(strippedAddress) else null

        if (naverResult != null) {
            val enriched = if (suffix.isNotBlank()) "$naverResult $suffix" else naverResult
            logger.info { "주소 자동 보정 (Naver): \"$address\" → \"$enriched\"" }
            return enriched
        }

        // 5. 모두 실패 → 원본 유지
        logger.info { "주소 자동 보정 실패, 원본 유지: \"$address\"" }
        return address
    }

    private fun isDetailedAddress(address: String): Boolean {
        val hasRegion = KoreanRegion.containsAny(address)
        val hasUnitDetail = detailUnitPattern.containsMatchIn(address)
        return hasRegion && hasUnitDetail
    }

    /**
     * OCR이 자주 틀리는 행정구역 약칭을 Juso가 인식하는 정식 명칭으로 변환.
     * 예: "전북도" → "전북특별자치도", "경남도" → "경상남도"
     */
    private fun normalizeRegionName(address: String): String {
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

    /**
     * 주소에서 검색용 핵심 주소와 붙여야 할 접미(상호명/동호수)를 분리.
     *
     * 예시:
     * - "경상남도 창녕군 유어면 대대리, 737-2 다온농장" → ("경상남도 창녕군 유어면 대대리 737-2", "다온농장")
     * - "서울 강남구 역삼동 123 SK빌딩 101동 1001호"  → ("서울 강남구 역삼동 123", "SK빌딩 101동 1001호")
     *
     * Juso 검색은 핵심 주소로 하되, 최종 결과에는 접미를 다시 붙여 상호명/동호수 정보 보존.
     */
    private fun extractSuffix(address: String): Pair<String, String> {
        // 쉼표 기준: "대대리, 737-2 다온농장" → 핵심=대대리 737-2, 접미=다온농장
        if (address.contains(",")) {
            val beforeComma = address.substringBefore(",").trim()
            val afterComma = address.substringAfter(",").trim()
            val numberMatch = Regex("^(\\d+(?:-\\d+)?)\\s*(.*)$").find(afterComma)
            if (numberMatch != null) {
                val number = numberMatch.groupValues[1]   // "737-2"
                val suffix = numberMatch.groupValues[2].trim()  // "다온농장"
                val core = if (number.isNotBlank()) "$beforeComma $number" else beforeComma
                return Pair(core, suffix)
            }
            return Pair(beforeComma, afterComma)
        }
        return Pair(address, "")
    }
}
