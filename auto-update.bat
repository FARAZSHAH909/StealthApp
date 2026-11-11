@echo off
title Stealth Monitor — Live Auto-Updater (Watch + Build + Push)
echo ============================================
echo  STEALTH MONITOR — LIVE AUTO-UPDATER
echo ============================================
echo.
echo  Kya hota hai:
echo    1. Aap is folder mein koi bhi code file edit/save karo
echo    2. APK automatically rebuild ho jaayegi
echo    3. Phone mein automatically install/update ho jaayegi (ADB)
echo.

if not defined JAVA_HOME (
    if exist "C:\Program Files\Android\Android Studio\jbr" (
        set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
    )
)

set "ADB_BIN=adb"
where adb >nul 2>nul
if %errorlevel% neq 0 (
    if exist "%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe" (
        set "ADB_BIN=%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"
    )
)

echo Java: %JAVA_HOME%
echo ADB:  %ADB_BIN%
echo.

powershell -NoProfile -ExecutionPolicy Bypass -Command "
$env:JAVA_HOME = '%JAVA_HOME%'
$adb = '%ADB_BIN%'

$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = '%~dp0'
$watcher.Filter = '*.*'
$watcher.IncludeSubdirectories = $true
$watcher.NotifyFilter = [System.IO.NotifyFilters]::LastWriteTime -bor [System.IO.NotifyFilters]::FileName

$lastRun = [DateTime]::MinValue

$watcher.Changed += { 
    $name = $_.Name
    if ($name -match '\.(java|xml|gradle|kts)$' -and $name -notmatch '\\build\\') {
        $now = [DateTime]::Now
        if (($now - $script:lastRun).TotalSeconds -lt 2) { return }
        $script:lastRun = $now

        Write-Host ''
        Write-Host ('  [' + ($now.ToString('HH:mm:ss')) + '] Code Changed: ' + $name) -ForegroundColor Green
        Write-Host '  Building APK...' -ForegroundColor Yellow
        $buildOut = & '.\gradlew.bat' assembleDebug 2>&1
        if ($LASTEXITCODE -eq 0) {
            Write-Host '  Build SUCCESS! Pushing update to phone...' -ForegroundColor Green
            & $adb install -r app\build\outputs\apk\debug\app-debug.apk
            Write-Host '  Phone updated successfully!' -ForegroundColor Cyan
        } else {
            Write-Host '  Build FAILED! Details:' -ForegroundColor Red
            $buildOut | Select-Object -Last 10
        }
        Write-Host ''
        Write-Host 'Watching for next change...' -ForegroundColor DimGray
    }
}

Write-Host 'Watching for code changes... (Press Ctrl+C to stop)' -ForegroundColor Green
$watcher.EnableRaisingEvents = $true
while ($true) { Start-Sleep -Seconds 1 }
"

pause
