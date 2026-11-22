package com.dan.steuerhaus.demo;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Sonde;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Motivsuche für die Regiebücher (Teil C, Werkbuch HalligHooge 134, F226 A), ohne Fenster: je Szenario aus dem Archiv
 * die Pegelkurve mit Tag und Nacht, Hoch- und Niedrigwasser und Landunter, dazu in gleichen Abständen der Szenenzeit
 * ein Standbild aus jeder Motiv-Ansicht – als Kontaktbogen je Ansicht (PNG) und als Tabelle (Text), damit die Zeiten und
 * Blicke der Bücher an den echten Daten gesetzt werden und nicht geschätzt.
 * <p>
 * Die Motive stehen im Manifest; ohne Eintrag gilt die Ansicht des Szenarios. Hinter „@“ die Ebenen des Messblicks
 * (partikel, lic, linien), etwa für die Strömung. Optional der Bereich der Szenenzeit (UTC):
 * <pre>
 * motivsuche.sturm         = hanswarft_haeuser, landunter_hanswarft, warften, prielmuendung@partikel,lic
 * motivsuche.flugsand.bereich = 2026-10-17T14:00:00Z 2026-10-18T08:00:00Z
 * </pre>
 * Ablage: {@code <sz>_<ansicht>.png} (6 Bilder je Zeile, 320 × 180, höchstens {@code --bilder} Zeiten), {@code <sz>_motive.txt}
 * (Hoch- und Niedrigwasser, Landunter, Sonnenauf- und -untergang, je Bild Pegel, Wind, Strömung, Sonne),
 * {@code <sz>_sonde.csv} (die Sonde am Pegel über alle Stände) und {@code Motivsuche.md} (Übersicht).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.Motivsuche --manifest src\szenen\Hooge.steuerhaus --ablage docs\motivsuche [--nur sturm,xaver] [--bilder 48]
 * </pre>
 */
public final class Motivsuche {
    static final int B = 320, H = 180, SPALTEN = 6, ZEILE = 22, KOPF = 250;
    static final int BREITE = SPALTEN * B;
    static final Color GRUND = new Color(0x0B1622), PAPIER = new Color(0xEDE4CC), MESSING = new Color(0xC8A04A), ROT = new Color(0xA8322D),
            NACHT = new Color(0x050B12), DAEMMER = new Color(0x13263A), TAG = new Color(0x1E3A55), WASSER = new Color(0x6FB7E0);

    /** Ein Motiv: Ansicht und die Ebenen des Messblicks (alle aus: ohne). */
    record Motiv(Ansicht ansicht, boolean partikel, boolean lic, boolean linien) {
        boolean messblick() { return partikel || lic || linien; }

        String datei() { return ansicht.schluessel + (messblick() ? "_messblick" : ""); }

        String name() {
            if (!messblick()) return ansicht.name;
            List<String> e = new ArrayList<>();
            if (partikel) e.add("Partikel");
            if (lic) e.add("LIC");
            if (linien) e.add("Linien");
            return ansicht.name + " – Messblick (" + String.join(", ", e) + ")";
        }
    }

