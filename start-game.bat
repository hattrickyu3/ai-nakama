@echo off
rem Start the development Minecraft with the AI Nakama mod.
cd /d "%~dp0"
set "JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-25.0.4.101-hotspot"
call gradlew.bat runClient
pause
