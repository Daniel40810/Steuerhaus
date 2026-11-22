package com.dan.steuerhaus.demo;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.render.overlay.Einblendung;
import com.dan.render.overlay.Kachel;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Einblendungen;
import com.dan.steuerhaus.thema.Thema;
import com.dan.steuerhaus.ui.Startbild;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Prüfung der Einblendungen mit Karte, ohne Fenster (Werkbuch HalligHooge 113, W2, Prüfsteine 1 und 3–7):
 * <ol>
 * <li>Prüfstein 7: die Schritte beim Öffnen der Bühne der Reihe nach, Anteil nie fallend, der letzte 1.</li>
 * <li>Prüfstein 6: Anteil Wasser der Minikarte beim Hochwasser 25.10. 23:45 UTC (Sturmflut) gegen den Anteil nasser
 * Zellen im Gitter der Nässe (± 5 Prozentpunkte [G]).</li>
 * <li>Prüfstein 5: Drehung der Kompassrose im Bild gegen die Richtung des Blicks (± 0,5°).</li>
 * <li>Prüfstein 4: der Maßstab gegen den Abstand der Orte aus {@code ort(fx, fy)} an seinen Enden (± 2 % [G]), im
 * Vogelblick und vom Boden.</li>
 * <li>Prüfstein 3: Proben im Bild (9 × 9, Magenta): eine frei sichtbare liegt ≤ 1 px auf {@code Blick.bildpunkt}, eine
 * hinter der Warft ist aus; dazu die Lage aller gezeigten Ortsnamen gegen ihre Projektion.</li>
 * <li>Prüfstein 1: Kosten der Einblendungen auf der Karte, 1920 × 1080, alle an ({@code bildzeiten}, Abschnitt
 * „Einblendung“; ≤ 0,5 ms [G]).</li>
 * </ol>
 * Dazu Bilder in beiden Themen ({@code docs/status/W2_Einblendung_*.png}).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.EinblendungCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class EinblendungCheck {
    static int steine, verstoesse;
    static final int W = 1920, H = 1080;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        String szenario = "sturm", ansichtVerdeckt = "hanswarft_haeuser", ansichtOben = "vogelblick";
        Instant zeit = Instant.parse("2026-10-25T23:45:00Z");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                case "--szenario" -> szenario = args[++i];
                case "--zeit" -> zeit = Instant.parse(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("EinblendungCheck – Steuerhaus W2 (Werkbuch HalligHooge 113), " + W + " × " + H);
        Manifest m = Manifest.lies(manifest);
        Szenen sz = Szenen.lies(m.szenen());
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        String pegel = sz.zahlen("pegel.ort") != null ? sz.text("pegel.name", "Pegel") : null;
        Thema.NAUTIK.anwenden();

        teil("7 Schritte beim Öffnen");
        List<Startbild.Schritt> schritte = new CopyOnWriteArrayList<>();
        try (Buehne b = Buehne.ohneFenster(sz.gebiet(), W, H, (n, a) -> schritte.add(new Startbild.Schritt(n, a)))) {
            pruefe(Startbild.folgeGut(schritte), "Prüfstein 7: Schritte der Reihe nach, Anteil steigt bis 1", schritte.size() + ": " + schritte, "monoton, letzter 1");

            double[] p = sz.zahlen("pegel.ort");
            if (p != null) b.pegel(pegel, p[0], p[1], sz.zahl("pegel.umkreis", 300));
            Szenario s = sz.szenario(szenario);
            b.oeffne(s);
            double t = Duration.between(b.uhr().start, zeit).toMillis() / 1000.0;
            info("Szenario", s.name + ", " + zeit + " (t = " + Math.round(t) + " s)");
            Blick blV = blick(b, sz.ansicht(ansichtVerdeckt), zeit), blO = blick(b, sz.ansicht(ansichtOben), zeit);

            float[] vorher = b.standbild(blV, t);
            String[] meldung = new String[1];
            Einblendungen ein = new Einblendungen(b, m, zone, pegel, sz.zahl("pegel.marke", Double.NaN), sz.text("pegel.marke.name", "Marke"), 1.0, meldung);
            info("Ortsnamen", meldung[0] + "; " + ein.orte() + " gezeigt");
            ein.setze(EnumSet.allOf(Einblendungen.Teil.class));
            Buehne.Zustand z = zustand(b, blV, t);
            ein.takt(z);
            float[] nachher = b.standbild(blV, t);
            info("Standbild ohne Einblendung vor und nach dem Anlegen und Malen (dieselbe Bühne; Prüfstein 2 tragen BuehneCheck und BildCheck)", gleich(vorher, nachher));
            b.messblick(true);
            z = zustand(b, blV, t);
            ein.takt(z);

            // ---- 6 Minikarte
            teil("6 Minikarte");
            double[] k = ein.karte();
            double soll = k == null ? Double.NaN : b.nassAnteil(k[0], k[1], k[2], k[3]), ist = ein.wasserAnteil();
            pruefe(!Double.isNaN(soll) && !Double.isNaN(ist) && Math.abs(ist - soll) <= 0.05, "Prüfstein 6: Anteil Wasser der Minikarte gegen das Gitter der Nässe",
                    String.format(Locale.GERMAN, "Minikarte %.1f %%, Gitter %.1f %% (Rechteck %s)", 100 * ist, 100 * soll, k == null ? "–" : java.util.Arrays.toString(k)), "± 5 Prozentpunkte");

            // ---- 5 Kompass
            teil("5 Kompass");
            float[] mitAllen = b.standbild(blV, t, true);
            Kachel rose = ein.kachel(Einblendungen.Teil.KOMPASS);
            double dr = Math.toDegrees(rose.zuletzt()[2]), soll5 = -blV.richtung, d5 = ((dr - soll5) % 360 + 540) % 360 - 180;
            pruefe(Math.abs(d5) <= 0.5, "Prüfstein 5: Drehung der Rose gegen die Richtung des Blicks", String.format(Locale.GERMAN, "Rose %.2f°, Blick %.2f°: %+.3f°", dr, blV.richtung, d5), "± 0,5°");

            // ---- 4 Maßstab
            teil("4 Maßstab");
            for (Blick bl : List.of(blO, blV)) {
                double[] ms = Einblendungen.massstabRechnen(b, bl);
                if (ms == null) {
                    pruefe(false, "Prüfstein 4: Maßstab " + richtung(bl), "kein Boden im unteren Drittel", "± 2 %");
                    continue;
                }
                double h = ms[1] / 2 / b.breite();
                double[] a = b.ort(bl, ms[3] - h, ms[2]), c = b.ort(bl, ms[3] + h, ms[2]);
                double l = a == null || c == null ? Double.NaN : Math.hypot(c[0] - a[0], c[1] - a[1]), f = (l - ms[0]) / ms[0];
                pruefe(Math.abs(f) <= 0.02, "Prüfstein 4: Maßstab " + richtung(bl), String.format(Locale.GERMAN, "Balken %.0f m auf %.0f px, an den Enden %.1f m: %+.2f %%; Boden in %.0f m",
                        ms[0], ms[1], l, 100 * f, ms[4]), "± 2 %");
            }

            // ---- 3 Ortsnamen und Proben
            teil("3 Ortsnamen und Verdecken");
            Einblendung e = b.einblendung();
            int gezeigt = 0, falsch = 0;
            double groesst = 0;
            for (Kachel o : e.kacheln()) {
                if (!o.name.startsWith("Ort ") || !o.sichtbar) continue;
                double[] zl = o.zuletzt();
                if (Double.isNaN(zl[0])) continue;
                gezeigt++;
                double[] q = o.ort();
                double[] pp = Einblendung.projiziere(blV.kopie().groesse(W, H), q[0], q[1], q[2], e.kruemmung);
                double dx = Math.abs(zl[0] - Math.round(pp[0] - 0.5 * o.breite)), dy = Math.abs(zl[1] - Math.round(pp[1] - o.hoehe));
                groesst = Math.max(groesst, Math.max(dx, dy));
                if (dx > 1 || dy > 1) falsch++;
            }
            pruefe(falsch == 0, "Prüfstein 3: Lage der gezeigten Ortsnamen gegen Blick.bildpunkt (mit Senke d²/2R)", gezeigt + " gezeigt, größte Abweichung " + groesst + " px", "≤ 1 px");
            ein.setze(Set.of());
            ein.takt(z);
            e.an = true;
            // Proben längs zweier Sehstrahlen (seit Lauf W2: die freie Probe lag 2 m über dem Boden 2 m vor dem Auge, also über dem
            // Bild): frei auf 80 % des Wegs zur ersten Fläche, verdeckt 150 m hinter der Warft
            double[] treffer = b.ort(blV, 0.5, 0.52), flaeche = b.ort(blV, 0.3, 0.65);
            if (treffer == null || flaeche == null) pruefe(false, "Prüfstein 3: Proben", "der Strahl trifft nichts", "Treffer");
            else {
                double dT = abstand(blV, treffer), dF = abstand(blV, flaeche);
                double[] hinter = entlang(blV, treffer, dT + 150), frei = entlang(blV, flaeche, 0.8 * dF);
                Kachel kf = probe(e, "Probe frei", frei[0], frei[1], frei[2]), kh = probe(e, "Probe hinter der Warft", hinter[0], hinter[1], hinter[2]);
                float[] bild = b.standbild(blV, t, true);
                double[] sf = schwerpunkt(bild, kf), sh = schwerpunkt(bild, kh);
                double[] pf = Einblendung.projiziere(blV.kopie().groesse(W, H), frei[0], frei[1], frei[2], e.kruemmung);
                double fehler = Math.hypot(sf[0] - pf[0], sf[1] - pf[1]);
                pruefe(sf[2] >= 60 && fehler <= 1, String.format(Locale.GERMAN, "Prüfstein 3: freie Probe (%.0f m vor der Fläche in %.0f m) auf Blick.bildpunkt", 0.2 * dF, dF),
                        String.format(Locale.GERMAN, "%.0f von 81 Bildpunkten, Abstand %.2f px", sf[2], fehler), "an, ≤ 1 px");
                pruefe(sh[2] == 0, String.format(Locale.GERMAN, "Prüfstein 3: Probe hinter der Warft (Treffer in %.0f m, Gelände dort %.1f m NHN; Probe 150 m dahinter)", dT,
                        b.gelaendeHoehe(treffer[0], treffer[1])), String.format(Locale.GERMAN, "%.0f Bildpunkte", sh[2]), "aus");
                e.weg(kf);
                e.weg(kh);
                if (bilder != null) schreibe(bild, new File(bilder, "W2_Proben.png"), "Proben: frei (links, Magenta) und hinter der Warft (Mitte, aus)");
            }

            // ---- 1 Kosten
            teil("1 Kosten");
            ein.setze(EnumSet.allOf(Einblendungen.Teil.class));
            ein.takt(zustand(b, blV, t));
            b.standbild(blV, t, true);
            Buehne.Bildzeiten bz = b.bildzeiten(blV, t, 30, true);
            double kosten = bz.abschnitte().getOrDefault("Einblendung", Double.NaN), hoch = bz.abschnitte().getOrDefault("Einblendung, hochladen", 0.0);
            pruefe(kosten <= 0.5, "Prüfstein 1: Kosten der Einblendungen je Bild, 1920 × 1080, alle an (" + e.zuletztGezeichnet + " Kacheln)",
                    String.format(Locale.GERMAN, "%.3f ms; hochladen %.3f ms im Mittel (nur nach Änderung)", kosten, hoch), "≤ 0,5 ms [G]");
            java.util.Arrays.sort(bz.karte());
            info("Bild auf der Karte (Median aus 30, mit Einblendungen und Messblick)", String.format(Locale.GERMAN, "%.1f ms", bz.karte()[bz.karte().length / 2]));

            // ---- Bilder in beiden Themen
            if (bilder != null) {
                bilder.mkdirs();
                for (Thema th : new Thema[] {Thema.NAUTIK, Thema.LABOR}) {
                    th.anwenden();
                    int n = 0;
                    for (Blick bl : List.of(blV, blO)) {
                        n++;
                        ein.takt(zustand(b, bl, t));
                        float[] bild = b.standbild(bl, t, true);
                        File f = new File(bilder, "W2_Einblendung_" + n + "_" + th.name + ".png");
                        schreibe(bild, f, s.name + " · " + zeit + " · " + richtung(bl) + " · " + th.name);
                        info("Bild", f.getPath());
                    }
                }
                Thema.NAUTIK.anwenden();
            }
            b.messblick(false);
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

    /** Ein Zustand wie im Fenster: Blick, Zeit, Pegel und Wind aus dem letzten Bild. */
    static Buehne.Zustand zustand(Buehne b, Blick bl, double t) {
        b.standbild(bl, t);
        Buehne.Zustand z = new Buehne.Zustand();
        z.blick = bl;
        z.t = t;
        z.start = b.uhr().start;
        z.jetzt = b.uhr().bei(t);
        Pegelreihe pr = b.pegelreihe();
        z.pegel = pr == null ? Double.NaN : pr.bei(t);
        z.windU = b.bild().windU;
        z.windV = b.bild().windV;
        z.wind = Math.hypot(z.windU, z.windV);
        return z;
    }

    static String richtung(Blick bl) { return String.format(Locale.GERMAN, "Blick %.0f° / Neigung %.0f° aus %.0f m", bl.richtung, bl.neigung, bl.hoehe); }

    static double abstand(Blick bl, double[] p) { return Math.sqrt(Math.pow(p[0] - bl.ost, 2) + Math.pow(p[1] - bl.nord, 2) + Math.pow(p[2] - bl.hoehe, 2)); }

    static double[] entlang(Blick bl, double[] p, double d) {
        double dx = p[0] - bl.ost, dy = p[1] - bl.nord, dz = p[2] - bl.hoehe, n = Math.sqrt(dx * dx + dy * dy + dz * dz);
        return new double[] {bl.ost + dx / n * d, bl.nord + dy / n * d, bl.hoehe + dz / n * d};
    }

    static Kachel probe(Einblendung e, String name, double ost, double nord, double h) {
        Kachel k = e.neu(name, 9, 9).inWelt(ost, nord, h, 0.5, 0.5, true);
        k.male(g -> {
            g.setColor(Color.MAGENTA);
            g.fillRect(0, 0, 9, 9);
        });
        return k;
    }

    /** Schwerpunkt der magentafarbenen Bildpunkte im Umkreis von 20 px um die letzte Lage der Kachel: {x, y, Zahl}. */
    static double[] schwerpunkt(float[] p, Kachel k) {
        double[] zl = k.zuletzt();
        if (Double.isNaN(zl[0])) return new double[] {Double.NaN, Double.NaN, 0};
        int x0 = (int) zl[0] - 20, y0 = (int) zl[1] - 20;
        double sx = 0, sy = 0;
        int n = 0;
        for (int y = Math.max(0, y0); y < Math.min(H, y0 + 49); y++)
            for (int x = Math.max(0, x0); x < Math.min(W, x0 + 49); x++) {
                int i = 4 * (x + W * y);
                if (Math.abs(p[i] - 1) < 2.0 / 255 && p[i + 1] < 2.0 / 255 && Math.abs(p[i + 2] - 1) < 2.0 / 255) {
                    sx += x + 0.5;
                    sy += y + 0.5;
                    n++;
                }
            }
        return n == 0 ? new double[] {Double.NaN, Double.NaN, 0} : new double[] {sx / n, sy / n, n};
    }

    static String gleich(float[] a, float[] b) {
        long n = 0;
        double gr = 0;
        for (int i = 0; i < a.length; i++) {
            double d = Math.abs(a[i] - b[i]);
            if (d > 0) n++;
            gr = Math.max(gr, d);
        }
        return n == 0 ? "bitgleich" : n + " Werte verschieden (größte " + String.format(Locale.GERMAN, "%.4f", gr) + ")";
    }

    static void schreibe(float[] p, File f, String titel) throws Exception {
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int i = 4 * (x + W * y);
                int r = Math.round(Math.max(0, Math.min(1, p[i])) * 255), g = Math.round(Math.max(0, Math.min(1, p[i + 1])) * 255), bl = Math.round(Math.max(0, Math.min(1, p[i + 2])) * 255);
                img.setRGB(x, y, (r << 16) | (g << 8) | bl);
            }
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(0, H - 24, W, 24);
        g.setColor(Color.WHITE);
        g.drawString(titel, 10, H - 7);
        g.dispose();
        f.getParentFile().mkdirs();
        ImageIO.write(img, "png", f);
    }

    static void teil(String t) { System.out.println("\n== " + t); }

    static void info(String g, String w) { System.out.println("  info   " + g + ": " + w); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + g + ": " + w + "  [" + grenze + "]");
    }
}
