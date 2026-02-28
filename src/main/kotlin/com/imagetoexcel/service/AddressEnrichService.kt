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

    /**
     * 주소 정보를 받아 정규화 및 API(Juso, Naver) 검색을 통해 보정된 주소와 유효성 여부를 반환합니다.
     * @param address 원본 주소 문자열
     * @return Pair<보정된 주소, 유효성 성공 여부>
     */
    fun enrich(address: String): Pair<String, Boolean> {
        // 1. 스킵 조건: 빈 문자열, "외국인", 인식 실패 메시지
        if (address.isBlank() || address == "외국인" || address.startsWith("[인식 실패]")) {
            return Pair(address, false)
        }

        // 2. 상세 주소(건물명 + 동/호수)가 포함된 경우 우선 처리
        // 기본 주소와 상세 주소를 분리하여 기본 주소만 검색 후 상세 결과를 결합합니다.
        if (isDetailedAddress(address)) {
            return enrichDetailedAddress(address)
        }

        // Juso API 검색을 위한 주소 정규화 및 변환 (행정구역명 약칭 복원, 도로명 추출 등)
        val normalizedAddress = normalizeRegionName(address)
        val (strippedAddress, suffix) = extractSuffix(normalizedAddress)
        val roadOnly = extractRoadNameQuery(normalizedAddress)
        val noTrailingNum = normalizedAddress.replace(Regex("\\s+\\d+(-\\d+)?\\s*$"), "").trim()

        // 3. Juso API 검색 전략 (순차적 폴백: 전체 -> 뒷번호제거 -> 상호명제거 -> 도로명만)
        val jusoStrategies =
                sequenceOf(
                        normalizedAddress to "",
                        (if (noTrailingNum != normalizedAddress && noTrailingNum.isNotBlank())
                                noTrailingNum
                        else null) to "",
                        (if (strippedAddress != normalizedAddress && strippedAddress.isNotBlank())
                                strippedAddress
                        else null) to suffix,
                        (if (roadOnly != null && roadOnly != normalizedAddress) roadOnly
                        else null) to ""
                )

        for ((query, currentSuffix) in jusoStrategies) {
            if (query == null) continue
            val result = jusoApiClient.search(query, page = 1, countPerPage = 1)
            if (result.totalCount > 0 && result.addresses.isNotEmpty()) {
                val roadAddr = result.addresses.first().roadAddr
                val enriched =
                        if (currentSuffix.isNotBlank()) "$roadAddr $currentSuffix" else roadAddr
                logger.info { "주소 자동 보정 (Juso): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        // 4. Naver Geocoding API 검색 전략 (최종 폴백)
        val naverStrategies =
                sequenceOf(
                        normalizedAddress,
                        roadOnly ?: normalizedAddress,
                        if (strippedAddress != normalizedAddress) strippedAddress else null
                )

        for (query in naverStrategies) {
            if (query == null) continue
            val naverResult = naverGeocodingClient.geocode(query)
            if (naverResult != null) {
                val enriched = if (suffix.isNotBlank()) "$naverResult $suffix" else naverResult
                logger.info { "주소 자동 보정 (Naver): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }
        }

        logger.info { "주소 자동 보정 실패, 원본 유지 (검증 실패): \"$address\"" }
        return Pair(address, false)
    }

    private fun enrichDetailedAddress(address: String): Pair<String, Boolean> {
        val (base, detail) = splitBuildingDetail(address)
        if (base.isNotBlank() && detail.isNotBlank()) {
            val normalizedBase = normalizeRoadSpacing(normalizeRegionName(base))

            val jusoResult = jusoApiClient.search(normalizedBase, page = 1, countPerPage = 1)
            if (jusoResult.totalCount > 0 && jusoResult.addresses.isNotEmpty()) {
                val enriched = combineWithDetail(jusoResult.addresses.first().roadAddr, detail)
                logger.info { "주소 자동 보정 (Juso 상세분리): \"$address\" → \"$enriched\"" }
                return Pair(enriched, true)
            }

            val roadOnly = extractRoadNameQuery(normalizedBase)
            if (roadOnly != null && roadOnly != normalizedBase) {
                val roadFallback = jusoApiClient.search(roadOnly, page = 1, countPerPage = 1)
                if (roadFallback.totalCount > 0 && roadFallback.addresses.isNotEmpty()) {
                    val enriched =
                            combineWithDetail(roadFallback.addresses.first().roadAddr, detail)
                    logger.info { "주소 자동 보정 (Juso 상세분리 도로명): \"$address\" → \"$enriched\"" }
                    return Pair(enriched, true)
                }
            }
        }
        logger.info { "상세 주소 감지, 보정 생략: \"$address\"" }
        return Pair(address, false)
    }

    /** 상세주소(건물명 + 동/호수)가 포함되어 있는지 확인합니다. */
    private fun isDetailedAddress(address: String): Boolean {
        return KoreanRegion.containsAny(address) && detailUnitPattern.containsMatchIn(address)
    }

    /** 도로명과 건물번호 사이 띄어쓰기를 정규화합니다. OCR이 공백 없이 뭉쳐낸 경우를 대비합니다. 예: "평택시지제동삭2로177" → "평택시지제동삭2로 177" */
    private fun normalizeRoadSpacing(address: String): String {
        return address.replace(Regex("([가-힣](?:로|길))(\\d)"), "$1 $2")
    }

    /** 주소에서 도로명 + 건물번호만 추출하여 Juso 검색용 쿼리를 생성합니다. 도로명 앞의 노이즈가 있는 경우 활용됩니다. */
    private fun extractRoadNameQuery(address: String): String? {
        val roadNameWithNumber = Regex("([가-힣]+\\d*[로길])\\s*(\\d{1,4}(?:-\\d{1,4})?)")
        val match = roadNameWithNumber.find(address) ?: return null
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

    /** OCR이 자주 틀리는 행정구역 약칭을 정식 명칭으로 변환합니다. (예: "전북도" → "전북특별자치도") */
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

    /** 주소에서 검색용 핵심 주소와 최종적으로 붙여야 할 접미(상호명/동호수)를 분리합니다. */
    private fun extractSuffix(address: String): Pair<String, String> {
        if (address.contains(",")) {
            val beforeComma = address.substringBefore(",").trim()
            val afterComma = address.substringAfter(",").trim()
            val numberMatch = Regex("^(\\d+(?:-\\d+)?)\\s*(.*)$").find(afterComma)
            if (numberMatch != null) {
                val number = numberMatch.groupValues[1]
                val suffix = numberMatch.groupValues[2].trim()
                val core = if (number.isNotBlank()) "$beforeComma $number" else beforeComma
                return Pair(core, suffix)
            }
            return Pair(beforeComma, afterComma)
        }
        return Pair(address, "")
    }

    private fun splitBuildingDetail(address: String): Pair<String, String> {
        val buildingStart = Regex("[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)")
        buildingStart.find(address)?.let {
            val base = address.substring(0, it.range.first).trim()
            val detail = address.substring(it.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }

        val roadWithNumber = Regex("([가-힣]+\\d*[로길])\\s*(\\d{1,4}(?:-\\d{1,4})?)")
        roadWithNumber.find(address)?.let {
            val afterRoadNumber = it.range.last + 1
            if (afterRoadNumber < address.length) {
                val remaining = address.substring(afterRoadNumber).trim()
                if (remaining.isNotBlank() && detailUnitPattern.containsMatchIn(remaining)) {
                    val base = address.substring(0, afterRoadNumber).trim()
                    return Pair(base, remaining)
                }
            }
        }

        val unitStart = Regex("\\s+\\d+동\\s+\\d+호|\\s+\\d+호\\s*$")
        unitStart.find(address)?.let {
            val base = address.substring(0, it.range.first).trim()
            val detail = address.substring(it.range.first).trim()
            if (base.isNotBlank()) return Pair(base, detail)
        }

        return Pair(address, "")
    }

    private fun combineWithDetail(roadAddr: String, detail: String): String {
        if (detail.isBlank()) return roadAddr

        val unitInfo =
                detail.replace(
                                Regex("^[가-힣A-Za-z0-9]+(?:아파트|빌라|오피스텔|맨션|타운|파크|하우스|APT|apt)\\s*"),
                                ""
                        )
                        .trim()
        val parenMatch = Regex("\\(([^)]+)\\)\\s*$").find(roadAddr)

        if (parenMatch != null && unitInfo.isNotBlank()) {
            val basePart = roadAddr.substring(0, parenMatch.range.first)
            val buildingName = parenMatch.groupValues[1]
            return "$basePart($buildingName $unitInfo)"
        }

        return if (unitInfo.isNotBlank()) "$roadAddr ($detail)" else "$roadAddr ($detail)"
    }
}
