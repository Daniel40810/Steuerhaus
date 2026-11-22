# com.dan.steuerhaus – der Werkzeugkasten für Szenen-Apps

**Stand: DB1 Messarchiv (22.11.2026); Y1 Vergleich (21.11.2026); W5c Analyse und Betrieb (20.11.2026).** Werkbuch HalligHooge 110, 111, 113, 114, 116, 117, 121, 122, 126 bis 131. Eigenes NetBeans-Projekt neben dem Renderer (F174 A), gebunden an `com.dan.lauf` (F175 A), seit W2 an `com.dan.render.overlay` (die Kacheln der Einblendungen) und seit W3 an den Kern von `com.dan.kino` (Balken, Titel, Abspann; F194 A).

Das Steuerhaus ist die Anwendung zwischen den Bauteilen von FStyle und dem Bild des Renderers. Eine Szenen-App besteht aus einem Manifest, einer Szenendatei und wenigen Zeilen Java:

```java
public static void main(String[] args) throws Exception {
    Szenenapp.starten(new File("src/szenen/Hooge.steuerhaus"), args);
}
```

## Schichten

```
com.dan.steuerhaus          nur java.*              Manifest, Befehl, Befehle, Einstellungen, Chronik (seit DB2)
com.dan.steuerhaus.regie    nur java.*              Regiebuch, Regie, Fahrt, Lesezeichen (seit W3)
com.dan.steuerhaus.analyse  nur java.*              Qualitaet, Protokoll (seit W5)
com.dan.steuerhaus.thema    + Swing, FStyle         Thema (Labor, Nautik), Faerber
com.dan.steuerhaus.ui       + Swing, FStyle         Hauptfenster, Dock, Abschnitt, Arbeitsbereich, Menueleiste, Tasten,
                                                    Befehlspalette, Startbild, Statuszeile, Hinweis, UeberFenster
com.dan.steuerhaus.buehne   + com.dan.lauf, core,   Szenenapp, Katalog, Bedienung, Zeitleiste, FotoFenster, SchnittFenster, Vorschau,
                              render.overlay,       Einblendungen, Orte, Regisseur, Kinobild, LesezeichenListe,
                                                    SondenPaneel, LeistungPaneel, ProtokollPaneel, Vergleichsfenster (W5)
                              com.dan.kino
com.dan.steuerhaus.vergleich wie atlas            Vergleich, Mappe, Vergleichsansicht, Spieler (seit Y1)
com.dan.steuerhaus.jahr     wie atlas               Jahresauftrag, Jahresmappe, Zeitplan, Jahresbild, Jahresansicht, Jahresfenster (Y3)
com.dan.steuerhaus.gezeiten wie atlas, + core.astro Gezeitenauftrag, Gezeitentafel, Vorhersage, Mond, Kalenderansicht, Kalenderfenster (Z1)
com.dan.steuerhaus.chronik  wie atlas               Abdeckung, Chronikansicht, Chronikfenster (seit DB2)
com.dan.steuerhaus.messung  java.*, java.sql,        Sammler (Hauptklasse, ohne Fenster), Messauftrag, Messarchiv, Uebernahme,
                              com.dan.store,        Scheitelsuche, Ausreisser, Aufbewahrung, Abruf (seit DB1), Chronikbau (DB2)
                              core.pruef/astro/raum,
                              Kern, gezeiten
com.dan.steuerhaus.ausgabe  wie buehne, + Kern von   Ausgabe (Hauptklasse), Aufnahme, Korn, Kodierer (seit W4), Vergleichsfolge (Y1),
                                                    Jahresrechnung (Y3), Gezeitenrechnung (Z1)
                              com.dan.film
com.dan.steuerhaus.demo     alles                   SteuerhausCheck, RegieCheck (ohne Fenster und Grafikkarte), EinblendungCheck,
                                                    KinoCheck, AusgabeCheck, AnalyseCheck (mit Karte)
```

Importregel (Prüfstein 5, geprüft von `SteuerhausCheck`; seit DB1 auch von `MessungCheck`): Der Kern kennt weder AWT, Swing, FStyle noch den Renderer. `thema` und `ui` kennen FStyle, nicht den Renderer. `regie` und seit W5 `analyse` sind so rein wie der Kern: Regiebücher und Fahrten laufen ohne Bild (RegieCheck). Nur `buehne` (und `demo`) kennt `com.dan.lauf` (ohne `demo`), `com.dan.core` (ohne `gpu`), seit W2 `com.dan.render.overlay` und seit W3 `com.dan.kino` (ohne `kino.gl`). Seit DB1 kennt `messung` nur `java.*` (mit `java.sql`), `com.dan.store`, `core.pruef`, `core.astro`, `core.raum`, den Kern und `gezeiten` – kein AWT, kein Swing, kein FStyle. Kein Paket kennt ein Projekt, sonst `render`, `physics` oder LWJGL. Der Renderer kennt das Steuerhaus nicht.

## Manifest

Format wie Rezept und Szenendatei (`schluessel = wert`, `#` Kommentar):

| Schlüssel | Bedeutung |
|---|---|
| `app.titel`, `app.unterzeile`, `app.version` | Titel (Fenster, Startbild, Einstellungen), Unterzeile, Fassung |
| `app.logo` | `FIconType` (z. B. `HARBOR`) |
| `app.icon` | Programmsymbol für Fenster und Taskleiste, PNG relativ zum Manifest (seit dem Abschluss, Werkbuch HalligHooge 142); `tools/abbild.cmd` gibt `<Name>.ico` neben dem Manifest an jpackage (`--icon`) |
| `app.szenen` | Szenendatei, relativ zum Manifest |
| `app.thema` | `labor` oder `nautik` (erster Start) |
| `app.arbeitsbereich` | `betrachten`, `steuern` oder `messen` (erster Start) |
| `app.fotos` | Ablage der Fotos, relativ zum Arbeitsordner |
| `app.zone` | Zeitzone der Anzeige (Vorgabe `Europe/Berlin`) |
| `quelle.N` | Zeilen des Quellennachweises („Über“, später Abspann) |
| `katalog.szenarien.N`, `katalog.ansichten.N` | `Gruppe: schluessel, schluessel, …` |
| `vorschau.ansicht.<k>` | `<szenario> [ISO-Zeit UTC]` für das Vorschaubild einer Ansicht |
| `minikarte` | Rechteck der Minikarte in UTM (`Ost Nord Ost Nord`); Vorgabe: das Gitter der Nässe des Szenarios |
| `orte.datei`, `orte.arten`, `orte.ohne`, `orte.umbenennen`, `orte.dazu.N` | Ortsnamen: Datei (Vorgabe `orte.txt` im Gebiet, aus `tools/orte/orte.py`), Arten, Ausnahmen, `Alt > Neu; …`, von Hand `Art; Name; Ost; Nord` |
| `einblendung.mass` | Größe der Einblendungen (Vorgabe: Skalierung der Anzeige) |
| `regie.N` | Regiebücher, relativ zum Manifest (z. B. `regie/landunter.regie`); jede Datei kann auch Fahrten tragen |
| `regie.aufnahmen` | Ablage der aufgenommenen Fahrten (Vorgabe `regie/aufnahmen.regie`) |
| `ausgabe.videos`, `ausgabe.korn` | seit W4: Ablage der Videos (Vorgabe `docs/video`), Stärke des Filmkorns (1 = Vision3 250D, 0 = aus) |
| `app.lesezeichen` | Datei der Lesezeichen, relativ zum Manifest (Vorgabe `<Titel>.lesezeichen`) |

