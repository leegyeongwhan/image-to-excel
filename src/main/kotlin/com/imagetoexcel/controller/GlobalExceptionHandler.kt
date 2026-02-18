package com.imagetoexcel.controller

import com.imagetoexcel.config.exception.OrderException
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.http.ResponseEntity
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.ControllerAdvice
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.servlet.resource.NoResourceFoundException

@ControllerAdvice
class GlobalExceptionHandler {

    private val logger = KotlinLogging.logger {}

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNoResource(e: NoResourceFoundException): ResponseEntity<Void> {
        return ResponseEntity.notFound().build()
    }

    @ExceptionHandler(OrderException::class)
    fun handleOrderException(e: OrderException, model: Model): String {
        model.addAttribute("error", e.message)
        return "index"
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception, model: Model): String {
        logger.error(e) { "예상치 못한 오류 발생" }
        model.addAttribute("error", "예상치 못한 오류가 발생했습니다: ${e.message}")
        return "index"
    }
}
