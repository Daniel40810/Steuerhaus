@echo off
rem Steuerhaus W2, Lauf W2b (Werkbuch HalligHooge 115): Nachlauf nach Lauf W2.
rem 1-3 Renderer, Steuerhaus, HalligHooge bauen (Ortsnamen weichen den Kacheln am Rand aus; Uhr deckender; letzte Marke);
rem 4 Ortsnamen neu (orte.py strenger: keine Nummern, keine Gebaeude und Betriebe; Japsand als Sand);
rem 5 OverlayCheck; 6 MessblickCheck mit den Schaltern seit P12 und ohne Brandung (A43, nur zur Auskunft: haelt
rem Pruefstein 19 ohne die Landschaft?); 7 EinblendungCheck (Pruefstein 3 mit neuen Proben); 8 App 30 s, Sturmflut, Nautik.
rem Ausgaben: HalligHooge\docs\status\Status_W2b_*.txt, W2_*.png (ueberschrieben), W2_orte.txt.
rem Erwartete Dauer: rund 5-6 min. Das Fenster der Anwendung oeffnet sich einmal von selbst.
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

set "PYTHONIOENCODING=utf-8"
set "RM=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W2b_.txt"
set "L1=%ST%\Status_W2b_1.txt"
set "L4=%ST%\Status_W2b_4.txt"
set "L6=%ST%\Status_W2b_6.txt"
set "L7=%ST%\Status_W2b_7.txt"
set "E=0"
>"%UE%" echo Lauf W2b (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W2b_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W2b_4: Ortsnamen, OverlayCheck
>"%L6%" echo Status_W2b_6: MessblickCheck ohne Landschaft (A43)
>"%L7%" echo Status_W2b_7: EinblendungCheck, Anwendung

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

rem ---------------------------------------------------------------- 4-5 Ortsnamen, OverlayCheck
set "L=%L4%"
set "N=4 Ortsnamen neu nach %G%\orte.txt"
set "C=%PY% tools\orte\orte.py --gebiet %G%"
call :lauf
if exist "%G%\orte.txt" copy /y "%G%\orte.txt" "%ST%\W2_orte.txt" >>"%L4%" 2>&1
set "E=0"
popd
pushd "%RE%"
set "N=5 OverlayCheck"
set "C=%R% com.dan.render.overlay.demo.OverlayCheck"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6 A43
set "L=%L6%"
set "N=6 MessblickCheck ohne Haeuser, Gestalt, Bewuchs, Brandung (A43, Auskunft)"
set "C=%RM% com.dan.lauf.demo.MessblickCheck --szenen %SZ% --attest W2b_Messblick_ohneLandschaft"
call :lauf
set "E=0"
popd

rem ---------------------------------------------------------------- 7-8 Steuerhaus
pushd "%HH%"
set "L=%L7%"
set "N=7 EinblendungCheck (Pruefsteine 1, 3-7; Bilder)"
set "C=%J% com.dan.steuerhaus.demo.EinblendungCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"
if exist "%EI%" del /q "%EI%"
set "N=8 Anwendung: Sturmflut, Nautik, Steuern, 30 s"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich steuern --einstellungen %EI% --sekunden 30"
call :lauf
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
timeout /t 20 >nul
goto :eof

:abbruch
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
