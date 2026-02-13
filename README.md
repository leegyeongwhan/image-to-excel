# Image to Excel

메신저 스크린샷에서 주문 정보(이름, 주소, 전화번호)를 자동으로 추출하여 Excel 파일로 변환하는 웹 애플리케이션입니다.

## 주요 기능

- **이미지 OCR 분석** - Google Cloud Vision API를 활용한 텍스트 인식
- **주문 정보 자동 추출** - 이름, 주소, 전화번호를 구조화된 데이터로 파싱
- **Excel 다운로드** - 추출된 주문 정보를 `.xlsx` 파일로 내보내기
- **다중 이미지 처리** - 여러 장의 이미지를 동시에 업로드하고 병렬 처리 (최대 20건 동시 처리)
- **드래그 앤 드롭** - 파일을 끌어다 놓는 간편한 업로드 지원
- **API 사용량 추적** - 월별 호출 횟수 및 예상 요금 실시간 표시
- **자동 재시도** - API 호출 실패 시 지수 백오프 방식으로 최대 3회 재시도

## 기술 스택

| 구분 | 기술 |
|------|------|
| Language | Kotlin 2.0 |
| Framework | Spring Boot 3.4 |
| Template | Thymeleaf |
| Build | Gradle |
| Runtime | Java 21 |
| OCR | Google Cloud Vision API |
| Excel | Apache POI |
| Async | Kotlin Coroutines |

## 시작하기

### 사전 요구사항

- **Java 21** 이상 ([Adoptium](https://adoptium.net)에서 다운로드)
- **Google Cloud API Key** ([Google Cloud Console](https://console.cloud.google.com)에서 발급)
  - Cloud Vision API 활성화 필요

### 설정

프로젝트 루트에 `.env` 파일을 생성하고 API 키를 설정합니다.

```
GOOGLE_CLOUD_API_KEY=your_api_key_here
```

### 실행

**개발 환경**

```bash
./gradlew bootRun
```

**배포용 (Windows)**

```bash
# 빌드
./gradlew build

# 실행 (start.bat 더블클릭 또는)
java -jar build/libs/image-to-excel-0.0.1-SNAPSHOT.jar
```

`start.bat`을 더블클릭하면 서버가 시작되고 브라우저가 자동으로 열립니다.

서버 주소: http://localhost:8080

## 사용 방법

1. 브라우저에서 `http://localhost:8080` 접속
2. 주문 정보가 포함된 메신저 스크린샷 이미지를 업로드 (PNG, JPG, JPEG)
3. **분석 시작** 버튼 클릭
4. 추출된 주문 정보(이름, 주소, 전화번호) 확인
5. **Excel 다운로드** 버튼으로 결과 저장

## 프로젝트 구조

```
src/main/kotlin/com/imagetoexcel/
├── ImageToExcelApplication.kt      # 애플리케이션 진입점
├── controller/
│   ├── UploadController.kt          # 업로드/다운로드 API
│   └── GlobalExceptionHandler.kt    # 전역 예외 처리
├── service/
│   ├── UploadService.kt             # 업로드 비즈니스 로직
│   ├── GoogleVisionOrderExtractor.kt # Vision API 연동 및 병렬 처리
│   ├── OrderExcelGenerator.kt       # Excel 파일 생성
│   ├── OrderExtractor.kt            # 추출기 인터페이스
│   └── ApiUsageTracker.kt           # API 사용량 추적
├── domain/
│   ├── OrderTextParser.kt           # 텍스트 → 주문 정보 파싱
│   └── enum/
│       ├── GoogleApiError.kt        # API 에러 분류
│       └── KoreanRegion.kt          # 한국 지역명 사전
├── dto/
│   └── OrderData.kt                 # 주문 데이터 DTO
└── config/
    ├── OpenAiConfig.kt              # 외부 API 설정
    └── exception/OrderException.kt  # 커스텀 예외
```

## API 비용

Google Cloud Vision API는 월 **1,000건까지 무료**입니다. 초과 시 건당 $0.0015가 과금됩니다. 애플리케이션 내 사용량 대시보드에서 현재 사용량과 예상 요금을 확인할 수 있습니다.

## 라이선스

This project is proprietary software. All rights reserved.
