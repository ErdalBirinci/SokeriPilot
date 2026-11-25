@echo off
rem Wrapper for the daily live-site watch.
rem   schtasks /Create /SC DAILY /ST 09:00 /TN "SokeriPilot site watch" ^
rem            /TR "\"%CD%\tools\watch-task.cmd\"" /F
rem Remove with: schtasks /Delete /TN "SokeriPilot site watch" /F
rem WATCH_WEBHOOK can be set as a user environment variable to get a POST on failure.
cd /d "%~dp0.."
if not exist dist mkdir dist
node tools\watch.mjs >> dist\watch.log 2>&1
