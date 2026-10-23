@echo off
rem get-steam-game.bat: gets the Linux Darkest Dungeon from your Steam library and packs it into
rem darkest-dungeon-steam.zip next to this file, for the Darkest Dungeon Porter app's "Import game".
rem Steam must be running and logged in to an account that owns the game. Plain batch with tools
rem built into Windows 10 (1803) and newer: reg, tasklist, findstr, tar.
rem
rem Steam runs "steam.exe +download_depot <app> <depot>" in the running client and logs the result
rem to logs\console_log.txt; the depot lands in steamapps\content\app_<app>\depot_<depot>.
rem (Inside ( ) blocks, %paths% are avoided: "Program Files (x86)" would close the block. find and
rem tar are called by full path: Git, Cygwin or MSYS can put Unix tools of those names first in PATH.)
setlocal EnableExtensions
set "APP=262060"
set "OUT=%~dp0"
set "ZIP=%OUT%darkest-dungeon-steam.zip"
set "LIST=%TEMP%\darkest-dungeon-steam-files.txt"
set "TAR=%SystemRoot%\System32\tar.exe"
set "FIND=%SystemRoot%\System32\find.exe"
call :main
if errorlevel 1 echo. & echo Error: %ERRMSG%
if exist "%LIST%" del "%LIST%"
echo.
pause
exit /b

:main
if not exist "%TAR%" set "ERRMSG=This needs Windows 10 version 1803 or newer, which has tar.exe." & exit /b 1
set "STEAMEXE="
set "STEAMDIR="
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Valve\Steam" /v SteamExe 2^>nul ^| "%FIND%" "REG_SZ"') do set "STEAMEXE=%%b"
for /f "tokens=2,*" %%a in ('reg query "HKCU\Software\Valve\Steam" /v SteamPath 2^>nul ^| "%FIND%" "REG_SZ"') do set "STEAMDIR=%%b"
if not defined STEAMEXE set "ERRMSG=Steam is not installed." & exit /b 1
set "STEAMEXE=%STEAMEXE:/=\%"
set "STEAMDIR=%STEAMDIR:/=\%"
if not exist "%STEAMEXE%" set "ERRMSG=Steam is not installed." & exit /b 1
set "USERID=0x0"
for /f "tokens=3" %%a in ('reg query "HKCU\Software\Valve\Steam\ActiveProcess" /v ActiveUser 2^>nul ^| "%FIND%" "REG_DWORD"') do set "USERID=%%a"
tasklist /fi "imagename eq steam.exe" /nh 2>nul | "%FIND%" /i "steam.exe" >nul || set "USERID=0x0"
if "%USERID%"=="0x0" set "ERRMSG=Steam is not running. Start Steam, log in, then run this again." & exit /b 1
set "LOG=%STEAMDIR%\logs\console_log.txt"
set "CONTENT=%STEAMDIR%\steamapps\content\app_%APP%"

echo Downloading the Linux Darkest Dungeon through Steam (about 2.5 GB; the zip needs another 2.5 GB):
call :depot 262065 "game binaries"
if errorlevel 1 set "ERRMSG=Steam couldn't download the game binaries: %REASON%" & exit /b 1
call :depot 262066 "game data"
if errorlevel 1 set "ERRMSG=Steam couldn't download the game data: %REASON%" & exit /b 1

rem The phone needs only the DRM-free Linux build: no Steam build (_linux), no Windows/Xbox files.
> "%LIST%" echo -C
>> "%LIST%" echo(%CONTENT%\depot_262066
for /f "delims=" %%e in ('dir /b "%CONTENT%\depot_262066"') do if /i not "%%e"=="__build" if /i not "%%e"=="_xb1" >> "%LIST%" echo(%%e
>> "%LIST%" echo -C
>> "%LIST%" echo(%CONTENT%\depot_262065
>> "%LIST%" echo _linuxnosteam

call :dlc 445702 "The Musketeer"
call :dlc 580102 "The Crimson Court"
call :dlc 702542 "The Shieldbreaker"
call :dlc 735732 "The Color of Madness"
call :dlc 1117862 "The Butcher's Circus"
call :dlc 4964111 "Fire's Edge"

echo.
echo Packing the game into %ZIP% (about a minute)...
"%TAR%" -c -f "%ZIP%.part" --format zip --options zip:compression=deflate,zip:compression-level=1 -T "%LIST%"
if errorlevel 1 del "%ZIP%.part" 2>nul & set "ERRMSG=Packing failed, see the message above. Is the disk full?" & exit /b 1
move /y "%ZIP%.part" "%ZIP%" >nul

echo.
echo The zip holds everything now. Steam's downloaded copy is in %CONTENT%
choice /c YN /n /m "Delete Steam's downloaded copy? [Y/N] "
if errorlevel 255 goto :done
if errorlevel 2 goto :done
rd /s /q "%CONTENT%"
echo Deleted.
:done
echo.
echo Done: %ZIP%
echo Copy it to the phone, open Darkest Dungeon Porter, tap Import game and pick it.
echo You can delete the zip from the phone after the import.
exit /b 0

rem :dlc ID NAME: downloads a DLC depot and adds its dlc folder to the zip; skipped if not owned.
:dlc
call :depot %1 %2
if errorlevel 1 echo   skipped, not owned: %REASON%& exit /b 0
>> "%LIST%" echo -C
>> "%LIST%" echo(%CONTENT%\depot_%~1
>> "%LIST%" echo dlc
exit /b 0

rem :depot ID NAME: asks the running Steam to download a depot and waits for the result in its log.
rem Only log lines after the ones already there count. errorlevel 1 and REASON when Steam refuses.
:depot
set "ID=%~1"
set "REASON="
set "STARTED="
set "SKIP=0"
set "TICKS=0"
echo - %~2 (depot %~1)
if exist "%LOG%" for /f %%n in ('type "%LOG%" ^| "%FIND%" /c /v ""') do set "SKIP=%%n"
start "" "%STEAMEXE%" +download_depot %APP% %ID%
:depot_wait
ping -n 2 127.0.0.1 >nul
set /a TICKS+=1
set "DONE="
set "FAIL="
set "NEWSTART="
for /f "tokens=1 delims=:" %%a in ('findstr /n /c:"Depot download complete" "%LOG%" 2^>nul') do if %%a GTR %SKIP% set "DONE=1"
for /f "tokens=1* delims=:" %%a in ('findstr /n /c:"Depot download failed" "%LOG%" 2^>nul') do if %%a GTR %SKIP% set "FAIL=%%b"
if not defined STARTED for /f "tokens=1* delims=:" %%a in ('findstr /n /c:"Downloading depot %ID% " "%LOG%" 2^>nul') do if %%a GTR %SKIP% set "NEWSTART=%%b"
if defined NEWSTART set "STARTED=1" & echo   downloading %NEWSTART:*Downloading depot =%
if defined DONE echo   done& exit /b 0
if defined FAIL set "REASON=%FAIL:*failed : =%"
if defined FAIL exit /b 1
if not defined STARTED if %TICKS% GEQ 60 set "REASON=Steam didn't answer. Is it running and logged in?" & exit /b 1
goto :depot_wait
