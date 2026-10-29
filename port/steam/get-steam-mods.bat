@echo off
rem get-steam-mods.bat: downloads Darkest Dungeon mods from the Steam Workshop and packs them into
rem darkest-dungeon-mods.zip next to this file, for "Add mods" in the Darkest Dungeon Porter app.
rem The game doesn't need to be installed: paste the mods' Workshop links. With the game installed,
rem it can take the mods you subscribed to instead. Steam must be running and logged in to an account
rem that owns the game. Plain batch with tools built into Windows 10 (1803) and newer: reg, tasklist,
rem findstr, choice, tar.
rem
rem Steam runs "steam.exe +workshop_download_item <app> <item>" in the running client and logs the
rem result to logs\workshop_log.txt; the item lands in steamapps\workshop\content\<app>\<item>. The
rem client knows the subscriptions only of an installed game (steamapps\workshop\appworkshop_<app>.acf).
rem (Inside ( ) blocks, %paths% are avoided: "Program Files (x86)" would close the block. find and
rem tar are called by full path: Git, Cygwin or MSYS can put Unix tools of those names first in PATH.
rem There is no delayed expansion: a pasted link can hold "!".)
setlocal EnableExtensions
set "APP=262060"
set "ZIP=%~dp0darkest-dungeon-mods.zip"
set "IDS=%TEMP%\darkest-dungeon-mods-ids.txt"
set "LIBS=%TEMP%\darkest-dungeon-mods-libraries.txt"
set "LIST=%TEMP%\darkest-dungeon-mods-files.txt"
set "TAR=%SystemRoot%\System32\tar.exe"
set "FIND=%SystemRoot%\System32\find.exe"
call :main
if errorlevel 1 echo. & echo Error: %ERRMSG%
if exist "%IDS%" del "%IDS%"
if exist "%LIBS%" del "%LIBS%"
if exist "%LIST%" del "%LIST%"
echo.
pause
exit /b

:main
set "ERRMSG="
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
rem the account id, as the subscriptions name it
set /a "ACCOUNT=%USERID%"
set "LOG=%STEAMDIR%\logs\workshop_log.txt"
call :libraries
set "INSTALLED="
for /f "usebackq delims=" %%l in ("%LIBS%") do if exist "%%l\steamapps\appmanifest_%APP%.acf" set "INSTALLED=1"

type nul > "%IDS%"
call :subscriptions
set "COUNT=0"
for /f %%n in ('type "%IDS%" ^| "%FIND%" /c /v ""') do set "COUNT=%%n"
if %COUNT%==0 goto :links
choice /c YN /n /m "Pack the %COUNT% mods you subscribed to? [Y/N] "
if errorlevel 255 type nul > "%IDS%"
if errorlevel 2 type nul > "%IDS%"
:links
for /f %%n in ('type "%IDS%" ^| "%FIND%" /c /v ""') do set "COUNT=%%n"
if %COUNT%==0 call :ask_links
for /f %%n in ('type "%IDS%" ^| "%FIND%" /c /v ""') do set "COUNT=%%n"
if %COUNT%==0 set "ERRMSG=No mods to download." & exit /b 1

echo.
echo Downloading the mods through Steam:
type nul > "%LIST%"
set "KEPT=0"
for /f "usebackq" %%i in ("%IDS%") do if not defined ERRMSG call :item %%i
if defined ERRMSG exit /b 1
if %KEPT%==0 set "ERRMSG=None of the mods could be downloaded." & exit /b 1

echo.
echo Packing the mods into %ZIP%...
"%TAR%" -c -f "%ZIP%.part" --format zip --options zip:compression=deflate,zip:compression-level=1 -T "%LIST%"
if errorlevel 1 del "%ZIP%.part" 2>nul & set "ERRMSG=Packing failed, see the message above. Is the disk full?" & exit /b 1
move /y "%ZIP%.part" "%ZIP%" >nul
rem Steam's copies of the mods stay: it reuses their files for other items, and an item whose files
rem are gone fails its next download ("Missing game files") until Steam has validated them again.
echo.
echo Done: %ZIP%
echo Copy it to the phone, open Darkest Dungeon Porter, tap Add mods and pick it.
echo You can delete the zip from the phone after that.
exit /b 0

