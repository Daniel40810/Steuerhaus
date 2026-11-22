@echo off
rem Steuerhaus W4, Lauf W4 (Werkbuch HalligHooge 121/122): Ausgabe – Video Bild fuer Bild, Standbild 4K, Korn, Rolltitel.
rem 1-3 bauen; 4 BuehneCheck und BildCheck 2,3 (Pruefstein 8: Fenster unveraendert); 5 SteuerhausCheck, RegieCheck,
rem KinoCheck (Pruefstein 8); 6 AusgabeCheck (Pruefsteine 1-5, 7; Bilder W4_*.png); 7 ffmpeg suchen;
rem 8 Video "Landunter an der Hanswarft" 1080p, 24 B/s, 8 Teilbilder, Korn (ohne ffmpeg: nur 96 Bilder als PNG-Folge);
rem 9 AusgabeCheck --datei (Pruefstein 6); 10 Standbild 4K "Warften von oben, 00:00".
rem Ausgaben: HalligHooge\docs\status\Status_W4_*.txt, docs\video\W4_Landunter_1080p.mp4, docs\status\W4_*.png.
rem Erwartete Dauer: rund 25-35 min (das Video allein rund 10-20 min). Es oeffnet sich kein Fenster.
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
set "EI=%ST%\W4_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "VI=%HH%\docs\video"
if not exist "%VI%" mkdir "%VI%"
set "UE=%ST%\Status_W4_.txt"
set "L1=%ST%\Status_W4_1.txt"
set "L4=%ST%\Status_W4_4.txt"
set "L6=%ST%\Status_W4_6.txt"
set "L8=%ST%\Status_W4_8.txt"
set "E=0"
>"%UE%" echo Lauf W4 (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W4_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W4_4: BuehneCheck, BildCheck, SteuerhausCheck, RegieCheck, KinoCheck
>"%L6%" echo Status_W4_6: AusgabeCheck
>"%L8%" echo Status_W4_8: Video, Datei, Standbild 4K

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

rem ---------------------------------------------------------------- 4-5 Fenster unveraendert
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
popd
pushd "%HH%"
set "N=5a SteuerhausCheck (Importregel mit ausgabe)"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"
set "N=5b RegieCheck"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"
set "N=5c KinoCheck"
set "C=%J% com.dan.steuerhaus.demo.KinoCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 6 AusgabeCheck
set "L=%L6%"
set "N=6 AusgabeCheck (Pruefsteine 1-5, 7)"
set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 7-10 Video, Datei, Standbild
set "L=%L8%"
set "FF="
where ffmpeg >>"%L8%" 2>&1 && set "FF=1"
if defined FF (
  >>"%UE%" echo ffmpeg gefunden
  ffmpeg -hide_banner -version >>"%L8%" 2>&1
) else (
  >>"%UE%" echo ffmpeg NICHT gefunden - nur eine kurze PNG-Folge; einmalig: winget install Gyan.FFmpeg
)
set "V=%VI%\W4_Landunter_1080p.mp4"
if defined FF (
  set "N=8 Video Landunter an der Hanswarft, 1080p, 24 B/s, 8 Teilbilder, Korn"
  set "C=%J% com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --regie "Landunter an der Hanswarft" --groesse 1080p --ziel %V%"
) else (
  set "N=8 Video ohne ffmpeg: 96 Bilder als PNG-Folge"
  set "C=%J% com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --regie "Landunter an der Hanswarft" --groesse 1080p --png --bis 96 --ziel %VI%\W4_Landunter_png"
)
call :lauf
set "E=0"
if defined FF (
  set "N=9 AusgabeCheck --datei (Pruefstein 6)"
  set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --datei %V%"
  call :lauf
  set "E=0"
)
set "N=10 Standbild 4K, Warften von oben"
set "C=%J% com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --standbild --lesezeichen "Warften von oben, 00:00" --groesse 4k --ziel %ST%\W4_Standbild_Warften_4K.png"
call :lauf
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Video: %V%
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
