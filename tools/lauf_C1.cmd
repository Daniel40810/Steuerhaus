@echo off
rem Teil C, Lauf C1 (Werkbuch HalligHooge 135): die Buecher 1-3 im Kino der App (Sichtpruefung, F228 A).
rem Kein Bau (nur Inhalt in src\szenen). 1 RegieCheck; 2-4 App mit je einem Buch im Kino, danach Ende von selbst:
rem 2 Ein Tag auf Hooge (2:30), 3 Landunter an der Hanswarft (3:00), 4 Xaver (3:00). Bitte zusehen: das ist die Sichtpruefung.
rem Ausgaben: HalligHooge\docs\status\Status_C1_*.txt (Pruefstein 9 Bildrate, Meldungen der Regie, Protokoll).
rem Erwartete Dauer: rund 12-14 min. Das Fenster der App oeffnet sich dreimal von selbst.
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
set "EI=%ST%\C1_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_C1_.txt"
set "L1=%ST%\Status_C1_1.txt"
set "L4=%ST%\Status_C1_2.txt"
set "E=0"
>"%UE%" echo Lauf C1 (Teil C, Buecher 1-3 im Kino), gestartet %DATE% %TIME%
>"%L1%" echo Status_C1_1: RegieCheck
>"%L4%" echo Status_C1_2: App mit den Buechern 1-3

rem ---------------------------------------------------------------- 1 RegieCheck
set "L=%L1%"
pushd "%HH%"
set "N=1 RegieCheck (Buecher 1-3)"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 2-4 Buecher im Kino
set "L=%L4%"
>"%EI%" echo qualitaet=auto
set "N=2 Kino: Ein Tag auf Hooge (2:30)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario ruhigsed --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie tag --sekunden 240"
call :lauf
set "E=0"
call :protokoll
set "N=3 Kino: Landunter an der Hanswarft (3:00)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario seegang --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie landunter --sekunden 280"
call :lauf
set "E=0"
call :protokoll
set "N=4 Kino: Xaver (3:00)"
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
