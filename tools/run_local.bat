@echo off
setlocal
cd /d "%~dp0.."

rem Same interpreter as run_check.bat, so PATH and the Microsoft Store stub
rem are both bypassed.
set "PY=C:\Users\HP\AppData\Local\Programs\Python\Python311\python.exe"

if not exist "%PY%" (
    echo.
    echo Could not find Python at:
    echo   %PY%
    echo.
    echo Open this file and correct the PY line to the right location.
    echo.
    pause
    exit /b 1
)

"%PY%" "tools\local_test.py" %*

echo.
echo ------------------------------------------------------------
echo Done. You can close this window.
pause
