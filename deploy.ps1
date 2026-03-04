$utf8NoBom = New-Object System.Text.UTF8Encoding $false
$deployDir = Join-Path $HOME "Desktop\image-to-excel-deploy"

# Create deploy folder if not exists
if (!(Test-Path $deployDir)) { New-Item -ItemType Directory -Path $deployDir | Out-Null }

# Kill port 8080 if in use
$connections = netstat -ano | Select-String ':8080.*LISTENING'
foreach ($conn in $connections) {
    $procId = ($conn -split '\s+')[-1]
    if ($procId -match '^\d+$') {
        taskkill /F /PID $procId 2>$null
    }
}

# Build
.\gradlew clean build -x test

# Copy jar
Copy-Item build\libs\image-to-excel-0.0.1-SNAPSHOT.jar "$deployDir\image-to-excel.jar" -Force

# Copy .env from project (real keys)
Copy-Item .env "$deployDir\.env" -Force

# Create start.bat (no BOM)
$batLines = @(
    '@echo off'
    'cd /d "%~dp0"'
    ''
    'if not exist ".env" ('
    '    echo [Error] .env file not found.'
    '    echo Please check if the .env file is present in the same folder as this running script.'
    '    pause'
    '    exit /b 1'
    ')'
    ''
    'echo Checking port 8080...'
    'for /f "tokens=5" %%a in (''netstat -ano ^| findstr :8080 ^| findstr LISTENING'') do ('
    '    echo Killing process on port 8080 (PID: %%a)'
    '    taskkill /F /PID %%a >nul 2>&1'
    ')'
    ''
    'echo Starting Image-to-Excel Server...'
    'start "" cmd /c "timeout /t 5 /nobreak >nul && start http://localhost:8080"'
    'java -Dfile.encoding=UTF-8 -jar image-to-excel.jar'
    ''
    'pause'
)
$batContent = $batLines -join "`r`n"
[System.IO.File]::WriteAllText("$deployDir\start.bat", $batContent, $utf8NoBom)

Write-Host "Deploy completed! -> $deployDir"
