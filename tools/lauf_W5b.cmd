@echo off
rem Steuerhaus W5b, Lauf W5b (Werkbuch HalligHooge 128/129): Sonde, Vergleichsbild, Pruefstein 19, Abbild, Messung A47.
rem 1-3 bauen; 4 CoreCheck (Archiv.wert), MessblickCheck (Pruefstein 19 neu, F218 A), SteuerhausCheck;
rem 5 AnalyseCheck (Pruefsteine 1-6); 6 Abbild mit jpackage (JDK gesucht, F219 A); 7 Abbild 30 s ohne Java (Pruefstein 7);
rem 8 App 80 s Sturmflut 10:00 bis rund 16:30 UTC, lange Bilder mit Lesen, Sperre, Speicherbereinigung (A47).
rem Ausgaben: HalligHooge\docs\status\Status_W5b_*.txt, protokoll.txt (dort).
rem Erwartete Dauer: rund 10-15 min. Das Fenster der App oeffnet sich zweimal von selbst.
rem Anhalten zwischen zwei Schritten: eine Datei STOPP.txt neben dieses Skript legen.
rem Findet das Skript kein JDK fuer jpackage: hier den Pfad eintragen, etwa  set "JDK=C:\Program Files\Java\jdk-21"
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
set "EI=%ST%\W5b_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W5b_.txt"
set "L1=%ST%\Status_W5b_1.txt"
set "L4=%ST%\Status_W5b_4.txt"
set "L5=%ST%\Status_W5b_5.txt"
set "L6=%ST%\Status_W5b_6.txt"
set "L8=%ST%\Status_W5b_8.txt"
set "E=0"
>"%UE%" echo Lauf W5b (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W5b_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W5b_4: CoreCheck, MessblickCheck, SteuerhausCheck
>"%L5%" echo Status_W5b_5: AnalyseCheck
>"%L6%" echo Status_W5b_6: Abbild
>"%L8%" echo Status_W5b_8: App 80 s, Messung A47

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

rem ---------------------------------------------------------------- 4 Kern, Messblick, Steuerhaus
set "L=%L4%"
pushd "%RE%"
set "N=4a CoreCheck (Archiv.wert, Messung des Lesens)"
set "C=%R% com.dan.core.demo.CoreCheck"
call :lauf
set "E=0"
set "N=4b MessblickCheck (Pruefstein 19 gegen ohne, F218 A)"
set "C=%R% com.dan.lauf.demo.MessblickCheck --szenen %SZ%"
call :lauf
set "E=0"
popd
pushd "%HH%"
set "N=4c SteuerhausCheck"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 5 AnalyseCheck
set "L=%L5%"
set "N=5 AnalyseCheck (Pruefsteine 1-6; Sonde nur die Zelle, Vergleich in frischen Buehnen)"
set "C=%J% com.dan.steuerhaus.demo.AnalyseCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6-7 Abbild
set "L=%L6%"
set "N=6 Abbild mit jpackage (dist\abbild\HalligHooge)"
set "C=call "%SH%\tools\abbild.cmd" "%HH%" HalligHooge 1.0.0"
call :lauf
set "E6=%E%"
set "E=0"
popd
if not "%E6%"=="0" goto :ohneabbild
set "AB=%HH%\dist\abbild\HalligHooge"
set "EA=%ST%\W5b_abbild_einstellungen.properties"
if exist "%EA%" del /q "%EA%"
>>"%UE%" echo [%DATE% %TIME%] Start  7 Abbild 30 s ohne Java auf dem Suchpfad (Pruefstein 7)
pushd "%AB%"
set "PATH_ALT=%PATH%"
set "JAVA_HOME_ALT=%JAVA_HOME%"
set "PATH=%SystemRoot%\system32;%SystemRoot%"
set "JAVA_HOME="
start "" /wait "%AB%\HalligHooge.exe" --szenario sturm --sekunden 30 --einstellungen "%EA%"
set "E7=%ERRORLEVEL%"
set "PATH=%PATH_ALT%"
set "JAVA_HOME=%JAVA_HOME_ALT%"
popd
>>"%UE%" echo [%DATE% %TIME%] Ende   7 Abbild 30 s ohne Java auf dem Suchpfad (Pruefstein 7)  - Exit %E7%
dir /s "%AB%" | find "Datei(en)" >>"%L6%" 2>&1
if exist "%ST%\protokoll.txt" (
  >>"%L6%" echo Protokoll des Abbilds:
  type "%ST%\protokoll.txt" >>"%L6%"
  del /q "%ST%\protokoll.txt"
)
:ohneabbild

rem ---------------------------------------------------------------- 8 App 80 s, Messung A47
set "L=%L8%"
pushd "%HH%"
if exist "%ST%\protokoll.txt" del /q "%ST%\protokoll.txt"
>"%EI%" echo qualitaet=auto
set "N=8 App 80 s: Sturmflut 10:00 bis rund 16:30 UTC, Werkstatt, Messung langer Bilder (A47)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich werkstatt --einstellungen %EI% --sekunden 80"
call :lauf
set "E=0"
popd
if exist "%ST%\protokoll.txt" type "%ST%\protokoll.txt" >>"%L8%"

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
