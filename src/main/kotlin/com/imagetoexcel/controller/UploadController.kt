package com.imagetoexcel.controller

import com.imagetoexcel.component.ApiUsageTracker
import com.imagetoexcel.infrastructure.JusoApiClient
import com.imagetoexcel.infrastructure.JusoSearchResult
import com.imagetoexcel.service.UploadService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile

@Controller
class UploadController(
    private val uploadService: UploadService,
    private val apiUsageTracker: ApiUsageTracker,
    private val jusoApiClient: JusoApiClient
) {

    @GetMapping("/")
    fun index(): String {
        return "index"
    }

    @PostMapping("/upload")
    fun upload(
        @RequestParam("files") files: List<MultipartFile>,
        model: Model
    ): String {
        val validFiles = files.filter { !it.isEmpty }
        if (validFiles.isEmpty()) {
            model.addAttribute("error", "파일을 선택해주세요.")
            return "index"
        }

        val orders = uploadService.extractOrders(validFiles)

        model.addAttribute("orders", orders)
        model.addAttribute("ordersJson", uploadService.ordersToJson(orders))
        model.addAttribute("fileCount", validFiles.size)
        model.addAttribute("success", true)

        return "index"
    }

    @GetMapping("/api/usage")
    @ResponseBody
    fun getUsage(): ApiUsageTracker.UsageInfo {
        return apiUsageTracker.getUsage()
    }

    @GetMapping("/api/address/search")
    @ResponseBody
    fun searchAddress(
        @RequestParam("keyword") keyword: String,
        @RequestParam("page", defaultValue = "1") page: Int
    ): JusoSearchResult {
        return jusoApiClient.search(keyword, page)
    }

    @PostMapping("/download")
    fun download(@RequestParam("ordersJson") ordersJson: String): ResponseEntity<ByteArray> {
        val excelBytes = uploadService.generateExcel(ordersJson)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.xlsx")
            .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
            .body(excelBytes)
    }
}
