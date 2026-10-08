@echo off
cd /d "%~dp0"
set "Q52_PYTHON=py -3"
%Q52_PYTHON% -c "import sys; sys.exit(0 if sys.version_info >= (3, 12) else 1)" >nul 2>&1
if errorlevel 1 set "Q52_PYTHON=python"
%Q52_PYTHON% -c "import sys, tkinter; sys.exit(0 if sys.version_info >= (3, 12) else 1)"
if errorlevel 1 goto fail
%Q52_PYTHON% -m venv .venv
if errorlevel 1 goto fail
.venv\Scripts\python.exe -m pip install -r requirements.txt
if errorlevel 1 goto fail
echo Installation complete. Run start.cmd.
pause
exit /b 0
:fail
echo Installation failed. Python 3.12 or newer with tkinter is required.
echo Python 3.14 is supported. Check the error above and your Python installation.
pause
exit /b 1
