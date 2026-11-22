@echo off
rem Abschluss HalligHooge (Werkbuch HalligHooge 142): Programmsymbol, Pruefung der Videos, Abbild 1.0.0.
rem 1-2 Steuerhaus, HalligHooge bauen (Renderer unveraendert seit C2b); 3 SteuerhausCheck;
rem 4 AusgabeCheck --datei je Video C3_1 bis C3_6 (ffprobe gegen die Begleitdatei, aus C3 noch offen);
rem 5 Abbild mit jpackage und Symbol (src\szenen\HalligHooge.ico); 6 Abbild 30 s ohne Java auf dem Suchpfad.
rem Ausgaben: HalligHooge\docs\status\Status_Ende_*.txt
rem Erwartete Dauer: rund 10 min. Das Fenster der App oeffnet sich einmal von selbst (30 s).
rem Anhalten zwischen zwei Schritten: eine Datei STOPP.txt neben dieses Skript legen.
rem Findet das Skript kein JDK fuer jpackage: hier den Pfad eintragen, etwa  set "JDK=C:\Program Files\Java\jdk-21"
set "JDK="
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0.."

set "P=C:\Users\Dan40810\Documents\NetBeansProjects"
set "SH=%P%\Steuerhaus"
set "HH=%P%\HalligHooge"
set "ST=%HH%\docs\status"
set "VI=%HH%\docs\video"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_Ende_.txt"
set "L1=%ST%\Status_Ende_1.txt"
set "L3=%ST%\Status_Ende_3.txt"
set "L4=%ST%\Status_Ende_4.txt"
set "L5=%ST%\Status_Ende_5.txt"
set "E=0"
>"%UE%" echo Lauf Ende (Abschluss HalligHooge), gestartet %DATE% %TIME%
>"%L1%" echo Status_Ende_1: Steuerhaus, HalligHooge bauen
>"%L3%" echo Status_Ende_3: SteuerhausCheck
>"%L4%" echo Status_Ende_4: AusgabeCheck je Video
>"%L5%" echo Status_Ende_5: Abbild mit Symbol

rem ---------------------------------------------------------------- 1-2 Bauen
set "L=%L1%"
pushd "%SH%"
set "N=1 Steuerhaus bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch
pushd "%HH%"
set "N=2 HalligHooge bauen (clean jar; ImportPruefung)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
if not "%E%"=="0" goto :abbruch

rem ---------------------------------------------------------------- 3 SteuerhausCheck
set "L=%L3%"
set "N=3 SteuerhausCheck"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 4 AusgabeCheck je Video
set "L=%L4%"
for %%v in (C3_1_Tag C3_2_Landunter C3_3_Xaver C3_4_Stroemung C3_5_Wasser_traegt C3_6_Luft) do call :video %%v
set "E=0"

rem ---------------------------------------------------------------- 5-6 Abbild mit Symbol
set "L=%L5%"
set "N=5 Abbild mit jpackage und Symbol (dist\abbild\HalligHooge)"
set "C=call "%SH%\tools\abbild.cmd" "%HH%" HalligHooge 1.0.0"
call :lauf
set "E5=%E%"
set "E=0"
popd
if not "%E5%"=="0" goto :ende
set "AB=%HH%\dist\abbild\HalligHooge"
set "EA=%ST%\Ende_abbild_einstellungen.properties"
if exist "%EA%" del /q "%EA%"
if exist "%ST%\protokoll.txt" del /q "%ST%\protokoll.txt"
>>"%UE%" echo [%DATE% %TIME%] Start  6 Abbild 30 s ohne Java auf dem Suchpfad
pushd "%AB%"
set "PATH_ALT=%PATH%"
set "JAVA_HOME_ALT=%JAVA_HOME%"
set "PATH=%SystemRoot%\system32;%SystemRoot%"
set "JAVA_HOME="
start "" /wait "%AB%\HalligHooge.exe" --szenario ruhig --sekunden 30 --einstellungen "%EA%"
set "E6=%ERRORLEVEL%"
set "PATH=%PATH_ALT%"
set "JAVA_HOME=%JAVA_HOME_ALT%"
popd
>>"%UE%" echo [%DATE% %TIME%] Ende   6 Abbild 30 s ohne Java auf dem Suchpfad  - Exit %E6%
dir "%AB%\HalligHooge.exe" >>"%L5%" 2>&1
dir "%HH%\dist\abbild\*.exe" >>"%L5%" 2>&1
if exist "%ST%\protokoll.txt" (
  >>"%L5%" echo Protokoll des Abbilds:
  type "%ST%\protokoll.txt" >>"%L5%"
)

:ende
>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Bitte ansehen: Symbol von dist\abbild\HalligHooge\HalligHooge.exe im Explorer und in der Taskleiste.
timeout /t 30 >nul
goto :eof

:video
set "N=4 AusgabeCheck --datei %1.mp4"
set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --datei %VI%\%1.mp4"
call :lauf
goto :eof

:abbruch
>>"%UE%" echo.
>>"%UE%" echo ABBRUCH %DATE% %TIME% nach: %N%
echo.
echo Abbruch nach: %N%  - siehe %L%
timeout /t 60 >nul
exit /b 1

:lauf
if not exist "%~dp0STOPP.txt" goto :lauf2
echo [%DATE% %TIME%] STOPP  %N%
>>"%UE%" echo [%DATE% %TIME%] STOPP  %N%  - uebersprungen
set "E=99"
goto :eof
:lauf2
echo [%DATE% %TIME%] Start  %N%
>>"%UE%" echo [%DATE% %TIME%] Start  %N%
>>"%L%" echo.
>>"%L%" echo ^>%C%
%C% >>"%L%" 2>&1
set "E=%ERRORLEVEL%"
echo [%DATE% %TIME%] Ende   %N%  - Exit %E%
>>"%UE%" echo [%DATE% %TIME%] Ende   %N%  - Exit %E%
goto :eof
