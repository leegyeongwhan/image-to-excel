package com.imagetoexcel.infrastructure

import com.imagetoexcel.config.NaverProperties
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.client.RestTemplate
import org.springframework.web.util.UriComponentsBuilder

data class NaverGeocodeResult(
    val roadAddress: String,
    val jibunAddress: String
)

private val logger = KotlinLogging.logger {}

@Component
class NaverGeocodingClient(
    private val naverProperties: NaverProperties,
    private val restTemplate: RestTemplate
) {

    fun isConfigured(): Boolean =
        naverProperties.clientId.isNotBlank() && naverProperties.clientSecret.isNotBlank()

    fun geocode(query: String): String? {
        if (!isConfigured()) return null

        val uri = UriComponentsBuilder.fromUriString(naverProperties.geocodeUrl)
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
            parseAddress(response.body)
        } catch (e: Exception) {
            logger.error(e) { "Naver Geocoding API 호출 실패: query=$query" }
            null
        }
    }

    fun geocodeDetailed(query: String): NaverGeocodeResult? {
        if (!isConfigured()) return null

        val uri = UriComponentsBuilder.fromUriString(naverProperties.geocodeUrl)
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
            parseDetailedAddress(response.body)
        } catch (e: Exception) {
            logger.error(e) { "Naver Geocoding API 호출 실패: query=$query" }
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseAddress(body: Map<*, *>?): String? {
        val result = parseDetailedAddress(body) ?: return null
        return result.roadAddress.ifBlank { result.jibunAddress }.ifBlank { null }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseDetailedAddress(body: Map<*, *>?): NaverGeocodeResult? {
        val addresses = body?.get("addresses") as? List<Map<String, Any>>
        if (addresses.isNullOrEmpty()) return null
        val addr = addresses[0]
        val road = addr["roadAddress"]?.toString() ?: ""
        val jibun = addr["jibunAddress"]?.toString() ?: ""
        if (road.isBlank() && jibun.isBlank()) return null
        return NaverGeocodeResult(roadAddress = road, jibunAddress = jibun)
    }
}
