@echo off
cd /d "%~dp0"
set "ATTENDANCE_PYTHON=py -3"
%ATTENDANCE_PYTHON% -c "import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)" >nul 2>&1
if errorlevel 1 set "ATTENDANCE_PYTHON=python"
%ATTENDANCE_PYTHON% -c "import sys, tkinter; sys.exit(0 if sys.version_info >= (3, 12) else 1)"
if errorlevel 1 goto fail
%ATTENDANCE_PYTHON% -m venv .venv
if errorlevel 1 goto fail
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0enable-startup.ps1"
if errorlevel 1 echo Automatic startup registration failed. Enable it in settings.
echo Installation complete. Run start.cmd.
pause
exit /b 0
:fail
echo Installation failed. Python 3.12 or newer with tkinter is required.
echo Python 3.14 is supported. Check the error above and your Python installation.
pause
exit /b 1
