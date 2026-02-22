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

        // 3. Juso API 검색 (primary)
        val jusoResult = jusoApiClient.search(address, page = 1, countPerPage = 1)
        if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
            val enriched = jusoResult.addresses.first().roadAddr
            logger.info { "주소 자동 보정 (Juso): \"$address\" → \"$enriched\"" }
            return enriched
        }

        // 3-1. Juso 도로명만으로 재시도 (숫자 제거)
        val roadNameOnly = address.replace(Regex("\\s*\\d+[-\\d]*\\s*"), " ").trim()
        if (roadNameOnly != address && roadNameOnly.isNotBlank()) {
            val fallback = jusoApiClient.search(roadNameOnly, page = 1, countPerPage = 1)
            if (fallback.totalCount > 0 && fallback.addresses.isNotEmpty()) {
                val enriched = fallback.addresses.first().roadAddr
                logger.info { "주소 자동 보정 (Juso 도로명): \"$address\" → \"$enriched\"" }
                return enriched
            }
        }

        // 4. 네이버 Geocoding API (fallback)
        val naverResult = naverGeocodingClient.geocode(address)
        if (naverResult != null) {
            logger.info { "주소 자동 보정 (Naver): \"$address\" → \"$naverResult\"" }
            return naverResult
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
}
