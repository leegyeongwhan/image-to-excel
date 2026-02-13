package com.imagetoexcel.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.time.YearMonth
import java.time.LocalDateTime
import java.util.concurrent.atomic.AtomicInteger

@Service
class ApiUsageTracker(
    private val objectMapper: ObjectMapper
) {

    private val logger = KotlinLogging.logger {}
    private val filePath: Path = Path.of("data", "api-usage.json")
    private var currentMonth: YearMonth = YearMonth.now()
    private val callCount = AtomicInteger(0)

    init {
        loadFromFile()
    }

    fun increment() {
        resetIfNewMonth()
        val count = callCount.incrementAndGet()
        logger.info { "API 호출 카운트: $count (${currentMonth})" }
        saveToFile()
    }

    fun getUsage(): UsageInfo {
        resetIfNewMonth()
        val count = callCount.get()
        val freeLimit = 1000
        val freeRemaining = maxOf(0, freeLimit - count)
        val estimatedCost = if (count <= freeLimit) 0.0 else (count - freeLimit) * 0.0015

        return UsageInfo(
            yearMonth = currentMonth.toString(),
            callCount = count,
            freeLimit = freeLimit,
            freeRemaining = freeRemaining,
            estimatedCost = estimatedCost
        )
    }

    private fun resetIfNewMonth() {
        val now = YearMonth.now()
        if (now != currentMonth) {
            logger.info { "월 변경 감지: $currentMonth → $now, 카운터 리셋" }
            currentMonth = now
            callCount.set(0)
            saveToFile()
        }
    }

    private fun loadFromFile() {
        try {
            if (Files.exists(filePath)) {
                val data: UsageFileData = objectMapper.readValue(filePath.toFile())
                val savedMonth = YearMonth.parse(data.yearMonth)
                if (savedMonth == YearMonth.now()) {
                    callCount.set(data.callCount)
                    currentMonth = savedMonth
                    logger.info { "사용량 로드: ${data.callCount}건 (${data.yearMonth})" }
                } else {
                    logger.info { "이전 월 데이터 ($savedMonth), 새로 시작" }
                }
            }
        } catch (e: Exception) {
            logger.warn(e) { "사용량 파일 로드 실패, 0부터 시작" }
        }
    }

    private fun saveToFile() {
        try {
            Files.createDirectories(filePath.parent)
            val data = UsageFileData(
                yearMonth = currentMonth.toString(),
                callCount = callCount.get(),
                lastUpdated = LocalDateTime.now().toString()
            )
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(filePath.toFile(), data)
        } catch (e: Exception) {
            logger.warn(e) { "사용량 파일 저장 실패" }
        }
    }

    @PreDestroy
    fun onShutdown() {
        saveToFile()
        logger.info { "사용량 저장 완료 (종료): ${callCount.get()}건" }
    }

    data class UsageInfo(
        val yearMonth: String,
        val callCount: Int,
        val freeLimit: Int,
        val freeRemaining: Int,
        val estimatedCost: Double
    )

    private data class UsageFileData(
        val yearMonth: String = "",
        val callCount: Int = 0,
        val lastUpdated: String = ""
    )
}
