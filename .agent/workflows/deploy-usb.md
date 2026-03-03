---
description: [USB 배포용 폴더 만들기 (빌드 및 패키징)]
---

이 워크플로우는 `image-to-excel` 프로젝트를 빌드하여 USB 등에 담아 다른 PC에서 독립적으로 실행할 수 있도록 배포 폴더를 구성하는 방법을 안내합니다.

1. 기존 배포 파일 정리 (이전에 바탕화면에 흩어진 파일이 있다면 삭제)
```bash
Remove-Item -Force ~\Desktop\image-to-excel-deploy.jar, ~\Desktop\start-excel-app.bat -ErrorAction SilentlyContinue
```

2. 배포용 폴더 생성
```bash
mkdir -p ~\Desktop\image-to-excel-deploy
```

3. 8080 포트를 사용 중인 프로세스가 있으면 종료
```bash
for /f "tokens=5" %a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do taskkill /F /PID %a 2>nul
```

4. 최신 프로젝트 빌드 (테스트 제외)
// turbo
```bash
.\gradlew clean build -x test
```

5. 빌드된 jar 파일을 배포 폴더로 복사 (`image-to-excel.jar` 로 이름 간소화)
```bash
Copy-Item build\libs\image-to-excel-0.0.1-SNAPSHOT.jar ~\Desktop\image-to-excel-deploy\image-to-excel.jar
```

6. `.env` 템플릿 파일 생성
- 해당 서버가 실행될 PC에서 API Key를 입력해야 하므로 빈 템플릿을 제공합니다.
```bash
@"
GOOGLE_CLOUD_API_KEY=your_api_key_here
JUSO_API_KEY=your_juso_api_key_here
NAVER_CLIENT_ID=your_naver_client_id_here
NAVER_CLIENT_SECRET=your_naver_client_secret_here
"@ | Set-Content ~\Desktop\image-to-excel-deploy\.env -Encoding UTF8
```

7. `start.bat` 실행 스크립트 작성
- USB 등 경로가 바뀌어도 실행될 수 있도록 현재 디렉토리(`/d "%~dp0"`)로 이동하는 로직을 추가합니다.
- `.env` 파일 누락 시 경고를 띄웁니다.
- 8080 포트 충돌 시 기존 프로세스를 종료합니다.
- 서버 시작 5초 후 브라우저를 자동으로 엽니다.
```powershell
$batContent = @"
@echo off
cd /d "%~dp0"

if not exist ".env" (
    echo [Error] .env file not found.
    echo Please check if the .env file is present in the same folder as this running script.
    pause
    exit /b 1
)

echo Checking port 8080...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr :8080 ^| findstr LISTENING') do (
    echo Killing process on port 8080 (PID: %%a)
    taskkill /F /PID %%a >nul 2>&1
)

echo Starting Image-to-Excel Server...
start "" cmd /c "timeout /t 5 /nobreak >nul && start http://localhost:8080"
java -Dfile.encoding=UTF-8 -jar image-to-excel.jar

pause
"@
Set-Content -Path ~\Desktop\image-to-excel-deploy\start.bat -Value $batContent -Encoding UTF8
```

완료! 바탕화면의 `image-to-excel-deploy` 폴더를 통째로 USB에 복사하여 배포하시면 됩니다.
