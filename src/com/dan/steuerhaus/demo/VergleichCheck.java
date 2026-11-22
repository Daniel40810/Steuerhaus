package com.dan.steuerhaus.demo;

import com.dan.core.raum.Blick;
import com.dan.lauf.Szenario;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.ausgabe.Aufnahme;
import com.dan.steuerhaus.thema.Thema;
import com.dan.steuerhaus.vergleich.Mappe;
import com.dan.steuerhaus.vergleich.Vergleich;
import com.dan.steuerhaus.vergleich.Vergleichsansicht;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Y1 mit Karte und Archiv (Werkbuch HalligHooge 152): prüft die gerechneten Mappen der Vergleiche aus der Datei der
 * Vergleiche. Prüfstein 5 vollständig und aktuell, 6 Ausrichtung (beim Bezug steht das Hochwasser oder der Scheitel;
 * bei gleicher Uhrzeit dieselbe Uhrzeit), 7 das Bild beim Bezug gegen ein einzelnes Standbild in einer frischen Bühne
 * (Vorgeschichte und JPEG), A und B verschieden, 8 Kosten je Bild, 9 Sonden (Pegel mit Werten, eine Sonde im Bild).
 * Dazu die Spieler mit den echten Bildern ({@code Y1_Spieler_<schluessel>.png}).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.VergleichCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class VergleichCheck {
    /** Grenzen [G]. */
    static final double BILD_GRENZE = 2.5, JE_BILD = 2.5, PEGEL_ANTEIL = 0.9;
    static int steine, verstoesse;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("VergleichCheck – Y1 (Werkbuch HalligHooge 152)");
        Manifest m = Manifest.lies(manifest);
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        Thema.zu(m.thema()).anwenden();
        List<String> fehler = new ArrayList<>();
        List<Vergleich> l = Vergleich.lies(m, fehler);
        for (String f : fehler) System.out.println("  info   Datei: " + f);
        int imBild = 0;
        for (Vergleich v : l) {
            System.out.println("\n== " + v.name() + " (" + v.schluessel() + ")");
            File d = new File(m.vergleiche(), v.schluessel());
            Mappe mp;
            try {
                mp = Mappe.lies(d);
            } catch (Exception x) {
                pruefe(false, "Prüfstein 5: Mappe vorhanden", x.getMessage(), "vorhanden");
                continue;
            }
            // 5 vollständig und aktuell
            int fehlt = 0;
            for (int k = 0; k < mp.bilder.size(); k++) for (int s = 0; s < 2; s++) if (!mp.datei(k, s == 1).isFile()) fehlt++;
            BufferedImage b1 = mp.bild(0, false, 1);
            pruefe(mp.fertig && mp.kennung.equals(v.kennung(1920, 1080)) && fehlt == 0 && b1 != null && b1.getWidth() == mp.breite && mp.vorschau().isFile(),
                    "Prüfstein 5: Mappe fertig, zur Beschreibung passend, alle Bilder da",
                    String.format(Locale.GERMAN, "%d Bilder je Seite (von %d), %d fehlen, %d × %d, Kennung %s", mp.bilder.size(), v.bilder(), fehlt, mp.breite, mp.hoehe,
                            mp.kennung.equals(v.kennung(1920, 1080)) ? "gleich" : "anders"), "fertig, 0 fehlen");
            for (String h : mp.hinweise) info("Hinweis", h);
            // 6 Ausrichtung
            int kb = mp.beimBezug();
            Mappe.Bild bb = mp.bilder.get(kb);
            String art = mp.bezug.art();
            boolean ok6;
            String wert;
            if (art.equals("gleiche Uhrzeit") || art.equals("Zeit")) {
                boolean gleich = true;
                for (Mappe.Bild x : mp.bilder) gleich &= x.a().atZone(zone).toLocalTime().equals(x.b().atZone(zone).toLocalTime());
                ok6 = gleich || art.equals("Zeit");
                wert = gleich ? "dieselbe Uhrzeit in allen Bildern" : "Uhrzeiten verschieden";
            } else {
                double[] a = pegelUm(mp, kb, false), b = pegelUm(mp, kb, true);
                ok6 = a[0] >= a[1] - 0.01 && a[0] >= a[2] - 0.01 && b[0] >= b[1] - 0.01 && b[0] >= b[2] - 0.01 && Math.abs(bb.rel()) <= mp.schritt / 2;
                wert = String.format(Locale.GERMAN, "A %.2f (−30 min %.2f, +30 min %.2f), B %.2f (%.2f, %.2f) m NHN; Bezug A %s, B %s", a[0], a[1], a[2], b[0], b[1], b[2], mp.bezug.a(),
                        mp.bezug.b());
            }
            pruefe(ok6, "Prüfstein 6: Ausrichtung „" + art + "“", wert, art.equals("gleiche Uhrzeit") ? "gleiche Uhrzeit" : "beim Bezug der höchste Pegel ± 30 min");
            // 7 Bild beim Bezug gegen ein einzelnes Standbild
            double[] abw = new double[2];
            for (int s = 0; s < 2; s++) {
                try (Aufnahme a = new Aufnahme(m, new Aufnahme.Optionen(mp.breite, mp.hoehe, 24, 1, 360, 0, false, 1958), null)) {
                    Szenario sz = a.sz.szenario(s == 0 ? mp.schluesselA : mp.schluesselB);
                    a.oeffne(sz);
                    Blick bl = new Blick(mp.blick[0], mp.blick[1], mp.blick[2], mp.blick[3], mp.blick[4], mp.blick[5]).groesse(mp.breite, mp.hoehe);
                    java.time.Instant t = s == 0 ? bb.a() : bb.b();
                    byte[] rgb = a.standbild(bl, (t.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0);
                    BufferedImage frisch = new BufferedImage(mp.breite, mp.hoehe, BufferedImage.TYPE_INT_RGB);
                    int[] px = new int[mp.breite * mp.hoehe];
                    for (int i = 0; i < px.length; i++) px[i] = (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | rgb[3 * i + 2] & 0xFF;
                    frisch.setRGB(0, 0, mp.breite, mp.hoehe, px, 0, mp.breite);
                    abw[s] = SteuerhausCheck.mittel(frisch, mp.bild(kb, s == 1, 1));
                }
            }
            double ab = SteuerhausCheck.mittel(mp.bild(kb, false, 1), mp.bild(kb, true, 1));
            pruefe(abw[0] <= BILD_GRENZE && abw[1] <= BILD_GRENZE && ab > 2, "Prüfstein 7: Bild beim Bezug gegen ein einzelnes Standbild (frische Bühne), A und B verschieden",
                    String.format(Locale.GERMAN, "A %.2f/255, B %.2f/255; A gegen B %.1f/255", abw[0], abw[1], ab), "≤ " + BILD_GRENZE + "/255 [G]; A ≠ B");
            // 8 Kosten
            double je = Double.parseDouble(Manifest.lies(new File(d, "vergleich.txt")).text("je_bild_s", "NaN"));
            pruefe(je <= JE_BILD, "Prüfstein 8: Kosten je Bild (Standbild und JPEG)", String.format(Locale.GERMAN, "%.2f s; alles zusammen %.0f s für 2 × %d Bilder", je, mp.rechenzeit,
                    mp.bilder.size()), "≤ " + JE_BILD + " s [G]");
            // 9 Sonden
            int mitPegel = 0;
            List<String> so = new ArrayList<>();
            for (Mappe.Sonde x : mp.sonden) {
                Mappe.Reihe r = x.reihe("Wasserstand");
                double fa = anteil(r == null ? null : r.a()), fb = anteil(r == null ? null : r.b());
                if (fa >= PEGEL_ANTEIL && fb >= PEGEL_ANTEIL) mitPegel++;
                if (!Double.isNaN(x.x())) imBild++;
                so.add(String.format(Locale.GERMAN, "%s (Grund %.2f, nass A %.0f %%, B %.0f %%%s)", x.name(), x.grund(), 100 * fa, 100 * fb, Double.isNaN(x.x()) ? "" : ", im Bild"));
            }
            pruefe(mitPegel >= 1, "Prüfstein 9: eine Sonde mit Wasserstand in beiden Reihen", so.toString(), "≥ 1 mit ≥ 90 % Werten");
            if (bilder != null) {
                bilder.mkdirs();
                BufferedImage[] sp = new BufferedImage[1];
                Mappe mm = mp;
                File ab2 = bilder;
                SwingUtilities.invokeAndWait(() -> {
                    Vergleichsansicht va = new Vergleichsansicht(mm, zone, ab2);
                    va.zeige(kb);
                    va.teil(0.5);
                    sp[0] = va.alsBild(1600, 1000);
                    va.ende();
                });
                File f = new File(bilder, "Y1_Spieler_" + v.schluessel() + ".png");
                ImageIO.write(sp[0], "png", f);
                info("Bild", f.getName());
            }
        }
        System.out.println();
        pruefe(imBild >= 1, "Prüfstein 9: mindestens eine Sonde im Bild (Marke)", imBild + " Sonden im Bild", "≥ 1");
        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    /** Pegel am Bild k und 30 min davor und danach (aus dem Index; NaN zählt als −∞). */
    static double[] pegelUm(Mappe mp, int k, boolean b) {
        int d = (int) Math.round(1800 / mp.schritt);
        double[] r = new double[3];
        int[] ks = {k, k - d, k + d};
        for (int i = 0; i < 3; i++) {
            double v = ks[i] >= 0 && ks[i] < mp.bilder.size() ? (b ? mp.bilder.get(ks[i]).pegelB() : mp.bilder.get(ks[i]).pegelA()) : Double.NaN;
            r[i] = Double.isNaN(v) ? Double.NEGATIVE_INFINITY : v;
        }
        return r;
    }

    static double anteil(double[] w) {
        if (w == null || w.length == 0) return 0;
        int n = 0;
        for (double x : w) if (!Double.isNaN(x)) n++;
        return n / (double) w.length;
    }

    static void info(String g, String w) { System.out.println("  info   " + g + ": " + w); }

    static void pruefe(boolean ok, String was, String wert, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + wert + "  [" + grenze + "]");
    }
}
