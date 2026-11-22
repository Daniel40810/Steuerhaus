@echo off
rem Steuerhaus W5c, Lauf W5c (Werkbuch HalligHooge 131): Vorleser der Staende (F221 A, A47).
rem 1-3 bauen; 4 CoreCheck, VorleseCheck (nachgestellte Platte: schlaeft nach 3 s, wacht in 6 s auf), BuehneCheck, SteuerhausCheck;
rem 5 App 80 s Sturmflut wie in W5b (echte Platte); 6 App 80 s mit nachgestellter Platte (schlaeft nach 0,5 s, wacht in 20 s auf);
rem 7 Abbild neu (dist\abbild\HalligHooge).
rem Ausgaben: HalligHooge\docs\status\Status_W5c_*.txt (Protokolle der App haengen an 5 und 6), Renderer\docs\status\W5c_Vorleser.md.
rem Erwartete Dauer: rund 10-12 min. Das Fenster der App oeffnet sich zweimal von selbst; in 6 steht die Uhr anfangs rund 20 s (gewollt).
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
set "EI=%ST%\W5c_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W5c_.txt"
set "L1=%ST%\Status_W5c_1.txt"
set "L4=%ST%\Status_W5c_4.txt"
set "L5=%ST%\Status_W5c_5.txt"
set "L6=%ST%\Status_W5c_6.txt"
set "L7=%ST%\Status_W5c_7.txt"
set "E=0"
>"%UE%" echo Lauf W5c (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W5c_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W5c_4: CoreCheck, VorleseCheck, BuehneCheck, SteuerhausCheck
>"%L5%" echo Status_W5c_5: App 80 s, echte Platte
>"%L6%" echo Status_W5c_6: App 80 s, nachgestellte Platte (0,5 s / 20 s)
>"%L7%" echo Status_W5c_7: Abbild

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
set "N=4a CoreCheck (Vorlesen eines Stands)"
set "C=%R% com.dan.core.demo.CoreCheck"
call :lauf
set "E=0"
set "N=4b VorleseCheck (nachgestellte Platte 3 s / 6 s; Pruefsteine V1-V5)"
set "C=%R% com.dan.lauf.demo.VorleseCheck --szenen %SZ%"
call :lauf
set "E=0"
set "N=4c BuehneCheck (Pruefstein 8)"
set "C=%RS% com.dan.lauf.demo.BuehneCheck --szenen %SZ%"
call :lauf
set "E=0"
popd
pushd "%HH%"
set "N=4d SteuerhausCheck"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 5 App, echte Platte
set "L=%L5%"
if exist "%ST%\protokoll.txt" del /q "%ST%\protokoll.txt"
>"%EI%" echo qualitaet=auto
set "N=5 App 80 s: Sturmflut ab 10:00 UTC, echte Platte (in W5b: 19,5 s bei 14:10)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich werkstatt --einstellungen %EI% --sekunden 80"
call :lauf
set "E=0"
if exist "%ST%\protokoll.txt" (
  type "%ST%\protokoll.txt" >>"%L5%"
  del /q "%ST%\protokoll.txt"
)

rem ---------------------------------------------------------------- 6 App, nachgestellte Platte
set "L=%L6%"
>"%EI%" echo qualitaet=auto
set "N=6 App 80 s: Sturmflut, nachgestellte Platte (schlaeft nach 0,5 s, wacht in 20 s auf)"
set "C=%APPS% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich werkstatt --einstellungen %EI% --sekunden 80"
call :lauf
set "E=0"
if exist "%ST%\protokoll.txt" (
  type "%ST%\protokoll.txt" >>"%L6%"
  del /q "%ST%\protokoll.txt"
)

rem ---------------------------------------------------------------- 7 Abbild neu
set "L=%L7%"
set "N=7 Abbild mit jpackage (dist\abbild\HalligHooge)"
set "C=call "%SH%\tools\abbild.cmd" "%HH%" HalligHooge 1.0.0"
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
