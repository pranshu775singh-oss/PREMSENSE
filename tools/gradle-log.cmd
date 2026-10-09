@echo off
rem Runs Gradle and records the full output and the exit code under build-logs\ so the result
rem can be read afterwards without scrolling a terminal.  Usage: tools\gradle-log.cmd :core:test
cd /d "%~dp0.."
if not exist build-logs mkdir build-logs
if exist build-logs\last.exit del build-logs\last.exit
call "%~dp0..\gradlew.bat" %* --console=plain > build-logs\last.log 2>&1
echo %ERRORLEVEL% > build-logs\last.exit
