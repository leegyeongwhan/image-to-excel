package com.imagetoexcel

import com.imagetoexcel.config.GoogleVisionProperties
import com.imagetoexcel.config.JusoProperties
import com.imagetoexcel.config.NaverProperties
import com.imagetoexcel.config.OrderProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GoogleVisionProperties::class, JusoProperties::class, NaverProperties::class, OrderProperties::class)
class ImageToExcelApplication

fun main(args: Array<String>) {
    runApplication<ImageToExcelApplication>(*args)
}
