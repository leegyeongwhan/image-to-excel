package com.imagetoexcel.service

import com.imagetoexcel.infrastructure.JusoApiClient
import com.imagetoexcel.infrastructure.NaverGeocodingClient
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

private val logger = KotlinLogging.logger {}

/**
 * 검색 전략 하나를 나타냅니다.
 * @param query       Juso/Naver API에 보낼 검색어
 * @param suffix      검색 성공 시 결과 뒤에 붙일 텍스트 (상호명 등). 없으면 빈 문자열.
 * @param strictMatch true이면 검색 결과가 정확히 1건일 때만 채택 (도로명만 검색 시 여러 도시 중복 방지)
 */
private data class SearchStrategy(val query: String, val suffix: String = "", val strictMatch: Boolean = false)

/**
 * 주소 보정 서비스.
 * AddressParser로 주소를 변환하고, Juso/Naver API로 검증합니다.
 */
@Service
class AddressEnrichService(
        private val jusoApiClient: JusoApiClient,
        private val naverGeocodingClient: NaverGeocodingClient,
        private val parser: AddressParser
) {

    /**
     * 주소 정보를 받아 API 검색을 통해 보정된 주소와 유효성 여부를 반환합니다.
     * @return Pair<보정된 주소, 유효성 성공 여부>
     */
    fun enrich(address: String): Pair<String, Boolean> {
        // 1. 스킵 조건
        if (address.isBlank() || address == "외국인" || address.startsWith("[인식 실패]")) {
            return Pair(address, false)
        }

        // 2. 상세 주소(건물명 + 동/호수)가 포함된 경우 별도 처리
        if (parser.isDetailedAddress(address)) {
            return enrichDetailedAddress(address)
        }

        // 3. 주소 정규화
        val normalized = parser.normalizeRegionName(address)
        val cleaned = parser.cleanForSearch(normalized)
        val (stripped, suffix) = parser.extractSuffix(normalized)
        val roadOnly = parser.extractRoadNameQuery(normalized)
        val cleanedRoadOnly = parser.extractRoadNameQuery(cleaned)
        val noTrailingNum = parser.removeTrailingNumber(normalized)

        // 4. Juso API 순차 검색
        val jusoStrategies = buildJusoStrategies(
                normalized, cleaned, noTrailingNum, stripped, suffix, roadOnly, cleanedRoadOnly
        )
        for (strategy in jusoStrategies) {
            val result = jusoApiClient.search(strategy.query, page = 1, countPerPage = 1)
            if (result.totalCount > 0 && result.addresses.isNotEmpty()) {
                // 도로명만으로 검색한 경우, 여러 지역에 동일 도로명이 있으면 스킵 (잘못된 도시 매칭 방지)
                if (strategy.strictMatch && result.totalCount > 1) {
                    logger.info { "도로명 중복 감지, 스킵: \"${strategy.query}\" (${result.totalCount}건)" }
                    continue
                }
                val roadAddr = result.addresses.first().roadAddr
                val enriched = if (strategy.suffix.isNotBlank()) "$roadAddr ${strategy.suffix}" else roadAddr
                logger.info { "주소 자동 보정 (Juso): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        // 5. Naver API 순차 검색 (Juso 전체 실패 시)
        // 단, 행정구역 없는 주소는 Naver도 스킵 (Naver도 임의 도시를 선택하므로)
        val noAdmin = !parser.hasAdminContext(normalized)
        if (!noAdmin) {
            val naverQueries = buildNaverQueries(normalized, cleaned, roadOnly, stripped)
            for (query in naverQueries) {
                val naverResult = naverGeocodingClient.geocode(query)
                if (naverResult != null) {
                    val enriched = if (suffix.isNotBlank()) "$naverResult $suffix" else naverResult
                    logger.info { "주소 자동 보정 (Naver): \"$address\" → \"$enriched\"" }
                    return Pair(enriched, true)
                }
            }
        } else {
            logger.info { "행정구역 없는 주소, Naver 폴백도 스킵: \"$address\"" }
        }

        logger.info { "주소 자동 보정 실패, 원본 유지 (검증 실패): \"$address\"" }
        return Pair(address, false)
    }

    // ========================
    // 검색 전략 목록 생성
    // ========================

    private fun buildJusoStrategies(
            normalized: String,
            cleaned: String,
            noTrailingNum: String,
            stripped: String,
            suffix: String,
            roadOnly: String?,
            cleanedRoadOnly: String?
    ): List<SearchStrategy> = buildList {
        // 행정구역(시/도/군/구/면/읍)이 없으면 모든 전략에 strictMatch 적용
        // 예: "중앙로 188" → 여러 도시에 존재하므로 결과 1건일 때만 채택
        val noAdmin = !parser.hasAdminContext(normalized)

        // 1) 전체 주소 그대로
        add(SearchStrategy(normalized, strictMatch = noAdmin))

        // 2) 노이즈(괄호, 층수, 번지) 제거
        if (cleaned != normalized && cleaned.isNotBlank()) {
            add(SearchStrategy(cleaned, strictMatch = noAdmin))
        }

        // 3) 뒤 번호 제거 (예: "대청로 59번길 15-1" → "대청로 59번길")
        if (noTrailingNum != normalized && noTrailingNum.isNotBlank()) {
            add(SearchStrategy(noTrailingNum, strictMatch = noAdmin))
        }

        // 4) 쉼표 기준 상호명 분리 (예: "창서길 52-200, 다온농장" → 검색: "창서길 52-200", 붙임: "다온농장")
        if (stripped != normalized && stripped.isNotBlank()) {
            add(SearchStrategy(stripped, suffix, strictMatch = noAdmin))
        }

        // 5) 하위 행정구역 조합 (예: "함평군 창서길 52-200")
        parser.extractSubAdminQueries(normalized).forEach { add(SearchStrategy(it, strictMatch = noAdmin)) }

        // 6) 도로명 + 건물번호만 (예: "창서길 52-200") — 항상 strict
        if (roadOnly != null && roadOnly != normalized) {
            add(SearchStrategy(roadOnly, strictMatch = true))
        }

        // 7) 정리된 도로명 — 항상 strict
        if (cleanedRoadOnly != null && cleanedRoadOnly != roadOnly) {
            add(SearchStrategy(cleanedRoadOnly, strictMatch = true))
        }
    }

    private fun buildNaverQueries(
            normalized: String,
            cleaned: String,
            roadOnly: String?,
            stripped: String
    ): List<String> = buildList {
        add(normalized)
        if (cleaned != normalized) add(cleaned)
        addAll(parser.extractSubAdminQueries(normalized))
        add(roadOnly ?: normalized)
        if (stripped != normalized) add(stripped)
    }

    // ========================
    // 상세 주소 처리
    // ========================

    private fun enrichDetailedAddress(address: String): Pair<String, Boolean> {
        val (base, detail) = parser.splitBuildingDetail(address)
        if (base.isBlank() || detail.isBlank()) {
            logger.info { "상세 주소 감지, 보정 생략: \"$address\"" }
            return Pair(address, false)
        }

        val normalizedBase = parser.normalizeRoadSpacing(parser.normalizeRegionName(base))

        // 기본 주소로 검색
        val jusoResult = jusoApiClient.search(normalizedBase, page = 1, countPerPage = 1)
        if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
            val enriched = parser.combineWithDetail(jusoResult.addresses.first().roadAddr, detail)
            logger.info { "주소 자동 보정 (Juso 상세분리): \"$address\" → \"$enriched\"" }
            return Pair(enriched, true)
        }

        // 도로명만으로 재시도
        val roadOnly = parser.extractRoadNameQuery(normalizedBase)
        if (roadOnly != null && roadOnly != normalizedBase) {
            val roadFallback = jusoApiClient.search(roadOnly, page = 1, countPerPage = 1)
            if (roadFallback.totalCount > 0 && roadFallback.addresses.isNotEmpty()) {
                val enriched = parser.combineWithDetail(roadFallback.addresses.first().roadAddr, detail)
                logger.info { "주소 자동 보정 (Juso 상세분리 도로명): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        logger.info { "상세 주소 감지, 보정 생략: \"$address\"" }
        return Pair(address, false)
    }
}
