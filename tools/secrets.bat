@echo off
setlocal enabledelayedexpansion
cd /d "%~dp0.."

rem Moves personal files OUT of the project folder so they cannot be dragged into a
rem GitHub upload, then puts them back afterwards.
rem
rem GitHub's web uploader ignores .gitignore and uploads whatever you drag, so this
rem has to be an actual move, not a rule in a file.

set "VAULT=%USERPROFILE%\AiInterviewer-private"
set "FILES=tools\deepseek_key.txt tools\cv.txt tools\job.txt"

if /i "%~1"=="hide"    goto hide
if /i "%~1"=="restore" goto restore
if /i "%~1"=="status"  goto status

echo.
echo   Usage:  hide_secrets.bat      move them out, before uploading
echo           restore_secrets.bat   put them back, after uploading
echo.
pause
exit /b 1


:hide
echo.
echo Moving personal files out of the project folder...
echo.
if not exist "%VAULT%" mkdir "%VAULT%"

for %%F in (%FILES%) do (
    if exist "%%F" (
        move /y "%%F" "%VAULT%\%%~nxF" >nul
        echo   moved out   %%F
    ) else (
        echo   not found   %%F
    )
)

echo.
echo Checking the project folder for anything sensitive...
set "LEFTOVER=0"
for %%F in (%FILES%) do (
    if exist "%%F" (
        echo   STILL PRESENT: %%F
        set "LEFTOVER=1"
    )
)

echo.
if "!LEFTOVER!"=="0" (
    echo   Clean. Safe to upload to GitHub.
) else (
    echo   Something is still there. Do NOT upload yet.
)
echo.
echo   Files are parked at:
echo     %VAULT%
echo.
echo   Run restore_secrets.bat when you are done uploading.
echo   Remember: the local preview will not run until you restore the key.
echo.
pause
exit /b 0


:restore
echo.
echo Putting personal files back...
echo.
if not exist "%VAULT%" (
    echo   No parked files found at:
    echo     %VAULT%
    echo.
    pause
    exit /b 1
)

for %%F in (%FILES%) do (
    if exist "%VAULT%\%%~nxF" (
        move /y "%VAULT%\%%~nxF" "%%F" >nul
        echo   restored    %%F
    ) else (
        echo   not parked  %%F
    )
)

echo.
echo   Done. Check that the files are back and not empty.
echo.
pause
exit /b 0


:status
echo.
echo   Parked folder: %VAULT%
echo.
for %%F in (%FILES%) do (
    if exist "%%F" (
        echo   in project  %%F
    ) else (
        echo   parked      %%F
    )
)
echo.
pause
exit /b 0
