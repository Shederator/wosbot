@echo off
setlocal EnableExtensions
cd /d "%~dp0"
where java >nul 2>&1 || (echo [ERROR] Java 21 or newer is required.&amp; exit /b 1)
set "APP_JAR="
for %%F in ("app\frostguard-*.jar") do if exist "%%~fF" set "APP_JAR=%%~fF"
if not defined APP_JAR (echo [ERROR] Frostguard application JAR is missing.&amp; exit /b 1)
java --enable-native-access=ALL-UNNAMED -jar "%APP_JAR%" %*
