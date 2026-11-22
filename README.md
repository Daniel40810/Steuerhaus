# Steuerhaus

**Steuerhaus** ist ein Werkzeugkasten für Szenen-Apps auf dem [Renderer](https://github.com/Daniel40810/Renderer):
Fenster, Docks und Befehle, Einblendungen im Bild, Regie und Kino, Ausgabe von Video und Standbild, Analyse und
Betrieb, Themen, dazu Startseite und Atlas, Kapitel, Rundgang, Postkarte, Ton, Vergleich, Regler, Jahr und Gezeiten.
Entstanden ist es mit dem Projekt [HalligHooge](https://github.com/Daniel40810/HalligHooge), das es vollständig nutzt.

Eine Szenen-App besteht aus einem Manifest, einer Szenendatei und wenigen Zeilen Java:

```java
public static void main(String[] args) throws Exception {
    Szenenapp.starten(new File("src/szenen/Hooge.steuerhaus"), args);
}
```

Die ausführliche Beschreibung – Schichten, Schlüssel des Manifests, Bedienung, Aufrufoptionen, Prüfungen – steht in
[`src/com/dan/steuerhaus/README.md`](src/com/dan/steuerhaus/README.md).

---

## Pakete

| Paket | Inhalt |
|---|---|
| `com.dan.steuerhaus` | Manifest, Befehl, Befehle, Einstellungen (nur `java.*`) |
| `regie` | Regiebuch, Regie, Fahrt, Lesezeichen (nur `java.*`) |
| `analyse` | Qualität, Protokoll (nur `java.*`) |
| `thema` | Themen Labor und Nautik (FStyle) |
| `ui` | Hauptfenster, Docks, Arbeitsbereiche, Menüleiste, Befehlspalette, Startbild, Statuszeile |
| `buehne` | die Szenen-App über `com.dan.lauf`: Katalog, Bedienung, Zeitleiste, Einblendungen, Orte, Regisseur, Sonde, Leistung |
| `ausgabe` | Video und Standbild 4K mit Filmkorn, Vergleichsfolge, Rechnungen für Jahr und Gezeiten |
| `atlas`, `tafel` | Startseite mit Atlas, Kartenblatt, Kachelleiste und Infotafeln |
| `rundgang`, `ton` | Rundgang, Ton |
| `vergleich`, `regler`, `jahr` | Sturmflut-Labor: Vergleich mit Schieber, Regler im Live-Lauf, das Jahr |
| `gezeiten` | Tidenkalender, Vorhersage, Mond |
| `messung`, `chronik`, `guete` | Messarchiv (Sammler ohne Fenster, Oracle über `com.dan.store`), Chronik der Hochwasser, Modell gegen Messung |
| `demo` | SteuerhausCheck, RegieCheck, EinblendungCheck, KinoCheck, AusgabeCheck, AnalyseCheck, MessungCheck, ChronikCheck, GueteCheck |

Importregel: Der Kern kennt weder AWT, Swing, FStyle noch den Renderer; `thema` und `ui` kennen FStyle, erst `buehne`
kennt `com.dan.lauf`, `com.dan.core`, `com.dan.render.overlay` und `com.dan.kino`. Kein Paket kennt ein Projekt. Der
Renderer kennt das Steuerhaus nicht. `SteuerhausCheck` prüft das.

---

## Bauen

**Voraussetzungen:** JDK 21, NetBeans mit Ant (Java-Plattform **JDK_21**). `lib/` liegt nicht im Repository, nur die
Bibliotheksdefinition von NetBeans (`lib/nblibraries.properties`, `lib/CopyLibs/`). Vor dem Bauen gehören dorthin:

| Datei | Woher |
|---|---|
| `Renderer.jar` | aus dem [Renderer](https://github.com/Daniel40810/Renderer) gebaut (`dist/Renderer.jar`) |
| `FStyle.jar` | Bedienelemente, liegt im Renderer unter `lib/FStyle.jar` |
| `lwjgl/*.jar` | LWJGL 3.4.3 aus [github.com/LWJGL/lwjgl3/releases/tag/3.4.3](https://github.com/LWJGL/lwjgl3/releases/tag/3.4.3): `lwjgl`, `lwjgl-vulkan`, `lwjgl-vma`, `lwjgl-shaderc`, `lwjgl-jawt`, `lwjgl-glfw`, dazu je `-natives-windows` (außer bei vulkan und jawt) |

```bat
ant clean jar
```

Ergebnis: `dist/Steuerhaus.jar`. Renderer, Steuerhaus und HalligHooge zusammen baut `tools\bauen.cmd` in HalligHooge.

---

## Abbild

`tools/abbild.cmd` baut aus einer Szenen-App ein Abbild mit eigener Java-Laufzeit (jpackage aus einem JDK 21), mit
Programmsymbol, wenn `src/szenen/<Name>.ico` da ist; mit dem WiX Toolset 3 zusätzlich ein Installationsprogramm:

```bat
tools\abbild.cmd C:\…\HalligHooge HalligHooge 2.3.0
```

Die Daten der App gehören nicht ins Abbild; ihren Ort stellt die App ein (*Datei › Ort der Daten …*).

`tools/lauf_*.cmd` sind die Läufe der Stufen W1 bis W5 und C0 bis C3 aus dem Werkbuch von HalligHooge; sie erwarten
die drei Projekte nebeneinander.
