@echo off
rem get-saves.bat: packs this PC's Darkest Dungeon saves into darkest-dungeon-saves.zip next to this
rem file, for "Import" under "Save file" in the Darkest Dungeon Porter app. It takes the folder the
rem GOG game keeps its saves in, Documents\Darkest, or a save folder dropped onto this file (such as
rem Steam's userdata\<number>\262060\remote). Plain batch with tar, built into Windows 10 (1803) and
rem newer.
rem (tar is called by full path: Git, Cygwin or MSYS can put a Unix tar first in PATH. The zip is
rem deflated: tar's stored entries carry data descriptors, which the app's ZipInputStream rejects.)
setlocal EnableExtensions
set "ZIP=%~dp0darkest-dungeon-saves.zip"
set "TAR=%SystemRoot%\System32\tar.exe"
call :main "%~1"
if errorlevel 1 echo. & echo Error: %ERRMSG%
echo.
pause
exit /b

:main
if not exist "%TAR%" set "ERRMSG=This needs Windows 10 version 1803 or newer, which has tar.exe." & exit /b 1
set "SAVES=%~1"
if defined SAVES goto :pack
rem Documents can be moved (to OneDrive, say); Shell Folders has where it is now.
set "DOCS=%USERPROFILE%\Documents"
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Microsoft\Windows\CurrentVersion\Explorer\Shell Folders" /v Personal 2^>nul ^| "%SystemRoot%\System32\find.exe" "REG_SZ"') do set "DOCS=%%b"
set "SAVES=%DOCS%\Darkest"

:pack
if "%SAVES:~-1%"=="\" set "SAVES=%SAVES:~0,-1%"
if not exist "%SAVES%\profile_*" set "ERRMSG=There are no saves (profile_0, profile_1 and so on) in %SAVES%. For Steam saves, drop their folder onto this file." & exit /b 1
for %%d in ("%SAVES%") do set "PARENT=%%~dpd" & set "NAME=%%~nxd"
set "PARENT=%PARENT:~0,-1%"
echo Packing %SAVES% into %ZIP%...
"%TAR%" -c -f "%ZIP%.part" --format zip --options zip:compression=deflate,zip:compression-level=1 -C "%PARENT%" "%NAME%"
if errorlevel 1 del "%ZIP%.part" 2>nul & set "ERRMSG=Packing failed, see the message above." & exit /b 1
move /y "%ZIP%.part" "%ZIP%" >nul
echo.
echo Done: %ZIP%
echo Copy it to the phone, open Darkest Dungeon Porter, tap Import under Save file and pick it.
exit /b 0
