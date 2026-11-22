package com.dan.steuerhaus.demo;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.render.overlay.Einblendung;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Einblendungen;
import com.dan.steuerhaus.buehne.Regisseur;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Regiebuch;
import com.dan.steuerhaus.thema.Thema;

import java.awt.Color;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * Prüfung des Kinos und der Klicks mit Karte, ohne Fenster (Werkbuch HalligHooge 116, W3):
 * <ol>
 * <li>Prüfstein 7: Klick auf die Minikarte – der Ort unter dem Zeichen des Auges in der gezeichneten Minikarte gegen
 * den Ort des Auges (± 1 Zelle).</li>
 * <li>Prüfstein 6: Balken im Bild genau 2,39 : 1 (± 1 px); Überblendung: das gemerkte Bild mit Deckung ½ über dem
 * neuen gibt im Linearen die Mitte beider (Mittel ≤ 2/255); Kosten der Blende ≤ 0,5 ms [G] (seit W3b, F198 A).</li>
 * <li>A47 (seit W3b, Auskunft): Dauer eines Sprungs in der Zeit um 10,5 h, aufgeteilt nach den Teilen von {@code Bild.zu}.</li>
 * </ol>
 * Der Regisseur läuft dabei wie in der App, nur Bild für Bild von Hand. Bilder: {@code W3_Kino_*.png}.
 */
