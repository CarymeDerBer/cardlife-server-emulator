@echo off
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0"
title CardLife Launcher

echo ============================================================
echo  CardLife Launcher - build and start
echo ============================================================
echo.
echo [1/3] Checking JDK 17 and libraries (downloads only what is missing)...
if exist ".tools\java_home.txt" del /q ".tools\java_home.txt" >nul 2>&1
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1"
if errorlevel 1 (
  echo.
  echo [ERROR] JDK 17 is not available and could not be downloaded.
  echo         Install JDK 17 manually: https://adoptium.net/temurin/releases/?version=17
  echo         then run start.bat again.
  pause
  exit /b 1
)
if not exist ".tools\java_home.txt" (
  echo [ERROR] bootstrap.ps1 did not report a JDK folder.
  pause
  exit /b 1
)
set /p JAVA_HOME=<".tools\java_home.txt"
if not exist "%JAVA_HOME%\bin\javac.exe" (
  echo [ERROR] "%JAVA_HOME%" has no javac.exe - a JDK is required, not a JRE.
  pause
  exit /b 1
)
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo.
echo [2/3] Compiling...
if exist build rmdir /s /q build
mkdir build\classes
dir /b *.java > build\sources.txt
if exist lib (set "CP=lib\*") else (set "CP=.")
"%JAVA_HOME%\bin\javac.exe" -encoding UTF-8 --release 17 -cp "%CP%" -d build\classes @build\sources.txt
if errorlevel 1 (
  echo.
  echo [ERROR] Compilation failed - see the messages above.
  pause
  exit /b 1
)
(echo Main-Class: cardlife.Main& echo Class-Path: lib/flatlaf-3.6.1.jar)> build\manifest.mf
"%JAVA_HOME%\bin\jar.exe" --create --file cardlife-launcher.jar --manifest build\manifest.mf -C build\classes .
if errorlevel 1 (
  echo [ERROR] Could not create cardlife-launcher.jar
  pause
  exit /b 1
)

echo.
echo [3/3] Starting launcher...
"%JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -jar cardlife-launcher.jar --launcher
if errorlevel 1 (
  echo.
  echo [ERROR] Launcher exited with an error. See launcher-error.log if it exists.
  pause
)
endlocal
