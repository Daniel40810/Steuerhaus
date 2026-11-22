package com.dan.steuerhaus.atlas;

import com.dan.lauf.Buehne;
import com.dan.lauf.Karte;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Sonde;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Orte;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Rechnet den Atlas einmal aus Gebiet und Archiven (seit X1, Werkbuch HalligHooge 145, F241 A, F243 A; Aufruf
 * {@code --atlas}, wie {@code --vorschau}): die Karte von oben ({@link Karte}) je Ebene mit dem Wasser ihrer Zeit, und
 * die Werte der Orte. Ablage {@code <Ordner der Szenendatei>/atlas/}: {@code karte_<kennung>.jpg} und {@link Atlas#WERTE}.
 * <pre>
 * atlas.karte   = 459500 6041000 475500 6052000   # Ost, Nord, Ost, Nord (UTM); Vorgabe: die Minikarte
 * atlas.zelle   = 2.5                             # m je Bildpunkt
 * atlas.ebene.1 = niedrig; Niedrigwasser; ruhig; niedrig     # Kennung; Titel; Szenario; Zeit
 * atlas.ebene.2 = hoch; Hochwasser; ruhig; hoch               # Zeit: niedrig, hoch, scheitel oder ISO (UTC)
 * atlas.ebene.3 = landunter; Landunter; sturm; scheitel
 * atlas.sturm   = sturm                            # für „Wasser am Fuß“ und Landunter
 * atlas.ruhig   = ruhig                            # für „trocken“ an den Sänden
 * </pre>
 * Werte je Ort: an Warft und Kirche Kuppe (größte Höhe in 50 m), Fuß (Median im Ring 60–150 m; seit X2), Häuser
 * (Mitte in 120 m) und die Zeit, zu der der Pegel im Sturm über den Fuß steigt; an Sänden der Anteil der Zeit trocken am
 * ruhigen Tag (Sonde am Punkt, Tiefe bis 5 cm); an der Hallig die Häuser in 2,5 km. [G] alle Radien.
 */
public final class AtlasBau {
    public static final double KUPPE = 50, FUSS_INNEN = 60, FUSS_AUSSEN = 150, HAEUSER = 120, HALLIG = 2500, TROCKEN = 0.05;
    /** Orte mit Punkt auf der Karte. */
    static final Set<String> WARFT = Set.of("Warft", "Kirche", "Anleger");
    /** Orte mit Kuppe, Fuß und Häusern (seit X2 ohne Anleger: der steht nicht auf einer Warft). */
    static final Set<String> MIT_KUPPE = Set.of("Warft", "Kirche");

    private AtlasBau() { }

    private record Auftrag(String kennung, String titel, String szenario, String zeit) { }

    /** Rechnet den Atlas; gibt die Zahl der Bilder zurück (0: nichts gerechnet). */
    public static int erzeugen(Manifest m, Szenen szenen) throws Exception {
        long n0 = System.nanoTime();
        File ordner = Atlas.ordner(szenen.datei);
        if (!ordner.isDirectory() && !ordner.mkdirs()) throw new IOException("Atlas: Ordner nicht anlegbar: " + ordner);
        double[] r = Atlas.zahlen(m.text("atlas.karte", m.text("minikarte", "")));
        if (r == null || r.length < 4) throw new IllegalArgumentException("Atlas: atlas.karte (oder minikarte) fehlt im Manifest");
        double zelle = Atlas.zahl(m.text("atlas.zelle", "2.5"));
        File gebiet = szenen.gebiet();
        System.out.printf(Locale.GERMAN, "Atlas: Karte %.0f %.0f %.0f %.0f, %.1f m, aus %s%n", r[0], r[1], r[2], r[3], zelle, gebiet);
        Karte k = Karte.aus(gebiet, r, zelle);
        System.out.printf(Locale.GERMAN, "Atlas: Land %d × %d Bildpunkte in %.1f s%n", k.nx, k.ny, (System.nanoTime() - n0) / 1e9);

        List<Auftrag> auftraege = new ArrayList<>();
        for (String z : m.nummern("atlas.ebene.")) {
            String[] t = z.split(";");
            if (t.length >= 4) auftraege.add(new Auftrag(t[0].strip(), t[1].strip(), t[2].strip(), t[3].strip()));
        }
        String sturmK = m.text("atlas.sturm", ""), ruhigK = m.text("atlas.ruhig", "");
        Set<String> folge = new LinkedHashSet<>();
        for (Auftrag a : auftraege) folge.add(a.szenario());
        if (!sturmK.isEmpty()) folge.add(sturmK);
        if (!ruhigK.isEmpty()) folge.add(ruhigK);

        String[] meldung = new String[1];
        List<Orte.Ort> orte = Orte.lies(m, gebiet, meldung);
        System.out.println("Atlas: " + meldung[0]);
        double[] po = szenen.zahlen("pegel.ort");
        String pegelName = szenen.text("pegel.name", "Pegel");
        double marke = szenen.zahl("pegel.marke", Double.NaN);

        Map<String, String> ebenenZeilen = new LinkedHashMap<>();
        Map<String, Instant> amFuss = new LinkedHashMap<>();
        Map<String, Double> trocken = new LinkedHashMap<>();
        String sturmZeile = "", landunterZeile = "";
        int bilder = 0, fehler = 0;
        for (String sk : folge) {
            Szenario s = szenen.szenario(sk);
            if (s == null || s.live()) {
                System.out.println("  FEHLT  Atlas: Szenario " + sk + " gibt es nicht oder es ist live");
                fehler++;
                continue;
            }
            long n1 = System.nanoTime();
            try (Buehne b = Buehne.ohneFenster(gebiet, 160, 90)) {
                if (po != null) b.pegel(pegelName, po[0], po[1], szenen.zahl("pegel.umkreis", 300));
                b.oeffne(s);
                Pegelreihe pr = b.pegelreihe();
                System.out.printf(Locale.GERMAN, "  %s geöffnet in %.1f s; %s%n", s.name, (System.nanoTime() - n1) / 1e9, pr);
                for (Auftrag a : auftraege) {
                    if (!a.szenario().equals(sk)) continue;
                    long n2 = System.nanoTime();
                    double t = zeitpunkt(pr, a.zeit());
                    if (Double.isNaN(t)) {
                        System.out.println("  FEHLT  Ebene " + a.kennung() + ": Zeit „" + a.zeit() + "“ nicht bestimmbar");
                        fehler++;
                        continue;
                    }
                    float[] eta = k.wasser(b, t);
                    BufferedImage img = k.bild(eta);
                    String datei = "karte_" + a.kennung() + ".jpg";
                    schreibeJpeg(img, new File(ordner, datei), 0.9f);
                    double anteil = k.wasserAnteil(eta);
                    Instant zt = pr.start.plusMillis(Math.round(t * 1000));
                    double pegel = pr.bei(t);
                    ebenenZeilen.put(a.kennung(), String.join("; ", a.kennung(), a.titel(), zt.toString(), datei, f(anteil, 4), f(pegel, 2)));
                    bilder++;
                    System.out.printf(Locale.GERMAN, "  Ebene %-10s %s, %s: Pegel %.2f m NHN, Wasser %.1f %% der Karte, in %.1f s%n", a.kennung(), s.name, zt, pegel,
                            100 * anteil, (System.nanoTime() - n2) / 1e9);
                }
                if (sk.equals(sturmK) && pr != null) {
                    Pegelreihe.Marke h = pr.hoechster();
                    if (h != null) sturmZeile = s.name + "; " + f(h.pegel(), 2) + "; " + pr.start.plusMillis(Math.round(h.t() * 1000));
                    if (!Double.isNaN(marke)) {
                        List<double[]> u = pr.ueber(marke);
                        landunterZeile = f(marke, 2) + "; " + (u.isEmpty() ? "-; -" : pr.start.plusMillis(Math.round(u.get(0)[0] * 1000)) + "; "
                                + pr.start.plusMillis(Math.round(u.get(u.size() - 1)[1] * 1000)));
                    }
                    for (Orte.Ort o : orte) {
                        if (!MIT_KUPPE.contains(o.art())) continue;
                        double fuss = k.fuss(o.ost(), o.nord(), FUSS_INNEN, FUSS_AUSSEN);
                        if (Double.isNaN(fuss)) continue;
                        List<double[]> u = pr.ueber(fuss);
                        if (!u.isEmpty()) amFuss.put(o.name(), pr.start.plusMillis(Math.round(u.get(0)[0] * 1000)));
                    }
                    System.out.println("  Sturm: Scheitel " + sturmZeile + "; Landunter " + landunterZeile + "; Wasser am Fuß bei " + amFuss.size() + " Orten");
                }
                if (sk.equals(ruhigK)) {
                    for (Orte.Ort o : orte) {
                        if (!o.art().equals("Sand")) continue;
                        try {
                            Sonde so = b.sonde(o.name(), o.ost(), o.nord());
                            Sonde.Reihe t = so.reihe(Sonde.TIEFE);
                            if (t == null) continue;
                            int n = 0, tr = 0;
                            for (double v : t.wert()) {
                                if (Double.isNaN(v)) continue;
                                n++;
                                if (v <= TROCKEN) tr++;
                            }
                            if (n > 0) trocken.put(o.name(), (double) tr / n);
                        } catch (RuntimeException x) {
                            System.out.println("  Sonde " + o.name() + ": " + x);
                        }
                    }
                    System.out.println("  Ruhiger Tag: trocken an " + trocken.size() + " Sänden " + trocken);
                }
            } catch (Exception x) {
                fehler++;
                System.out.println("  FEHLT  Atlas, Szenario " + s.name + ": " + x);
                x.printStackTrace(System.out);
            }
        }

        StringBuilder w = new StringBuilder();
        w.append("# Werte des Atlas (Werkbuch HalligHooge 145): geschrieben von com.dan.steuerhaus.atlas.AtlasBau (--atlas), nicht von Hand ändern.\n");
        w.append("# ort.N = Art; Name; Ost; Nord; Höhe; Kuppe; Fuß (m NHN); Häuser; Wasser am Fuß im Sturm (UTC); Anteil trocken am ruhigen Tag\n");
        w.append("erzeugt   = ").append(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).append('\n');
        w.append("karte     = ").append(f(k.ost0, 1)).append(' ').append(f(k.nord0, 1)).append(' ').append(f(k.ost1, 1)).append(' ').append(f(k.nord1, 1)).append('\n');
        w.append("zelle     = ").append(f(zelle, 3)).append('\n');
        if (!sturmZeile.isEmpty()) w.append("sturm     = ").append(sturmZeile).append('\n');
        if (!landunterZeile.isEmpty()) w.append("landunter = ").append(landunterZeile).append('\n');
        int e = 0;
        for (Auftrag a : auftraege) if (ebenenZeilen.containsKey(a.kennung())) w.append("ebene.").append(++e).append(" = ").append(ebenenZeilen.get(a.kennung())).append('\n');
        int n = 0;
        for (Orte.Ort o : orte) {
            boolean warft = MIT_KUPPE.contains(o.art()), hallig = o.art().equals("Hallig");
            double hoehe = k.hoehe(o.ost(), o.nord());
            double kuppe = warft ? k.kuppe(o.ost(), o.nord(), KUPPE) : Double.NaN, fuss = warft ? k.fuss(o.ost(), o.nord(), FUSS_INNEN, FUSS_AUSSEN) : Double.NaN;
            int haeuser = warft ? k.haeuser(o.ost(), o.nord(), HAEUSER) : hallig ? k.haeuser(o.ost(), o.nord(), HALLIG) : 0;
            Instant af = amFuss.get(o.name());
            Double tr = trocken.get(o.name());
            w.append("ort.").append(++n).append(" = ").append(String.join("; ", o.art(), o.name().replace(";", ","), f(o.ost(), 1), f(o.nord(), 1), f(hoehe, 2), f(kuppe, 2),
                    f(fuss, 2), String.valueOf(haeuser), af == null ? "-" : af.toString(), tr == null ? "-" : f(tr, 3))).append('\n');
        }
        Files.writeString(new File(ordner, Atlas.WERTE).toPath(), w.toString(), StandardCharsets.UTF_8);
        System.out.printf(Locale.GERMAN, "Atlas: %d Bilder, %d Orte, %d Fehler in %s, %.1f min%n", bilder, n, fehler, ordner, (System.nanoTime() - n0) / 6e10);
        return fehler == 0 ? bilder : 0;
    }

    /** Die Zeit (s seit Start) zu „niedrig“, „hoch“, „scheitel“ oder einer Zeit nach ISO; NaN, wenn es sie nicht gibt. */
    static double zeitpunkt(Pegelreihe pr, String z) {
        if (pr == null) return Double.NaN;
        switch (z) {
            case "scheitel": {
                Pegelreihe.Marke h = pr.hoechster();
                return h == null ? Double.NaN : h.t();
            }
            case "niedrig":
            case "hoch": {
                Pegelreihe.Marke b = null;
                for (Pegelreihe.Marke mk : pr.marken()) {
                    boolean hw = mk.art() == Pegelreihe.Art.HOCHWASSER;
                    if (hw != z.equals("hoch")) continue;
                    if (b == null || (hw ? mk.pegel() > b.pegel() : mk.pegel() < b.pegel())) b = mk;
                }
                return b == null ? Double.NaN : b.t();
            }
            default: {
                Instant i = Atlas.zeit(z);
                return i == null ? Double.NaN : (i.toEpochMilli() - pr.start.toEpochMilli()) / 1000.0;
            }
        }
    }

    static String f(double v, int stellen) { return Double.isNaN(v) ? "-" : String.format(Locale.ROOT, "%." + stellen + "f", v); }

    static void schreibeJpeg(BufferedImage img, File datei, float guete) throws IOException {
        ImageWriter wr = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = wr.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(guete);
        if (datei.exists()) Files.delete(datei.toPath());              // sonst bliebe der Rest einer längeren alten Datei stehen
        try (ImageOutputStream out = ImageIO.createImageOutputStream(datei)) {
            wr.setOutput(out);
            wr.write(null, new IIOImage(img, null, null), p);
        } finally {
            wr.dispose();
        }
    }
}
