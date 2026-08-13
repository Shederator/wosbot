@echo off
set "TOOLS=C:\Frostguard-tools"
set "JAVA_HOME=%TOOLS%\jdk-21.0.12+8"
set "PATH=%JAVA_HOME%\bin;%PATH%"
cd /d C:\Bearguard
if not exist "%TEMP%\bgcheck2" mkdir "%TEMP%\bgcheck2"
javac -cp "fg-app\target\classes;fg-app\target\lib\*" -d "%TEMP%\bgcheck2" custom_tasks\bg_powerpriorities.java
echo === COMPILE EXIT: %ERRORLEVEL% ===
