@echo off
rem Teil C, Lauf C3 (Werkbuch HalligHooge 139): die Videos der sechs Regiebuecher, 1080p, 24 B/s, 8 Teilbilder, Korn 0,5
rem (wie W4), danach der Gesamtfilm "Hooge" aus den sechs Videos zusammengesetzt (ffmpeg, ohne neu zu rechnen).
rem Aufruf: lauf_C3.cmd a  = Buecher 1-3 (rund 70-80 min)
rem         lauf_C3.cmd b  = Buecher 4-6 und Gesamtfilm (rund 55-65 min; setzt a voraus)
rem         lauf_C3.cmd    = beides (rund 2-2,5 h)
rem Kein Bau (Stand von C2b). Ausgaben: HalligHooge\docs\video\C3_*.mp4 mit .txt, Status_C3_*.txt. Es oeffnet sich kein Fenster.
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
set "EI=%ST%\C3_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "APPS=java %U% -Dlauf.platte.schlaf=0.5:20 --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_C3_.txt"
set "L1=%ST%\Status_C3_1.txt"
set "L4=%ST%\Status_C3_4.txt"
set "L5=%ST%\Status_C3_5.txt"
set "L6=%ST%\Status_C3_6.txt"
set "E=0"
set "TEIL=%~1"
set "VI=%HH%\docs\video"
if not exist "%VI%" mkdir "%VI%"
>>"%UE%" echo Lauf C3 (Teil C, Videos) Teil "%TEIL%", gestartet %DATE% %TIME%
>>"%L1%" echo Status_C3_1: Videos (Teil "%TEIL%")

set "L=%L1%"
set "FF="
where ffmpeg >>"%L1%" 2>&1 && set "FF=1"
if not defined FF (
  >>"%UE%" echo ffmpeg NICHT gefunden - Abbruch; einmalig: winget install Gyan.FFmpeg
  goto :abbruch
)
pushd "%HH%"
set "A=%J% com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --groesse 1080p"
if /i "%TEIL%"=="b" goto :teilb

rem ---------------------------------------------------------------- a: Buecher 1-3
set "N=1 Video Ein Tag auf Hooge"
set "C=%A% --regie tag --ziel %VI%\C3_1_Tag.mp4"
call :lauf
set "N=2 Video Landunter an der Hanswarft"
set "C=%A% --regie landunter --ziel %VI%\C3_2_Landunter.mp4"
call :lauf
set "N=3 Video Xaver"
set "C=%A% --regie xaver --ziel %VI%\C3_3_Xaver.mp4"
call :lauf
if /i "%TEIL%"=="a" goto :ende

:teilb
rem ---------------------------------------------------------------- b: Buecher 4-6, Gesamtfilm
set "N=4 Video Stroemung"
set "C=%A% --regie stroemung --ziel %VI%\C3_4_Stroemung.mp4"
call :lauf
set "N=5 Video Was das Wasser traegt"
set "C=%A% --regie sediment --ziel %VI%\C3_5_Wasser_traegt.mp4"
call :lauf
set "N=6 Video Luft ueber dem Watt"
set "C=%A% --regie luft --ziel %VI%\C3_6_Luft.mp4"
call :lauf
rem Gesamtfilm: die sechs Videos hintereinander (gleiche Kodierung, ohne neu zu rechnen)
>"%VI%\C3_liste.txt" echo file 'C3_1_Tag.mp4'
>>"%VI%\C3_liste.txt" echo file 'C3_4_Stroemung.mp4'
>>"%VI%\C3_liste.txt" echo file 'C3_5_Wasser_traegt.mp4'
>>"%VI%\C3_liste.txt" echo file 'C3_6_Luft.mp4'
>>"%VI%\C3_liste.txt" echo file 'C3_2_Landunter.mp4'
>>"%VI%\C3_liste.txt" echo file 'C3_3_Xaver.mp4'
set "N=7 Gesamtfilm Hooge (ffmpeg concat)"
set "C=ffmpeg -hide_banner -loglevel error -y -f concat -safe 0 -i %VI%\C3_liste.txt -c copy -movflags +faststart %VI%\C3_Hooge_Gesamtfilm.mp4"
call :lauf
set "N=8 AusgabeCheck --datei Gesamtfilm"
set "C=%J% com.dan.steuerhaus.demo.AusgabeCheck --datei %VI%\C3_Hooge_Gesamtfilm.mp4"
call :lauf

:ende
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
