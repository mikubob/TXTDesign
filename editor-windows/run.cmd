@echo off
cd /d "%~dp0"
java -jar build\txteditor-windows.jar
if errorlevel 1 pause
