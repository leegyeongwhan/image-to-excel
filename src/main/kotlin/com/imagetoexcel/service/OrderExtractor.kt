package com.imagetoexcel.service

import com.imagetoexcel.dto.OrderData
import org.springframework.web.multipart.MultipartFile

interface OrderExtractor {
    fun extractOrderData(file: MultipartFile): OrderData
    fun extractOrderDataBatch(files: List<MultipartFile>): List<OrderData>
}
