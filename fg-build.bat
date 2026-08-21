@echo off
REM Paths corrected after the toolchain moved out of OneDrive. Maven and the
REM bundled Temurin JDK both live under C:\Frostguard-tools now; the old C:\apache-maven-3.9.12
REM path never existed on this machine, so `call mvn` below failed with "not recognized". JAVA_HOME
REM is set explicitly too, because there is no system-wide Java install for mvn to fall back on.
REM
REM Updated for the upstream module restructure (fg-app -> modules/desktop,
REM packaged via packaging/desktop). The old fg-app\target\frostguard-*.jar path is gone -- the real
REM launchable jar + lib/ classpath now live under packaging\desktop\target\input\, produced by the
REM new packaging module's dependency-copy step, not modules/desktop's own package phase.
set "JAVA_HOME=C:\Frostguard-tools\jdk-21.0.12+8"
set "PATH=C:\Frostguard-tools\apache-maven-3.9.9\bin;%JAVA_HOME%\bin;%PATH%"
echo ==========================================
echo      Bearguard Quick Recompile Script
echo ==========================================

echo.
echo Stopping any running Java and ADB processes...
taskkill /F /IM java.exe >nul 2>&1
taskkill /F /IM javaw.exe >nul 2>&1
taskkill /F /IM adb.exe >nul 2>&1
timeout /t 2 >nul

echo.
echo Building project (clean + package, packaging/desktop pulls in modules/desktop)...
call mvn -pl packaging/desktop -am clean package -DskipTests
if errorlevel 1 (
    echo [WARN] First build attempt failed. Applying quick cleanup for transient resource-copy issues...
    if exist "modules\vision\target" rmdir /S /Q "modules\vision\target"
    timeout /t 2 >nul

    echo.
    echo Retrying build once...
    call mvn -pl packaging/desktop -am clean package -DskipTests
    if errorlevel 1 (
        echo [ERROR] Build failed after retry!
        pause
        exit /b %errorlevel%
    )
)

echo.
echo Verifying packaged app JAR integrity...
set "APP_JAR="
for %%F in ("packaging\desktop\target\input\frostguard-desktop-*.jar") do set "APP_JAR=%%~fF"

if not defined APP_JAR (
    echo [ERROR] App JAR not found in packaging\desktop\target\input.
    pause
    exit /b 1
)

where jar >nul 2>&1
if errorlevel 1 (
    echo [WARN] 'jar' tool not found in PATH. Skipping JAR content verification.
) else (
    jar tf "%APP_JAR%" | findstr /C:"dev/frostguard/app/panel/launcher/LauncherLayoutController.class" >nul
    if errorlevel 1 (
        echo [WARN] LauncherLayoutController.class missing in packaged JAR. Rebuilding modules/desktop once...
        call mvn -pl modules/desktop -am clean package -DskipTests
        if errorlevel 1 (
            echo [ERROR] Fallback modules/desktop rebuild failed!
            pause
            exit /b %errorlevel%
        )
        call mvn -pl packaging/desktop clean package -DskipTests
        if errorlevel 1 (
            echo [ERROR] Fallback packaging/desktop rebuild failed!
            pause
            exit /b %errorlevel%
        )

        set "APP_JAR="
        for %%F in ("packaging\desktop\target\input\frostguard-desktop-*.jar") do set "APP_JAR=%%~fF"
        jar tf "%APP_JAR%" | findstr /C:"dev/frostguard/app/panel/launcher/LauncherLayoutController.class" >nul
        if errorlevel 1 (
            echo [ERROR] Packaged JAR is still incomplete after fallback rebuild.
            pause
            exit /b 1
        )
    )
)

echo.
echo ==========================================
echo BUILD SUCCESSFUL!
echo ==========================================
echo.

set "OUTPUT_DIR=%CD%\packaging\desktop\target\input"
if exist "%OUTPUT_DIR%" (
    echo Opening packaged app input directory: %OUTPUT_DIR%
    start "" explorer "%OUTPUT_DIR%"
) else (
    echo [WARN] Output directory not found: %OUTPUT_DIR%
)

pause
