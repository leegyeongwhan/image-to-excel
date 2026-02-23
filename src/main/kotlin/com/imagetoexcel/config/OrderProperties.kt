package com.imagetoexcel.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "order")
data class OrderProperties(
    val defaultPhone: String = "01086100102"  // OCR에서 전화번호 못찾을 때 기본값
)
