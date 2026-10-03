@echo off
setlocal enabledelayedexpansion

:: -------------------------------------------------------
:: Frostguard Telegram Watcher launcher
:: Searches for frostguard-watcher-*.jar starting from this
:: script's folder and walking up to the project root.
:: -------------------------------------------------------

set "JAR="
set "SEARCH_DIR=%~dp0"

for /l %%i in (1,1,6) do (
    for /f "delims=" %%f in ('dir /b /a-d /o-d "!SEARCH_DIR!frostguard-watcher*.jar" 2^>nul') do (
        set "CAND=%%~nxf"
        if /I not "!CAND:original-=!"=="!CAND!" (
            rem skip original-* backup artifact
        ) else if /I not "!CAND:-shaded=!"=="!CAND!" (
            rem skip *-shaded duplicate artifact
        ) else (
            set "JAR=!SEARCH_DIR!%%f"
            goto :found
        )
    )
    for /f "delims=" %%f in ('dir /b /a-d /o-d "!SEARCH_DIR!modules\watcher\target\frostguard-watcher*.jar" 2^>nul') do (
        set "CAND=%%~nxf"
        if /I not "!CAND:original-=!"=="!CAND!" (
            rem skip original-* backup artifact
        ) else if /I not "!CAND:-shaded=!"=="!CAND!" (
            rem skip *-shaded duplicate artifact
        ) else (
            set "JAR=!SEARCH_DIR!modules\watcher\target\%%f"
            goto :found
        )
    )
    for %%P in ("!SEARCH_DIR!..") do set "SEARCH_DIR=%%~fP\"
)

echo ERROR: frostguard-watcher jar not found.
echo Looked in and around: %~dp0
echo Build the project first: mvnw.cmd package -DskipTests
pause
exit /b 1

:found
echo Starting Frostguard Telegram Watcher (background)...
echo JAR: %JAR%
start "FG-TG-Watcher" /b javaw -jar "%JAR%"
exit /b 0
