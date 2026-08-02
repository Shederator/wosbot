@echo off
setlocal EnableExtensions
cd /d "%~dp0"

where java >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java was not found.
    echo.
    echo Frostguard requires Java 21 or newer.
    echo Download Temurin from: https://adoptium.net/temurin/releases/?version=21
    goto :failed
)

set "JAVA_VERSION="
set "JAVA_MAJOR="
for /f "tokens=3" %%V in ('java -version 2^>^&1 ^| findstr /i "version"') do if not defined JAVA_VERSION set "JAVA_VERSION=%%~V"
for /f "tokens=1 delims=." %%V in ("%JAVA_VERSION%") do set "JAVA_MAJOR=%%V"

if not defined JAVA_MAJOR (
    echo [ERROR] Could not determine the installed Java version.
    goto :failed
)
set /a JAVA_MAJOR_NUMBER=JAVA_MAJOR >nul 2>&1
if %JAVA_MAJOR_NUMBER% LSS 21 (
    echo [ERROR] Java %JAVA_VERSION% is too old. Frostguard requires Java 21 or newer.
    echo Download Temurin from: https://adoptium.net/temurin/releases/?version=21
    goto :failed
)

set "APP_JAR="
set "APP_JAR_COUNT=0"
for %%F in ("frostguard-*.jar") do if exist "%%~fF" (
    set /a APP_JAR_COUNT+=1
    set "APP_JAR=%%~fF"
)

if %APP_JAR_COUNT% EQU 0 (
    echo [ERROR] No frostguard-*.jar was found next to this launcher.
    echo Extract the complete desktop bundle before starting Frostguard.
    goto :failed
)
if %APP_JAR_COUNT% GTR 1 (
    echo [ERROR] Multiple frostguard-*.jar files were found.
    echo Remove old versions or extract the bundle into an empty folder.
    goto :failed
)

echo Starting Frostguard with Java %JAVA_VERSION%...
java --enable-native-access=ALL-UNNAMED -jar "%APP_JAR%" %*
exit /b %errorlevel%

:failed
echo.
pause
exit /b 1
