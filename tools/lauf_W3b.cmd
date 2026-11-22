@echo off
rem Steuerhaus W3, Lauf W3b (Werkbuch HalligHooge 118/119): Nachlauf nach Lauf W3.
rem A45 Probe zaehlt die Bildrate ueber die Zeit, die wirklich gelaufen ist; F198 A Grenze der Blende 0,5 ms und Kosten
rem im Fenster; F199 A das Kino beginnt erst, wenn Szenario, Zeit und Ansicht der ersten Sekunde gelten; A47 lange Bilder
rem der Bildschleife werden mit ihren Teilen gemeldet, KinoCheck misst einen Sprung in der Zeit um 10,5 h.
rem 1-3 bauen; 4 BildCheck 2,3 (Bild unveraendert); 5 KinoCheck; 6 Anwendung mit dem Regiebuch im Kino (hoechstens 300 s).
rem Ausgaben: HalligHooge\docs\status\Status_W3b_*.txt.
rem Erwartete Dauer: rund 5-6 min. Das Fenster der Anwendung oeffnet sich in Schritt 6 von selbst (rund 3 min).
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
set "EI=%ST%\W3b_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W3b_.txt"
set "L1=%ST%\Status_W3b_1.txt"
set "L4=%ST%\Status_W3b_4.txt"
set "L6=%ST%\Status_W3b_6.txt"
set "E=0"
>"%UE%" echo Lauf W3b (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W3b_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W3b_4: BildCheck, KinoCheck
>"%L6%" echo Status_W3b_6: Anwendung mit dem Regiebuch

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
>>"%UE%" echo Renderer.jar nach Steuerhaus\lib und HalligHooge\lib kopiert
pushd "%SH%"
set "N=2 Steuerhaus bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch
pushd "%HH%"
set "N=3 HalligHooge bauen (clean jar; holt Steuerhaus.jar, ImportPruefung)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch

rem ---------------------------------------------------------------- 4-5 Pruefungen
set "L=%L4%"
pushd "%RE%"
set "N=4 BildCheck 2,3 (Bild unveraendert)"
set "C=%R% com.dan.lauf.demo.BildCheck --gebiet %G% --archiv %A6% --nur 2,3"
call :lauf
set "E=0"
popd
pushd "%HH%"
set "N=5 KinoCheck (Pruefsteine 6, 7; Sprung in der Zeit)"
set "C=%J% com.dan.steuerhaus.demo.KinoCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6 die Anwendung mit dem Regiebuch
set "L=%L6%"
if exist "%EI%" del /q "%EI%"
set "N=6 Anwendung: Regiebuch Landunter an der Hanswarft im Kino, Nautik (Pruefstein 9; hoechstens 300 s)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie "Landunter an der Hanswarft" --sekunden 300"
call :lauf
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