    public static void main(String[] args) throws Exception {
        File manifest = null, ablage = new File("docs" + File.separator + "motivsuche");
        Set<String> nur = null;
        int bilder = 48;
        for (int i = 0; i < args.length; i++)
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--ablage" -> ablage = new File(args[++i]);
                case "--nur" -> nur = new LinkedHashSet<>(Arrays.asList(args[++i].split(",")));
                case "--bilder" -> bilder = Integer.parseInt(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        System.out.println("Motivsuche – Teil C (Werkbuch HalligHooge 134, F226 A), ohne Fenster");
        Manifest m = Manifest.lies(manifest);
        Szenen sz = Szenen.lies(m.szenen());
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        if (!ablage.isDirectory() && !ablage.mkdirs()) throw new IllegalStateException("Ablage nicht anlegbar: " + ablage);
        double marke = sz.zahl("pegel.marke", Double.NaN);
        double[] po = sz.zahlen("pegel.ort");
        String pegelName = sz.text("pegel.name", "Pegel");
        long n0 = System.nanoTime();
        StringBuilder index = new StringBuilder();
        index.append("# Motivsuche (Teil C, Werkbuch HalligHooge 134)\n\nZeiten in ").append(zone).append("; Pegel ").append(pegelName)
                .append(String.format(Locale.GERMAN, ", Marke Landunter %.2f m NHN.%n%n", marke));
        index.append("| Szenario | Archiv von | bis | Höchster Stand | Landunter | Bilder | Kontaktbögen | Zeit |\n|---|---|---|---|---|---|---|---|\n");
        int fehler = 0, gesamt = 0;
        for (Szenario s : sz.szenarien()) {
            if (s.live() || nur != null && !nur.contains(s.schluessel)) continue;
            long n1 = System.nanoTime();
            try {
                int n = szenario(m, sz, s, zone, ablage, po, pegelName, marke, bilder, index, n1);
                gesamt += n;
            } catch (Exception x) {
                fehler++;
                System.out.println("  FEHLT  Szenario " + s.name + ": " + x);
                x.printStackTrace(System.out);
                index.append("| ").append(s.name).append(" | Fehler: ").append(x.getMessage()).append(" | | | | | | |\n");
            }
        }
        index.append(String.format(Locale.GERMAN, "%n%d Bilder in %.1f min, %d Fehler.%n", gesamt, (System.nanoTime() - n0) / 6e10, fehler));
        Files.writeString(new File(ablage, "Motivsuche.md").toPath(), index.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.GERMAN, "%nMotivsuche: %d Bilder in %s, %d Fehler, %.1f min%n", gesamt, ablage, fehler, (System.nanoTime() - n0) / 6e10);
        System.exit(fehler == 0 ? 0 : 1);
    }

    /** Teilt „a, b@partikel,lic, c“ in „a“, „b@partikel,lic“, „c“: ein Teil nach @ endet erst vor dem nächsten Namen. */
    static List<String> teile(String liste) {
        List<String> r = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean ebenen = false;
        for (String t : liste.split(",")) {
            String x = t.strip();
            boolean ebene = ebenen && (x.equals("partikel") || x.equals("lic") || x.equals("linien"));
            if (ebene) cur.append(',').append(x);
            else {
                if (cur.length() > 0) r.add(cur.toString());
                cur = new StringBuilder(x);
                ebenen = x.contains("@");
            }
        }
        if (cur.length() > 0) r.add(cur.toString());
        return r;
    }

    static int szenario(Manifest m, Szenen sz, Szenario s, ZoneId zone, File ablage, double[] po, String pegelName, double marke, int bilder,
                        StringBuilder index, long n1) throws Exception {
        List<Motiv> motive = new ArrayList<>();
        for (String e : teile(m.text("motivsuche." + s.schluessel, s.ansicht != null ? s.ansicht : sz.ansichten().get(0).schluessel)))
            motive.addAll(motive(e, sz, s));
        System.out.println("\n== " + s.name + " (" + s.schluessel + "): " + motive.size() + " Motive");
        DateTimeFormatter tag = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN).withZone(zone);
        DateTimeFormatter uhr = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN).withZone(zone);
        try (Buehne b = Buehne.ohneFenster(sz.gebiet(), B, H)) {
            if (po != null) b.pegel(pegelName, po[0], po[1], sz.zahl("pegel.umkreis", 300));
            b.oeffne(s);
            Pegelreihe pr = b.pegelreihe();
            if (pr == null || pr.laenge() < 2) throw new IllegalStateException("keine Pegelreihe");
            double[] zt = pr.zeiten(), pg = pr.pegel(), so = pr.sonne();
            Instant a0 = pr.start.plusMillis(Math.round(zt[0] * 1000)), a1 = pr.start.plusMillis(Math.round(zt[zt.length - 1] * 1000));
            String[] bereich = m.text("motivsuche." + s.schluessel + ".bereich", "").strip().split("\\s+");
            Instant v0 = a0, v1 = a1;
            if (bereich.length == 2) {
                v0 = max(a0, Instant.parse(bereich[0]));
                v1 = min(a1, Instant.parse(bereich[1]));
            }
            System.out.printf(Locale.GERMAN, "  Archiv %s – %s, %d Stände; Bereich %s – %s; offen nach %.1f s%n", tag.format(a0), tag.format(a1), zt.length, tag.format(v0), tag.format(v1), (System.nanoTime() - n1) / 1e9);

            // Sonde am Pegel: Wind und Strömung (eigene Quelle, liest das Archiv noch einmal)
            Sonde sonde = null;
            long ns = System.nanoTime();
            try {
                sonde = Sonde.ausArchiv(s, b.gebiet, pegelName, po[0], po[1]);
                Files.writeString(new File(ablage, s.schluessel + "_sonde.csv").toPath(), sonde.csv(), StandardCharsets.UTF_8);
                System.out.printf(Locale.GERMAN, "  Sonde %s in %.1f s%n", pegelName, (System.nanoTime() - ns) / 1e9);
            } catch (Exception x) {
                System.out.println("  WARNUNG Sonde: " + x);
            }
            Sonde.Reihe wind = sonde != null ? sonde.reihe(Sonde.WIND) : null, strom = sonde != null ? sonde.reihe(Sonde.STROEMUNG) : null;

            // Zeiten der Bilder: auf volle halbe Stunden, Schritt ein Vielfaches davon
            long spanne = Math.max(1, v1.getEpochSecond() - v0.getEpochSecond());
            long schritt = Math.max(1800, (long) Math.ceil(spanne / (double) bilder / 1800.0) * 1800);
            List<Instant> zeiten = new ArrayList<>();
            for (long e = (v0.getEpochSecond() + 1799) / 1800 * 1800; e <= v1.getEpochSecond() && zeiten.size() < bilder; e += schritt) zeiten.add(Instant.ofEpochSecond(e));
            int zeilen = (zeiten.size() + SPALTEN - 1) / SPALTEN;

            // Kontaktbögen: je Motiv ein Bild, der Kopf mit der Pegelkurve
            List<Pegelreihe.Marke> marken = pr.marken();
            List<double[]> ueber = Double.isNaN(marke) ? List.of() : pr.ueber(marke);
            BufferedImage[] bogen = new BufferedImage[motive.size()];
            for (int j = 0; j < motive.size(); j++) {
                bogen[j] = new BufferedImage(BREITE, KOPF + zeilen * (H + ZEILE), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = bogen[j].createGraphics();
                g.setColor(GRUND);
                g.fillRect(0, 0, BREITE, bogen[j].getHeight());
                kopf(g, s, motive.get(j), pr, zt, pg, so, marken, ueber, marke, zeiten, schritt, zone, tag, uhr);
                g.dispose();
            }
            boolean hatMess = b.messquelle() != null;
            StringBuilder tab = new StringBuilder();
            int n = 0;
            long nb = System.nanoTime();
            for (int i = 0; i < zeiten.size(); i++) {
                Instant z = zeiten.get(i);
                double t = b.uhr().t(z), tp = (z.toEpochMilli() - pr.start.toEpochMilli()) / 1000.0;
                double p = pr.bei(tp), w = wind != null ? wind.bei((z.toEpochMilli() - sonde.start.toEpochMilli()) / 1000.0) : Double.NaN,
                        st = strom != null ? strom.bei((z.toEpochMilli() - sonde.start.toEpochMilli()) / 1000.0) : Double.NaN, son = sonneBei(zt, so, tp);
                tab.append(String.format(Locale.GERMAN, "%3d  %s  %s UTC  Pegel %s  Wind %5.1f m/s  Strömung %5.2f m/s  Sonne %+6.1f°%n", i + 1, tag.format(z),
                        DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("UTC")).format(z), Double.isNaN(p) ? "trocken    " : String.format(Locale.GERMAN, "%+6.2f m NHN", p), w, st, son));
                String beschr = String.format(Locale.GERMAN, "%d · %s · %s", i + 1, tag.format(z), Double.isNaN(p) ? "trocken" : String.format(Locale.GERMAN, "%+.2f m", p))
                        + (Double.isNaN(w) ? "" : String.format(Locale.GERMAN, " · %.0f m/s", w));
                for (int j = 0; j < motive.size(); j++) {
                    Motiv mo = motive.get(j);
                    int x0 = (i % SPALTEN) * B, y0 = KOPF + (i / SPALTEN) * (H + ZEILE);
                    Graphics2D g = bogen[j].createGraphics();
                    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g.setColor(GRUND);
                    g.fillRect(x0, y0, B, H + ZEILE);
                    g.setColor(PAPIER);
                    g.setFont(new Font("SansSerif", Font.PLAIN, 13));
                    g.drawString(beschr, x0 + 4, y0 + 16);
                    try {
                        if (mo.messblick() && !hatMess) throw new IllegalStateException("kein Messblick in diesem Szenario");
                        if (mo.messblick()) b.messblickEbenen(mo.partikel(), mo.lic(), mo.linien(), 0);
                        else b.messblick(false);
                        Blick bl = new Blick();
                        mo.ansicht().setze(bl, b.gelaende, b.utm(), z);
                        bl.groesse(B, H);
                        float[] px = b.standbild(bl, t);
                        BufferedImage kachel = new BufferedImage(B, H, BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < H; y++)
                            for (int x = 0; x < B; x++) {
                                int k = 4 * (y * B + x);
                                kachel.setRGB(x, y, (k8(px[k]) << 16) | (k8(px[k + 1]) << 8) | k8(px[k + 2]));
                            }
                        g.drawImage(kachel, x0, y0 + ZEILE, null);
                        n++;
                    } catch (Exception x) {
                        g.setColor(ROT);
                        g.drawString("fehlt: " + x.getMessage(), x0 + 4, y0 + ZEILE + 40);
                        System.out.println("  FEHLT  " + mo.datei() + " Bild " + (i + 1) + ": " + x);
                    }
                    g.dispose();
                }
                if ((i + 1) % SPALTEN == 0 || i + 1 == zeiten.size())
                    System.out.printf(Locale.GERMAN, "  Bilder %d von %d Zeiten, je Bild %.2f s%n", i + 1, zeiten.size(), (System.nanoTime() - nb) / 1e9 / Math.max(1, n));
            }
            b.messblick(false);
            for (int j = 0; j < motive.size(); j++) ImageIO.write(bogen[j], "png", new File(ablage, s.schluessel + "_" + motive.get(j).datei() + ".png"));

            // Tabelle
            StringBuilder txt = new StringBuilder();
            txt.append("Motivsuche: ").append(s.name).append(" (").append(s.schluessel).append(")\n");
            txt.append("Archiv: ").append(tag.format(a0)).append(" – ").append(tag.format(a1)).append(" (").append(zone).append("), ").append(zt.length).append(" Stände\n");
            txt.append("Motive: ");
            for (Motiv mo : motive) txt.append(mo.name()).append("; ");
            txt.append("\n\nHoch- und Niedrigwasser am Pegel ").append(pegelName).append(":\n");
            for (Pegelreihe.Marke mk : marken)
                txt.append(String.format(Locale.GERMAN, "  %-14s %s  %+.2f m NHN%n", mk.art() == Pegelreihe.Art.HOCHWASSER ? "Hochwasser" : "Niedrigwasser", tag.format(pr.start.plusMillis(Math.round(mk.t() * 1000))), mk.pegel()));
            Pegelreihe.Marke hoch = pr.hoechster();
            if (hoch != null) txt.append(String.format(Locale.GERMAN, "  Höchster Stand %s  %+.2f m NHN%n", tag.format(pr.start.plusMillis(Math.round(hoch.t() * 1000))), hoch.pegel()));
            txt.append(String.format(Locale.GERMAN, "%nLandunter (Pegel über %.2f m NHN):%n", marke));
            if (ueber.isEmpty()) txt.append("  keiner\n");
            for (double[] u : ueber) txt.append("  ").append(tag.format(pr.start.plusMillis(Math.round(u[0] * 1000)))).append(" – ").append(tag.format(pr.start.plusMillis(Math.round(u[1] * 1000)))).append('\n');
            txt.append("\nSonne (Höhe 0° am Pegel):\n");
            for (int i = 1; i < so.length; i++)
                if (so[i - 1] < 0 != so[i] < 0) {
                    double f = so[i - 1] / (so[i - 1] - so[i]);
                    Instant x = pr.start.plusMillis(Math.round((zt[i - 1] + f * (zt[i] - zt[i - 1])) * 1000));
                    txt.append("  ").append(so[i] >= 0 ? "Aufgang   " : "Untergang ").append(tag.format(x)).append('\n');
                }
            txt.append(String.format(Locale.GERMAN, "%nBilder alle %d min (Nummern wie in den Kontaktbögen):%n", schritt / 60)).append(tab);
            Files.writeString(new File(ablage, s.schluessel + "_motive.txt").toPath(), txt.toString(), StandardCharsets.UTF_8);
            double min = (System.nanoTime() - n1) / 6e10;
            index.append(String.format(Locale.GERMAN, "| %s | %s | %s | %s | %s | %d | %s | %.1f min |%n", s.name, tag.format(a0), tag.format(a1),
                    hoch == null ? "–" : String.format(Locale.GERMAN, "%+.2f m, %s", hoch.pegel(), tag.format(pr.start.plusMillis(Math.round(hoch.t() * 1000)))),
                    ueber.isEmpty() ? "–" : ueber.size() + "× ab " + tag.format(pr.start.plusMillis(Math.round(ueber.get(0)[0] * 1000))), n,
                    String.join(", ", motive.stream().map(mo -> s.schluessel + "_" + mo.datei() + ".png").toList()), min));
            System.out.printf(Locale.GERMAN, "  %d Bilder, %d Bögen, %.1f min%n", n, motive.size(), min);
            return n;
        }
    }

    static List<Motiv> motive(String eintrag, Szenen sz, Szenario s) {
        String[] t = eintrag.strip().split("@", 2);
        Ansicht a = sz.ansicht(t[0].strip());
        if (a == null) {
            System.out.println("  WARNUNG motivsuche." + s.schluessel + ": Ansicht „" + t[0].strip() + "“ gibt es nicht");
            return List.of();
        }
        String eb = t.length > 1 ? t[1].toLowerCase(Locale.ROOT) : "";
        return List.of(new Motiv(a, eb.contains("partikel"), eb.contains("lic"), eb.contains("linien")));
    }

    /** Der Kopf eines Bogens: Titel, Pegelkurve über Tag und Nacht, Hoch- und Niedrigwasser, Landunter, Zeiten der Bilder. */
    static void kopf(Graphics2D g, Szenario s, Motiv mo, Pegelreihe pr, double[] zt, double[] pg, double[] so, List<Pegelreihe.Marke> marken,
                     List<double[]> ueber, double marke, List<Instant> zeiten, long schritt, ZoneId zone, DateTimeFormatter tag, DateTimeFormatter uhr) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(MESSING);
        g.setFont(new Font("Serif", Font.BOLD, 22));
        g.drawString(s.name + " · " + mo.name(), 12, 28);
        g.setColor(PAPIER);
        g.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g.drawString(String.format(Locale.GERMAN, "Pegel %s · Bilder alle %d min · Zeiten %s · Kopf: Pegel (m NHN) über Tag, Dämmerung und Nacht; rote Linie: Landunter", pr.name, schritt / 60, zone), 12, 48);
        int x0 = 50, x1 = BREITE - 20, y0 = 60, y1 = KOPF - 30;
        double t0 = zt[0], t1 = zt[zt.length - 1];
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double p : pg) if (!Double.isNaN(p)) { lo = Math.min(lo, p); hi = Math.max(hi, p); }
        if (!Double.isNaN(marke)) { lo = Math.min(lo, marke); hi = Math.max(hi, marke); }
        if (lo > hi) { lo = -2; hi = 2; }
        lo = Math.floor(lo * 2) / 2 - 0.25;
        hi = Math.ceil(hi * 2) / 2 + 0.25;
        // Tag und Nacht
        for (int i = 0; i + 1 < zt.length; i++) {
            int xa = (int) Math.round(x0 + (zt[i] - t0) / (t1 - t0) * (x1 - x0)), xb = (int) Math.round(x0 + (zt[i + 1] - t0) / (t1 - t0) * (x1 - x0));
            g.setColor(so[i] < -6 ? NACHT : so[i] < 0 ? DAEMMER : TAG);
            g.fillRect(xa, y0, Math.max(1, xb - xa + 1), y1 - y0);
        }
        // Gitter der Höhen
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (double h = Math.ceil(lo); h <= hi; h += 1) {
            int y = y(h, lo, hi, y0, y1);
            g.setColor(new Color(255, 255, 255, 40));
            g.drawLine(x0, y, x1, y);
            g.setColor(PAPIER);
            g.drawString(String.format(Locale.GERMAN, "%+.0f", h), 8, y + 4);
        }
        // Landunter
        if (!Double.isNaN(marke)) {
            int y = y(marke, lo, hi, y0, y1);
            g.setColor(ROT);
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[] {6, 4}, 0));
            g.drawLine(x0, y, x1, y);
            g.setStroke(new BasicStroke(1));
            for (double[] u : ueber) {
                int xa = (int) Math.round(x0 + (u[0] - t0) / (t1 - t0) * (x1 - x0)), xb = (int) Math.round(x0 + (u[1] - t0) / (t1 - t0) * (x1 - x0));
                g.setColor(new Color(168, 50, 45, 90));
                g.fillRect(xa, y0, Math.max(2, xb - xa), y1 - y0);
            }
        }
        // Pegel
        g.setColor(WASSER);
        g.setStroke(new BasicStroke(2f));
        int px = -1, py = -1;
        for (int i = 0; i < zt.length; i++) {
            if (Double.isNaN(pg[i])) { px = -1; continue; }
            int x = (int) Math.round(x0 + (zt[i] - t0) / (t1 - t0) * (x1 - x0)), y = y(pg[i], lo, hi, y0, y1);
            if (px >= 0) g.drawLine(px, py, x, y);
            px = x;
            py = y;
        }
        g.setStroke(new BasicStroke(1));
        // Hoch- und Niedrigwasser
        for (Pegelreihe.Marke mk : marken) {
            int x = (int) Math.round(x0 + (mk.t() - t0) / (t1 - t0) * (x1 - x0)), y = y(mk.pegel(), lo, hi, y0, y1);
            boolean hw = mk.art() == Pegelreihe.Art.HOCHWASSER;
            g.setColor(hw ? MESSING : PAPIER);
            g.fillOval(x - 3, y - 3, 7, 7);
            g.drawString(String.format(Locale.GERMAN, "%s %s %+.2f", hw ? "HW" : "NW", uhr.format(pr.start.plusMillis(Math.round(mk.t() * 1000))), mk.pegel()), x - 40, hw ? y - 7 : y + 16);
        }
        // Zeiten der Bilder
        g.setColor(MESSING);
        for (int i = 0; i < zeiten.size(); i++) {
            double t = (zeiten.get(i).toEpochMilli() - pr.start.toEpochMilli()) / 1000.0;
            int x = (int) Math.round(x0 + (t - t0) / (t1 - t0) * (x1 - x0));
            g.drawLine(x, y1, x, y1 + 6);
            if (zeiten.size() <= 24 || i % 2 == 0) g.drawString(Integer.toString(i + 1), x - 4, y1 + 18);
        }
        // Tage
        g.setColor(PAPIER);
        Instant a = pr.start.plusMillis(Math.round(t0 * 1000)), e = pr.start.plusMillis(Math.round(t1 * 1000));
        g.drawString(tag.format(a), x0, y0 - 2);
        String se = tag.format(e);
        g.drawString(se, x1 - g.getFontMetrics().stringWidth(se), y0 - 2);
    }

    static int y(double h, double lo, double hi, int y0, int y1) { return (int) Math.round(y1 - (h - lo) / (hi - lo) * (y1 - y0)); }

    static double sonneBei(double[] zt, double[] so, double t) {
        if (t <= zt[0]) return so[0];
        if (t >= zt[zt.length - 1]) return so[so.length - 1];
        int i = Arrays.binarySearch(zt, t);
        if (i >= 0) return so[i];
        i = -i - 2;
        double f = (t - zt[i]) / (zt[i + 1] - zt[i]);
        return so[i] + f * (so[i + 1] - so[i]);
    }

    static int k8(float v) { return Math.round(Math.max(0, Math.min(1, v)) * 255); }

    static Instant max(Instant a, Instant b) { return a.isAfter(b) ? a : b; }

    static Instant min(Instant a, Instant b) { return a.isBefore(b) ? a : b; }
}