public final class KinoCheck {
    static int steine, verstoesse;
    static final int W = 1920, H = 1080;
    static final double DT = 1.0 / 60;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        Instant zeit = Instant.parse("2026-10-25T12:00:00Z");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                case "--zeit" -> zeit = Instant.parse(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("KinoCheck – Steuerhaus W3 (Werkbuch HalligHooge 116), " + W + " × " + H);
        Manifest m = Manifest.lies(manifest);
        Szenen sz = Szenen.lies(m.szenen());
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        Thema.NAUTIK.anwenden();
        try (Buehne b = Buehne.ohneFenster(sz.gebiet(), W, H)) {
            Szenario s = sz.szenario("sturm");
            b.oeffne(s);
            double t = Duration.between(b.uhr().start, zeit).toMillis() / 1000.0;
            Blick blO = blick(b, sz.ansicht("vogelblick"), zeit), blV = blick(b, sz.ansicht("hanswarft_haeuser"), zeit), blW = blick(b, sz.ansicht("warften"), zeit);
            info("Szenario", s.name + ", " + zeit);

            // ---- 7 Minikarte
            teil("7 Klick auf die Minikarte");
            String[] meldung = new String[1];
            Einblendungen ein = new Einblendungen(b, m, zone, null, Double.NaN, "", 1.0, meldung);
            ein.setze(EnumSet.of(Einblendungen.Teil.MINIKARTE));
            ein.takt(EinblendungCheck.zustand(b, blO, t));
            float[] bild = b.standbild(blO, t, true);
            double[] auge = augeInKarte(bild, ein);
            double[] ort = auge == null ? null : ein.karteOrt(auge[0], auge[1]);
            double fehler = ort == null ? Double.NaN : Math.hypot(ort[0] - blO.ost, ort[1] - blO.nord);
            pruefe(ort != null && fehler <= ein.zelle(), "Prüfstein 7: Ort unter dem Zeichen des Auges gegen das Auge",
                    String.format(Locale.GERMAN, "%.1f m (Zelle %.1f m; %s Bildpunkte im Zeichen)", fehler, ein.zelle(), auge == null ? "0" : String.format(Locale.GERMAN, "%.0f", auge[2])), "≤ 1 Zelle");
            ein.setze(EnumSet.noneOf(Einblendungen.Teil.class));
            ein.takt(EinblendungCheck.zustand(b, blO, t));

            // ---- 6 Kino
            teil("6 Kino");
            Einblendung e = b.einblendung();
            Regisseur r = new Regisseur(b, app(zone, m));
            Regiebuch probe = Regiebuch.lies("Regiebuch: Probe des Kinos\nBeschreibung: Balken, Titel, Untertitel\nLänge: 0:40\n0:09 sage Ein Untertitel zur Probe des Kinos\n", "Probe");
            r.spiele(probe, true);
            double vorspann = 0;
            for (int i = 0; i < 150; i++, vorspann += DT) r.weiter(DT, t, blV.kopie());     // 2,5 s: die Titeltafel steht
            Thread.sleep(500);
            r.weiter(DT, t, blV.kopie());
            if (bilder != null) EinblendungCheck.schreibe(b.standbild(blV, t, true), new File(bilder, "W3_Kino_Titel.png"), "Kino: Vorspann mit Titeltafel");
            for (int i = 0; i < 9 * 60; i++) r.weiter(DT, t, blV.kopie());                  // 11,5 s: Film, der Untertitel ist da
            Thread.sleep(500);
            r.weiter(DT, t, blV.kopie());
            bild = b.standbild(blV, t, true);
            if (bilder != null) EinblendungCheck.schreibe(bild, new File(bilder, "W3_Kino_Untertitel.png"), "Kino: Film mit Balken und Untertitel");
            int oben = schwarzeZeilen(bild, true), unten = schwarzeZeilen(bild, false), soll = r.kino.balkenPx(W, H);
            pruefe(Math.abs(oben - soll) <= 1 && Math.abs(unten - soll) <= 1, "Prüfstein 6: Balken im Bild",
                    String.format(Locale.GERMAN, "oben %d, unten %d schwarze Zeilen; soll %d (Bild 1920 × %d, %.3f : 1)", oben, unten, soll, H - 2 * soll, (double) W / (H - 2 * soll)), "± 1 px");

            // Überblendung: das gemerkte Bild (Warften von oben) mit Deckung ½ über dem neuen (Hanswarft)
            r.abbrechen();
            for (int i = 0; i < 180; i++) r.weiter(DT, t, blV.kopie());                     // Balken heraus
            float[] a0 = b.standbild(blW, t), b0 = b.standbild(blV, t);
            b.standbild(blW, t, true);
            long vor = e.gemerkt();
            e.merken();
            b.standbild(blW, t, true);
            e.blende = 0.5;
            float[] mix = b.standbild(blV, t, true);
            double summe = 0, groesst = 0;
            long n = 0;
            for (int y = 200; y < H - 200; y += 2)
                for (int x = 0; x < W; x += 2)
                    for (int c = 0; c < 3; c++) {
                        int i = 4 * (x + W * y) + c;
                        double soll2 = srgb(0.5 * linear(a0[i]) + 0.5 * linear(b0[i])), d = Math.abs(mix[i] - soll2);
                        summe += d;
                        groesst = Math.max(groesst, d);
                        n++;
                    }
            double mittel = summe / n * 255;
            pruefe(e.gemerkt() > vor && e.merkenMoeglich() && mittel <= 2, "Prüfstein 6: Überblendung ½ gegen die Mitte beider Bilder (linear)",
                    String.format(Locale.GERMAN, "Mittel %.2f/255, größte %.1f/255", mittel, groesst * 255), "Mittel ≤ 2/255");
            if (bilder != null) EinblendungCheck.schreibe(mix, new File(bilder, "W3_Blende.png"), "Überblendung ½: Warften von oben über der Hanswarft");
            Buehne.Bildzeiten bz = b.bildzeiten(blV, t, 30, true);
            double kosten = bz.abschnitte().getOrDefault("Einblendung, Blende", Double.NaN);
            pruefe(kosten <= 0.5, "Prüfstein 6: Kosten der Blende je Bild, 1920 × 1080, Ziel RGBA16F", String.format(Locale.GERMAN, "%.3f ms", kosten), "≤ 0,5 ms [G] (F198 A)");
            e.blende = 0;

            // ---- A47: ein Sprung in der Zeit (wie 0:00 zeit im Regiebuch: 10,5 h) gegen ein Standbild ohne Sprung
            teil("A47 Sprung in der Zeit");
            b.standbild(blV, t);
            b.zuMessung();
            long n0 = System.nanoTime();
            b.standbild(blV, t);
            double ohne = (System.nanoTime() - n0) / 1e9;
            String zuOhne = b.zuMessung();
            double t2 = Math.min(t + 10.5 * 3600, Duration.between(b.uhr().start, Instant.parse("2026-10-25T22:59:00Z")).toMillis() / 1000.0);
            n0 = System.nanoTime();
            b.standbild(blV, t2);
            double mit = (System.nanoTime() - n0) / 1e9;
            String zuMit = b.zuMessung();
            info("Standbild (12 Bilder) ohne Sprung", String.format(Locale.GERMAN, "%.2f s; längstes zu %s", ohne, zuOhne));
            info("Standbild (12 Bilder) mit Sprung um " + String.format(Locale.GERMAN, "%.1f h", (t2 - t) / 3600), String.format(Locale.GERMAN, "%.2f s; längstes zu %s", mit, zuMit));
            n0 = System.nanoTime();
            b.standbild(blV, t2 + 300);
            info("Standbild 5 min später (ein neuer Stand)", String.format(Locale.GERMAN, "%.2f s; längstes zu %s", (System.nanoTime() - n0) / 1e9, b.zuMessung()));
            n0 = System.nanoTime();
            b.standbild(blV, t);
            info("Standbild zurück zum Anfang", String.format(Locale.GERMAN, "%.2f s; längstes zu %s", (System.nanoTime() - n0) / 1e9, b.zuMessung()));

            // eine Fahrt durch den Regisseur: die Kamera trifft das Ende
            Fahrt f = Fahrt.flug("Probe", new double[] {blV.ost, blV.nord, blV.hoehe, blV.richtung, blV.neigung, blV.bildwinkel},
                    new double[] {blO.ost, blO.nord, blO.hoehe, blO.richtung, blO.neigung, blO.bildwinkel}, 3);
            r.fahre(f);
            Blick zuletzt = null;
            for (int i = 0; i < 200; i++) {
                Blick x = r.weiter(DT, t, blV.kopie());
                if (x != null) zuletzt = x;
            }
            double d = zuletzt == null ? Double.NaN : Math.hypot(zuletzt.ost - blO.ost, zuletzt.nord - blO.nord);
            pruefe(zuletzt != null && d < 0.001 && !r.faehrt(), "Fahrt über den Regisseur endet am Ziel", String.format(Locale.GERMAN, "%.4f m", d), "≤ 1 mm");
        } catch (Throwable x) {
            x.printStackTrace();
            pruefe(false, "Ablauf", x.toString(), "ohne Fehler");
        }
        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    static Blick blick(Buehne b, Ansicht a, Instant zeit) {
        Blick bl = new Blick();
        a.setze(bl, b.gelaende, b.utm(), zeit);
        return bl.groesse(W, H);
    }

    /** Das Zeichen des Auges in der Minikarte (Farbe Akzent, heller): Schwerpunkt {x, y, Zahl}; null: nicht gefunden. */
    static double[] augeInKarte(float[] p, Einblendungen ein) {
        Color c = Thema.aktuell().akzent.brighter();
        double[] k = ein.kachel(Einblendungen.Teil.MINIKARTE).zuletzt();
        if (Double.isNaN(k[0])) return null;
        int bw = ein.kachel(Einblendungen.Teil.MINIKARTE).breite, bh = ein.kachel(Einblendungen.Teil.MINIKARTE).hoehe;
        double sx = 0, sy = 0;
        int n = 0;
        for (int y = (int) k[1]; y < Math.min(H, k[1] + bh); y++)
            for (int x = (int) k[0]; x < Math.min(W, k[0] + bw); x++) {
                int i = 4 * (x + W * y);
                if (Math.abs(p[i] * 255 - c.getRed()) <= 8 && Math.abs(p[i + 1] * 255 - c.getGreen()) <= 10 && Math.abs(p[i + 2] * 255 - c.getBlue()) <= 10) {
                    sx += x + 0.5;
                    sy += y + 0.5;
                    n++;
                }
            }
        return n == 0 ? null : new double[] {sx / n, sy / n, n};
    }

    /** Zahl der schwarzen Zeilen von oben oder unten (alle Kanäle ≤ 1/255), gezählt am linken Rand (x < 200; der Untertitel steht mittig im Balken). */
    static int schwarzeZeilen(float[] p, boolean vonOben) {
        int n = 0;
        for (int k = 0; k < H; k++) {
            int y = vonOben ? k : H - 1 - k;
            boolean schwarz = true;
            for (int x = 0; x < 200 && schwarz; x++) {
                int i = 4 * (x + W * y);
                if (p[i] > 1.01 / 255 || p[i + 1] > 1.01 / 255 || p[i + 2] > 1.01 / 255) schwarz = false;
            }
            if (!schwarz) break;
            n++;
        }
        return n;
    }

    static double linear(double c) { return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4); }

    static double srgb(double l) { return l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055; }

    static Regisseur.App app(ZoneId zone, Manifest m) {
        return new Regisseur.App() {
            @Override public void szenario(String k) { }

            @Override public void ansicht(String k) { }

            @Override public Fahrt fahrt(String name) { return null; }

            @Override public void raffer(double x) { }

            @Override public void halt(boolean h) { }

            @Override public void ebene(String name, boolean an) { }

            @Override public void messblick(String arg) { }

            @Override public void linien(String art) { }

            @Override public void einblendung(String arg) { }

            @Override public void thema(String name) { }

            @Override public void lesezeichen(String name) { }

            @Override public void hinweis(String text) { System.out.println("  info   Hinweis: " + text); }

            @Override public double marke() { return 2.01; }

            @Override public ZoneId zone() { return zone; }

            @Override public String kopf() { return m.titel(); }

            @Override public List<String> abspann() { return List.of(m.titel()); }

            @Override public void kinoEnde() { }
        };
    }

    static void teil(String t) { System.out.println("\n== " + t); }

    static void info(String g, String w) { System.out.println("  info   " + g + ": " + w); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + g + ": " + w + "  [" + grenze + "]");
    }
}
