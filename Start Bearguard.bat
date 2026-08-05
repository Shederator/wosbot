@echo off
REM Bearguard launcher.
REM
REM Two deliberate differences from upstream's "Start Frostguard.bat":
REM
REM 1. It points at the bundled Temurin JDK 21 rather than requiring java on
REM    PATH, because this machine has no system-wide Java install.
REM
REM 2. It launches with -cp rather than -jar. This matters: CustomTaskService
REM    compiles custom_tasks\*.java at runtime using java.class.path, and with
REM    -jar that property contains ONLY the thin app jar -- the manifest
REM    Class-Path entries are resolved by the classloader but never appear in
REM    the property. Custom tasks would fail to compile against DelayedTask.
REM    Listing the dependencies explicitly puts them on java.class.path, which
REM    is what a bundle install gets naturally by having lib\ beside the jar.
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

start "" "%JDK%\bin\javaw.exe" --enable-native-access=ALL-UNNAMED ^
    -cp "fg-app\target\frostguard-2.1.0.jar;fg-app\target\lib\*" ^
    dev.frostguard.app.bootstrap.Main
