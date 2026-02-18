package com.imagetoexcel.service

import com.imagetoexcel.config.JusoProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
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

@Service
class JusoAddressService(
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

    private fun emptyResult() = JusoSearchResult(totalCount = 0, addresses = emptyList())
}