## Was die App kann (W1 bis W3)

| Teil | Bedienung |
|---|---|
| Docks | links Katalog (F4), rechts Bedienung (F2), unten Zeitleiste (F3); Griff klicken: klappen; ziehen: Breite/Höhe |
| Arbeitsbereiche | Betrachten (Strg+1), Steuern (Strg+2), Messen (Strg+3) |
| Nur das Bild | F11 (Menü, Docks, Statuszeile weg; Einblendung in der Titelleiste) |
| Befehlspalette | Strg+K: Suche über alle Befehle, Szenarien und Ansichten |
| Zeit | Leertaste Halt/weiter, Strg+→ vor, Strg+← zurück, Strg+↑/↓ Zeitraffer |
| Messen | Strg+Umschalt+1/2/3 Partikel, LIC, Linien; Strg+J Schnitt |
| Foto | F9 |
| Einblendungen | F6 alle an und aus; Ansicht › Einblendungen je Teil für den Arbeitsbereich: Uhr und Pegel, Kompass, Maßstab, Minikarte, Ortsnamen, Farblegende (Vorgaben: Betrachten Uhr und Kompass, Steuern alle, Messen Legende und Maßstab; im Kinoblick nur die Ortsnamen) |
| Regie | Regie › Regiebücher und Fahrten; Esc bricht Kino, Regiebuch oder Fahrt ab |
| Aufnahme | K Schlüsselbild, Alt+K letztes weg, P abspielen, Strg+S als Fahrt nach `regie.aufnahmen` |
| Lesezeichen | B setzen (Szenario, Zeit, Blick); Szene › Lesezeichen; Marken in der Zeitleiste anklicken |
| Klicks mit Karte | Klick auf die Minikarte: die Kamera fliegt dorthin (3 s); Klick auf einen Ortsnamen: Flug zum Ort |
| Ausgabe | Datei › Video ausgeben … (Strg+E), Datei › Standbild 4K (Strg+F9), Datei › Ausgabe abbrechen |
| Über, Quellen, Tasten | F1 |
| Thema | Ansicht › Thema: Labor, Nautik |
| Beenden | Strg+Q |

Die App merkt sich Fenster, Docks, Abschnitte, Arbeitsbereich, Thema, Szenario, Ansicht, Zeitraffer und die Einblendungen je Arbeitsbereich in `%USERPROFILE%\.steuerhaus\<Titel>\einstellungen.properties` (oder `--einstellungen <datei>`).

## Aufrufoptionen

`--manifest`, `--szenen`, `--regie "<Name des Regiebuchs>"` (spielt das Buch im Kino ab Start; mit `--sekunden` Probe), `--szenario <k>`, `--thema <labor|nautik>`, `--arbeitsbereich <k>`, `--einstellungen <datei>`, `--sekunden <s>` (Probe: Einblendung alle 2 s, Bilder/s, Ende), `--erwarte k=v,…` (Prüfstein 6), `--messblick`, `--schnitt O1 N1 O2 N2`, `--vorschau` (Vorschaubilder des Katalogs ohne Fenster, dann Ende).

## Über der Vulkan-Fläche

Die Leinwand ist schwergewichtig. Was über ihr erscheinen soll, ist ein eigenes Fenster: Menüs und Auswahllisten (schwere Popups), Befehlspalette, Startbild, Hinweise (statt FToast, der in der `JLayeredPane` läge), Foto und Schnitt. Docks liegen neben der Leinwand. Einblendungen im Bild sind seit W2 Kacheln der Auflage `render.overlay.Einblendung`: in Java2D gemalt, hochgeladen, wenn sie sich ändern, nie im Foto.

## Einblendungen (W2)

| Teil | Wo | Inhalt |
|---|---|---|
| Uhr und Pegel | oben links | Zeit in der Zone, Pegel mit Tendenz (15 min), nächstes Hoch- oder Niedrigwasser, Wind mit Pfeil; im Landunter „Landunter seit …“ |
| Kompass | oben rechts | Rose (dreht auf der Karte mit dem Blick, ohne Verzug), Kurs und Neigung im Glas |
| Maßstab | unten links | Balken mit runder Länge aus zwei Strahlen im unteren Drittel, Abstand zum Boden |
| Minikarte | unten rechts | Land, Watt und Wasser beim Stand jetzt (Raster 256, alle 2 s), Warften, Auge und Blickkegel |
| Ortsnamen | an ihrem Ort | aus OSM; verdeckte blenden aus, nahe groß, ferne klein, überdecken einander nicht |
| Farblegende | über dem Maßstab | nur mit Messblick: Farbleiter der Strömung, Art und Spanne der Isolinien |

Das Startbild zeigt seit W2 die Schritte der Bühne mit einem Balken (`Buehne.Laden`).

## Regie (W3)

Ein Regiebuch ist eine Textdatei wie in Rungholt (F193 A):

```
Regiebuch: Landunter an der Hanswarft
Beschreibung: …
Länge: 1:50
0:00 zeit 25.10.2026 21:40
0:00 raffer 120
0:10 warte landunter
0:22 fahrt Um die Hanswarft | Untertitel ab hier
1:40 sage Das Wasser geht …

Fahrt: Um die Hanswarft
0:00 470800 6046240 10 nhn 0 -3 55
0:08 …
Ende
```

Zeilen `m:ss befehl argumente`, nach `|` ein Untertitel. Befehle: `szenario`, `ansicht`, `fahrt`, `zeit [TT.MM.JJJJ] hh:mm`, `raffer`, `halt`, `weiter`, `ebene <name> an|aus`, `messblick aus|<ebenen>`, `linien`, `einblendung alle|aus|<teile>`, `thema`, `warte uhr [TT.MM.] hh:mm | pegel [>|<] <m> | landunter | fahrt`, `sage <text>`, `lesezeichen <name>`, `folge <Regiebuch>`. Die Uhr des Buchs ist Filmzeit: `warte` hält sie an, bis die Szene so weit ist (höchstens 120 s; F195 A). Schlüsselbilder einer Fahrt: Zeit, Ost, Nord, Höhe, `nhn|boden`, Richtung, Neigung, Bildwinkel; dazwischen Hermite in der Zeit mit Catmull-Rom-Tangenten, an den Enden Ruhe, die Richtung auf kürzestem Weg.

