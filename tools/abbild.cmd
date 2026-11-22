@echo off
rem Steuerhaus W5 (Werkbuch HalligHooge 126, F214 A; W5b: Suche nach dem JDK, F219 A): ein Abbild der App mit eigener Java-Laufzeit (jpackage, Java 21).
rem Aufruf: abbild.cmd <Projektordner> <Name> [<Version>]   etwa: abbild.cmd C:\...\HalligHooge HalligHooge 1.0.0
rem Ergebnis: <Projektordner>\dist\abbild\<Name>\<Name>.exe (Ordner mit app\ und runtime\; die Szenen liegen in app\szenen).
rem Ist das WiX Toolset 3 da (candle.exe), entsteht dazu ein Installationsprogramm <Name>-<Version>.exe.
rem Die Daten (Gebiet, Archive) gehoeren nicht ins Abbild; ihren Ort stellt die App ein (Datei - Ort der Daten).
setlocal EnableExtensions
set "PROJ=%~1"
set "NAME=%~2"
set "VER=%~3"
if "%VER%"=="" set "VER=1.0.0"
rem seit W5b (Werkbuch HalligHooge 128, F219 A): jpackage gehoert zu einem JDK, nicht zu einer JRE. Gesucht wird in
rem dieser Reihenfolge: %JDK% (von Hand gesetzt), %JAVA_HOME%, die Plattform JDK_21 von NetBeans (build.properties),
rem der Suchpfad, uebliche Orte unter %ProgramFiles%.
set "JP="
if defined JDK if exist "%JDK%\bin\jpackage.exe" set "JP=%JDK%\bin\jpackage.exe" & set "WOHER=JDK"
if not defined JP if defined JAVA_HOME if exist "%JAVA_HOME%\bin\jpackage.exe" set "JP=%JAVA_HOME%\bin\jpackage.exe" & set "WOHER=JAVA_HOME"
if not defined JP call :netbeans
if not defined JP for /f "delims=" %%p in ('where jpackage 2^>nul') do if not defined JP set "JP=%%p" & set "WOHER=Suchpfad"
if not defined JP for /d %%d in ("%ProgramFiles%\Java\jdk-21*" "%ProgramFiles%\Eclipse Adoptium\jdk-21*" "%ProgramFiles%\Zulu\zulu-21*" "%ProgramFiles%\Microsoft\jdk-21*" "%ProgramFiles%\Amazon Corretto\jdk21*" "%ProgramFiles%\BellSoft\LibericaJDK-21*" "%ProgramFiles%\Java\jdk*" "%ProgramFiles%\Eclipse Adoptium\jdk*" "%ProgramFiles%\Zulu\zulu*") do if not defined JP if exist "%%~d\bin\jpackage.exe" set "JP=%%~d\bin\jpackage.exe" & set "WOHER=%%~d"
if not defined JP (
  echo jpackage nicht gefunden: kein JDK unter JDK, JAVA_HOME, der NetBeans-Plattform JDK_21, dem Suchpfad oder %ProgramFiles%.
  echo Abhilfe: oben in lauf_W5b.cmd "set JDK=C:\Pfad\zum\jdk-21" eintragen.
  exit /b 3
)
echo jpackage: %JP%  ^(gefunden ueber %WOHER%^)
"%JP%" --version
set "IN=%TEMP%\steuerhaus_abbild_%NAME%"
set "AUS=%PROJ%\dist\abbild"
if exist "%IN%" rmdir /s /q "%IN%"
mkdir "%IN%"
copy /y "%PROJ%\dist\%NAME%.jar" "%IN%\" >nul || exit /b 1
xcopy /e /i /q /y "%PROJ%\dist\lib" "%IN%\lib" >nul || exit /b 1
xcopy /e /i /q /y "%PROJ%\src\szenen" "%IN%\szenen" >nul || exit /b 1
if exist "%AUS%\%NAME%" rmdir /s /q "%AUS%\%NAME%"
if not exist "%AUS%" mkdir "%AUS%"
rem seit dem Abschluss (Werkbuch HalligHooge 142): Programmsymbol, wenn src\szenen\<Name>.ico da ist
set "SYMBOL="
if exist "%PROJ%\src\szenen\%NAME%.ico" set "SYMBOL=--icon "%PROJ%\src\szenen\%NAME%.ico""
"%JP%" --type app-image --name %NAME% --input "%IN%" --main-jar %NAME%.jar --dest "%AUS%" --app-version %VER% --vendor "Dan" %SYMBOL% ^
  --description "%NAME% (Steuerhaus)" --java-options "-Xmx12g" --java-options "--enable-native-access=ALL-UNNAMED" ^
  --java-options "-Dorg.lwjgl.system.stackSize=256" --java-options "-Dstdout.encoding=UTF-8" ^
  --jlink-options "--strip-debug --no-header-files --no-man-pages"
if errorlevel 1 exit /b 1
if not exist "%AUS%\%NAME%\runtime\bin\java.exe" (
  echo Abbild ohne runtime\bin\java.exe - die Ausgabe ^(Video, Standbild 4K^) startet dann nicht
  exit /b 2
)
where candle >nul 2>nul
if errorlevel 1 (
  echo WiX Toolset 3 nicht gefunden - nur das Abbild, kein Installationsprogramm
  exit /b 0
)
"%JP%" --type exe --app-image "%AUS%\%NAME%" --name %NAME% --app-version %VER% --vendor "Dan" --dest "%AUS%" %SYMBOL% ^
  --win-menu --win-shortcut --win-dir-chooser
exit /b %ERRORLEVEL%

:netbeans
rem Die Plattform JDK_21 steht in der build.properties von NetBeans (Pfad in nbproject\private\private.properties)
set "NBP="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"user.properties.file=" "%PROJ%\nbproject\private\private.properties" 2^>nul') do set "NBP=%%b"
if not defined NBP goto :eof
set "NBP=%NBP:\\=\%"
set "NBP=%NBP:\:=:%"
if not exist "%NBP%" goto :eof
set "NBJ="
for /f "tokens=1,* delims==" %%a in ('findstr /b /c:"platforms.JDK_21.home=" "%NBP%" 2^>nul') do set "NBJ=%%b"
if not defined NBJ goto :eof
set "NBJ=%NBJ:\\=\%"
set "NBJ=%NBJ:\:=:%"
if exist "%NBJ%\bin\jpackage.exe" set "JP=%NBJ%\bin\jpackage.exe" & set "WOHER=NetBeans-Plattform JDK_21"
goto :eof
