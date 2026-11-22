@echo off
rem Steuerhaus W2, Lauf W2 (Werkbuch HalligHooge 113 und 114; F186-F190, alle A): die Einblendungen.
rem 1 Renderer bauen (clean jar: SPIR-V, check-packages mit render.overlay), Renderer.jar nach Steuerhaus\lib und HalligHooge\lib;
rem 2 Steuerhaus bauen; 3 HalligHooge bauen (holt Steuerhaus.jar, ImportPruefung);
rem 4 Ortsnamen aus OpenStreetMap nach gebiet\orte.txt (tools\orte\orte.py, braucht das Netz);
rem 5 OverlayCheck (render.overlay ohne Gebiet); 6 BuehneCheck mit den Schaltern seit P12 (A42), BildCheck, MessblickCheck (W2-a);
rem 7 SteuerhausCheck und EinblendungCheck (Pruefsteine 1, 3-7; Bilder in beiden Themen);
rem 8 App 30 s: Sturmflut, Nautik, Steuern (alle Einblendungen); 9 App 30 s: ruhiger Tag, Labor, Betrachten (Pruefstein 10).
rem Ausgaben: HalligHooge\docs\status\Status_W2_*.txt, W2_*.png; Renderer\docs\status\W2_Overlay.md.
rem Erwartete Dauer: rund 15-20 min (Bauen 3-4, Pruefungen des Renderers 6-9, EinblendungCheck 2-4, App 1,5).
rem Das Fenster der Anwendung oeffnet sich zweimal von selbst und schliesst sich wieder.
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
set "EI=%ST%\W2_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"
set "PY=python"
where python >nul 2>nul || set "PY=py -3"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W2_.txt"
set "L1=%ST%\Status_W2_1.txt"
set "L4=%ST%\Status_W2_4.txt"
set "L5=%ST%\Status_W2_5.txt"
set "L6=%ST%\Status_W2_6.txt"
set "L7=%ST%\Status_W2_7.txt"
set "L8=%ST%\Status_W2_8.txt"
set "E=0"

>"%UE%" echo Lauf W2 (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W2_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W2_4: Ortsnamen aus OpenStreetMap
>"%L5%" echo Status_W2_5: OverlayCheck
>"%L6%" echo Status_W2_6: BuehneCheck (mit Schaltern), BildCheck, MessblickCheck
>"%L7%" echo Status_W2_7: SteuerhausCheck, EinblendungCheck
>"%L8%" echo Status_W2_8: Anwendung (zwei Starts)

rem ---------------------------------------------------------------- 1-3 Bauen
set "L=%L1%"
pushd "%RE%"
set "N=1 Renderer bauen (clean jar; SPIR-V, check-packages)"
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
if not "%E%"=="0" (
  popd
  goto :abbruch
)

rem ---------------------------------------------------------------- 4 Ortsnamen
set "N=4 Ortsnamen aus OpenStreetMap nach %G%\orte.txt"
set "L=%L4%"
set "C=%PY% tools\orte\orte.py --gebiet %G%"
call :lauf
if exist "%G%\orte.txt" copy /y "%G%\orte.txt" "%ST%\W2_orte.txt" >>"%L4%" 2>&1
set "E=0"
popd

rem ---------------------------------------------------------------- 5-6 Renderer
pushd "%RE%"
set "N=5 OverlayCheck (render.overlay ohne Gebiet)"
set "L=%L5%"
set "C=%R% com.dan.render.overlay.demo.OverlayCheck"
call :lauf
set "E=0"
set "N=6a BuehneCheck mit den Schaltern seit P12 (Pruefstein 2, A42)"
set "L=%L6%"
set "C=%RS% com.dan.lauf.demo.BuehneCheck --szenen %SZ%"
call :lauf
set "E=0"
set "N=6b BildCheck (Pruefstein 2)"
set "C=%R% com.dan.lauf.demo.BildCheck --gebiet %G% --archiv %A6% --nur 2,3"
call :lauf
set "E=0"
set "N=6c MessblickCheck (Pruefstein 8, W2-a: morpho auf japsand_oben)"
set "C=%R% com.dan.lauf.demo.MessblickCheck --szenen %SZ%"
call :lauf
set "E=0"
popd

rem ---------------------------------------------------------------- 7 Steuerhaus
pushd "%HH%"
set "N=7a SteuerhausCheck (Pruefstein 9)"
set "L=%L7%"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"
set "N=7b EinblendungCheck (Pruefsteine 1, 3-7; Bilder)"
set "C=%J% com.dan.steuerhaus.demo.EinblendungCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 8-9 die Anwendung
if exist "%EI%" del /q "%EI%"
set "L=%L8%"
set "N=8 Anwendung: Sturmflut, Nautik, Steuern, 30 s (alle Einblendungen; Pruefsteine 7, 10)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich steuern --einstellungen %EI% --sekunden 30"
call :lauf
set "E=0"
set "N=9 Anwendung: ruhiger Tag, Labor, Betrachten, 30 s (Uhr und Kompass; Pruefstein 10)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario ruhig --thema labor --arbeitsbereich betrachten --einstellungen %EI% --sekunden 30"
call :lauf
type "%EI%" >>"%L8%" 2>&1
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Zur Sichtpruefung: %HH%\HalligHooge.cmd  (F6 Einblendungen, Ansicht - Einblendungen, F11 nur Ortsnamen)
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
