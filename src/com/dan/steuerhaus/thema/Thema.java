package com.dan.steuerhaus.thema;

import com.dan.fstyle.FTheme;
import com.dan.fstyle.FThemePreset;

import java.awt.Color;
import java.awt.Font;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Die Themen des Steuerhauses (Werkbuch HalligHooge 110, F176 B, F183 A): Farben und Schriften als Werte an einer
 * Stelle. {@link #LABOR} sind genau die Farben der App bis Teil A (FStyle Laboratory Dark); {@link #NAUTIK} ist die
 * Seekarte bei Nacht: Nachtblau, Messing, Tonnenrot, Pergament.
 * <p>
 * {@link #anwenden} stellt das {@code FTheme} von FStyle ein (die Bauteile, die ihm folgen, färben sich selbst) und
 * meldet den Wechsel; was FTheme nicht folgt (Knopf, Auswahlliste, Fenster), färbt {@link Faerber} nach. Eigene
 * Zeichnungen lesen ihre Farben je Bild aus {@link #aktuell()}.
 */
public enum Thema {
    LABOR("Labor", FThemePreset.LABORATORY_DARK, "Segoe UI",
            rgb(10, 14, 18), rgb(10, 14, 18), rgb(8, 12, 15), rgb(4, 7, 10), rgb(20, 30, 38), rgb(43, 58, 67),
            rgb(220, 230, 235), rgb(140, 160, 170), rgb(0x00, 0xB5, 0xAD), rgb(0x8B, 0x00, 0x24),
            rgb(127, 195, 223), rgb(226, 163, 90), new Color(140, 200, 255, 160), rgb(18, 22, 34), rgb(0xBF, 0xEE, 0xEB)),
    NAUTIK("Nautik", null, "Georgia",
            rgb(0x0B, 0x16, 0x22), rgb(0x0E, 0x1B, 0x2B), rgb(0x08, 0x11, 0x1C), rgb(0x05, 0x0B, 0x13), rgb(0x16, 0x28, 0x3D), rgb(0x2A, 0x3E, 0x55),
            rgb(0xED, 0xE4, 0xCC), rgb(0x9D, 0xA9, 0xB5), rgb(0xC8, 0xA0, 0x4A), rgb(0xA8, 0x32, 0x2D),
            rgb(0x78, 0xBE, 0xDC), rgb(0xE0, 0xB8, 0x5A), new Color(0xE0, 0xC8, 0x8C, 150), rgb(0x13, 0x24, 0x38), rgb(0xE6, 0xD3, 0x9E));

    public final String name;
    private final FThemePreset preset;
    /** Schrift für Titel und Abschnitte; der Fließtext bleibt bei der Familie von FStyle. */
    public final String titelFamilie;
    /** Grund des Fensters, Fläche der Docks, Grund der Zeitleiste und Statuszeile, Nacht und Tag in der Zeitleiste, Linien. */
    public final Color grund, flaeche, tief, nacht, tag, linie;
    public final Color text, leise, primaer, akzent;
    /** Pegelkurve, Marken (HW, NW, Landunter), Rand der Gläser, Körper der Auswahllisten, Pfeile. */
    public final Color kurve, marke, glasRand, glasKoerper, pfeil;

    Thema(String name, FThemePreset preset, String titelFamilie, Color grund, Color flaeche, Color tief, Color nacht, Color tag, Color linie,
          Color text, Color leise, Color primaer, Color akzent, Color kurve, Color marke, Color glasRand, Color glasKoerper, Color pfeil) {
        this.name = name;
        this.preset = preset;
        this.titelFamilie = titelFamilie;
        this.grund = grund;
        this.flaeche = flaeche;
        this.tief = tief;
        this.nacht = nacht;
        this.tag = tag;
        this.linie = linie;
        this.text = text;
        this.leise = leise;
        this.primaer = primaer;
        this.akzent = akzent;
        this.kurve = kurve;
        this.marke = marke;
        this.glasRand = glasRand;
        this.glasKoerper = glasKoerper;
        this.pfeil = pfeil;
    }

    private static volatile Thema aktuell = LABOR;
    private static final List<Runnable> beiWechsel = new CopyOnWriteArrayList<>();

    public static Thema aktuell() { return aktuell; }

    /** Das Thema zu einem Namen ({@code labor}, {@code nautik}); unbekannt: Labor. */
    public static Thema zu(String name) {
        for (Thema t : values()) if (t.name().equalsIgnoreCase(name == null ? "" : name.strip()) || t.name.equalsIgnoreCase(name == null ? "" : name.strip())) return t;
        return LABOR;
    }

    /** Wird nach jedem {@link #anwenden} gerufen (im Faden des Aufrufers, gewöhnlich der Oberfläche). */
    public static void beiWechsel(Runnable r) { beiWechsel.add(r); }

    /** Stellt das Thema ein: FTheme von FStyle, dann alle, die sich mit {@link #beiWechsel} gemeldet haben. */
    public void anwenden() {
        aktuell = this;
        FTheme f = FTheme.getInstance();
        if (preset != null) f.applyPreset(preset);
        else {
            f.applyPreset(FThemePreset.LABORATORY_DARK);
            f.setBackground(grund);
            f.setSurface(flaeche);
            f.setPrimary(primaer);
            f.setAccent(akzent);
            f.setText(text);
            f.setTextMuted(leise);
            f.setSelectionBackground(primaer);
            f.setSelectionForeground(tief);
            f.setGlassRim(glasRand);
            f.setGlow(new Color(primaer.getRed(), primaer.getGreen(), primaer.getBlue(), 120));
        }
        for (Runnable r : beiWechsel) r.run();
    }

    /** Schrift für Titel (Startbild, Abschnitte, Über). */
    public Font titel(float groesse) {
        return new Font(titelFamilie, Font.BOLD, 12).deriveFont(groesse);
    }

    /** Schrift für Text in Docks und Statuszeile (wie bisher SansSerif 12). */
    public Font klein() { return new Font(Font.SANS_SERIF, Font.PLAIN, 12); }

    public static Color mitAlpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.max(0, Math.min(255, a))); }

    /** Linear zwischen a und b (s in 0 … 1). */
    public static Color mische(Color a, Color b, double s) {
        double t = Math.max(0, Math.min(1, s));
        return new Color((int) Math.round(a.getRed() + t * (b.getRed() - a.getRed())), (int) Math.round(a.getGreen() + t * (b.getGreen() - a.getGreen())),
                (int) Math.round(a.getBlue() + t * (b.getBlue() - a.getBlue())));
    }

    private static Color rgb(int r, int g, int b) { return new Color(r, g, b); }

    @Override public String toString() { return name; }

    /** Der Name für Einstellungen und Manifest. */
    public String schluessel() { return name().toLowerCase(Locale.ROOT); }
}
