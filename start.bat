@echo off
chcp 65001 >nul
title Image to Excel

echo ============================================
echo   Image to Excel - Starting...
echo ============================================
echo.

java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java가 설치되어 있지 않습니다.
    echo https://adoptium.net 에서 JDK 21을 설치해주세요.
    echo.
    pause
    exit /b 1
)

echo Starting server...
echo Browser will open at http://localhost:8080
echo Close this window to stop the server.
echo.

start "" http://localhost:8080
java -jar image-to-excel-0.0.1-SNAPSHOT.jar

pause
