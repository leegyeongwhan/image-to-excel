# Image to Excel

메신저 스크린샷에서 주문 정보(이름, 주소, 전화번호)를 자동으로 추출하여 Excel 파일로 변환하는 웹 애플리케이션입니다.

## 주요 기능

- **이미지 OCR 분석** - Google Cloud Vision API를 활용한 텍스트 인식
- **주석 주소 정제** - Juso(도로명주소) API 및 Naver Geocoding API를 활용한 추출된 주체의 유효성 검증 및 보정
- **주문 정보 자동 추출** - 이름, 주소, 전화번호를 구조화된 데이터로 파싱
- **Excel 다운로드** - 추출된 주문 정보를 `.xlsx` 파일로 내보내기
- **다중 이미지 처리** - 여러 장의 이미지를 동시에 업로드하고 병렬 처리 (최대 20건 동시 처리)
- **드래그 앤 드롭** - 파일을 끌어다 놓는 간편한 업로드 지원
- **API 사용량 추적** - 월별 호출 횟수 및 예상 요금 실시간 표시
- **자동 재시도** - API 호출 실패 시 지수 백오프 방식으로 최대 3회 재시도

<!-- 향후 시각적 스크린샷이나 동작 GIF 이미지를 이 영역에 추가하면 좋습니다! -->
<!-- 예시: ![앱 구동 화면 스크린샷](/docs/images/app_screenshot.png) -->
<!-- 예시: ![엑셀 변환 결과 캡처](/docs/images/excel_result.png) -->

## 기술 스택

| 구분      | 기술                                          |
| --------- | --------------------------------------------- |
| Language  | Kotlin 2.0                                    |
| Framework | Spring Boot 3.4                               |
| Template  | Thymeleaf                                     |
| Build     | Gradle                                        |
| Runtime   | Java 21                                       |
| OCR       | Google Cloud Vision API                       |
| Geocoding | 공공데이터포털(Juso) API, Naver Geocoding API |
| Excel     | Apache POI                                    |
| Async     | Kotlin Coroutines                             |

## 시스템 한계점 및 주의사항 (Limitations)

- **형태소/정규식 기반 추출**: 완벽한 AI 모델이 아닌 패턴 매칭 기술에 의존하므로, 사용자의 스크린샷에 예기치 않은 문구가 섞일 경우 오차가 발생할 수 있습니다.
- **OCR 인식 한계**: 해상도가 낮거나 배경이 복잡한 이미지는 텍스트 인식률이 저하됩니다.
- **언어 제약**: 한국어와 약간의 로마자 외에, 외국어(예: 태국어)가 감지된 텍스트 블록은 주소 추출에서 의도적으로 배제됩니다.
- **주소 검증 실패 시**: 모든 주소 정제 API 연동에 실패할 경우 UI상에서 붉은색 텍스트로 표기되며, 사용자의 수동 검수가 요구됩니다.

## 시작하기

### 사전 요구사항

- **Java 21** 이상 ([Adoptium](https://adoptium.net)에서 다운로드)
- **API Key 준비**
  - [Google Cloud Console](https://console.cloud.google.com) - Cloud Vision API 활성화
  - [공공데이터포털](https://www.data.go.kr/) - 도로명주소 검색 API 
  - [Naver Cloud Platform](https://www.ncloud.com/) - Geocoding API (선택, Fallback 용도)

### 설정

프로젝트 루트에 `.env` 파일을 생성하고 발급받은 API 키를 설정합니다.

```
GOOGLE_CLOUD_API_KEY=your_google_cloud_vision_api_key_here
JUSO_API_KEY=your_juso_api_key_here
NAVER_CLIENT_ID=your_naver_client_id_here
NAVER_CLIENT_SECRET=your_naver_client_secret_here
```

### 실행

**1. 개발 환경 모드**

```bash
./gradlew bootRun
```

**2. 💻 컴파일된 버전으로 실행 (USB 등 단독 로컬 배포용)**

이미 배포된 `image-to-excel-deploy` 폴더를 USB 등으로 가져온 경우, 아래와 같이 실행합니다.

1. `.env` 파일을 해당 폴더 안에 만들고 API 키들을 기입합니다. (동봉된 `start.bat`은 이 파일이 없으면 실행을 막습니다.)
2. `start.bat`을 더블클릭하면 서버가 시작되고 브라우저가 열립니다.

서버 주소: http://localhost:8080

## 사용 방법

1. 브라우저에서 `http://localhost:8080` 접속
2. 주문 정보가 포함된 메신저 스크린샷 이미지를 업로드 (PNG, JPG, JPEG)
3. **분석 시작** 버튼 클릭
4. 추출된 주문 정보(이름, 주소, 전화번호) 및 화면 내 자동 보정 오류(붉은 글씨) 유무 확인
5. **Excel 다운로드** 버튼으로 결과 저장

## 프로젝트 구조

```text
src/main/kotlin/com/imagetoexcel/
├── ImageToExcelApplication.kt      # 애플리케이션 진입점
├── controller/
│   ├── UploadController.kt          # 업로드/다운로드 화면 및 처리 API
│   └── GlobalExceptionHandler.kt    # 전역 뷰 및 REST 예외 처리
├── service/
│   ├── UploadService.kt             # 파일 업로드 및 전체 흐름 제어 로직
│   ├── AddressEnrichService.kt      # Juso/Naver Geocoding API 연동 주소 보정
│   ├── GoogleVisionOrderExtractor.kt # Vision API 연동 및 코루틴 병렬 처리
│   ├── OrderExcelGenerator.kt       # Apache POI Excel 생성기
│   ├── OrderExtractor.kt            # 추출기 공통 인터페이스
│   └── ApiUsageTracker.kt           # Google Vision API 사용 비용/횟수 추적기
├── domain/
│   ├── parser/
│   │   ├── OrderTextParser.kt       # 전화번호 등 전역 텍스트 파싱
│   │   └── AddressExtractor.kt      # 주소 유형별 정규식 패턴 추출 로직 모음
│   └── enum/
│       ├── GoogleApiError.kt        # API 에러 분류
│       └── KoreanRegion.kt          # 한국 행정구역 지역명 사전
├── dto/
│   ├── OrderData.kt                 # 주문 정보 데이터 전송 객체 (Address Valid 포함)
│   ├── JusoApiResponse.kt           # 주소 API 응답 규격
│   └── NaverGeocodingResponse.kt    # 네이버 API 응답 규격
└── config/
    ├── AppConfig.kt                 # Juso, Naver API 등 클라이언트 빈 설정
    ├── OpenAiConfig.kt              # 외부 AI 플랫폼 연동 설정
    └── exception/OrderException.kt  # 데이터 및 파싱 커스텀 예외 모음
```

## API 비용

Google Cloud Vision API는 월 **1,000건까지 무료**입니다. 초과 시 건당 $0.0015가 과금됩니다. 애플리케이션 내 사용량 대시보드에서 현재 사용량과 예상 요금을 확인할 수 있습니다.

## 라이선스

This project is proprietary software. All rights reserved.
