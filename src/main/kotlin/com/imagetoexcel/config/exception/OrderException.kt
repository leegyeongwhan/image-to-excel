package com.imagetoexcel.config.exception

import com.imagetoexcel.domain.enum.GoogleApiError

sealed class OrderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    class ApiKeyNotConfigured :
        OrderException("Google Cloud API 키가 설정되지 않았습니다. .env 파일에 GOOGLE_CLOUD_API_KEY를 설정해주세요.")

    class VisionApiError(error: GoogleApiError) :
        OrderException(error.message)

    class VisionApiErrorWithDetail(message: String) :
        OrderException(message)

    class NetworkError(cause: Throwable) :
        OrderException(GoogleApiError.NETWORK_ERROR.message, cause)

}