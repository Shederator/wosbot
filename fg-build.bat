@echo off
set "PATH=C:\apache-maven-3.9.12\bin;%PATH%"
echo ==========================================
echo      Frostguard Quick Recompile Script
echo ==========================================

echo.
echo Building verified portable distribution...
call mvn clean package -Dfrostguard.platform=win -Djavafx.platform=win
if errorlevel 1 (
    echo [WARN] First build attempt failed. Applying quick cleanup for transient resource-copy issues...
    if exist "fg-vision\target" rmdir /S /Q "fg-vision\target"
    timeout /t 2 >nul

    echo.
    echo Retrying build once...
    call mvn clean package -Dfrostguard.platform=win -Djavafx.platform=win
    if errorlevel 1 (
        echo [ERROR] Build failed after retry!
        pause
        exit /b %errorlevel%
    )
)

echo.
echo Verifying packaged app JAR integrity...
set "APP_JAR="
for %%F in ("fg-app\target\frostguard-*.jar") do set "APP_JAR=%%~fF"

if not defined APP_JAR (
    echo [ERROR] App JAR not found in fg-app\target.
    pause
    exit /b 1
)

where jar >nul 2>&1
if errorlevel 1 (
    echo [WARN] 'jar' tool not found in PATH. Skipping JAR content verification.
) else (
    jar tf "%APP_JAR%" | findstr /C:"dev/frostguard/app/panel/launcher/LauncherLayoutController.class" >nul
    if errorlevel 1 (
        echo [WARN] LauncherLayoutController.class missing in packaged JAR. Rebuilding fg-app once...
        call mvn -pl fg-app -am clean package -DskipTests
        if errorlevel 1 (
            echo [ERROR] Fallback fg-app rebuild failed!
            pause
            exit /b %errorlevel%
        )

        set "APP_JAR="
        for %%F in ("fg-app\target\frostguard-*.jar") do set "APP_JAR=%%~fF"
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

set "OUTPUT_DIR=%CD%\fg-distribution\target"
set "BUNDLE_ZIP="
for %%F in ("fg-distribution\target\*-win.zip") do set "BUNDLE_ZIP=%%~fF"

if defined BUNDLE_ZIP (
    echo Opening desktop bundle ZIP: %BUNDLE_ZIP%
    start "" explorer /select,"%BUNDLE_ZIP%"
) else if exist "%OUTPUT_DIR%" (
    echo [WARN] Desktop bundle ZIP not found. Opening output directory: %OUTPUT_DIR%
    start "" explorer "%OUTPUT_DIR%"
) else (
    echo [WARN] Output directory not found: %OUTPUT_DIR%
)

pause

