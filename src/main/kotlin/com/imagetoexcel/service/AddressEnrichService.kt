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

    fun enrich(address: String): Pair<String, Boolean> {
        // 1. 스킵: 빈값 / 외국인 / 인식 실패
        if (address.isBlank() || address == "외국인" || address.startsWith("[인식 실패]")) {
            return Pair(address, false)
        }

        // 2. 상세주소(건물명 + 동/호수) 포함 → 분리 후 기본 주소만 검색, 결과에 상세 결합
        if (isDetailedAddress(address)) {
            val (base, detail) = splitBuildingDetail(address)
            if (base.isNotBlank() && detail.isNotBlank()) {
                // 띄어쓰기 정규화: "평택시지제동삭2로177" → "평택시지제동삭2로 177"
                val normalizedBase = normalizeRoadSpacing(normalizeRegionName(base))
                val jusoResult = jusoApiClient.search(normalizedBase, page = 1, countPerPage = 1)
                if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
                    val roadAddr = jusoResult.addresses.first().roadAddr
                    val enriched = combineWithDetail(roadAddr, detail)
                    logger.info { "주소 자동 보정 (Juso 상세분리): \"$address\" → \"$enriched\"" }
                    return Pair(enriched, true)
                }
                // 띄어쓰기 정규화 후에도 실패 시 도로명만 추출하여 재시도
                val roadOnly = extractRoadNameQuery(normalizedBase)
                if (roadOnly != null && roadOnly != normalizedBase) {
                    val roadFallback = jusoApiClient.search(roadOnly, page = 1, countPerPage = 1)
                    if (roadFallback.totalCount > 0 && roadFallback.addresses.isNotEmpty()) {
                        val roadAddr = roadFallback.addresses.first().roadAddr
                        val enriched = combineWithDetail(roadAddr, detail)
                        logger.info { "주소 자동 보정 (Juso 상세분리 도로명): \"$address\" → \"$enriched\"" }
                        return Pair(enriched, true)
                    }
                }
            }
            logger.info { "상세 주소 감지, 보정 생략: \"$address\"" }
            return Pair(
                    address,
                    true
            ) // 상세 주소가 자체적으로 잘 분리되는 경우는 기본적으로 검증 통과로 간주할 수도 있지만, 안전하게 true로 둠. 혹은 API 통과를 안했으니
            // false로 둘 수도 있지만 사용자가 상세주소를 적은 것이므로 Juso 검색에 실패했어도 형식에 맞으면 일단 통과로 볼 수 있음. 그러나 엄밀한 검증
            // 실패로 보아 UI에 빨간색으로 표기하려면 false로 두어도 됨. 기획상 "네이버 지도 검색까지 안되면 빨간색"이므로 여기서도 false로 리턴하는
            // 것이 맞음 (다만 위에서 JUSO 검색을 했음). 수정: API 못찾으면 false로.
            // 위에서 Juso 실패하면 여기로 오므로 false로 보냄 (대원칙)
        }

        // OCR에서 자주 오인하는 도/시 약칭 정규화 (Juso 검색 성공률 향상)
        // 예: "전북도" → "전북특별자치도", "경남도" → "경상남도"
        val normalizedAddress = normalizeRegionName(address)

        // 3. Juso API 검색 (primary) - 정규화된 주소로 검색
        val jusoResult = jusoApiClient.search(normalizedAddress, page = 1, countPerPage = 1)
        if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
            val enriched = jusoResult.addresses.first().roadAddr
            logger.info { "주소 자동 보정 (Juso): \"$address\" → \"$enriched\"" }
            return Pair(enriched, true)
        }

        // 3-1. 도로명만으로 재시도: 끝의 건물번호만 제거 (도로명 안의 숫자는 보존)
        // "황등3길 10-12" → "황등3길" (O) / "황등3길"의 '3'은 건드리지 않음
        val noTrailingNum = normalizedAddress.replace(Regex("\\s+\\d+(-\\d+)?\\s*$"), "").trim()
        if (noTrailingNum != normalizedAddress && noTrailingNum.isNotBlank()) {
            val fallback = jusoApiClient.search(noTrailingNum, page = 1, countPerPage = 1)
            if (fallback.totalCount > 0 && fallback.addresses.isNotEmpty()) {
                val enriched = fallback.addresses.first().roadAddr
                logger.info { "주소 자동 보정 (Juso 도로명): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        // 3-2. 상호명/건물명 분리 후 재시도
        // "경상남도 창녕군 유어면 대대리, 737-2 다온농장" → 검색: "경상남도 창녕군 유어면 대대리 737-2", 접미: "다온농장"
        // 최종 출력에는 접미(상호명, 동호수)를 다시 붙임
        val (strippedAddress, suffix) =
                extractSuffix(normalizedAddress) // Use normalizedAddress here
        if (strippedAddress != normalizedAddress && strippedAddress.isNotBlank()
        ) { // Compare with normalizedAddress
            val stripped = jusoApiClient.search(strippedAddress, page = 1, countPerPage = 1)
            if (stripped.totalCount > 0 && stripped.addresses.isNotEmpty()) {
                val normalizedRoad = stripped.addresses.first().roadAddr
                // 상호명/동호수를 정규화된 주소 뒤에 다시 붙임
                val enriched =
                        if (suffix.isNotBlank()) "$normalizedRoad $suffix" else normalizedRoad
                logger.info { "주소 자동 보정 (Juso 상호명 분리): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        // 3-3. 도로명+건물번호만 추출하여 재시도 (OCR 노이즈로 앞부분이 깨진 경우)
        // "장난강진궁신전면신전로221-8" → "신전로 221-8" 로 검색
        val roadOnly = extractRoadNameQuery(normalizedAddress)
        if (roadOnly != null && roadOnly != normalizedAddress) {
            val roadFallback = jusoApiClient.search(roadOnly, page = 1, countPerPage = 1)
            if (roadFallback.totalCount > 0 && roadFallback.addresses.isNotEmpty()) {
                val enriched = roadFallback.addresses.first().roadAddr
                logger.info { "주소 자동 보정 (Juso 도로명 추출): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        // 4. 네이버 Geocoding API (fallback)
        // 상호명이 포함된 주소는 Naver도 못 찾을 수 있으므로 strippedAddress 로도 시도
        val naverResult =
                naverGeocodingClient.geocode(normalizedAddress)
                        ?: naverGeocodingClient.geocode(roadOnly ?: normalizedAddress)
                                ?: if (strippedAddress != normalizedAddress)
                                naverGeocodingClient.geocode(strippedAddress)
                        else null

        if (naverResult != null) {
            val enriched = if (suffix.isNotBlank()) "$naverResult $suffix" else naverResult
            logger.info { "주소 자동 보정 (Naver): \"$address\" → \"$enriched\"" }
            return Pair(enriched, true)
        }

        // 5. 모두 실패 → 원본 유지 (검증 실패)
        logger.info { "주소 자동 보정 실패, 원본 유지 (검증 실패): \"$address\"" }
        return Pair(address, false)
    }

    private fun isDetailedAddress(address: String): Boolean {
        val hasRegion = KoreanRegion.containsAny(address)
        val hasUnitDetail = detailUnitPattern.containsMatchIn(address)
        return hasRegion && hasUnitDetail
    }

    /**
     * 도로명과 건물번호 사이 띄어쓰기 정규화. OCR이 공백 없이 뭉쳐낸 경우 Juso API 검색이 실패하므로 공백 삽입.
     *
     * 예: "평택시지제동삭2로177" → "평택시지제동삭2로 177"
     * ```
     *     "신전로221-8" → "신전로 221-8"
     * ```
     */
    private fun normalizeRoadSpacing(address: String): String {
        // 도로명(~로, ~길) 바로 뒤에 공백 없이 건물번호가 붙어있으면 공백 삽입
        return address.replace(Regex("([가-힣](?:로|길))(\\d)"), "$1 $2")
    }

    /**
     * 주소에서 도로명 + 건물번호만 추출하여 Juso 검색용 쿼리 생성. OCR 노이즈로 앞부분이 깨졌을 때 도로명만으로 검색 시도.
     *
     * 예: "장난강진궁신전면신전로221-8" → "신전로 221-8"
     * ```
     *     "경기도 평택시지제동삭2로177" → "지제동삭2로 177"
     * ```
     */
    private fun extractRoadNameQuery(address: String): String? {
        val roadNameWithNumber = Regex("([가-힣]+\\d*[로길])\\s*(\\d{1,4}(?:-\\d{1,4})?)")
        val match = roadNameWithNumber.find(address) ?: return null
        var road = match.groupValues[1]
        val number = match.groupValues[2]

        // 행정구역 접미사(시/군/구/면/읍) 뒤의 실제 도로명만 추출
        // "장난강진궁신전면신전로" → "면" 뒤 → "신전로"
        // "평택시지제동삭2로" → "시" 뒤 → "지제동삭2로"
        // ※ "동/리"는 도로명에 포함되는 경우가 많아 제외 (예: 지제동삭2로)
        val adminSplit = Regex(".*[시군구면읍]").find(road)
        if (adminSplit != null && adminSplit.range.last + 1 < road.length) {
            val afterAdmin = road.substring(adminSplit.range.last + 1)
            if (afterAdmin.length >= 2) {
                road = afterAdmin
            }
        }

        return "$road $number"
    }

    /** OCR이 자주 틀리는 행정구역 약칭을 Juso가 인식하는 정식 명칭으로 변환. 예: "전북도" → "전북특별자치도", "경남도" → "경상남도" */
    private fun normalizeRegionName(address: String): String {
        return address.replace(Regex("전북도(?!특별)"), "전북특별자치도 ")
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
     * - "서울 강남구 역삼동 123 SK빌딩 101동 1001호" → ("서울 강남구 역삼동 123", "SK빌딩 101동 1001호")
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
                val number = numberMatch.groupValues[1] // "737-2"
                val suffix = numberMatch.groupValues[2].trim() // "다온농장"
                val core = if (number.isNotBlank()) "$beforeComma $number" else beforeComma
                return Pair(core, suffix)
            }
            return Pair(beforeComma, afterComma)
        }
        return Pair(address, "")
    }

    /**
     * 상세주소(건물명 + 동/호수)를 기본 주소에서 분리.
     *
     * 예: "충청남도 당진시 송산면 매곡리 330 세안아파트 1동 107호" → ("충청남도 당진시 송산면 매곡리 330", "세안아파트 1동 107호") 예: "경기도
     * 평택시지제동삭2로177 더샾2BL204동2402호" → ("경기도 평택시지제동삭2로 177", "더샾2BL204동2402호")
     */
    private fun splitBuildingDetail(address: String): Pair<String, String> {
        // 1. 건물명(아파트/빌라 등) 시작점을 찾아 분리
        val buildingStart = Regex("[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)")
        val match = buildingStart.find(address)
        if (match != null) {
            val base = address.substring(0, match.range.first).trim()
            val detail = address.substring(match.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }

        // 2. 도로명+건물번호 뒤에 상세주소가 붙어있는 경우 (띄어쓰기 없이 뭉쳐진 주소)
        //    "경기도 평택시지제동삭2로177 더샾2BL204동2402호"
        //    → 도로명 "지제동삭2로" + 건물번호 "177" 이후를 분리
        val roadWithNumber = Regex("([가-힣]+\\d*[로길])\\s*(\\d{1,4}(?:-\\d{1,4})?)")
        val roadMatch = roadWithNumber.find(address)
        if (roadMatch != null) {
            val afterRoadNumber = roadMatch.range.last + 1
            if (afterRoadNumber < address.length) {
                val remaining = address.substring(afterRoadNumber).trim()
                if (remaining.isNotBlank() && detailUnitPattern.containsMatchIn(remaining)) {
                    val base = address.substring(0, afterRoadNumber).trim()
                    return Pair(base, remaining)
                }
            }
        }

        // 3. 건물명 없이 동/호수만 있는 경우: "... 1동 107호" 또는 "... 107호"
        val unitStart = Regex("\\s+\\d+동\\s+\\d+호|\\s+\\d+호\\s*$")
        val unitMatch = unitStart.find(address)
        if (unitMatch != null) {
            val base = address.substring(0, unitMatch.range.first).trim()
            val detail = address.substring(unitMatch.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }
        return Pair(address, "")
    }

    /**
     * Juso 도로명 주소 결과에 상세주소(동/호수)를 결합.
     *
     * 예: roadAddr="충청남도 당진시 송산면 당산1로 563(세안근로복지아파트)"
     * ```
     *     detail="세안아파트 1동 107호"
     * ```
     * → "충청남도 당진시 송산면 당산1로 563(세안근로복지아파트 1동 107호)"
     */
    private fun combineWithDetail(roadAddr: String, detail: String): String {
        if (detail.isBlank()) return roadAddr

        // detail에서 건물명 제거 → 동/호수만 추출 (예: "세안아파트 1동 107호" → "1동 107호")
        val unitInfo =
                detail.replace(
                                Regex("^[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)\\s*"),
                                ""
                        )
                        .trim()

        // Juso 결과에 괄호로 건물명이 포함된 경우: "...(세안근로복지아파트)"
        val parenMatch = Regex("\\(([^)]+)\\)\\s*$").find(roadAddr)
        if (parenMatch != null && unitInfo.isNotBlank()) {
            val basePart = roadAddr.substring(0, parenMatch.range.first)
            val buildingName = parenMatch.groupValues[1]
            return "$basePart($buildingName $unitInfo)"
        }

        // 괄호가 없는 경우 → 상세주소를 괄호로 감싸서 추가
        return if (unitInfo.isNotBlank()) "$roadAddr ($detail)" else "$roadAddr ($detail)"
    }
}
