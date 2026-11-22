@echo off
rem Teil C, Lauf C1b/C2 (Werkbuch HalligHooge 137): Renderer A52 (Saum der Wasserlinie), A51 (eigene Kanaele des Vorlesers),
rem Steuerhaus A53 (Qualitaet im Kino) und Pruefstein 9; dazu die Buecher 4-6 und der Gesamtfilm.
rem 1-3 bauen; 4 WaterCheck, BildCheck 2,3, RegieCheck, AnalyseCheck, SteuerhausCheck; 5 Motivsuche ohne und mit Saum
rem (Vorher/Nachher); 6-10 Kino: Buch 1, Buch 3, Buecher 4-6 (Sichtpruefung: bitte zusehen).
rem Ausgaben: HalligHooge\docs\status\Status_C2_*.txt; Vorher/Nachher in HalligHooge\docs\motivsuche_saum_aus und _an.
rem Erwartete Dauer: rund 30 min. Das Fenster der App oeffnet sich fuenfmal von selbst.
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
set "EI=%ST%\C2_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_C2_.txt"
set "L1=%ST%\Status_C2_1.txt"
set "L4=%ST%\Status_C2_4.txt"
set "L5=%ST%\Status_C2_5.txt"
set "L6=%ST%\Status_C2_6.txt"
set "E=0"
>"%UE%" echo Lauf C1b/C2 (Teil C), gestartet %DATE% %TIME%
>"%L1%" echo Status_C2_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_C2_4: WaterCheck, BildCheck, RegieCheck, AnalyseCheck, SteuerhausCheck
>"%L5%" echo Status_C2_5: Motivsuche ohne und mit Saum
>"%L6%" echo Status_C2_6: Kino, Buecher 1, 3, 4, 5, 6

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
pushd "%RE%"
set "N=4a WaterCheck (Teil 0 Saum; Karte gegen Java bitgleich)"
set "C=%R% com.dan.render.water.demo.WaterCheck --ohne-attest"
call :lauf
set "E=0"
set "N=4b BildCheck --nur 2,3"
set "C=%R% com.dan.lauf.demo.BildCheck --nur 2,3"
call :lauf
set "E=0"
popd
pushd "%HH%"
set "N=4c RegieCheck (Buecher 1-6, Gesamtfilm)"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"
set "N=4d AnalyseCheck (Qualitaet im Kino)"
set "C=%J% com.dan.steuerhaus.demo.AnalyseCheck"
call :lauf
set "E=0"
set "N=4e SteuerhausCheck"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 5 Vorher/Nachher (A52)
set "L=%L5%"
set "N=5a Motivsuche ohne Saum (wie bis C1)"
set "C=java %U% -Dwasser.saum=aus --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.Motivsuche --manifest src\szenen\Hooge.steuerhaus --ablage docs\motivsuche_saum_aus --nur ruhigsed,seegang,xaver --bilder 12"
call :lauf
set "E=0"
set "N=5b Motivsuche mit Saum"
set "C=%J% com.dan.steuerhaus.demo.Motivsuche --manifest src\szenen\Hooge.steuerhaus --ablage docs\motivsuche_saum_an --nur ruhigsed,seegang,xaver --bilder 12"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6-10 Kino (Sichtpruefung)
set "L=%L6%"
>"%EI%" echo qualitaet=auto
set "N=6 Kino: Ein Tag auf Hooge (Kacheln? Bild steht?)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario ruhigsed --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie tag --sekunden 240"
call :lauf
set "E=0"
call :protokoll
set "N=7 Kino: Xaver (Bildrate, Qualitaet)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario xaver --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie xaver --sekunden 280"
call :lauf
set "E=0"
call :protokoll
set "N=8 Kino: Stroemung (2:30)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario ruhig --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie stroemung --sekunden 240"
call :lauf
set "E=0"
call :protokoll
set "N=9 Kino: Was das Wasser traegt (2:15)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sediment --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie sediment --sekunden 230"
call :lauf
set "E=0"
call :protokoll
set "N=10 Kino: Luft ueber dem Watt (2:00)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario kimmung --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie luft --sekunden 220"
call :lauf
set "E=0"
call :protokoll
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

:protokoll
if exist "%ST%\protokoll.txt" (
  type "%ST%\protokoll.txt" >>"%L%"
  del /q "%ST%\protokoll.txt"
)
goto :eof

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
