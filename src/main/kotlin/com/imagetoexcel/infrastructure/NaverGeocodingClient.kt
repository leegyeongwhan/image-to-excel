package com.imagetoexcel.infrastructure

import com.imagetoexcel.config.NaverProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

private val logger = KotlinLogging.logger {}

@Component
class NaverGeocodingClient(
    private val naverProperties: NaverProperties,
    private val restTemplate: RestTemplate
) {

    companion object {
        private const val GEOCODE_URL = "https://naveropenapi.apigw.ntruss.com/map-geocode/v2/geocode"
    }

    fun isConfigured(): Boolean =
        naverProperties.clientId.isNotBlank() && naverProperties.clientSecret.isNotBlank()

    fun geocode(query: String): String? {
        if (!isConfigured()) return null

        val uri = UriComponentsBuilder.fromUriString(GEOCODE_URL)
            .queryParam("query", query)
            .build()
            .toUriString()

        val headers = HttpHeaders().apply {
            set("X-NCP-APIGW-API-KEY-ID", naverProperties.clientId)
            set("X-NCP-APIGW-API-KEY", naverProperties.clientSecret)
        }

        return try {
            val response = restTemplate.exchange(
                uri,
                HttpMethod.GET,
                HttpEntity<Void>(headers),
                Map::class.java
            )
            parseRoadAddress(response.body)
        } catch (e: Exception) {
            logger.error(e) { "Naver Geocoding API 호출 실패: query=$query" }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseRoadAddress(body: Map<*, *>?): String? {
        val addresses = body?.get("addresses") as? List<Map<String, Any>>
        if (addresses.isNullOrEmpty()) return null
        val roadAddress = addresses[0]["roadAddress"]?.toString()
        return if (roadAddress.isNullOrBlank()) null else roadAddress
    }
}
