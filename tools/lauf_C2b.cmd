@echo off
rem Teil C, Lauf C2b (Werkbuch HalligHooge 138): Naesse des Gelaendes bilinear (A52, Kacheln bei Xaver), BildCheck mit Saum.
rem 1-3 bauen; 4 BildCheck 2,3; 5 Motivsuche Xaver und ruhiger Tag mit Sediment (Vergleich mit docs\motivsuche_saum_an);
rem 6 Kino Xaver (Sichtpruefung: Kacheln?). Ausgaben: HalligHooge\docs\status\Status_C2b_*.txt, docs\motivsuche_c2b.
rem Erwartete Dauer: rund 10 min. Das Fenster der App oeffnet sich einmal.
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
set "EI=%ST%\C2b_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_C2b_.txt"
set "L1=%ST%\Status_C2b_1.txt"
set "L4=%ST%\Status_C2b_4.txt"
set "L5=%ST%\Status_C2b_5.txt"
set "L6=%ST%\Status_C2b_6.txt"
set "E=0"
>"%UE%" echo Lauf C2b (Teil C), gestartet %DATE% %TIME%
>"%L1%" echo Status_C2b_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_C2b_4: BildCheck
>"%L5%" echo Status_C2b_5: Motivsuche
>"%L6%" echo Status_C2b_6: Kino Xaver

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

rem ---------------------------------------------------------------- 4 BildCheck
set "L=%L4%"
pushd "%RE%"
set "N=4 BildCheck --nur 2,3"
set "C=%R% com.dan.lauf.demo.BildCheck --nur 2,3"
call :lauf
set "E=0"
popd
pushd "%HH%"

rem ---------------------------------------------------------------- 5 Motivsuche
set "L=%L5%"
set "N=5 Motivsuche Xaver und ruhiger Tag mit Sediment"
set "C=%J% com.dan.steuerhaus.demo.Motivsuche --manifest src\szenen\Hooge.steuerhaus --ablage docs\motivsuche_c2b --nur ruhigsed,xaver --bilder 12"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6 Kino Xaver
set "L=%L6%"
>"%EI%" echo qualitaet=auto
set "N=6 Kino: Xaver (Kacheln?)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario xaver --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie xaver --sekunden 280"
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
