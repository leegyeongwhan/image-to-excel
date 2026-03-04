package com.imagetoexcel.controller

import com.imagetoexcel.component.ApiUsageTracker
import com.imagetoexcel.infrastructure.JusoApiClient
import com.imagetoexcel.infrastructure.JusoAddress
import com.imagetoexcel.infrastructure.JusoSearchResult
import com.imagetoexcel.infrastructure.NaverGeocodingClient
import com.imagetoexcel.service.ReferenceDataService
import com.imagetoexcel.service.UploadService
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseBody
import org.springframework.web.multipart.MultipartFile

@Controller
class UploadController(
    private val uploadService: UploadService,
    private val apiUsageTracker: ApiUsageTracker,
    private val jusoApiClient: JusoApiClient,
    private val naverGeocodingClient: NaverGeocodingClient,
    private val referenceDataService: ReferenceDataService
) {

    @GetMapping("/")
    fun index(): String {
        return "index"
    }

    @PostMapping("/upload")
    fun upload(
        @RequestParam("files") files: List<MultipartFile>,
        @RequestParam("existingOrdersJson", required = false) existingOrdersJson: String?,
        model: Model
    ): String {
        val validFiles = files.filter { !it.isEmpty }
        if (validFiles.isEmpty()) {
            model.addAttribute("error", "파일을 선택해주세요.")
            return "index"
        }

        val existingOrders =
            if (!existingOrdersJson.isNullOrBlank()) {
                uploadService.jsonToOrders(existingOrdersJson)
            } else {
                emptyList()
            }

        val newOrders = uploadService.extractOrders(validFiles)
        val combinedOrders = existingOrders + newOrders

        model.addAttribute("orders", combinedOrders)
        model.addAttribute("ordersJson", uploadService.ordersToJson(combinedOrders))
        model.addAttribute("newFileCount", validFiles.size)
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

    @GetMapping("/api/address/naver-search")
    @ResponseBody
    fun naverSearchAddress(@RequestParam("keyword") keyword: String): JusoSearchResult {
        val result = naverGeocodingClient.geocodeDetailed(keyword)
            ?: return JusoSearchResult(totalCount = 0, addresses = emptyList())
        val address = JusoAddress(
            roadAddr = result.roadAddress.ifBlank { result.jibunAddress },
            jibunAddr = result.jibunAddress,
            zipNo = ""
        )
        return JusoSearchResult(totalCount = 1, addresses = listOf(address))
    }

    // ========================
    // 참조 데이터 (그린부동산 원장 등)
    // ========================

    @PostMapping("/api/reference/upload")
    @ResponseBody
    fun uploadReference(@RequestParam("file") file: MultipartFile): Map<String, Any> {
        val count = referenceDataService.load(file)
        return mapOf("success" to true, "count" to count, "fileName" to (file.originalFilename ?: ""))
    }

    @GetMapping("/api/reference/status")
    @ResponseBody
    fun referenceStatus(): Map<String, Any?> {
        return mapOf(
            "loaded" to referenceDataService.isLoaded(),
            "count" to referenceDataService.getCount(),
            "fileName" to referenceDataService.loadedFileName
        )
    }

    @GetMapping("/api/reference/lookup")
    @ResponseBody
    fun lookupByPhone(@RequestParam("phone") phone: String): Map<String, Any?> {
        val infoList = referenceDataService.lookup(phone)
        if (infoList.isEmpty()) return mapOf("found" to false)
        return mapOf(
            "found" to true,
            "results" to infoList.map { mapOf("name" to it.name, "address" to it.address, "phone" to it.phone) }
        )
    }

    /** 여러 전화번호를 한 번에 조회 (프론트에서 일괄 매칭 시 사용, 1:N) */
    @PostMapping("/api/reference/lookup-batch")
    @ResponseBody
    fun lookupBatch(@RequestBody phones: List<String>): Map<String, Any> {
        val results = referenceDataService.lookupBatch(phones)
        val mapped = results.map { (phone, infoList) ->
            phone to infoList.map { mapOf("name" to it.name, "address" to it.address, "phone" to it.phone) }
        }.toMap()
        val totalMatches = results.values.sumOf { it.size }
        return mapOf("matches" to mapped, "matchCount" to totalMatches)
    }

    @PostMapping("/download")
    fun download(@RequestParam("ordersJson") ordersJson: String): ResponseEntity<ByteArray> {
        val excelBytes = uploadService.generateExcel(ordersJson)

        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=orders.xlsx")
            .contentType(
                MediaType.parseMediaType(
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                )
            )
            .body(excelBytes)
    }
}
