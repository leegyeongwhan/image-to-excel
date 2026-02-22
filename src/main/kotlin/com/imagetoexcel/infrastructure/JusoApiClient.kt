package com.imagetoexcel.infrastructure

import com.imagetoexcel.config.JusoProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

private val logger = KotlinLogging.logger {}

data class JusoAddress(
    val roadAddr: String,
    val jibunAddr: String,
    val zipNo: String
)

data class JusoSearchResult(
    val totalCount: Int,
    val addresses: List<JusoAddress>
)

@Component
class JusoApiClient(
    private val jusoProperties: JusoProperties,
    private val restTemplate: RestTemplate
) {

    fun search(keyword: String, page: Int = 1, countPerPage: Int = 10): JusoSearchResult {
        if (jusoProperties.apiKey.isBlank()) {
            logger.warn { "JUSO_API_KEY가 설정되지 않았습니다." }
            return JusoSearchResult(totalCount = 0, addresses = emptyList())
        }

        val uri = UriComponentsBuilder.fromUriString(jusoProperties.apiUrl)
            .queryParam("confmKey", jusoProperties.apiKey)
            .queryParam("currentPage", page)
            .queryParam("countPerPage", countPerPage)
            .queryParam("keyword", keyword)
            .queryParam("resultType", "json")
            .build()
            .toUriString()

        return try {
            val response = restTemplate.getForObject(uri, Map::class.java)
            parseResponse(response)
        } catch (e: Exception) {
            logger.error(e) { "도로명주소 검색 실패: keyword=$keyword" }
            JusoSearchResult(totalCount = 0, addresses = emptyList())
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseResponse(response: Map<*, *>?): JusoSearchResult {
        val results = response?.get("results") as? Map<String, Any> ?: return emptyResult()
        val common = results["common"] as? Map<String, Any> ?: return emptyResult()

        val errorCode = common["errorCode"]?.toString() ?: ""
        if (errorCode != "0") {
            val errorMsg = common["errorMessage"]?.toString() ?: "알 수 없는 오류"
            logger.warn { "도로명주소 API 오류: $errorCode - $errorMsg" }
            return emptyResult()
        }

        val totalCount = common["totalCount"]?.toString()?.toIntOrNull() ?: 0
        val jusoList = results["juso"] as? List<Map<String, Any>> ?: return emptyResult()

        val addresses = jusoList.map { juso ->
            JusoAddress(
                roadAddr = juso["roadAddr"]?.toString() ?: "",
                jibunAddr = juso["jibunAddr"]?.toString() ?: "",
                zipNo = juso["zipNo"]?.toString() ?: ""
            )
        }

        return JusoSearchResult(totalCount = totalCount, addresses = addresses)
    }

    fun enrich(address: String): String {
        if (address.isBlank() || address.startsWith("[인식 실패]")) return address

        val result = search(address, page = 1, countPerPage = 1)
        if (result.totalCount > 0 && result.addresses.isNotEmpty()) {
            val enriched = result.addresses.first().roadAddr
            logger.info { "주소 자동 보정: \"$address\" → \"$enriched\"" }
            return enriched
        }

        val roadNameOnly = address.replace(Regex("\\s*\\d+[-\\d]*\\s*"), " ").trim()
        if (roadNameOnly != address && roadNameOnly.isNotBlank()) {
            val fallback = search(roadNameOnly, page = 1, countPerPage = 1)
            if (fallback.totalCount > 0 && fallback.addresses.isNotEmpty()) {
                val enriched = fallback.addresses.first().roadAddr
                logger.info { "주소 자동 보정(도로명): \"$address\" → \"$enriched\"" }
                return enriched
            }
        }

        logger.info { "주소 자동 보정 실패, 원본 유지: \"$address\"" }
        return address
    }

    private fun emptyResult() = JusoSearchResult(totalCount = 0, addresses = emptyList())
}
