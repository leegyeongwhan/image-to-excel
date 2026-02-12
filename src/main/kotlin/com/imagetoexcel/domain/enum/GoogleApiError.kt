package com.imagetoexcel.domain.enum

enum class GoogleApiError(val message: String) {
    BAD_REQUEST("잘못된 요청입니다. 이미지 형식을 확인해주세요."),
    UNAUTHORIZED("API 키가 유효하지 않거나 Cloud Vision API가 활성화되지 않았습니다. Google Cloud Console을 확인해주세요."),
    RATE_LIMIT("API 할당량을 초과했습니다. 잠시 후 다시 시도해주세요."),
    NETWORK_ERROR("Google Vision API에 연결할 수 없습니다. 네트워크 연결을 확인해주세요."),
    EMPTY_RESPONSE("Google Vision API 응답이 없습니다"),
    INVALID_FORMAT("Google Vision 응답 형식 오류"),
    NO_TEXT_DETECTED("이미지에서 텍스트를 인식하지 못했습니다. 텍스트가 포함된 이미지인지 확인해주세요.");

    companion object {
        fun userMessageForStatus(status: Int): String = when (status) {
            400 -> BAD_REQUEST.message
            401, 403 -> UNAUTHORIZED.message
            429 -> RATE_LIMIT.message
            else -> "Google API 오류 (HTTP $status)"
        }
    }
}
