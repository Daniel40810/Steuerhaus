@echo off
rem Teil C, Lauf C0 (Werkbuch HalligHooge 134): Motivsuche fuer die Regiebuecher (F226 A); dazu warte pegel kleiner x und folge (F227 A).
rem 1-3 bauen (Renderer mit F222 A); 4 RegieCheck, SteuerhausCheck; 5 Motivsuche ohne Fenster (Kontaktboegen aus den Archiven auf D:).
rem Ausgaben: HalligHooge\docs\status\Status_C0_*.txt; Kontaktboegen und Tabellen in HalligHooge\docs\motivsuche.
rem Erwartete Dauer: rund 15-20 min (Motivsuche rund 12-15 min). Es oeffnet sich kein Fenster.
rem Anhalten zwischen zwei Schritten: eine Datei STOPP.txt neben dieses Skript legen.
set "JDK="
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
set "EI=%ST%\C0_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_C0_.txt"
set "L1=%ST%\Status_C0_1.txt"
set "L4=%ST%\Status_C0_4.txt"
set "L5=%ST%\Status_C0_5.txt"
set "E=0"
>"%UE%" echo Lauf C0 (Teil C, Motivsuche), gestartet %DATE% %TIME%
>"%L1%" echo Status_C0_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_C0_4: RegieCheck, SteuerhausCheck
>"%L5%" echo Status_C0_5: Motivsuche

rem ---------------------------------------------------------------- 1-3 Bauen
set "L=%L1%"
pushd "%RE%"
set "N=1 Renderer bauen (clean jar; check-packages)"
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
set "N=3 HalligHooge bauen (clean jar; ImportPruefung)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch

rem ---------------------------------------------------------------- 4 Pruefungen
set "L=%L4%"
pushd "%HH%"
set "N=4a RegieCheck (Teil C: warte pegel kleiner x, folge)"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"
set "N=4b SteuerhausCheck (Importregel mit Motivsuche)"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 5 Motivsuche
set "L=%L5%"
set "N=5 Motivsuche (Kontaktboegen und Tabellen nach docs\motivsuche)"
set "C=%J% com.dan.steuerhaus.demo.Motivsuche --manifest src\szenen\Hooge.steuerhaus --ablage docs\motivsuche --bilder 48"
call :lauf
set "E=0"
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
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
