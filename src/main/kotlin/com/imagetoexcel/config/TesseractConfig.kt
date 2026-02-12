package com.imagetoexcel.config

import net.sourceforge.tess4j.Tesseract
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TesseractConfig {

    @Value("\${tesseract.data-path}")
    private lateinit var dataPath: String

    @Value("\${tesseract.language}")
    private lateinit var language: String

    @Bean
    fun tesseract(): Tesseract {
        return Tesseract().apply {
            setDatapath(dataPath)
            setLanguage(language)
            setPageSegMode(1) // Automatic page segmentation with OSD
            setOcrEngineMode(1) // LSTM only
        }
    }
}
