@echo off
rem Steuerhaus W5, Lauf W5 (Werkbuch HalligHooge 126/127): Analyse und Betrieb.
rem 1-3 bauen; 4 BuehneCheck, BildCheck 2,3 (Pruefstein 8), MessblickCheck (A43: LIC in 8 Streifen); 5 SteuerhausCheck;
rem 6 AnalyseCheck (Pruefsteine 1-6); 7 App 60 s im Arbeitsbereich Werkstatt, Qualitaet automatisch (Protokoll in docs\status);
rem 8 Abbild mit jpackage (dist\abbild\HalligHooge); 9 das Abbild 30 s ohne Java auf dem Suchpfad (Pruefstein 7).
rem Ausgaben: HalligHooge\docs\status\Status_W5_*.txt, protokoll.txt (dort), W5_*.png.
rem Erwartete Dauer: rund 12-18 min. Das Fenster der App oeffnet sich zweimal von selbst.
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
set "EI=%ST%\W5_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W5_.txt"
set "L1=%ST%\Status_W5_1.txt"
set "L4=%ST%\Status_W5_4.txt"
set "L6=%ST%\Status_W5_6.txt"
set "L7=%ST%\Status_W5_7.txt"
set "E=0"
>"%UE%" echo Lauf W5 (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W5_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W5_4: BuehneCheck, BildCheck, MessblickCheck, SteuerhausCheck
>"%L6%" echo Status_W5_6: AnalyseCheck
>"%L7%" echo Status_W5_7: App, Abbild

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

rem ---------------------------------------------------------------- 4-5 Fenster unveraendert, Messblick, Importregel
set "L=%L4%"
pushd "%RE%"
set "N=4a BuehneCheck mit den Schaltern seit P12 (Pruefstein 8)"
set "C=%RS% com.dan.lauf.demo.BuehneCheck --szenen %SZ%"
call :lauf
set "E=0"
set "N=4b BildCheck 2,3 (Pruefstein 8)"
set "C=%R% com.dan.lauf.demo.BildCheck --gebiet %G% --archiv %A6% --nur 2,3"
call :lauf
set "E=0"
set "N=4c MessblickCheck (A43: Pruefstein 19 mit LIC in 8 Streifen)"
set "C=%R% com.dan.lauf.demo.MessblickCheck --szenen %SZ%"
call :lauf
set "E=0"
popd
pushd "%HH%"
set "N=5 SteuerhausCheck (Importregel mit analyse, Befehle W4/W5)"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6 AnalyseCheck
set "L=%L6%"
set "N=6 AnalyseCheck (Pruefsteine 1-6)"
set "C=%J% com.dan.steuerhaus.demo.AnalyseCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 7 App: Werkstatt, Qualitaet automatisch
set "L=%L7%"
if exist "%ST%\protokoll.txt" del /q "%ST%\protokoll.txt"
>"%EI%" echo qualitaet=auto
set "N=7 App 60 s: Sturmflut, Werkstatt, Qualitaet automatisch (Protokoll nach docs\status)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich werkstatt --einstellungen %EI% --sekunden 60"
call :lauf
set "E=0"
if exist "%ST%\protokoll.txt" type "%ST%\protokoll.txt" >>"%L7%"

rem ---------------------------------------------------------------- 8-9 Abbild
set "N=8 Abbild mit jpackage (dist\abbild\HalligHooge)"
set "C=call "%SH%\tools\abbild.cmd" "%HH%" HalligHooge 1.0.0"
call :lauf
set "E8=%E%"
set "E=0"
popd
if not "%E8%"=="0" goto :ohneabbild
set "AB=%HH%\dist\abbild\HalligHooge"
set "EA=%ST%\W5_abbild_einstellungen.properties"
if exist "%EA%" del /q "%EA%"
>>"%UE%" echo [%DATE% %TIME%] Start  9 Abbild 30 s ohne Java auf dem Suchpfad (Pruefstein 7)
pushd "%AB%"
set "PATH_ALT=%PATH%"
set "PATH=%SystemRoot%\system32;%SystemRoot%"
start "" /wait "%AB%\HalligHooge.exe" --szenario sturm --sekunden 30 --einstellungen "%EA%"
set "E9=%ERRORLEVEL%"
set "PATH=%PATH_ALT%"
popd
>>"%UE%" echo [%DATE% %TIME%] Ende   9 Abbild 30 s ohne Java auf dem Suchpfad (Pruefstein 7)  - Exit %E9%
dir /s "%AB%" | find "Datei(en)" >>"%L7%" 2>&1
:ohneabbild

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
