package com.dan.steuerhaus.atlas;

import com.dan.steuerhaus.tafel.Tafel;

import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Infotafeln des Atlas (seit X1, Werkbuch HalligHooge 145): aus den Werten eines Orts und den Texten der Datei
 * {@code *.atlas} der Inhalt einer {@link Tafel}. Knöpfe aus den Texten:
 * <pre>
 * tafel.hanswarft.knopf.1 = Ansehen; ruhig; hanswarft                                  # Titel; Szenario; Ansicht
 * tafel.hanswarft.knopf.2 = Im Landunter; sturm; landunter_hanswarft; 2026-10-25T22:30:00Z   # dazu die Zeit (UTC)
 * </pre>
 * Das Bild der Tafel ist das Vorschaubild der Ansicht {@code tafel.<ort>.bild}, sonst das der Ansicht des ersten Knopfs.
 */
public final class Tafeln {
    /** Was ein Knopf tut: Szenario, Ansicht (null: die des Szenarios), Zeit (null: Beginn). */
    public interface Szene {
        void zeige(String szenario, String ansicht, Instant zeit);
    }

    static final DateTimeFormatter TAG_ZEIT = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN);
    /** Mittlere Dauer einer Tide (h), für „trocken je Tide“. */
    static final double TIDE_H = 12.42;

    private Tafeln() { }

    /** Die Tafel, solange kein Ort gewählt ist: Text der Startseite und die Kennzahlen des Sturms. */
    public static Tafel.Inhalt start(Atlas a, String titel, ZoneId zone) {
        List<Tafel.Zeile> z = new ArrayList<>();
        long warften = a.orte.stream().filter(o -> o.art().equals("Warft")).count();
        if (warften > 0) z.add(new Tafel.Zeile("Warften auf der Karte", String.valueOf(warften)));
        if (!Double.isNaN(a.scheitel))
            z.add(new Tafel.Zeile(a.sturmName + ": Scheitel am Pegel", m(a.scheitel) + (a.scheitelZeit == null ? "" : ", " + TAG_ZEIT.withZone(zone).format(a.scheitelZeit) + " Uhr")));
        if (!Double.isNaN(a.marke))
            z.add(new Tafel.Zeile("Landunter ab", m(a.marke) + (a.landunterAb == null ? "" : " · " + TAG_ZEIT.withZone(zone).format(a.landunterAb) + " bis "
                    + TAG_ZEIT.withZone(zone).format(a.landunterBis) + " Uhr")));
        List<String> abs = a.absaetze("start");
        if (abs.isEmpty()) abs = List.of("Ein Klick auf einen Ort der Karte zeigt seine Tafel. Mit dem Rad oder einem Doppelklick kommt man näher, "
                + "ziehen verschiebt die Karte. Oben links wechseln die Ebenen; unten führen die Kacheln in die Szene.");
        String t = a.text("start.titel");
        return new Tafel.Inhalt(t != null ? t : titel, "Atlas", null, z, abs, a.text("start.quelle"), List.of());
    }

    /** Die Tafel eines Orts. */
    public static Tafel.Inhalt ort(Atlas a, Atlas.Ort o, ZoneId zone, File vorschau, Szene szene) {
        String k = "tafel." + o.schluessel();
        List<Tafel.Zeile> z = new ArrayList<>();
        boolean warft = AtlasBau.MIT_KUPPE.contains(o.art());
        if (warft) {
            if (!Double.isNaN(o.kuppe())) z.add(new Tafel.Zeile("Höhe der Kuppe", m(o.kuppe())));
            if (!Double.isNaN(o.fuss())) z.add(new Tafel.Zeile("Fuß der Warft", m(o.fuss())));
            if (o.haeuser() > 0) z.add(new Tafel.Zeile("Häuser", String.valueOf(o.haeuser())));
            if (o.wasserAmFuss() != null) z.add(new Tafel.Zeile(a.sturmName + ": Wasser am Fuß ab", TAG_ZEIT.withZone(zone).format(o.wasserAmFuss()) + " Uhr"));
            if (!Double.isNaN(o.kuppe()) && !Double.isNaN(a.scheitel))
                z.add(new Tafel.Zeile("Über dem Scheitel des Sturms", String.format(Locale.GERMAN, "%+.2f m", o.kuppe() - a.scheitel)));
        } else if (o.art().equals("Hallig")) {
            if (o.haeuser() > 0) z.add(new Tafel.Zeile("Häuser", String.valueOf(o.haeuser())));
            if (!Double.isNaN(a.marke)) z.add(new Tafel.Zeile("Landunter ab", m(a.marke)));
            if (a.landunterAb != null)
                z.add(new Tafel.Zeile(a.sturmName + ": Landunter", TAG_ZEIT.withZone(zone).format(a.landunterAb) + " bis " + TAG_ZEIT.withZone(zone).format(a.landunterBis) + " Uhr"));
        } else {
            if (!Double.isNaN(o.hoehe())) z.add(new Tafel.Zeile("Höhe am Punkt", m(o.hoehe())));
            if (!Double.isNaN(o.trocken()))
                z.add(new Tafel.Zeile("Am ruhigen Tag", o.trocken() >= 0.99 ? "immer trocken (Hochsand)"
                        : o.trocken() <= 0.01 ? "immer unter Wasser" : String.format(Locale.GERMAN, "trocken rund %.1f h je Tide (%.0f %%)", o.trocken() * TIDE_H, 100 * o.trocken())));
        }
        List<Tafel.Knopf> kn = new ArrayList<>();
        String bv = a.text(k + ".bild");
        File bild = bv != null && vorschau != null && new File(vorschau, "ansicht_" + bv + ".png").isFile() ? new File(vorschau, "ansicht_" + bv + ".png") : null;
        for (String zeile : a.nummern(k + ".knopf.")) {
            String[] t = zeile.split(";");
            if (t.length < 2) continue;
            String titel = t[0].strip(), sz = t[1].strip(), an = t.length > 2 && !t[2].isBlank() ? t[2].strip() : null;
            Instant zt = t.length > 3 ? Atlas.zeit(t[3]) : null;
            if (bild == null && an != null && vorschau != null) {
                File f = new File(vorschau, "ansicht_" + an + ".png");
                if (f.isFile()) bild = f;
            }
            kn.add(new Tafel.Knopf(titel, () -> szene.zeige(sz, an, zt)));
        }
        return new Tafel.Inhalt(o.name(), o.art(), bild, z, a.absaetze(k), a.text(k + ".quelle"), kn);
    }

    static String m(double v) { return String.format(Locale.GERMAN, "%.2f m NHN", v); }
}
