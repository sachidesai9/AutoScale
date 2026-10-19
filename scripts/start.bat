@echo off
rem Starts the dashboard on http://localhost:8080 (builds first if needed). Data is kept in .\data
cd /d "%~dp0.."
if not exist dist\autoscalesim-lab.jar powershell -ExecutionPolicy Bypass -File scripts\build.ps1
if "%PORT%"=="" set PORT=8080
echo Open http://localhost:%PORT%/ in your browser. Press Ctrl+C to stop.
java -jar dist\autoscalesim-lab.jar serve --port %PORT% --data-dir data
