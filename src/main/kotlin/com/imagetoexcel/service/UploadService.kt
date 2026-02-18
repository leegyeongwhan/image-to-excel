package com.imagetoexcel.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.imagetoexcel.dto.OrderData
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class UploadService(
    private val orderExtractor: OrderExtractor,
    private val orderExcelGenerator: OrderExcelGenerator,
    private val jusoAddressService: JusoAddressService,
    private val objectMapper: ObjectMapper
) {

    fun extractOrders(files: List<MultipartFile>): List<OrderData> {
        val orders = orderExtractor.extractOrderDataBatch(files)
        return orders.map { it.copy(address = jusoAddressService.enrich(it.address)) }
    }

    fun ordersToJson(orders: List<OrderData>): String {
        return objectMapper.writeValueAsString(orders)
    }

    fun generateExcel(ordersJson: String): ByteArray {
        val orders: List<OrderData> = objectMapper.readValue(ordersJson)
        return orderExcelGenerator.createExcel(orders)
    }
}
