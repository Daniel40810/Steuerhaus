@echo off
rem Steuerhaus W3, Lauf W3 (Werkbuch HalligHooge 116/117): Zeit und Regie.
rem 1-3 Renderer, Steuerhaus, HalligHooge bauen (Kamera in der Buehne, Blende in render.overlay, Swapchain mit TRANSFER_SRC;
rem     regie, Regisseur, Kino, Lesezeichen; Regiebuch "Landunter an der Hanswarft");
rem 4 OverlayCheck; 5 BuehneCheck mit den Schaltern seit P12 und BildCheck 2,3 (Pruefstein 5: ohne Kamera unveraendert);
rem 6 SteuerhausCheck (Pruefstein 10); 7 RegieCheck (Pruefsteine 1-4, 8, ohne Bild); 8 EinblendungCheck (W2, unveraendert?);
rem 9 KinoCheck (Pruefsteine 6, 7; Bilder W3_Kino_*.png, W3_Blende.png);
rem 10 Anwendung mit dem Regiebuch im Kino, bis das Kino zu Ende ist (hoechstens 5 min; Pruefstein 9).
rem Die Schritte aus W2b sind schon gelaufen (17:51-17:55) und fehlen hier.
rem Ausgaben: HalligHooge\docs\status\Status_W3_*.txt und die Bilder.
rem Erwartete Dauer: rund 9-12 min. Das Fenster der Anwendung oeffnet sich in Schritt 10 von selbst (rund 3-5 min).
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
set "EI=%ST%\W3_einstellungen.properties"
set "U=-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8"
set "APP=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -jar dist\HalligHooge.jar"
set "J=java %U% --enable-native-access=ALL-UNNAMED -Xmx12g -cp dist\HalligHooge.jar"
set "R=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -cp dist\Renderer.jar"
set "RS=java %U% --enable-native-access=ALL-UNNAMED -Xmx16g -Dlauf.haeuser=aus -Dlauf.gestalt=aus -Dlauf.bewuchs=aus -Dlauf.brandung=aus -cp dist\Renderer.jar"
set "ANT=ant"
where ant >nul 2>nul || set "ANT=C:\Program Files\Apache NetBeans\extide\ant\bin\ant.bat"

if not exist "%ST%" mkdir "%ST%"
set "UE=%ST%\Status_W3_.txt"
set "L1=%ST%\Status_W3_1.txt"
set "L4=%ST%\Status_W3_4.txt"
set "L6=%ST%\Status_W3_6.txt"
set "L9=%ST%\Status_W3_9.txt"
set "L10=%ST%\Status_W3_10.txt"
set "E=0"
>"%UE%" echo Lauf W3 (Steuerhaus), gestartet %DATE% %TIME%
>"%L1%" echo Status_W3_1: Renderer, Steuerhaus, HalligHooge bauen
>"%L4%" echo Status_W3_4: OverlayCheck, BuehneCheck, BildCheck (Pruefstein 5)
>"%L6%" echo Status_W3_6: SteuerhausCheck, RegieCheck, EinblendungCheck
>"%L9%" echo Status_W3_9: KinoCheck
>"%L10%" echo Status_W3_10: Anwendung mit dem Regiebuch

rem ---------------------------------------------------------------- 1-3 Bauen
set "L=%L1%"
pushd "%RE%"
set "N=1 Renderer bauen (clean jar; SPIR-V mit voll.vert und blende.frag, check-packages)"
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

rem ---------------------------------------------------------------- 4-5 Renderer
set "L=%L4%"
pushd "%RE%"
set "N=4 OverlayCheck (render.overlay ohne Gebiet)"
set "C=%R% com.dan.render.overlay.demo.OverlayCheck"
call :lauf
set "E=0"
set "N=5a BuehneCheck mit den Schaltern seit P12 (Pruefstein 5)"
set "C=%RS% com.dan.lauf.demo.BuehneCheck --szenen %SZ%"
call :lauf
set "E=0"
set "N=5b BildCheck 2,3 (Pruefstein 5)"
set "C=%R% com.dan.lauf.demo.BildCheck --gebiet %G% --archiv %A6% --nur 2,3"
call :lauf
set "E=0"
popd

rem ---------------------------------------------------------------- 6-9 Steuerhaus
pushd "%HH%"
set "L=%L6%"
set "N=6 SteuerhausCheck (Pruefstein 10)"
set "C=%J% com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status"
call :lauf
set "E=0"
set "N=7 RegieCheck (Pruefsteine 1-4, 8; ohne Bild)"
set "C=%J% com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus"
call :lauf
set "E=0"
set "N=8 EinblendungCheck (W2, Pruefsteine 1, 3-7)"
set "C=%J% com.dan.steuerhaus.demo.EinblendungCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"
set "L=%L9%"
set "N=9 KinoCheck (Pruefsteine 6, 7; Bilder)"
set "C=%J% com.dan.steuerhaus.demo.KinoCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status"
call :lauf
set "E=0"

rem ---------------------------------------------------------------- 10 die Anwendung mit dem Regiebuch
set "L=%L10%"
if exist "%EI%" del /q "%EI%"
set "N=10 Anwendung: Regiebuch Landunter an der Hanswarft im Kino, Nautik (Pruefstein 9; hoechstens 300 s)"
set "C=%APP% --szenen src\szenen\Hooge.szenen --szenario sturm --thema nautik --arbeitsbereich betrachten --einstellungen %EI% --regie "Landunter an der Hanswarft" --sekunden 300"
call :lauf
popd

>>"%UE%" echo.
>>"%UE%" echo FERTIG %DATE% %TIME%
echo.
echo Fertig. Uebersicht: %UE%
echo Zur Sichtpruefung (Pruefstein 11): %HH%\HalligHooge.cmd, Regie - Landunter an der Hanswarft (Esc bricht ab)
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