rem :libraries: writes Steam's library folders to LIBS, one per line, Steam's own first.
:libraries
> "%LIBS%" echo(%STEAMDIR%
set "VDF=%STEAMDIR%\steamapps\libraryfolders.vdf"
if not exist "%VDF%" exit /b 0
for /f "usebackq tokens=1,*" %%a in ("%VDF%") do if "%%~a"=="path" call :library %%b
exit /b 0

:library
set "LIB=%~1"
set "LIB=%LIB:\\=\%"
if /i "%LIB%"=="%STEAMDIR%" exit /b 0
>> "%LIBS%" echo(%LIB%
exit /b 0

rem :subscriptions: adds this account's subscribed items, from each library's workshop file, to IDS.
:subscriptions
for /f "usebackq delims=" %%l in ("%LIBS%") do call :acf "%%l\steamapps\workshop\appworkshop_%APP%.acf"
exit /b 0

:acf
if not exist "%~1" exit /b 0
set "ITEM="
for /f "usebackq tokens=1,2" %%a in ("%~1") do call :acfline %%a %%b
exit /b 0

rem :acfline KEY [VALUE]: a key without a value names the block that follows, such as an item's ("<id>")
:acfline
if "%~1"=="{" exit /b 0
if "%~1"=="}" exit /b 0
if not "%~2"=="" goto :acfvalue
set "ITEM=%~1"
exit /b 0
:acfvalue
if /i "%~1"=="subscribedby" if "%~2"=="%ACCOUNT%" call :addid "%ITEM%"
exit /b 0

rem :addid ID: adds ID to IDS unless it is there already; errorlevel 1 if it isn't a number.
:addid
echo(%~1| findstr /r "^[0-9][0-9]*$" >nul || exit /b 1
findstr /x /c:"%~1" "%IDS%" >nul && exit /b 0
>> "%IDS%" echo(%~1
exit /b 0

:ask_links
echo.
if not defined INSTALLED echo Steam lists the mods you subscribed to only while the game is installed.
echo Paste the Workshop links of the mods (or their numbers), one per line. An empty line ends the list.
:ask_loop
set "LINK="
set /p "LINK=> "
if not defined LINK exit /b 0
call :link
goto :ask_loop

rem :link: the number after "?id=" in LINK, up to a "&", or LINK itself
rem (%LINK:*?id=% cuts up to "?id": the = ends the text to find)
:link
set "ID=%LINK:*?id=%"
if "%ID:~0,1%"=="=" set "ID=%ID:~1%"
for /f "tokens=1 delims=& " %%a in ("%ID%") do set "ID=%%a"
call :addid "%ID%" && exit /b 0
echo   not a Workshop link: "%LINK%"
exit /b 0

rem :item ID: asks the running Steam to download a Workshop item and waits for the result in its log,
rem then lists the item's folder for the zip. Only log lines after the ones already there count.
rem ERRMSG when Steam doesn't answer.
:item
set "ID=%~1"
set "RESULT="
set "STARTED="
set "SKIP=0"
set "TICKS=0"
if exist "%LOG%" for /f %%n in ('type "%LOG%" ^| "%FIND%" /c /v ""') do set "SKIP=%%n"
start "" "%STEAMEXE%" +workshop_download_item %APP% %ID%
:item_wait
ping -n 2 127.0.0.1 >nul
set /a TICKS+=1
if not defined STARTED for /f "tokens=1 delims=:" %%a in ('findstr /n /c:"Download item %ID% requested" "%LOG%" 2^>nul') do if %%a GTR %SKIP% set "STARTED=1"
for /f "tokens=1* delims=:" %%a in ('findstr /n /c:"Download item %ID% result : " "%LOG%" 2^>nul') do if %%a GTR %SKIP% set "RESULT=%%b"
if defined RESULT goto :item_result
if not defined STARTED if %TICKS% GEQ 60 set "ERRMSG=Steam didn't answer. Is it running and logged in?" & exit /b 1
goto :item_wait
:item_result
set "RESULT=%RESULT:*result : =%"
if not "%RESULT%"=="OK" echo - %ID%: skipped, Steam says "%RESULT%"& exit /b 0
set "DIR="
for /f "usebackq delims=" %%l in ("%LIBS%") do if not defined DIR if exist "%%l\steamapps\workshop\content\%APP%\%ID%\" set "DIR=%%l\steamapps\workshop\content\%APP%"
if not defined DIR echo - %ID%: skipped, Steam says OK but its folder isn't there& exit /b 0
>> "%LIST%" echo -C
>> "%LIST%" echo(%DIR%
>> "%LIST%" echo %ID%
set /a KEPT+=1
echo - %ID%: done
exit /b 0
