package com.imagetoexcel.domain.enum

enum class KoreanRegion(val keyword: String) {
    SEOUL("서울"),
    BUSAN("부산"),
    DAEGU("대구"),
    INCHEON("인천"),
    GWANGJU("광주"),
    DAEJEON("대전"),
    ULSAN("울산"),
    SEJONG("세종"),
    GYEONGGI("경기"),
    GANGWON("강원"),
    CHUNGBUK("충북"),
    CHUNGNAM("충남"),
    JEONBUK("전북"),
    JEONNAM("전남"),
    GYEONGBUK("경북"),
    GYEONGNAM("경남"),
    JEJU("제주"),
    SPECIAL_CITY("특별시"),
    METROPOLITAN_CITY("광역시"),
    SPECIAL_AUTONOMOUS("특별자치"),
    CHUNGCHEONGBUK_DO("충청북도"),
    CHUNGCHEONGNAM_DO("충청남도"),
    JEOLLABUK_DO("전라북도"),
    JEOLLANAM_DO("전라남도"),
    GYEONGSANGBUK_DO("경상북도"),
    GYEONGSANGNAM_DO("경상남도"),
    GANGWON_DO("강원도"),
    GYEONGGI_DO("경기도"),
    JEJU_DO("제주도");

    companion object {
        private val ALL_KEYWORDS = entries.map { it.keyword }

        fun containsAny(text: String): Boolean =
            ALL_KEYWORDS.any { text.contains(it) }
    }
}
