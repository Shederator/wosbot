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
REM
REM matt/Claude, 2026-08-17: updated for the upstream module restructure. The old
REM fg-app\target\frostguard-2.1.1.jar + fg-app\target\lib\ layout is gone -- run
REM fg-build.bat first, which now packages via packaging/desktop and produces the
REM real jar + lib\ classpath under packaging\desktop\target\input\.
REM
REM matt/Claude, 2026-08-17 (later): Dave's upstream restructure added
REM WorkspacePaths, which auto-detects a "development workspace" whenever
REM the app's working directory sits inside a source checkout (finds pom.xml
REM + modules/desktop) and NO explicit -Dfrostguard.workspace is passed --
REM it then silently uses a brand-new .frostguard-dev/ database instead of
REM the real one, which looked like every setting had been wiped. Passing
REM the workspace explicitly here pins it to this folder's frostguard.db
REM every launch, so it can never drift onto that dev-detection path again.
setlocal EnableExtensions
cd /d "%~dp0"

REM frostguard-tools moved out of OneDrive sync (bundled binaries + a
REM constantly-changing SQLite WAL file are a bad fit for cloud sync).
set "JDK=C:\Frostguard-tools\jdk-21.0.12+8"

if not exist "%JDK%\bin\javaw.exe" (
    echo [ERROR] Bundled JDK not found at:
    echo   %JDK%
    pause
    exit /b 1
)

set "APP_JAR="
for %%F in ("packaging\desktop\target\input\frostguard-desktop-*.jar") do set "APP_JAR=%%~nxF"

if not defined APP_JAR (
    echo [ERROR] Bearguard is not built yet.
    echo Run fg-build.bat first, then start again.
    pause
    exit /b 1
)

start "" "%JDK%\bin\javaw.exe" --enable-native-access=ALL-UNNAMED ^
    -Dfrostguard.workspace="%~dp0" ^
    -cp "packaging\desktop\target\input\%APP_JAR%;packaging\desktop\target\input\lib\*" ^
    dev.frostguard.app.bootstrap.Main
