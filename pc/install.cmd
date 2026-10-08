@echo off
cd /d "%~dp0"
py -3.12 -m venv .venv
if errorlevel 1 goto fail
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
echo Installation complete. Run start.cmd.
pause
exit /b 0
:fail
echo Installation failed. Install Python 3.12 from python.org and try again.
pause
exit /b 1
