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

3. 최신 프로젝트 빌드 (테스트 제외)
// turbo
```bash
.\gradlew clean build -x test
```

4. 빌드된 jar 파일을 배포 폴더로 복사 (`image-to-excel.jar` 로 이름 간소화)
```bash
Copy-Item build\libs\image-to-excel-0.0.1-SNAPSHOT.jar ~\Desktop\image-to-excel-deploy\image-to-excel.jar
```

5. `.env` 템플릿 파일 생성
- 해당 서버가 실행될 PC에서 Google Cloud API Key를 입력해야 하므로 빈 템플릿을 제공합니다.
```bash
echo "GOOGLE_CLOUD_API_KEY=your_api_key_here" > ~\Desktop\image-to-excel-deploy\.env
```

6. `start.bat` 실행 스크립트 작성
- USB 등 경로가 바뀌어도 실행될 수 있도록 현재 디렉토리(`/d "%~dp0"`)로 이동하는 로직을 추가합니다.
- `.env` 파일 누락 시 경고를 띄웁니다.
```powershell
$batContent = "@echo off`r`ncd /d `"%~dp0`"`r`n`r`nif not exist `".env`" (`r`n    echo [Error] .env file not found.`r`n    echo Please check if the .env file is present in the same folder as this running script.`r`n    pause`r`n    exit /b 1`r`n)`r`n`r`necho Starting Image-to-Excel Server...`r`njava -Dfile.encoding=UTF-8 -jar image-to-excel.jar`r`n`r`npause"`r`nSet-Content -Path ~\Desktop\image-to-excel-deploy\start.bat -Value $batContent -Encoding UTF8
```

완료! 바탕화면의 `image-to-excel-deploy` 폴더를 통째로 USB에 복사하여 배포하시면 됩니다.
