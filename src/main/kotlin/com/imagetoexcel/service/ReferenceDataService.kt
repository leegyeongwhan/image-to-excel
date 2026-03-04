package com.imagetoexcel.service

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.Row
import org.apache.poi.ss.usermodel.WorkbookFactory
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import java.io.BufferedReader
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private val logger = KotlinLogging.logger {}

/**
 * 참조 Excel(예: 그린부동산 원장) 데이터를 메모리에 보관하고,
 * 전화번호 기반으로 이름·주소를 조회하는 서비스.
 *
 * 데이터는 data/ 폴더에 캐시 파일로 영구 저장되며,
 * 서버 재시작 시 자동으로 로드됩니다.
 *
 * 같은 전화번호에 여러 고객이 매핑될 수 있습니다 (1:N).
 */
@Service
class ReferenceDataService {

    data class CustomerInfo(val name: String, val address: String, val phone: String)

    /** 전화번호(숫자만) → 고객 정보 목록 (1:N), 스레드 간 가시성 보장 */
    @Volatile
    private var customerMap: Map<String, List<CustomerInfo>> = emptyMap()

    /** 로드된 파일명 */
    @Volatile
    private var _loadedFileName: String? = null
    val loadedFileName: String? get() = _loadedFileName

    companion object {
        private val DATA_DIR: Path = Path.of("data")
        private val CACHE_FILE: Path = DATA_DIR.resolve("reference-cache.tsv")
        private val META_FILE: Path = DATA_DIR.resolve("reference-meta.txt")
        private const val TSV_SEPARATOR = "\t"
    }

    fun isLoaded(): Boolean = customerMap.isNotEmpty()

    /** 전체 고유 전화번호 수 */
    fun getCount(): Int = customerMap.values.sumOf { it.size }

    /** 서버 시작 시 캐시 파일이 있으면 자동 로드 */
    @PostConstruct
    fun init() {
        if (Files.exists(CACHE_FILE)) {
            try {
                val count = loadFromCache()
                logger.info { "참조 데이터 자동 로드 완료 (캐시): ${count}건" }
            } catch (e: Exception) {
                logger.error(e) { "참조 데이터 캐시 로드 실패: ${e.message}" }
            }
        } else {
            logger.info { "참조 데이터 캐시 없음, 엑셀 업로드 대기" }
        }
    }

    /**
     * Excel 파일의 "원장" 시트를 파싱하여 메모리에 로드합니다.
     * 파싱 후 캐시 파일(TSV)로 저장하여 재시작 시 빠르게 로드합니다.
     */
    fun load(file: MultipartFile): Int {
        val map = parseExcel(file)

        customerMap = map
        _loadedFileName = file.originalFilename
        val totalEntries = map.values.sumOf { it.size }
        logger.info { "참조 데이터 로드 완료: ${totalEntries}건 (전화번호 ${map.size}개, 파일: ${file.originalFilename})" }

        // 캐시 저장 (TSV 형식으로 빠르게 재로드 가능)
        saveToCache(map, file.originalFilename)

        return totalEntries
    }

    /** 전화번호로 고객 정보를 조회합니다 (여러 건 반환 가능). */
    fun lookup(phone: String): List<CustomerInfo> {
        return customerMap[normalizePhone(phone)] ?: emptyList()
    }

    /** 여러 전화번호를 일괄 조회합니다 (1:N). */
    fun lookupBatch(phones: List<String>): Map<String, List<CustomerInfo>> {
        return phones
                .filter { it.isNotBlank() }
                .mapNotNull { phone ->
                    val normalized = normalizePhone(phone)
                    customerMap[normalized]?.let { phone to it }
                }
                .toMap()
    }

    // ========================
    // Excel 파싱
    // ========================

