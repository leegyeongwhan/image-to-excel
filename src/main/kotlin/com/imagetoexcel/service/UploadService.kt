package com.imagetoexcel.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.imagetoexcel.component.OrderExcelGenerator
import com.imagetoexcel.dto.OrderData
import com.imagetoexcel.infrastructure.OrderExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile

@Service
class UploadService(
        private val orderExtractor: OrderExtractor,
        private val orderExcelGenerator: OrderExcelGenerator,
        private val addressEnrichService: AddressEnrichService,
        private val objectMapper: ObjectMapper
) {

    companion object {
        private const val MAX_CONCURRENT_JUSO_CALLS = 10
    }

    fun extractOrders(files: List<MultipartFile>): List<OrderData> {
        val orders = orderExtractor.extractOrderDataBatch(files)
        return enrichAddressesBatch(orders)
    }

    private fun enrichAddressesBatch(orders: List<OrderData>): List<OrderData> {
        return runBlocking(Dispatchers.IO) {
            val semaphore = Semaphore(MAX_CONCURRENT_JUSO_CALLS)
            orders
                    .map { order ->
                        async {
                            semaphore.withPermit {
                                val (enrichedAddress, isValid) =
                                        addressEnrichService.enrich(order.address)
                                order.copy(address = enrichedAddress, addressValid = isValid)
                            }
                        }
                    }
                    .awaitAll()
        }
    }

    fun jsonToOrders(ordersJson: String): List<OrderData> {
        if (ordersJson.isBlank()) return emptyList()
        return objectMapper.readValue(ordersJson)
    }

    fun ordersToJson(orders: List<OrderData>): String {
        return objectMapper.writeValueAsString(orders)
    }

    fun generateExcel(ordersJson: String): ByteArray {
        val orders: List<OrderData> = objectMapper.readValue(ordersJson)
        return orderExcelGenerator.createExcel(orders)
    }
}
