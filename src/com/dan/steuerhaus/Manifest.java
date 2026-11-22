package com.dan.steuerhaus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Manifest einer App (Werkbuch HalligHooge 110, F178 A): Titel, Unterzeile, Logo, Szenendatei, Thema,
 * Arbeitsbereich beim ersten Start, Ablage der Fotos, Quellennachweis und die Gruppen des Katalogs. Format wie
 * Rezept und Szenendatei: {@code schluessel = wert}, {@code #} leitet einen Kommentar ein.
 * <pre>
 * app.titel          = HalligHooge
 * app.unterzeile     = Gezeiten, Priele und Landunter
 * app.logo           = HARBOR                    # FIconType
 * app.icon           = HalligHooge.png           # Programmsymbol (PNG, relativ zum Manifest); das Abbild nimmt <Name>.ico daneben
 * app.szenen         = Hooge.szenen              # relativ zum Manifest
 * app.thema          = nautik                    # labor | nautik
 * app.arbeitsbereich = steuern                   # betrachten | steuern | messen
 * app.fotos          = docs/fotos                # relativ zum Arbeitsordner
 * ausgabe.videos     = docs/video                # seit W4, relativ zum Arbeitsordner
 * ausgabe.korn       = 1                         # seit W4: Filmkorn in Video und Standbild (0 = aus)
 * quelle.1           = DGM1, DOP20 © GeoBasis-DE/LVermGeo SH (CC BY 4.0)
 * katalog.szenarien.1 = Tide und Sturm: ruhig, sturm, xaver, live
 * katalog.ansichten.1 = Hooge: hanswarft, kirchwarft, backenswarft
 * </pre>
 * Doppelte Schlüssel sind ein Fehler; {@link #doppelte(File)} prüft jede Datei dieses Formats (auch die Szenendatei).
 */
public final class Manifest {
    public final File datei;
    private final Map<String, String> werte = new LinkedHashMap<>();
    private final List<String> doppelt = new ArrayList<>();

    private Manifest(File datei) {
        this.datei = datei;
    }

    public static Manifest lies(File datei) throws IOException {
        Manifest m = new Manifest(datei.getAbsoluteFile());
        for (String[] kv : paare(datei)) {
            if (m.werte.containsKey(kv[0])) m.doppelt.add(kv[0]);
            m.werte.put(kv[0], kv[1]);
        }
        return m;
    }

    /** Die Schlüssel, die in einer Datei dieses Formats mehr als einmal stehen (leer: keiner). */
    public static List<String> doppelte(File datei) throws IOException {
        Map<String, Integer> n = new LinkedHashMap<>();
        for (String[] kv : paare(datei)) n.merge(kv[0], 1, Integer::sum);
        List<String> d = new ArrayList<>();
        n.forEach((k, v) -> {
            if (v > 1) d.add(k + " (" + v + "-mal)");
        });
        return d;
    }

    private static List<String[]> paare(File datei) throws IOException {
        List<String[]> l = new ArrayList<>();
        for (String z : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) {
            String s = z.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p <= 0) continue;
            String w = s.substring(p + 1);
            int k = w.indexOf(" #");
            if (k >= 0) w = w.substring(0, k);
            l.add(new String[] {s.substring(0, p).strip(), w.strip()});
        }
        return l;
    }

    public String text(String schluessel, String vorgabe) {
        String w = werte.get(schluessel);
        return w == null || w.isEmpty() ? vorgabe : w;
    }

    public String titel() { return text("app.titel", "Steuerhaus"); }

    public String unterzeile() { return text("app.unterzeile", ""); }

    public String logo() { return text("app.logo", "GEO"); }

    public String thema() { return text("app.thema", "labor"); }

    public String arbeitsbereich() { return text("app.arbeitsbereich", "steuern"); }

    public String version() { return text("app.version", ""); }

    /** Die Szenendatei (relativ zum Manifest); null, wenn keine genannt ist. */
    public File szenen() {
        String s = werte.get("app.szenen");
        return s == null ? null : relativ(s);
    }

    /** Der Ordner der Fotos (relativ zum Arbeitsordner). */
    public File fotos() { return new File(text("app.fotos", "docs" + File.separator + "fotos")); }

    /** Seit W4: Ablage der Videos, relativ zum Arbeitsordner ({@code ausgabe.videos}, Vorgabe {@code docs/video}). */
    public File videos() { return new File(text("ausgabe.videos", "docs" + File.separator + "video")); }

    /** Seit Y1: Ablage der gerechneten Vergleiche (je Vergleich ein Ordner, {@code ausgabe.vergleiche}). */
    public File vergleiche() { return new File(text("ausgabe.vergleiche", "docs" + File.separator + "vergleich")); }

    /** Seit W4: Stärke des Filmkorns in Video und Standbild ({@code ausgabe.korn}, Vorgabe 1 = Vision3 250D; 0 = aus). */
    public double korn() {
        try {
            return Double.parseDouble(text("ausgabe.korn", "1").replace(',', '.'));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Ein Pfad relativ zum Ordner des Manifests. */
    public File relativ(String pfad) {
        File f = new File(pfad.replace('/', File.separatorChar).replace('\\', File.separatorChar));
        return f.isAbsolute() ? f : new File(datei.getParentFile(), f.getPath());
    }

    /** Die Zeilen des Quellennachweises in der Reihenfolge ihrer Nummern. */
    public List<String> quellen() { return liste("quelle."); }

    /** Gruppen des Katalogs: „Name: k1, k2“ aus {@code katalog.szenarien.N} oder {@code katalog.ansichten.N}. */
    public List<Gruppe> gruppen(String art) {
        List<Gruppe> g = new ArrayList<>();
        for (String z : liste("katalog." + art + ".")) {
            int p = z.indexOf(':');
            String name = p < 0 ? z : z.substring(0, p).strip();
            List<String> k = new ArrayList<>();
            if (p >= 0) for (String s : z.substring(p + 1).split(",")) if (!s.isBlank()) k.add(s.strip());
            g.add(new Gruppe(name, Collections.unmodifiableList(k)));
        }
        return g;
    }

    /** Eine Gruppe im Katalog: Name und die Schlüssel ihrer Einträge. */
    public record Gruppe(String name, List<String> schluessel) { }

    /** Seit W2: die Werte von {@code praefix}1, {@code praefix}2 … in der Reihenfolge ihrer Nummern. */
    public List<String> nummern(String praefix) { return liste(praefix); }

    /** Doppelte Schlüssel im Manifest selbst. */
    public List<String> doppelt() { return Collections.unmodifiableList(doppelt); }

    private List<String> liste(String praefix) {
        Map<Integer, String> n = new java.util.TreeMap<>();
        werte.forEach((k, v) -> {
            if (k.startsWith(praefix)) {
                try {
                    n.put(Integer.parseInt(k.substring(praefix.length())), v);
                } catch (NumberFormatException e) {
                    // kein Eintrag mit Nummer
                }
            }
        });
        return new ArrayList<>(n.values());
    }
}
