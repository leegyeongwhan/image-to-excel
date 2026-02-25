package com.imagetoexcel.infrastructure

import com.imagetoexcel.component.ApiUsageTracker
import com.imagetoexcel.config.GoogleVisionProperties
import com.imagetoexcel.config.OrderProperties
import com.imagetoexcel.config.exception.OrderException
import com.imagetoexcel.domain.OrderTextParser
import com.imagetoexcel.domain.enum.GoogleApiError
import com.imagetoexcel.dto.OrderData
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.Base64
import kotlin.collections.get
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
import org.springframework.web.client.RestTemplate
import org.springframework.web.multipart.MultipartFile

@Component
class GoogleVisionOrderExtractor(
    private val restTemplate: RestTemplate,
    private val properties: GoogleVisionProperties,
    private val orderProperties: OrderProperties,
    private val apiUsageTracker: ApiUsageTracker,
    private val imagePreprocessor: ImagePreprocessor
) : OrderExtractor {

    private val logger = KotlinLogging.logger {}
    private val parser = OrderTextParser()

    companion object {
        private const val MAX_CONCURRENT_CALLS = 20
    }

    override fun extractOrderData(file: MultipartFile): OrderData {
        if (properties.apiKey.isBlank()) {
            throw OrderException.ApiKeyNotConfigured()
        }

        val processedImageBytes = imagePreprocessor.preprocess(file)
        val base64Image = Base64.getEncoder().encodeToString(processedImageBytes)
        val ocrText = callGoogleVision(base64Image)
        logger.info { "OCR 결과:\n$ocrText" }
        val orderData = parser.parse(ocrText)

        // OCR에서 전화번호를 못 찾으면 기본 번호 사용
        return if (orderData.phone.isNullOrBlank()) {
            logger.info { "전화번호 미감지 → 기본번호 사용: ${orderProperties.defaultPhone}" }
            orderData.copy(phone = orderProperties.defaultPhone)
        } else {
            orderData
        }
    }

    override fun extractOrderDataBatch(files: List<MultipartFile>): List<OrderData> {
        logger.info { "병렬 처리 시작: ${files.size}개 이미지 (동시 ${MAX_CONCURRENT_CALLS}개)" }
        return runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(MAX_CONCURRENT_CALLS)
            files
                .map { file ->
                    async {
                        semaphore.withPermit {
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
                }
                .awaitAll()
        }
    }

    private fun callGoogleVision(base64Image: String): String {
        val requestBody =
            mapOf(
                "requests" to
                        listOf(
                            mapOf(
                                "image" to mapOf("content" to base64Image),
                                "features" to
                                        listOf(
                                            mapOf(
                                                "type" to
                                                        "DOCUMENT_TEXT_DETECTION"
                                            )
                                        ),
                                // 한국어+태국어 힌트: 파란 채팅버블/위치카드처럼 배경이 복잡해도 한글 인식률 향상
                                "imageContext" to
                                        mapOf("languageHints" to listOf("ko", "th"))
                            )
                        )
            )

        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }

        val url = "${properties.apiUrl}?key=${properties.apiKey}"
        val entity = HttpEntity(requestBody, headers)

        val response = executeApiCall(url, entity)
        apiUsageTracker.increment()
        checkApiLevelError(response)
        return extractText(response)
    }

    private fun executeApiCall(
        url: String,
        entity: HttpEntity<Map<String, List<Map<String, Any>>>>
    ): Map<*, *> {
        val maxRetries = 3
        var lastException: Exception? = null

        repeat(maxRetries) { attempt ->
            try {
                return restTemplate.postForObject(url, entity, Map::class.java)
                    ?: throw OrderException.VisionApiError(GoogleApiError.EMPTY_RESPONSE)
            } catch (e: HttpClientErrorException) {
                val status = e.statusCode.value()
                if (status == 429) {
                    logger.warn { "API 할당량 초과 (429), 재시도 ${attempt + 1}/$maxRetries" }
                    lastException = e
                    Thread.sleep(1000L * (1 shl attempt))
                } else {
                    logger.error {
                        "Google Vision API 호출 실패 (HTTP $status): ${e.responseBodyAsString}"
                    }
                    throw OrderException.VisionApiErrorWithDetail(
                        GoogleApiError.userMessageForStatus(status)
                    )
                }
            } catch (e: HttpServerErrorException) {
                val status = e.statusCode.value()
                logger.warn {
                    "Google Vision API 서버 오류 (HTTP $status), 재시도 ${attempt + 1}/$maxRetries"
                }
                lastException = e
                Thread.sleep(1000L * (1 shl attempt))
            } catch (e: ResourceAccessException) {
                logger.warn { "Google Vision API 연결 실패, 재시도 ${attempt + 1}/$maxRetries" }
                lastException = e
                Thread.sleep(1000L * (1 shl attempt))
            }
        }

        when (val finalException = lastException) {
            is HttpClientErrorException ->
                throw OrderException.VisionApiErrorWithDetail(GoogleApiError.RATE_LIMIT.message)

            is HttpServerErrorException -> {
                val status = finalException.statusCode.value()
                throw OrderException.VisionApiErrorWithDetail(
                    "Google API 서버 오류 (HTTP $status). 잠시 후 다시 시도해주세요."
                )
            }

            is ResourceAccessException -> throw OrderException.NetworkError(finalException)
            else -> throw OrderException.VisionApiErrorWithDetail("알 수 없는 오류가 발생했습니다.")
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
        val responses =
            response["responses"] as? List<Map<String, Any>>
                ?: throw OrderException.VisionApiError(GoogleApiError.INVALID_FORMAT)

        val firstResponse =
            responses.firstOrNull()
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
