package com.imagetoexcel.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.client.RestTemplate

@ConfigurationProperties(prefix = "google.vision")
data class GoogleVisionProperties(
    val apiKey: String = "",
    val apiUrl: String = "https://vision.googleapis.com/v1/images:annotate"
)

@ConfigurationProperties(prefix = "juso")
data class JusoProperties(
    val apiKey: String = "",
    val apiUrl: String = "https://business.juso.go.kr/addrlink/addrLinkApi.do"
)

@Configuration
class AppConfig {

    @Bean
    fun restTemplate(): RestTemplate {
        return RestTemplate()
    }
}