Die Kamera fragt die Bühne je Bild (`lauf.Kamera`); der Blick geht danach in die Steuerung über, die Maus übernimmt ohne Sprung. Ein Regiebuch läuft im Kino (`Kinobild`, auf `com.dan.kino`): Balken 2,39 : 1, Vorspann mit Titel und Beschreibung, Untertitel, Abspann aus `quelle.N`. Schnitte (`szenario`, `ansicht`, `zeit`) blenden 1,6 s über das gemerkte Bild (`Einblendung.merken`, `blende`); ohne `TRANSFER_SRC` an der Swapchain ist es ein harter Schnitt. Die Einblendungen am Rand rücken um die Balken nach innen. Seit W3b beginnt das Kino schwarz: Erst gelten die Zeilen der ersten Sekunde (Szenario, Zeit, Ansicht), dann läuft der Vorspann; braucht das Zeit, steht der Hinweis „Szene wird vorbereitet“. Die Zeitleiste zeigt die Spur des Buchs (Zeilen, Fahrten, `warte`) und die Lesezeichen.

Lesezeichen (F196 A) stehen im Projekt (`app.lesezeichen`), eine Zeile je Zeichen: `lesezeichen.N = Name; Szenario; ISO-Zeit UTC; Ost Nord Höhe Richtung Neigung Bildwinkel`.

**Seit Teil C** (Werkbuch HalligHooge 134, F227 A): `warte pegel < x` wartet auf fallendes Wasser (ein trockener Pegel gilt als darunter); `warte pegel x` und `warte pegel > x` wie bisher auf steigendes. `m:ss folge <Name>` spielt an dieser Stelle ein anderes Buch: Beim Laden (App, Ausgabe, `RegieCheck`) wird die Zeile durch dessen Zeilen ersetzt, um m:ss verschoben, seine Fahrten kommen dazu (`Regiebuch.aufgeloest`). Ein Teil darf nicht vor dem Ende des vorigen beginnen und selbst kein `folge` enthalten. So entsteht ein Gesamtfilm aus einzelnen Büchern.

**Motivsuche** (`demo.Motivsuche`, ohne Fenster): je Szenario aus dem Archiv Kontaktbögen (Pegelkurve mit Tag und Nacht, Hoch- und Niedrigwasser, Landunter; darunter bis zu 48 Standbilder je Motiv-Ansicht) und eine Tabelle mit Pegel, Wind, Strömung und Sonne je Bild. Motive im Manifest: `motivsuche.<szenario> = ansicht, ansicht@partikel,lic`, optional `motivsuche.<szenario>.bereich = von bis` (UTC).

## Ausgabe (W4)

Video und Standbild rechnet ein eigener Prozess ohne Fenster (`ausgabe.Ausgabe`, F200 A); die App startet ihn über das Menü, zeigt seinen Fortschritt in der Statuszeile und hält so lange ihr eigenes Bild an (`Buehne.ruhen`). Dasselbe von der Kommandozeile:

```bash
java -cp dist\HalligHooge.jar com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --regie "Landunter an der Hanswarft" [--groesse 1080p|4k]
java -cp dist\HalligHooge.jar com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus --standbild --lesezeichen "Warften von oben, 00:00" --groesse 4k
```

- **Bild für Bild** (`Aufnahme`, `Buehne.filmbild`): fester Takt, unabhängig von der Echtzeit; Regisseur, Kino und Einblendungen wie im Fenster (die Uhr der Einblendungen ist die Filmzeit, Tafeln werden sofort gemalt). Dieselbe Aufnahme gibt dieselben Bilder.
- **Bewegungsunschärfe** (F202 A): 24 B/s, Verschluss 180°, 8 Teilbilder je Filmbild, im Linearen gemittelt; `--teilbilder`, `--verschluss`, `--bilder-je-s`.
- **Korn** (F203 A, `Korn`): nach `com.dan.film` (Selwyn, Vision3 250D auf 2383, Super 35) in den Dichten von Negativ und Kopie, umgerechnet in die Helligkeit des Bildschirms; nur in Video und Standbild; Stärke `ausgabe.korn`, `--korn`, `--ohne-korn`.
- **Kodierer** (F201 A): ffmpeg über eine Pipe, H.264 High, CRF 18 (seit W4b, F207 A), yuv420p, BT.709, MP4 (`--ffmpeg`, `--crf`); ohne ffmpeg eine PNG-Folge (`--png` erzwingt sie). Neben der Datei `<ziel>.txt` mit Bildern, Größe, Bildrate, Rechenzeit, Speicher.
- **4K** (F204 A) in einem Stück; Video 1080p als Vorgabe. **Live** gibt es im Video nicht (F205 A).
- **Rolltitel** (F206 A): Der Abspann rollt mit allen Quellen (`Regisseur.abspann`), im Fenster und im Video.

## Analyse und Betrieb (W5)

