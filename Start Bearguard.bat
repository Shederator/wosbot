@echo off
REM Bearguard launcher using the bundled Temurin JDK 21 that ships alongside the
REM Frostguard tooling. The upstream "Start Frostguard.bat" requires java on PATH;
REM this machine has no system-wide Java, so point directly at the bundled runtime.
setlocal EnableExtensions
cd /d "%~dp0"

set "JDK=C:\Users\matt\OneDrive - Elucid Systems\Desktop\lol\frostguard-tools\jdk-21.0.12+8"

if not exist "%JDK%\bin\javaw.exe" (
    echo [ERROR] Bundled JDK not found at:
    echo   %JDK%
    pause
    exit /b 1
)

if not exist "fg-app\target\frostguard-2.1.0.jar" (
    echo [ERROR] Bearguard is not built yet.
    echo Run a Maven package first, then start again.
    pause
    exit /b 1
)

start "" "%JDK%\bin\javaw.exe" --enable-native-access=ALL-UNNAMED -jar "fg-app\target\frostguard-2.1.0.jar"
