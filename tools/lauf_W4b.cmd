@echo off
rem Steuerhaus W4, Lauf W4b (Werkbuch HalligHooge 123/124): Nachlauf nach Lauf W4.
rem A50 die Zeilen der ersten Sekunde sehen die Szenenzeit (zeit 21:40 gilt im Video); A51 Probe der Unschaerfe am Tag;
rem A53 lange Bilder der Ausgabe mit Nummer; F207 A Korn 0,5, CRF 18.
rem 1-3 bauen; 4 RegieCheck, KinoCheck; 5 AusgabeCheck (Pruefsteine 1-5, 7); 6 Video "Landunter an der Hanswarft" 1080p
rem (ueberschreibt docs\video\W4_Landunter_1080p.mp4); 7 AusgabeCheck --datei (Pruefstein 6).
rem Ausgaben: HalligHooge\docs\status\Status_W4b_*.txt.
rem Erwartete Dauer: rund 30 min (das Video allein rund 25 min). Es oeffnet sich kein Fenster.
rem Anhalten zwischen zwei Schritten: eine Datei STOPP.txt neben dieses Skript legen.
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0.."

set "P=C:\Users\Dan40810\Documents\NetBeansProjects"
set "SH=%P%\Steuerhaus"
set "HH=%P%\HalligHooge"
set "RE=%P%\Renderer"
set "ST=%HH%\docs\status"
set "SZ=%HH%\src\szenen\Hooge.szenen"
set "G=D:\Daten\HalligHooge\gebiet"
set "A6=D:\Daten\HalligHooge\_p6\archiv"
set "EI=%ST%\W4b_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "VI=%HH%\docs\video"
if not exist "%VI%" mkdir "%VI%"
set "UE=%ST%\Status_W4b_.txt"
set "L1=%ST%\Status_W4b_1.txt"
set "L4=%ST%\Status_W4b_4.txt"
set "L6=%ST%\Status_W4b_6.txt"
set "E=0"
>"%UE%" echo Lauf W4b (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W4b_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W4b_4: RegieCheck, KinoCheck, AusgabeCheck
>"%L6%" echo Status_W4b_6: Video, Datei

rem ---------------------------------------------------------------- 1-3 Bauen
set "L=%L1%"
pushd "%RE%"
set "N=1 Renderer bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch
copy /y "%RE%\dist\Renderer.jar" "%SH%\lib\Renderer.jar" >>"%L1%" 2>&1
copy /y "%RE%\dist\Renderer.jar" "%HH%\lib\Renderer.jar" >>"%L1%" 2>&1
pushd "%SH%"
set "N=2 Steuerhaus bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch
pushd "%HH%"
set "N=3 HalligHooge bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
if not "%E%"=="0" (
  popd
  goto :abbruch
)

rem ---------------------------------------------------------------- 4-5 Pruefungen
set "L=%L4%"
set "N=4a RegieCheck"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"
set "N=4b KinoCheck"
set "C=%J% com.dan.steuerhaus.demo.KinoCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"
set "N=5 AusgabeCheck (Pruefsteine 1-5, 7)"
set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6-7 Video und Datei
set "L=%L6%"
set "V=%VI%\W4_Landunter_1080p.mp4"
set "N=6 Video Landunter an der Hanswarft, 1080p, 24 B/s, 8 Teilbilder, Korn 0,5, CRF 18"
set "C=%J% com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --regie "Landunter an der Hanswarft" --groesse 1080p --ziel %V%"
call :lauf
set "E=0"
set "N=7 AusgabeCheck --datei (Pruefstein 6)"
set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --datei %V%"
call :lauf
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Video: %V%
timeout /t 20 >nul
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
