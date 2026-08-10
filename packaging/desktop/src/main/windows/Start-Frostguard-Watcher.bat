@echo off
setlocal enabledelayedexpansion

:: -------------------------------------------------------
:: Frostguard Telegram Watcher launcher
:: Searches for frostguard-watcher-*.jar starting from this
:: script's folder and walking up to the project root.
:: -------------------------------------------------------

set "JAR="
set "SEARCH_DIR=%~dp0"

for /l %%i in (1,1,5) do (
    for %%f in ("!SEARCH_DIR!frostguard-watcher*.jar") do (
        set "CAND=%%~nxf"
        if /I not "!CAND:original-=!"=="!CAND!" (
            rem skip original-* backup artifact
        ) else if /I not "!CAND:-shaded=!"=="!CAND!" (
            rem skip *-shaded duplicate artifact
        ) else (
            set "JAR=%%f"
            goto :found
        )
    )
    for %%f in ("!SEARCH_DIR!modules\watcher\target\frostguard-watcher*.jar") do (
        set "CAND=%%~nxf"
        if /I not "!CAND:original-=!"=="!CAND!" (
            rem skip original-* backup artifact
        ) else if /I not "!CAND:-shaded=!"=="!CAND!" (
            rem skip *-shaded duplicate artifact
        ) else (
            set "JAR=%%f"
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
echo Log: %USERPROFILE%\.frostguard\tg-watcher.log
start "FG-TG-Watcher" /b javaw -jar "%JAR%"
exit /b 0
