package com.imagetoexcel.service

import com.imagetoexcel.config.GoogleVisionProperties
import com.imagetoexcel.config.exception.OrderException
import com.imagetoexcel.domain.OrderTextParser
import com.imagetoexcel.domain.enum.GoogleApiError
import com.imagetoexcel.dto.OrderData
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile
import java.util.Base64
import kotlin.collections.get

@Service
class GoogleVisionOrderExtractor(
    private val restTemplate: RestTemplate,
    private val properties: GoogleVisionProperties
) : OrderExtractor {

    private val logger = KotlinLogging.logger {}
    private val parser = OrderTextParser()

    override fun extractOrderData(file: MultipartFile): OrderData {
        if (properties.apiKey.isBlank()) {
            throw OrderException.ApiKeyNotConfigured()
        }

        val base64Image = Base64.getEncoder().encodeToString(file.bytes)
        val ocrText = callGoogleVision(base64Image)
        logger.info { "OCR 결과:\n$ocrText" }
        return parser.parse(ocrText)
    }

    override fun extractOrderDataBatch(files: List<MultipartFile>): List<OrderData> {
        return files.map { file ->
            try {
                extractOrderData(file)
            } catch (e: OrderException) {
                logger.error(e) { "이미지 처리 실패: ${file.originalFilename}" }
                OrderData(
                    name = "ERROR: ${file.originalFilename}",
                    address = "처리 실패: ${e.message}"
                )
            }
        }
    }

    private fun callGoogleVision(base64Image: String): String {
        val requestBody = mapOf(
            "requests" to listOf(
                mapOf(
                    "image" to mapOf("content" to base64Image),
                    "features" to listOf(
                        mapOf("type" to "DOCUMENT_TEXT_DETECTION")
                    )
                )
            )
        )

        val headers = HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
        }

        val url = "${properties.apiUrl}?key=${properties.apiKey}"
        val entity = HttpEntity(requestBody, headers)

        val response = executeApiCall(url, entity)
        checkApiLevelError(response)
        return extractText(response)
    }

    private fun executeApiCall(url: String, entity: HttpEntity<Map<String, List<Map<String, Any>>>>): Map<*, *> {
        try {
            return restTemplate.postForObject(url, entity, Map::class.java)
                ?: throw OrderException.VisionApiError(GoogleApiError.EMPTY_RESPONSE)
        } catch (e: HttpClientErrorException) {
            val status = e.statusCode.value()
            logger.error { "Google Vision API 호출 실패 (HTTP $status): ${e.responseBodyAsString}" }
            throw OrderException.VisionApiErrorWithDetail(GoogleApiError.userMessageForStatus(status))
        } catch (e: ResourceAccessException) {
            logger.error(e) { "Google Vision API 연결 실패" }
            throw OrderException.NetworkError(e)
        }
    }

    private fun checkApiLevelError(response: Map<*, *>) {
        val error = response["error"] as? Map<*, *> ?: return
        val errorMsg = error["message"] as? String ?: "알 수 없는 오류"
        val errorCode = error["code"] as? Int ?: 0
        logger.error { "Google Vision API 에러: code=$errorCode, message=$errorMsg" }
        throw OrderException.VisionApiErrorWithDetail("Google Vision API 에러: $errorMsg")
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractText(response: Map<*, *>): String {
        val responses = response["responses"] as? List<Map<String, Any>>
            ?: throw OrderException.VisionApiError(GoogleApiError.INVALID_FORMAT)

        val firstResponse = responses.firstOrNull()
            ?: throw OrderException.VisionApiError(GoogleApiError.EMPTY_RESPONSE)

        val responseError = firstResponse["error"] as? Map<*, *>
        if (responseError != null) {
            val errorMsg = responseError["message"] as? String ?: "알 수 없는 오류"
            logger.error { "Google Vision 이미지 처리 에러: $errorMsg" }
            throw OrderException.VisionApiErrorWithDetail("이미지 처리 실패: $errorMsg")
        }

        val fullTextAnnotation = firstResponse["fullTextAnnotation"] as? Map<String, Any>
        return fullTextAnnotation?.get("text") as? String
            ?: throw OrderException.VisionApiError(GoogleApiError.NO_TEXT_DETECTED)
    }
}
