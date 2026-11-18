@echo off
setlocal enabledelayedexpansion
title IPTV - Android TV Wireless Installer

set "ADB_EXE=C:\Android\Sdk\platform-tools\adb.exe"
set "APK_PATH=%~dp0dist\iptv.apk"

echo ========================================================
echo   IPTV - Android TV Installer
echo ========================================================
echo.

if not exist "%ADB_EXE%" (
    echo [ERROR] adb.exe not found at %ADB_EXE%
    pause
    exit /b 1
)

if not exist "%APK_PATH%" (
    echo [INFO] APK not found at %APK_PATH%. Building APK first...
    powershell -ExecutionPolicy Bypass -File "%~dp0build-apk.ps1"
    if errorlevel 1 (
        echo [ERROR] APK build failed.
        pause
        exit /b 1
    )
)

echo Before continuing on your Android TV:
echo  1. Go to Settings ^> System ^> About ^> Android TV OS build (click 7 times for Developer Options)
echo  2. Go to Settings ^> System ^> Developer options ^> Enable "USB debugging" (and Network/Wireless debugging if shown)
echo  3. Check your TV's IP address under Settings ^> Network ^& Internet
echo.

set /p TV_IP=Enter your Android TV IP address (e.g. 192.168.1.50): 
if "%TV_IP%"=="" (
    echo No IP entered. Aborting.
    pause
    exit /b 1
)

echo.
echo [1/4] Connecting to %TV_IP%:5555 ...
"%ADB_EXE%" disconnect >nul 2>&1
"%ADB_EXE%" connect %TV_IP%:5555

echo.
echo [2/4] Check your TV screen and tap "Always allow from this computer" if prompted!
timeout /t 3 /nobreak >nul
"%ADB_EXE%" devices

echo.
echo [3/4] Installing %APK_PATH% ...
"%ADB_EXE%" install -r "%APK_PATH%"
if errorlevel 1 (
    echo.
    echo [WARN] Install failed. Make sure you accepted the RSA prompt on your TV screen and try again.
    pause
    exit /b 1
)

echo.
echo [4/4] Launching IPTV on your Android TV ...
"%ADB_EXE%" shell monkey -p tv.iptv.player -c android.intent.category.LEANBACK_LAUNCHER 1 >nul 2>&1
if errorlevel 1 (
    "%ADB_EXE%" shell am start -n tv.iptv.player/.MainActivity
)

echo.
echo ========================================================
echo   SUCCESS! IPTV is installed and running on TV!
echo ========================================================
pause
