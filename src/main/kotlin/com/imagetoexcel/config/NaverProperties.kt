package com.imagetoexcel.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "naver")
data class NaverProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    val geocodeUrl: String = "https://maps.apigw.ntruss.com/map-geocode/v2/geocode"
)
