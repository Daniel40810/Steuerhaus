@echo off
rem Steuerhaus W1, Lauf W1 (Werkbuch HalligHooge 110 und 111; F173-F183, F182 B): das Geruest.
rem 0 Bezug: die alte Anwendung (dist von HalligHooge vor W1) 40 s, ruhiger Tag (Pruefsteine 1 und 4 zum Vergleich);
rem 1 FStyle bauen (FButton malt mit liquidColorTop/Bottom), FStyle.jar nach Steuerhaus, HalligHooge und Renderer\lib;
rem 2 Steuerhaus bauen; 3 HalligHooge bauen (holt Steuerhaus.jar, ImportPruefung: Pruefstein 5);
rem 4 SteuerhausCheck (Pruefsteine 5, 7, 8; Bilder der Oberflaeche in docs\status); 5 Vorschaubilder des Katalogs;
rem 6 BuehneCheck und BildCheck (Pruefstein 3, Renderer unveraendert); 7 neue Anwendung 40 s, ruhiger Tag, Labor, Steuern
rem (Pruefsteine 1, 4, 7); 8 neue Anwendung 25 s, Sturmflut, Nautik, Messen (schreibt Einstellungen);
rem 9 neue Anwendung 25 s ohne Wahl: liest die Einstellungen (Pruefstein 6).
rem Ausgaben: HalligHooge\docs\status\Status_W1_*.txt, W1_Oberflaeche_*.png, W1_Startbild_*.png, src\szenen\vorschau\*.png.
rem Erwartete Dauer: rund 15-20 min (Bauen 3-4, Vorschau 3-6, Pruefungen 3-4, Anwendungen 4).
rem Das Fenster der Anwendung oeffnet sich viermal von selbst und schliesst sich wieder.
rem Anhalten zwischen zwei Schritten: eine Datei STOPP.txt neben dieses Skript legen.
setlocal EnableExtensions
chcp 65001 >nul
cd /d "%~dp0.."

set "P=C:\Users\Dan40810\Documents\NetBeansProjects"
set "SH=%P%\Steuerhaus"
set "HH=%P%\HalligHooge"
set "RE=%P%\Renderer"
set "FS=%P%\FStyle"
set "ST=%HH%\docs\status"
set "SZ=%HH%\src\szenen\Hooge.szenen"
set "G=D:\Daten\HalligHooge\gebiet"
set "A6=D:\Daten\HalligHooge\_p6\archiv"
set "EI=%ST%\W1_einstellungen.properties"
set "ALT=%TEMP%\HalligHooge_vorW1"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

set "UE=%ST%\Status_W1_.txt"
set "L0=%ST%\Status_W1_0.txt"
set "L1=%ST%\Status_W1_1.txt"
set "L4=%ST%\Status_W1_4.txt"
set "L5=%ST%\Status_W1_5.txt"
set "L6=%ST%\Status_W1_6.txt"
set "L7=%ST%\Status_W1_7.txt"
set "E=0"

>"%UE%" echo Lauf W1 (Steuerhaus), gestartet %DATE% %TIME%
>"%L0%" echo Status_W1_0: alte Anwendung (vor W1), ruhiger Tag, 40 s
>"%L1%" echo Status_W1_1: FStyle, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W1_4: SteuerhausCheck
>"%L5%" echo Status_W1_5: Vorschaubilder des Katalogs
>"%L6%" echo Status_W1_6: BuehneCheck, BildCheck
>"%L7%" echo Status_W1_7: neue Anwendung (drei Starts)

rem ---------------------------------------------------------------- 0 Bezug: die alte Anwendung
if exist "%HH%\dist\HalligHooge.jar" (
  if not exist "%ALT%\HalligHooge.jar" xcopy /E /I /Y /Q "%HH%\dist" "%ALT%" >>"%L0%" 2>&1
)
pushd "%HH%"
set "N=0 alte Anwendung: ruhiger Tag, 40 s (Bezug)"
set "L=%L0%"
set "C=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar "%ALT%\HalligHooge.jar" --szenen src\szenen\Hooge.szenen --szenario ruhig --sekunden 40"
call :lauf
popd
set "E=0"

rem ---------------------------------------------------------------- 1-3 Bauen
set "L=%L1%"
pushd "%FS%"
set "N=1 FStyle bauen (clean jar)"
set "C=call "%ANT%" -f build.xml clean jar"
call :lauf
popd
if not "%E%"=="0" goto :abbruch
copy /y "%FS%\dist\FStyle.jar" "%SH%\lib\FStyle.jar" >>"%L1%" 2>&1
copy /y "%FS%\dist\FStyle.jar" "%HH%\lib\FStyle.jar" >>"%L1%" 2>&1
copy /y "%FS%\dist\FStyle.jar" "%RE%\lib\FStyle.jar" >>"%L1%" 2>&1
>>"%UE%" echo FStyle.jar nach Steuerhaus\lib, HalligHooge\lib und Renderer\lib kopiert

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
if not "%E%"=="0" (
  popd
  goto :abbruch
)

rem ---------------------------------------------------------------- 4 SteuerhausCheck, 5 Vorschau
set "N=4 SteuerhausCheck (Pruefsteine 5, 7, 8; Bilder der Oberflaeche)"
set "L=%L4%"
set "C=java %U% -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

set "N=5 Vorschaubilder des Katalogs (ohne Fenster)"
set "L=%L5%"
set "C=%APP% --vorschau"
call :lauf
set "E=0"
popd

rem ---------------------------------------------------------------- 6 Renderer: Bild unveraendert
pushd "%RE%"
set "N=6a BuehneCheck (Pruefstein 3)"
set "L=%L6%"
set "C=%R% com.dan.lauf.demo.BuehneCheck --szenen %SZ%"
call :lauf
set "E=0"
set "N=6b BildCheck (Pruefstein 3)"
set "C=%R% com.dan.lauf.demo.BildCheck --gebiet %G% --archiv %A6% --nur 2,3"
call :lauf
set "E=0"
popd

rem ---------------------------------------------------------------- 7-9 die neue Anwendung
pushd "%HH%"
if exist "%EI%" del /q "%EI%"
set "L=%L7%"
set "N=7 neue Anwendung: ruhiger Tag, Labor, Steuern, 40 s (Pruefsteine 1, 4, 7)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario ruhig --thema labor --arbeitsbereich steuern --einstellungen %EI% --sekunden 40"
call :lauf
set "E=0"
set "N=8 neue Anwendung: Sturmflut, Nautik, Messen, 25 s (schreibt die Einstellungen)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich messen --einstellungen %EI% --sekunden 25"
call :lauf
set "E=0"
set "N=9 neue Anwendung ohne Wahl, 25 s: liest die Einstellungen (Pruefstein 6)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --einstellungen %EI% --sekunden 25 --erwarte szenario=sturm,thema=nautik,arbeitsbereich=messen,links=false,rechts=true,unten=true"
call :lauf
type "%EI%" >>"%L7%" 2>&1
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Zur Sichtpruefung: %HH%\HalligHooge.cmd  (Strg+K Befehle, F1 Ueber und Tasten, Ansicht - Thema)
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