    private fun parseExcel(file: MultipartFile): Map<String, List<CustomerInfo>> {
        val workbook = WorkbookFactory.create(file.inputStream)

        // "원장" 시트 찾기 (없으면 첫 번째 시트)
        val sheet = workbook.getSheet("원장")
                ?: workbook.getSheet("원장 ")
                ?: workbook.getSheetAt(0)

        val headerRow = sheet.getRow(0) ?: throw IllegalArgumentException("시트에 헤더 행이 없습니다.")

        // 헤더에서 컬럼 인덱스 자동 감지
        var phoneCol = -1
        var nameCol = -1
        var addressCol = -1

        for (cell in headerRow) {
            val header = getCellString(cell).trim()
            when {
                phoneCol == -1 && header.matches(Regex(".*(?:전화|연락|핸드폰|HP|TEL|휴대폰|번호).*", RegexOption.IGNORE_CASE)) -> phoneCol = cell.columnIndex
                nameCol == -1 && header.matches(Regex(".*(?:이름|성명|수취인|고객명|임차인|세입자).*")) -> nameCol = cell.columnIndex
                addressCol == -1 && header.matches(Regex(".*(?:주소|거소|소재지).*")) -> addressCol = cell.columnIndex
            }
        }

        if (phoneCol == -1) throw IllegalArgumentException("전화번호 컬럼을 찾을 수 없습니다. (헤더에 '전화', '연락처', '핸드폰' 등이 필요)")

        val map = mutableMapOf<String, MutableList<CustomerInfo>>()
        for (i in 1..sheet.lastRowNum) {
            val row = sheet.getRow(i) ?: continue
            val phone = normalizePhone(getCellString(row, phoneCol))
            if (phone.isBlank()) continue

            val name = if (nameCol >= 0) getCellString(row, nameCol) else ""
            val address = if (addressCol >= 0) getCellString(row, addressCol) else ""

            if (name.isNotBlank() || address.isNotBlank()) {
                val info = CustomerInfo(name = name, address = address, phone = phone)
                val list = map.getOrPut(phone) { mutableListOf() }
                // 이름+주소가 완전히 같은 중복은 제거
                if (list.none { it.name == info.name && it.address == info.address }) {
                    list.add(info)
                }
            }
        }

        workbook.close()
        // 엑셀 아래쪽(최신) 데이터가 리스트 앞에 오도록 역순
        return map.mapValues { (_, list) -> list.reversed() }
    }

    // ========================
    // 캐시 저장/로드 (TSV)
    // ========================

    private fun saveToCache(map: Map<String, List<CustomerInfo>>, fileName: String?) {
        try {
            Files.createDirectories(DATA_DIR)

            // TSV 캐시: phone\tname\taddress (같은 phone이 여러 줄 가능)
            BufferedWriter(Files.newBufferedWriter(CACHE_FILE)).use { writer ->
                for ((phone, infoList) in map) {
                    for (info in infoList) {
                        // 탭/줄바꿈을 공백으로 치환 (TSV 안전)
                        val safeName = info.name.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                        val safeAddr = info.address.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')
                        writer.write("${phone}${TSV_SEPARATOR}${safeName}${TSV_SEPARATOR}${safeAddr}")
                        writer.newLine()
                    }
                }
            }

            // 메타 정보 (파일명)
            Files.writeString(META_FILE, fileName ?: "unknown")

            val totalEntries = map.values.sumOf { it.size }
            logger.info { "참조 데이터 캐시 저장 완료: ${totalEntries}건 → $CACHE_FILE" }
        } catch (e: Exception) {
            logger.error(e) { "참조 데이터 캐시 저장 실패: ${e.message}" }
        }
    }

    private fun loadFromCache(): Int {
        val map = mutableMapOf<String, MutableList<CustomerInfo>>()

        BufferedReader(Files.newBufferedReader(CACHE_FILE)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val parts = line!!.split(TSV_SEPARATOR, limit = 3)
                if (parts.size >= 3) {
                    val phone = parts[0]
                    val name = parts[1]
                    val address = parts[2]
                    if (phone.isNotBlank()) {
                        map.getOrPut(phone) { mutableListOf() }
                            .add(CustomerInfo(name = name, address = address, phone = phone))
                    }
                }
            }
        }

        customerMap = map

        // 메타 정보 로드
        _loadedFileName = if (Files.exists(META_FILE)) {
            Files.readString(META_FILE).trim().ifBlank { null }
        } else null

        return map.values.sumOf { it.size }
    }

    // ========================
    // 유틸리티
    // ========================

    /** 전화번호를 숫자만 남기고 정규화합니다. (010-1234-5678 → 01012345678) */
    private fun normalizePhone(phone: String): String {
        return phone.replace(Regex("[^0-9]"), "")
    }

    private fun getCellString(row: Row, colIndex: Int): String {
        val cell = row.getCell(colIndex) ?: return ""
        return getCellString(cell)
    }

    private fun getCellString(cell: org.apache.poi.ss.usermodel.Cell): String {
        return when (cell.cellType) {
            CellType.STRING -> cell.stringCellValue?.trim() ?: ""
            CellType.NUMERIC -> {
                val num = cell.numericCellValue
                if (num == num.toLong().toDouble()) num.toLong().toString() else num.toString()
            }
            CellType.FORMULA -> try { cell.stringCellValue?.trim() ?: "" } catch (_: Exception) { "" }
            else -> ""
        }
    }
}