- **Sonden** (F209 A, `com.dan.lauf.Sonde`): Strg+Klick ins Bild setzt eine Sonde (bis 4, nummerierte Marken im Bild). Je Sonde Wasserstand, Wassertiefe, Strömung und Wind über die ganze Szenariozeit, gerechnet aus dem Archiv neben der laufenden Bühne (Wasserstand und Tiefe in der Zelle der Pegelreihe, Strömung und Wind aus ihren Gittern). Das Paneel steht im Dock „Sonden“ (Arbeitsbereich Messen); `CSV` legt `docs/sonden/Sonden_<Zeit>.csv` ab.
- **Vergleich** (F210 A): Im Sonden-Paneel ein zweites Szenario wählen, ausgerichtet nach gleicher Uhrzeit oder gleichem Hochwasser; seine Kurven gestrichelt. „Vergleichsbild“ (Messen-Menü) rechnet in der Ausgabe zwei Standbilder 1080p desselben Blicks (`--vergleich <szenario> --ausrichten uhrzeit|hochwasser`, Ablage `fotos/Vergleich_<Zeit>_A/_B.png`) und zeigt sie mit Schieber; seit W5b rechnet jede Hälfte in einer frischen Bühne (`Aufnahme.frischeBuehne`), so gleicht sie einem einzelnen Standbild bitgleich.
- **Qualität** (F211 A, `analyse.Qualitaet`): Hoch, Mittel, Niedrig oder automatisch (Ansicht – Qualität). Mittel ohne Bewuchs und Kimmung, Niedrig auch ohne Brandung und Gestalt. Automatisch geht nach 3 s unter 30 B/s eine Stufe herunter, nach 15 s über 55 B/s eine hinauf, frühestens 15 s nach dem letzten Wechsel. Die Wahl steht in den Einstellungen (`qualitaet`).
- **Leistung** (Dock „Leistung“, Arbeitsbereich Werkstatt, Strg+4): Bildzeit der letzten 60 s (`Buehne.bildzeitVerlauf`), Median und 95 %, die Abschnitte der Stoppuhr je Bild (`Buehne.fensterAbschnitte`), Speicher, das letzte lange Bild.
- **Protokoll** (F213 A, `analyse.Protokoll`): `protokoll.txt` neben der Einstellungsdatei (1 MB, 5 Dateien), Dock „Protokoll“ mit den letzten 500 Einträgen: Start und Ende, Szenario, Qualität, Sonden, Ausgabe, lange Bilder, Fehler und alle Hinweise. Hilfe – Protokoll-Datei öffnen.
- **Ort der Daten** (F214 A): Die Szenendatei schreibt `${daten}` (`daten = D:\Daten\HalligHooge` als Vorgabe in der Datei); die App setzt den Ort aus den Einstellungen (Datei – Ort der Daten, wirkt beim nächsten Start), die Kommandozeile mit `-Dszenen.daten=…`.
- **Warten auf Stände** (W5c, F221 A): Die Bühne liest die Stände voraus; wacht die Platte erst auf, hält die Uhr an, das Bild läuft weiter. Ab 1 s Warten ein Hinweis, danach ein Eintrag „Daten“ im Protokoll mit der Dauer. Abhilfe ohne Warten: den Schlaf der Platte ausschalten oder die Daten aufs innere Laufwerk (Ort der Daten).
- **Abbild** (F214 A): `tools\abbild.cmd <Projekt> <Name> [<Version>]` packt mit jpackage (Java 21) Jar, lib und `src\szenen` samt eigener Laufzeit nach `dist\abbild\<Name>` (`<Name>.exe`, `app\`, `runtime\`); die App findet ihr Manifest dann in `app\szenen`. jpackage sucht das Skript seit W5b in einem JDK: `%JDK%`, `%JAVA_HOME%`, die NetBeans-Plattform `JDK_21`, Suchpfad, übliche Orte unter `%ProgramFiles%`. Mit dem WiX Toolset 3 entsteht zusätzlich ein Installationsprogramm. Die Daten bleiben, wo sie sind.

## Themen

`Thema.LABOR` hat genau die Farben der App bis Teil A; `Thema.NAUTIK` ist die Seekarte bei Nacht (Nachtblau, Messing, Tonnenrot, Pergament). `Thema.anwenden()` stellt `com.dan.fstyle.FTheme` ein; `Faerber` färbt FButton und FComboBox und alle Komponenten mit einer Rolle. FButton malt seit W1 mit `liquidColorTop/Bottom` (FStyle, Änderung in `FButton`), sonst ließen sich Knöpfe nicht umfärben.

## Prüfung

```bash
java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status
java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.EinblendungCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus
java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.KinoCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
```

`AnalyseCheck --manifest …` (Karte nur für 3 und 4, sonst `--ohne-karte`): Qualitätsstufen mit Hysterese, Protokoll mit Rotation, Sonde gegen Pegelreihe, Strömung, Vergleichsbild gegen zwei einzelne Standbilder, Kosten des Bildzeitrings.

`AusgabeCheck --manifest … --bilder docs\status` (mit Karte): zweimal dieselbe Aufnahme bitgleich, Filmzeit und Szenenzeit je Teilbild, Mittel der Teilbilder, Korn gegen das Modell, Standbild 4K gegen 1080p und Speicher, Kosten je Bild; `AusgabeCheck --datei x.mp4`: ffprobe gegen die Begleitdatei.

`RegieCheck` (ohne Bild): Lesen und Fehler der Bücher, Filmzeit mit `warte`, Fahrten (Schlüsselbilder getroffen, glatt, Richtung kurz), Aufnahme schreiben und lesen, Lesezeichen. `KinoCheck` (mit Karte): Balken im Bild, Überblendung ½ linear gegen die Mitte beider Bilder, Kosten der Blende, Klick auf die Minikarte gegen das Auge.

## Nächste Stufen

Werkbuch HalligHooge 144: C Ausstellung (X1 Startseite und Atlas, X2 Kapitel, X3 Rundgang, X4 Postkarte, X5 Ton), dann B Sturmflut-Labor (Y1–Y3), dann A Hallig-Begleiter (Z1–Z3).

## Seit C1b (Werkbuch HalligHooge 137)

- **Qualität im Kino (A53):** Die automatische Qualitätsstufe läuft auch im Kino, dort höchstens bis Mittel (die Brandung bleibt), und eine neue Stufe gilt erst in einer Überblendung (`Regisseur.blendet()`), damit sie nicht mitten in einer Einstellung springt. `Qualitaet.tiefste(Stufe)`.
- **Probe mit `--regie`:** Prüfstein 9 zählt bei einem Buch ohne Fahrt die Bildrate über das ganze Kino; je 10 s stehen die teuersten Abschnitte der Bildschleife in der Ausgabe.
- **Regiebücher von Hooge:** sechs Bücher und der Gesamtfilm „Hooge“ (`folge`), siehe `HalligHooge/src/szenen/regie`.

## Startseite und Atlas (X1, Werkbuch HalligHooge 145)

Zwei neue Pakete: `atlas` (Rechte wie `buehne`: `com.dan.lauf` und `buehne.Orte`; kein Renderer, kein Kern) und `tafel` (wie `ui`).

| Klasse | Aufgabe |
|---|---|
| `atlas.AtlasBau` | rechnet den Atlas einmal (`--atlas`): die Karte von oben (`lauf.Karte` im Renderer) je Ebene mit dem Wasser ihrer Zeit als JPEG, die Werte der Orte (Kuppe, Fuß, Häuser, Wasser am Fuß im Sturm, trocken am ruhigen Tag) in `atlas.werte`; Ablage `<Ordner der Szenendatei>/atlas/` |
| `atlas.Atlas` | liest Werte und Texte (`*.atlas`, Format wie das Manifest); nur Dateien im Projekt |
| `atlas.Kartenblatt` | die Karte zum Ziehen und Vergrößern, Ebenen, Orte, Maßstab, Nordpfeil |
| `atlas.Kachelleiste` | Kacheln in Gruppen (Regiebücher, Szenarien, Ansichten, Lesezeichen) |
| `atlas.Startseite` | eigenes Fenster über dem Inhalt des Hauptfensters (die Vulkan-Fläche bleibt, die Bühne lädt dahinter); Kopf mit dem Stand der Bühne und „Zur Szene“ |
| `atlas.Tafeln` | Inhalt der Infotafeln aus Werten und Texten; Knöpfe `tafel.<ort>.knopf.N = Titel; Szenario; Ansicht; Zeit` |
| `tafel.Tafel` | eine Infotafel: Titel, Art, Bild, Kennzahlen, Text, Quelle, Knöpfe |

Manifest: `app.startseite = an`, `atlas.texte`, `atlas.karte`, `atlas.zelle`, `atlas.blick` (Ost Nord Breite), `atlas.ebene.N = Kennung; Titel; Szenario; Zeit` (niedrig, hoch, scheitel oder ISO), `atlas.sturm`, `atlas.ruhig`. Die Startseite kommt, wenn es einen Atlas gibt und der Aufruf nicht gleich in eine Szene führt (`--szenario`, `--sekunden`, `--regie`, `--messblick`, `--schnitt`); `--startseite`, `--ohne-startseite` erzwingen es. Pos1 (Datei › Startseite) führt zurück; die Bühne ruht so lange.

## Kapitel (X2, Werkbuch HalligHooge 146)

Ein Kapitel ist ein Regiebuch mit Tafeln und Haltepunkten (`kapitel.N` im Manifest). Neue Befehle im Regiebuch: `tafel <Titel> | <Text>` (Tafel unten links, `buehne.Kapitelbild`), `sonde <Ost> <Nord> [Name]` (Wasserstandskurve in der Tafel), `warte klick` (wartet ohne Grenze auf Weiter). `Regie.abschnitte()`, `abschnitt()`, `springe(n)`: ein Abschnitt beginnt nach jedem `warte klick`; jeder Abschnitt stellt `weiter`, `szenario`, `zeit` selbst, damit „zurück“ in die richtige Szene führt (SteuerhausCheck X2 Prüfstein 1). In der App: Klick ins Bild oder Eingabe = weiter, Rücktaste = zurück, Esc = Ende; am Ende geht es zur Startseite. `--kapitel <Name>` startet eins nach dem ersten Bild (mit `--sekunden` alle 12 s weiter).

## Rundgang (X3, Werkbuch HalligHooge 148)
Ein Rundgang hat Halte an Orten der Karte; dazwischen geht die Kamera auf Augenhöhe die Wege entlang. Die Quelle schreibt man von Hand (`rundgang.N` im Manifest, `rundgang.Rundgang`: Szenario, Zeit, Raffer, Tempo, Auge, wahlweise Wasser und Boot; `halt.N = Ort | Titel | Text | blick … | sonde`). `--wege` (`rundgang.RundgangBau`) rechnet je Strecke den kürzesten Weg über das Netz der OSM-Wege (`com.dan.aufbereitung.Wegenetz`, Renderer), tastet den Boden alle 2 m ab und schreibt daneben `<name>.weg`: ein Regiebuch im Format der Kapitel – je Halt Tafel, Sonde und `warte klick`, je Strecke eine Fahrt (Schlüsselbilder alle rund 10 m, Augenhöhe = Boden + Auge, mindestens Wasser + Boot) und die Zwischentafel `unterwegs <Titel> | <Text>` (neuer Befehl; zählt nicht als Halt). Die App spielt `.weg` wie ein Kapitel; Startseite mit der Gruppe „Rundgänge“, Menü Regie › Rundgang, `--rundgang <Name>`. Neu im `Regisseur`: Während einer Fahrt im Kapitel oder Rundgang kann man sich mit der Maus umsehen; lässt man los, kehrt der Blick in rund 1 s zur Fahrt zurück, und eine neue Fahrt setzt dort an, wohin man gerade schaut. Importregel: `rundgang` hat die Rechte von `atlas`, dazu `com.dan.aufbereitung` (SteuerhausCheck X3 Prüfsteine 1–3).

## Postkarte (X4, Werkbuch HalligHooge 150)
`ausgabe.Postkarte`: drei Formate (A6 quer mit 300 dpi in der PNG-Datei, 4K, Quadrat), drei Stile (Goldkante wie das Symbol der App, Weiß, randlos mit Band), Vorlagen im Manifest (`postkarte.titel`, `postkarte.vorlage.N = Name; Stil; Titel; Zeile` mit Platzhaltern `{titel} {ort} {ansicht} {szenario} {datum} {uhr} {pegel} {pegelname} {wind}`; ein Teil zwischen „·“ mit leerem Platzhalter fällt weg). `Postkarte.werte` füllt sie aus dem Zustand der Bühne (Ort: der nächste Ort in 1,5 km, aus der Luft „Über …“; Wind als Richtung der Windrose, woher). Die Ausgabe (`--postkarte a6|4k|quadrat --vorlage … [--zeilen "Titel|Zeile"]` mit `--blick` oder `--lesezeichen`) rechnet das Bild genau in der Bildfläche, ohne Einblendungen, und setzt Rahmen, Unterschrift und Symbol darum; daneben `<ziel>.txt`. In der App: Datei › Postkarte (Strg P) – Format, Vorlage, die zwei Zeilen (aus dem Zustand jetzt, änderbar), Vorschau mit dem Vorschaubild der Ansicht; danach öffnet sich die Karte. SteuerhausCheck X4 Prüfsteine 1–3, Bilder `X4_Postkarte_<n>.png`.

## Ton (X5, Werkbuch HalligHooge 151)
Paket `ton` (F245 A: synthetisch, ohne Aufnahmen): `Klang` rechnet aus Rauschen und Filtern vier Stimmen – Wind (Bandpass mit dem Wind, Böen, Stereo nach der Richtung), Brandung (braunes Rauschen in Wellen, Gischt), Plätschern (platzende Blasen) und Möwen (Rufe mit fallendem Ton) –, gesteuert aus `Klang.Werte`; dieselbe Saat gibt dieselben Abtastwerte. `Tonwerte` macht aus dem Zustand der Bühne die Werte (Renderer: `Buehne.tonWerte` – Wasser am Ort, Abstand zum Wasser, Hs, Sonnenhöhe). `Ausgang` spielt über die Soundkarte (48 kHz, Stereo); ohne Gerät bleibt die App still. `WavDatei` schreibt WAV. In der App: Ansicht › Ton (an/aus Strg M, lauter, leiser), stumm auf der Startseite und während einer Ausgabe. Videos der Ausgabe haben Ton (AAC im MP4, sonst WAV daneben); `--ohne-ton`. Importregel: `ton` hat die Rechte von `atlas`, dazu `javax.sound`. SteuerhausCheck X5 Prüfsteine 1–3, Hörproben `X5_Ton_ruhig.wav`, `X5_Ton_sturm.wav`.

## Vergleich (Y1, Werkbuch HalligHooge 152)
Paket `vergleich` (F246 B: Bildfolge aus der Ausgabe, live bleibt eine Bühne): `Vergleich` liest die Datei der Vergleiche (`vergleich.datei`, Vorgabe `<Szenendatei>.vergleiche`; je Eintrag `name`, `text`, `a`, `b`, `ansicht` oder `blick`, `ausrichten`, `bereich` in Stunden, `schritt` in Minuten, bis zu vier `sonde.N`) und richtet A und B aus (`Ausrichtung`: `hochwasser [Zeit]`, `niedrigwasser [Zeit]`, `scheitel`, `zeit T`, auf B `uhrzeit` – dieselbe Uhrzeit, um ganze Tage verschoben). `Mappe` ist der gerechnete Ordner (`ausgabe.vergleiche/<schluessel>`): `vergleich.txt` mit Bezug, Zeiten und Pegeln je Bild und den Sonden samt Ort im Bild, `A_0001.jpg` …, `B_0001.jpg` …, `sonde_N.csv`, `vorschau.jpg`; sie liest Bilder bei Bedarf (auch jeden zweiten Bildpunkt) und hält eine begrenzte Zahl im Speicher. `Vergleichsansicht` zeigt eine Mappe: Schieber, nebeneinander oder im Wechsel, Zeitleiste über dem Abstand zum Bezug, Kurven derselben Sonden (A durchgezogen, B gestrichelt, Werte und B − A am Zeiger), Abspielen (6/12/24 Bilder/s), Bild sichern; Tasten Leertaste, ← →, Pos1, Ende, 1 2 3. `Spieler` ist das Fenster dazu (FFrame, Esc schließt).
Die Ausgabe rechnet die Folge (`ausgabe.Vergleichsfolge`, `--vergleichsfolge <Name>` oder `--vergleichsfolge jetzt --blick … --vergleich <szenario>`): Pegelreihen beider Archive (ohne Bühne) für Spanne und Marken, dieselbe Kamera, je Bild ein Standbild (ohne Korn), A in einer frischen Bühne, dann B; die Sonden aus beiden Archiven. In der App: Menü Labor › Vergleich vom Blick jetzt … (Strg L), Gerechnete Vergleiche …, je Vergleich der Datei ein Eintrag; Startseite mit der Gruppe „Vergleiche“ (noch nicht gerechnet: die App fragt und rechnet). `--vergleich <Name>` öffnet einen gerechneten Vergleich nach dem ersten Bild (mit `--sekunden`: Probe). Importregel: `vergleich` hat die Rechte von `atlas`. SteuerhausCheck Y1 Prüfsteine 1–4 (ohne Archiv), `demo.VergleichCheck` Prüfsteine 5–9 (mit Karte und Archiv).
Nebenbei seit Y1: Die Fenster Schnitt, Foto (Referenz) und Über schließen nur sich selbst (FFrame stand auf EXIT_ON_CLOSE; der Knopf in der Leiste beendete die App).

## Regler (Y2, Werkbuch HalligHooge 153)
Paket `regler` (F247 A): `Reglerwerte` (Faktor und Drehung des Winds, Aufschlag, Meeresspiegel; Grenzen × 0–3, ±180° in 15°, −1 bis +3 m, 0 bis +1,5 m; `text()`, `lies("wind=2;drehung=30;aufschlag=1;meer=0.5")`), `Reglerpult` (vier FSlider, Windrose: gestrichelt wie gemessen, durchgezogen wie er wirkt; was im Lauf wirkt und ob die Physik noch folgt; „Zurück auf ERA5“, „Live starten“). Die Ziele gehen über `Buehne.regler` an den Live-Lauf (Renderer: Antrieb 0.5, HydroModul 0.7); Aufschlag und Meeresspiegel wirken zusammen am Rand von G2. In der App: Abschnitt „Regler (Labor)“, Arbeitsbereich **Labor** (Strg 5: Regler, Zeit, Sonden), Menü Labor › Regler (Live-Lauf starten, Regler zurück auf ERA5); Eingriffe stehen im Protokoll und als Marke in der Zeitleiste; die Einblendung nennt sie live. `--regler "…"` stellt sie nach dem Start (mit `--sekunden`: eine Zeile alle 50 Takte). Importregel: `regler` hat die Rechte von `atlas`. SteuerhausCheck Y2 Prüfsteine 1–3; Renderer `demo.ReglerCheck`.
Seit Y2 liest der Spieler eines Vergleichs mit zwei Fäden voraus und bringt die Bilder vorher auf die Größe der Fläche (`Mappe.skaliert`); beim Abspielen wartet er auf das nächste Bild, statt es im Takt zu lesen (Y1: 2,5 statt 12 Bilder/s).

## Ein Jahr in einer Minute (Y3, Werkbuch HalligHooge 154)
Paket `jahr`: `Jahresauftrag` liest die Schlüssel `jahr.*` des Manifests (Abschnitte `jahr.N = Szenario; Titel` der Reihe nach, der Boden geht von Lauf zu Lauf weiter; `jahr.dauer` Teil der Spielzeit je Abschnitt; `jahr.kasten`, `jahr.schwelle`, `jahr.sand` für den Sand; `jahr.profil`; `jahr.ansicht` für „Im Bild zeigen“; `jahr.hinweis`; `jahr.rand`, `jahr.schritt`, `jahr.karte.zelle`, `jahr.orte`; Ordner `ausgabe.jahr`) und kennt die Kennung einer passenden Mappe. `Jahresmappe` ist der gerechnete Ordner: `jahr.txt` (Gitter ohne Randstreifen, Abschnitte mit Faktor, Zeit im Archiv und Modelltagen, Orte, Übergänge, Prüfwerte), `grund.f32` (Höhe zu Beginn), `dz.bin` (je Bild die Änderung seit Beginn in mm, Deflate, Bytes getrennt), `bilder.csv` (Kennzahlen je Bild), `karte.jpg`, `vorschau.jpg`; sie liest Bilder bei Bedarf und hält 32 im Speicher, zwischen zwei Bildern gilt die Gerade. `Zeitplan` verteilt die Spielzeit (Vorgabe 60 s) auf die Abschnitte. `Jahresbild` malt Differenz (Abtrag blau, Auftrag ocker bis rotbraun, unter 1 cm durchsichtig, Skala ± 0,1 … 2 m), Höhe mit Schummerung, die Linie der Schwelle (Marching Squares) und die Kachel. `Jahresansicht`: Karte mit Kasten, Linie der Schwelle zu Beginn (gestrichelt) und jetzt, Profil mit zwei Griffen (ziehen; Doppelklick: zurück), Werte unter der Maus; rechts Zahlen des Sands und des Fensters, Legende, Hinweis; darunter Profil (Höhe zu Beginn und jetzt, darunter die Änderung als Balken), das Band des Jahrs (Abschnitte nach Spielzeit) und eine Kurve über das Jahr; Abspielen (30 s, 1 min, 2 min), Bild für Bild, Darstellung, Skala, „Im Bild zeigen“, Bild sichern; Tasten Leertaste, ← →, Pos1, Ende, 1 2, + −. `Jahresfenster` ist das Fenster dazu (Esc schließt).
Die Ausgabe rechnet die Mappe (`ausgabe.Jahresrechnung`, `--jahr`, ohne Bühne): Gitter des Bodens aus dem ersten Archiv, Höhe zu Beginn aus der Packung von G3 (wie `MorphoFeldCheck`), je Abschnitt jeder `jahr.schritt`-te Stand von `bed.dz` und der letzte (der erste eines späteren Abschnitts ist der letzte des vorigen und wird nur verglichen: `uebergang.N` im Kopf), Faktor aus dem Kopf des Archivs; je Bild Auftrag und Abtrag im Fenster, Fläche, Schwerpunkt und Volumen des Sands; dazu `pruef.sand` auf dem ganzen Gitter wie P9c Prüfstein 13; Orte und Karte. In der App: Menü Labor › Ein Jahr in einer Minute … (Strg Umschalt J), Startseite mit der Gruppe „Labor“ (noch nicht gerechnet oder veraltet: die App fragt und rechnet); `--jahr` öffnet das Fenster nach dem ersten Bild (mit `--sekunden`: spielt einmal durch, misst, sichert drei Bilder). Importregel: `jahr` hat die Rechte von `atlas`. SteuerhausCheck Y3 Prüfsteine 1–5 (ohne Archiv), `demo.JahrCheck` Prüfsteine 6–10 (mit Archiven und Gebiet).
Nebenbei seit Y3: Die Knöpfe des Spielers eines Vergleichs heißen „« Zurück“ und „Vor »“ (die Schrift der Knöpfe hat ◀ ▶ nicht); die Probe misst das Abspielen ab Bild 1.

## Tidenkalender (Z1, Werkbuch HalligHooge 155)
Paket `gezeiten` (F248 B): `Gezeitenauftrag` liest `gezeiten.*` (Name, Datei der Konstanten, Ort mit Lage und Umkreis, Bezugspegel und Prüfpegel aus `gebiet/pegel`, Szenarien für die Übertragung, Einschwingen, Schwelle des Windstaus, Marke im Tagesbild, Hinweis). `Gezeitentafel` ist die Datei der Konstanten (`ort`, `mittel`, je Tide eine Zeile `tide = Name; A; G`, Marken des Modells, `pruef.*` und `wert.*`). `Vorhersage` rechnet η = Z₀ + Σ f·A·cos(V + u − G) mit den Partialtiden des Kerns (gleich `Harmonik.Ergebnis.wert`), Hoch- und Niedrigwasser (5 min, Parabel; Paare unter 0,1 m fallen weg), Tage mit Tidenhub, Spring- und Nipptide (Phasen von M2 und S2 am Ort) und das Alter der Gezeit. `Mond` findet die Hauptphasen aus den Ephemeriden des Kerns (FMoonPosition, FSun). `Kalenderansicht`: der Tag (Kurve 0–24 Uhr Ortszeit, Hoch- und Niedrigwasser, jetzt, Mond, Spring/Nipp, Marke, Marken des Modells), vier Wochen als Liste, Ausblick über acht Wochen; Bild und CSV sichern; Tasten ← → ↑ ↓ Pos1. `Kalenderfenster` ist das Fenster dazu.
Die Ausgabe rechnet die Konstanten (`ausgabe.Gezeitenrechnung`, `--gezeiten`, ohne Bühne): robuste Analyse am Bezugspegel ohne Zeiten mit Windstau (25-h-Mittel des Rests über `gezeiten.stau`), Ableitung von K2, P1, NU2, 2N2 (und Q1), Amplituden auf den mittleren Tidenhub der Kennwerte gebracht und Z₀ auf MThw/MTnw; Übertragung zum Ort mit den Läufen des Modells (M2, M4, M6: Verhältnis und Verspätung je Art); Prüfung am Bezug und am Prüfpegel. In der App: Menü Begleiter › Tidenkalender … (Strg Umschalt T), Startseite Gruppe „Begleiter“ (die Kachel zeigt den heutigen Tag); `--kalender` öffnet ihn nach dem ersten Bild (mit `--sekunden`: blättert acht Wochen, sichert). Importregel: `gezeiten` hat die Rechte von `atlas` und kennt `com.dan.core.astro`. SteuerhausCheck Z1 Prüfsteine 1–5, `demo.GezeitenCheck` 6–10.

## Landunter-Uhr (Z2, Werkbuch HalligHooge 156)
Paket `gezeiten` dazu: `Landunterauftrag` (Schlüssel `landunter.*`: Krone, Höhe des Landunters, Stürme zur Prüfung, Hallig der Warften, Wahl, Hinweis), `Landunter` (Gezeit des Kalenders plus Windstau gegen zwei Höhen: die niedrigste Krone des Sommerdeichs, über der Wasser überläuft, und die Höhe außen, ab der im Modell die Fennen an den Warften nass werden; Zeiten darüber, nötiger Windstau, Warften beim Scheitel, Stellung der Uhr), `Pegelonline` (Messwerte W eines Pegels über die REST-Schnittstelle der WSV, cm über Pegelnull → m NHN; Windstau = Mittel von Messung minus Gezeit über die letzte Stunde), `Landunteruhr` (Uhr von Hochwasser zu Hochwasser mit Wasserstand, Bögen über Krone und Landunter; Pegellatte; Warften; Messung am Bezug gegen die Gezeit, 24 h; Ausblick über 14 Tage; Windstau gemessen, ohne Wind, gewählt oder eigener Wert), `Landunterfenster`. `Gezeitentafel` liest seit Z2 den Bezug (`bezug.pegel`, `bezug.z0`, `bezug.tide`) und die Fehler des Scheitels mit gemessenem Windstau je Vorlauf (`wert.stau.*`); beides schreibt `ausgabe.Gezeitenrechnung` mit. Die Uhr zeigt beim gemessenen Windstau den Scheitel mit ± diesem Fehler. In der App: Menü Begleiter › Landunter-Uhr … (Strg Umschalt U), Startseite Gruppe „Begleiter“; `--landunter` öffnet sie nach dem ersten Bild (mit `--sekunden`: Messung, jede Wahl, eigener Wert, Bild). SteuerhausCheck Z2 Prüfsteine 1–5, `demo.LandunterCheck` 6–9.
Seit Z2b (Werkbuch HalligHooge 157, F250 A): Der gemessene Windstau ist das Mittel aus Pellworm Anleger und dem Prüfpegel Wittdün (Hooge liegt dazwischen). `Gezeitenrechnung` schreibt dazu `bezug2.*` (eigene Analyse von Wittdün, auf seine Kennwerte gebracht wie der Bezug) und den Fehler des Scheitels für das Mittel, an beiden Pegeln gemessen (`stauFehlerMittel`); `Gezeitentafel.bezuege` hält alle Pegel des Windstaus, `Landunteruhr` lädt sie je für sich (`messung` ist eine Liste) und zeigt rechts den Windstau beider Pegel und ihr Mittel über 24 h. Ist ein Pegel nicht erreichbar, gilt der andere allein. Dazu Layout für kleine Fenster (Uhr und Beschriftungen skalieren, Zeilen der Warften passen sich an).

## Watt-Fenster (Z3, Werkbuch HalligHooge 158)
Paket `gezeiten` dazu: `Wattauftrag` (Schlüssel `watt.*`: Start, Ziel mit Umkreis, Rechteck und Zelle der Karte, Umkreis des Priels, Läufe, Gehgeschwindigkeit, Hinweis), `Watttafel` (die Datei `Hooge.watt` mit Weg, Sattel, Schwellen, Ereignissen der Läufe und Kopf; daneben `watt/hoehe.png`, 16 Bit in mm über −10 m, Süden unten, und `watt/land.jpg`), `Watt` (die Fenster: je Niedrigwasser der Gezeit des Kalenders plus Windstau von „trocken ab“ fallend bis „nass ab“ steigend, auf 1 s; Licht nach der Sonne; Stand der Karte im Einklang mit dem Fenster), `Windstaumessung` (wie bei der Uhr: Mittel der Pegel), `Wattansicht` (Karte mit dem Weg als Band, trocken Sand, nass Blau; Tag mit den trockenen Zeiten; die nächsten sieben Tage mit Dauer, Licht und Zeit dort für hin und zurück; Profil des Wegs mit dem Wasser), `Wattfenster`. `ausgabe.Wattrechnung` (`Ausgabe --watt`, ohne Bühne): Karte 5 m aus DGM1 und TrilaWatt, Sattel per Engpass-Suche, kürzester Weg über Zellen nicht tiefer als der Sattel, Schwellen als Median der Wasserstände am Anleger, wenn in den Läufen `watt.modell` der Priel am Sattel dessen Höhe kreuzt; Prüfung je Lauf mit den Schwellen der anderen. In der App: Menü Begleiter › Watt-Fenster … (Strg Umschalt W), Startseite Gruppe „Begleiter“; fehlt `Hooge.watt`, rechnet die App ihn auf Nachfrage; `--wattfenster` öffnet es nach dem ersten Bild (mit `--sekunden`: Woche, Niedrigwasser, Messung, eigener Wert, Bild). SteuerhausCheck Z3 Prüfsteine 1–5, `demo.WattCheck` 6–9. Modell, nicht zur Navigation.

## Messarchiv (DB1)

Seit DB1 (Werkbuch HalligHooge 167, F261–F265 A) hält `messung` fest, was die Quellen nur kurz hergeben: Ein Sammler
ohne Fenster holt den Wasserstand von PEGELONLINE (30 Tage rückwirkend) und den Wind des DWD in ein Oracle-Schema der
App, über `com.dan.store` (Einrichter, Db). Die App braucht die Datenbank nicht.

```bash
java -cp dist\HalligHooge.jar com.dan.steuerhaus.messung.Sammler --manifest src\szenen\Hooge.steuerhaus --abruf
```

Aufträge: `--probe` (Quellen ohne Datenbank), `--verbinden` (fragt einmal nach dem Passwort), `--einrichten`,
`--uebernehmen --gebiet <ordner>` (Pegel als Maßstab, Wind, Orte, Ort ohne Pegel), `--abruf` (stündlich),
`--pflege [--nur-plan] [--grenze MB]`, `--bericht`, `--pruefen [--gebiet <ordner>]`. Eine Sperre neben dem Protokoll
lässt nie zwei Sammler zugleich laufen (Exit 3).

| Klasse | Aufgabe |
|---|---|
| `Messauftrag` | Schlüssel `messung.*`: `db`, `sql`, `schema`, `praefix`, `passwort`, `srid`, `roh.monate`, `abruf.tage`, `grenze.mb`, `warnung`, `pegelonline`, `dwd`, `dwd.stunden`, `ort`; dazu `gezeiten.datei`, `landunter.krone`, `landunter.nass` |
| `Abruf` | PEGELONLINE (`start` als Zeitpunkt nach ISO 8601), ZIP des DWD lesen |
| `Ausreisser` | Grenzen −6 … 8 m, Spitze 0,3 m neben dem Median über ±15 min (wie `aufbereitung.Pegelonline`) |
| `Scheitelsuche` | Hoch- und Niedrigwasser im gleitenden Mittel über 31 min, Fenster ±3 h, nur mit vollem Fenster |
| `Messarchiv` | Tabellen über `java.sql`; Zeiten als Text mit `TO_DATE` gebunden (DATE bleibt indizierbar) |
| `Aufbewahrung` | Wachstumsgrenze: Monatsfächer der Minutenwerte nach `roh.monate` weg, über `grenze.mb` das Älteste, sonst Einfügen aus |
| `Uebernahme` | Bestand aus dem Gebiet (`pegel/`, `wind/`, `orte.txt`) und der Ort ohne Pegel aus der Gezeitentafel |
| `Sammler` | die Aufträge, Sperre, Protokoll (`%USERPROFILE%\.steuerhaus\<Titel>\sammler.log`, höchstens 1 MB) |

`demo.MessungCheck` prüft ohne Datenbank und ohne Netz: Skripte wie der Einrichter sie zerlegt, Scheitel an einer reinen
Tide und an den Pegeln des Bestands, Ausreißer, Aufbewahrung und Grenze, Antworten der Quellen, Schlüssel, Importregel.

## Chronik der Hochwasser (DB2)

Seit DB2 (Werkbuch HalligHooge 169, F266 A, F267 A) zeigt die App, was das Messarchiv gefunden hat, ohne selbst die
Datenbank zu fragen: Der Sammler schreibt eine Textdatei `chronik.datei` (Format in `Chronik`) neben das Manifest und in die
Kopien `chronik.kopie` (etwa ins Abbild), **nur wenn sich ein Ereignis ändert oder dazukommt** – so hat ein Clone den letzten
Stand, und Git sieht die Datei nur bei einem echten Ereignis.

| Teil | Inhalt |
|---|---|
| `Chronik` (Kern) | Ereignisse LANDUNTER, KRONE (am Ort, geschätzt), STURMFLUT (an den Pegeln nach BSH); lesen, schreiben nur bei Änderung, Zeile `zuletzt(zone)` |
| `messung.Chronikbau` | Kette der Hochwasser über der Krone (Abstand unter 18 h), Landunter ab der Höhe `nass`; Sturmfluten aller Pegel zu Ereignissen gefasst |
| `chronik.Chronikansicht` | Band der Zeit mit Krone und Landunter, Liste der Ereignisse; Knopf „Ansehen“ ins erste Szenario, dessen Archiv die Zeit abdeckt (`Abdeckung`: `lauf.txt` und `zeiten.f64`) |
| `Szenenapp` | Menü Labor › Chronik der Hochwasser (Strg Umschalt H), Kachel in der Gruppe „Labor“; Landunter-Uhr mit der Zeile der Chronik und dem gemessenen Windstau des jüngsten Landunters zur Wahl |

Manifest: `chronik.name`, `chronik.datei`, `chronik.kopie`, `chronik.ansicht` (Ansicht beim Sprung, nur am Ort), `chronik.hinweis`.
Neue Aufträge des Sammlers: `--chronik`, `--wind-nachladen` (Lücken im Wind aus den historischen Dateien des DWD),
`--fach-weg TABELLE TAG`. Prüfung: `demo.MessungCheck` (8–11) und `demo.ChronikCheck` (mit den echten Dateien, Bilder `DB2_*.png`).
