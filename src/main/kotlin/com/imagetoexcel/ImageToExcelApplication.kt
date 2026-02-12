package com.imagetoexcel

import com.imagetoexcel.config.GoogleVisionProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@EnableConfigurationProperties(GoogleVisionProperties::class)
class ImageToExcelApplication

fun main(args: Array<String>) {
    runApplication<ImageToExcelApplication>(*args)
}
