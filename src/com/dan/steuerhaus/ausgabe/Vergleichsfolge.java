package com.dan.steuerhaus.ausgabe;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Sonde;
import com.dan.lauf.Szenario;
import com.dan.steuerhaus.buehne.Orte;
import com.dan.steuerhaus.thema.Thema;
import com.dan.steuerhaus.vergleich.Mappe;
import com.dan.steuerhaus.vergleich.Vergleich;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die Bildfolge eines Vergleichs (seit Y1, Werkbuch HalligHooge 152, F246 B): dieselbe Kamera in Szenario A und B,
 * Bild für Bild im Abstand {@code schritt} um den Bezug (gleiches Hochwasser, gleicher Scheitel, gleiche Uhrzeit). Jedes
 * Bild ist ein Standbild der Bühne (zwölf Durchgänge, stehende Zeit, wie das Vergleichsbild aus W5); erst alle Bilder
 * von A in einer Bühne, dann in einer frischen Bühne alle von B. Dazu die Sonden aus beiden Archiven (ohne Bühne) und
 * ihr Ort im Bild. Schreibt eine {@link Mappe}.
 */
public final class Vergleichsfolge {
    /** Güte der JPEG-Bilder [G]. */
    public static final float GUETE = 0.92f;
    /** Abstand der Sondenwerte in der CSV (s) [G]. */
    public static final double SONDEN_SCHRITT = 120;

    private Vergleichsfolge() { }

    /** Ergebnis: die Mappe und Kennzahlen für die Prüfung. */
    public record Ergebnis(Mappe mappe, int bilder, double jeBild, double sekunden) { }

    public static Ergebnis rechne(Aufnahme a, Vergleich v, File ordner, Consumer<String> meldung) throws Exception {
        long n0 = System.nanoTime();
        Szenario sa = a.sz.szenario(v.a()), sb = a.sz.szenario(v.b());
        if (sa == null || sb == null) throw new IllegalArgumentException("Vergleich „" + v.name() + "“: Szenario " + (sa == null ? v.a() : v.b()) + " gibt es nicht");
        if (sa.archiv == null || sb.archiv == null) throw new IllegalArgumentException("Vergleich „" + v.name() + "“: nur Szenarien mit Archiv, nicht live");
        String pn = a.sz.text("pegel.name", "Pegel"), pegelName = pn.startsWith("Pegel") ? pn : "Pegel " + pn;
        double[] po = a.sz.zahlen("pegel.ort");
        double umkreis = a.sz.zahl("pegel.umkreis", 300);
        if (a.benutzt()) a.frischeBuehne();
        // die Pegelreihen beider Seiten aus dem Archiv (wie die Bühne sie rechnet): Spanne und Marken
        Ansicht an = v.ansicht() != null ? a.sz.ansicht(v.ansicht()) : null;
        if (v.blick() == null && an == null) throw new IllegalArgumentException("Vergleich „" + v.name() + "“: Ansicht „" + v.ansicht() + "“ gibt es nicht");
        double pox = po != null ? po[0] : v.blick() != null ? v.blick()[0] : an.ost, poy = po != null ? po[1] : v.blick() != null ? v.blick()[1] : an.nord;
        Pegelreihe pa = reihe(sa, a, pegelName, pox, poy, umkreis), pb = reihe(sb, a, pegelName, pox, poy, umkreis);
        Instant[] spanA = spanne(pa), spanB = spanne(pb);
        if (spanA == null || spanB == null) throw new IllegalArgumentException("Vergleich „" + v.name() + "“: keine Stände am Pegel in " + (spanA == null ? sa.name : sb.name));
        Vergleich.Bezug bz = v.ausrichtung().bezug(marken(pa), hoechster(pa), spanA, marken(pb), hoechster(pb), spanB, v.von(), v.bis(), a.zone);
        List<Double> rel = Vergleich.folge(bz, spanA, spanB, v.von(), v.bis(), v.schritt());
        if (rel.isEmpty()) throw new IllegalArgumentException("Vergleich „" + v.name() + "“: A und B haben im Bereich keine gemeinsamen Zeiten");
        meldung.accept(String.format(Locale.GERMAN, "Vergleich „%s“: %s %s | %s %s, Bezug %s, %d Bilder je Seite (%s bis %s), %d × %d", v.name(), sa.name, bz.a(), sb.name,
                bz.b(), bz.art(), rel.size(), Vergleich.abstand(rel.get(0)), Vergleich.abstand(rel.get(rel.size() - 1)), a.o.breite(), a.o.hoehe()));
        List<String> hinweise = new ArrayList<>();
        if (rel.size() < v.bilder()) hinweise.add(String.format(Locale.GERMAN, "Bereich auf die Archive beschnitten: %d von %d Bildern", rel.size(), v.bilder()));

        // die Kamera: für beide Seiten dieselbe (eine Ansicht zur Sonne nach der Sonne beim Bezug von A)
        Blick bl = new Blick();
        if (v.blick() != null) {
            double[] x = v.blick();
            bl.ost = x[0];
            bl.nord = x[1];
            bl.hoehe = x[2];
            bl.richtung = x[3];
            bl.neigung = x[4];
            bl.bildwinkel = x[5];
        } else an.setze(bl, a.b.gelaende, a.b.utm(), bz.a());
        bl.groesse(a.o.breite(), a.o.hoehe());

        Mappe.Schreiber sch = new Mappe.Schreiber(ordner, GUETE);
        // der Stand von A beim Bezug (für den Ort der Sonden im Bild); die Folge rechnet danach in frischen Bühnen
        a.oeffne(sa);
        a.standbild(bl, (bz.a().toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0);
        // Sonden: aus beiden Archiven, auf gemeinsamen Abständen zum Bezug
        List<String[]> sonden = new ArrayList<>();
        List<Orte.Ort> orte = null;
        int ns = 0;
        for (String s : v.sonden()) {
            double[] ort = null;
            String name = s;
            String[] t = s.split(";");
            if (t.length >= 3) {
                name = t[0].strip();
                ort = new double[] {zahl(t[1]), zahl(t[2])};
            } else if (s.equalsIgnoreCase("pegel")) {
                if (po != null) ort = po;
                name = pegelName;
            } else if (s.equalsIgnoreCase("mitte")) {
                double[] p = a.b.ort(bl, 0.5, 0.5);
                if (p != null) ort = new double[] {p[0], p[1]};
                name = "Bildmitte";
            } else {
                if (orte == null) orte = Orte.lies(a.m, a.sz.gebiet(), null);
                for (Orte.Ort o : orte) if (o.name().equalsIgnoreCase(s.strip())) ort = new double[] {o.ost(), o.nord()};
            }
            if (ort == null) {
                hinweise.add("Sonde „" + s + "“: Ort nicht gefunden");
                continue;
            }
            Sonde xa = Sonde.ausArchiv(sa, a.b.gebiet, name, ort[0], ort[1]), xb = Sonde.ausArchiv(sb, a.b.gebiet, name, ort[0], ort[1]);
            List<Mappe.Reihe> reihen = new ArrayList<>();
            int n = (int) Math.floor((rel.get(rel.size() - 1) - rel.get(0)) / SONDEN_SCHRITT + 1e-9) + 1;
            double[] tr = new double[n];
            for (int i = 0; i < n; i++) tr[i] = rel.get(0) + i * SONDEN_SCHRITT;
            for (String g : new String[] {Sonde.WASSERSTAND, Sonde.TIEFE, Sonde.STROEMUNG, Sonde.WIND}) {
                Sonde.Reihe ra = xa.reihe(g), rb = xb.reihe(g);
                if (ra == null && rb == null) continue;
                double[] wa = new double[n], wb = new double[n];
                for (int i = 0; i < n; i++) {
                    wa[i] = ra == null ? Double.NaN : ra.bei((bz.a().toEpochMilli() - xa.start.toEpochMilli()) / 1000.0 + tr[i]);
                    wb[i] = rb == null ? Double.NaN : rb.bei((bz.b().toEpochMilli() - xb.start.toEpochMilli()) / 1000.0 + tr[i]);
                }
                reihen.add(new Mappe.Reihe(g, (ra != null ? ra : rb).einheit(), tr, wa, wb));
            }
            ns++;
            sch.sonde(ns, Mappe.csv(String.format(Locale.ROOT, "Sonde %s Ost %.1f Nord %.1f, Grund %.3f m NHN; Abstand zum Bezug in s; A %s, B %s", name, ort[0], ort[1], xa.grund, sa.name,
                    sb.name), reihen));
            // der Ort im Bild: über dem Boden oder auf dem Wasser beim Bezug von A, verdeckt: ohne Marke
            double h = a.b.gelaendeHoehe(ort[0], ort[1]);
            double wA = xa.reihe(Sonde.WASSERSTAND) != null ? xa.reihe(Sonde.WASSERSTAND).bei((bz.a().toEpochMilli() - xa.start.toEpochMilli()) / 1000.0) : Double.NaN;
            if (Double.isNaN(h) || !Double.isNaN(wA) && wA > h) h = Double.isNaN(wA) ? 0 : wA;
            String xy = "-";
            double[] bp = bl.bildpunkt(bl.relativ(ort[0], ort[1], h));
            if (bp != null && bp[0] >= 0 && bp[0] < bl.breite && bp[1] >= 0 && bp[1] < bl.hoehe_px) {
                double[] treff = a.b.ort(bl, bp[0] / bl.breite, bp[1] / bl.hoehe_px);
                double d = Math.hypot(Math.hypot(ort[0] - bl.ost, ort[1] - bl.nord), h - bl.hoehe);
                if (treff != null && Math.hypot(Math.hypot(treff[0] - ort[0], treff[1] - ort[1]), treff[2] - h) <= Math.max(30, 0.04 * d))
                    xy = String.format(Locale.ROOT, "%.1f %.1f", bp[0], bp[1]);
            }
            sonden.add(new String[] {name, String.format(Locale.ROOT, "%.1f", ort[0]), String.format(Locale.ROOT, "%.1f", ort[1]), f3(xa.grund), xy});
            meldung.accept(String.format(Locale.GERMAN, "Vergleich: Sonde %d %s (O %.0f, N %.0f) %s", ns, name, ort[0], ort[1], xy.equals("-") ? "nicht im Bild" : "im Bild"));
        }

        // die Bilder: A, dann B in einer frischen Bühne
        double[] pegelA = new double[rel.size()], pegelB = new double[rel.size()];
        BufferedImage[] beimBezug = new BufferedImage[2];
        int kb = 0;
        for (int i = 1; i < rel.size(); i++) if (Math.abs(rel.get(i)) < Math.abs(rel.get(kb))) kb = i;
        long nb0 = System.nanoTime();
        for (int seite = 0; seite < 2; seite++) {
            Szenario s = seite == 0 ? sa : sb;
            Instant t0 = seite == 0 ? bz.a() : bz.b();
            Pegelreihe p = seite == 0 ? pa : pb;
            if (seite == 1 || a.benutzt()) a.frischeBuehne();
            a.oeffne(s);
            long ns0 = System.nanoTime();
            for (int k = 0; k < rel.size(); k++) {
                Instant t = t0.plusMillis(Math.round(rel.get(k) * 1000));
                double ts = (t.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0;
                byte[] rgb = a.standbild(bl, ts);
                BufferedImage im = bild(rgb, a.o.breite(), a.o.hoehe());
                sch.bild(k, seite == 1, im);
                if (k == kb) beimBezug[seite] = im;
                (seite == 0 ? pegelA : pegelB)[k] = p.bei((t.toEpochMilli() - p.start.toEpochMilli()) / 1000.0);
                if (k == 0 || (k + 1) % 6 == 0 || k == rel.size() - 1)
                    meldung.accept(String.format(Locale.GERMAN, "Vergleich %s %d/%d · %s · %.2f s je Bild", seite == 0 ? "A" : "B", k + 1, rel.size(), Vergleich.abstand(rel.get(k)),
                            (System.nanoTime() - ns0) / 1e9 / (k + 1)));
            }
        }
        double jeBild = (System.nanoTime() - nb0) / 1e9 / (2.0 * rel.size());
        if (beimBezug[0] != null && beimBezug[1] != null) sch.vorschau(beimBezug[0], beimBezug[1], Thema.aktuell().marke);

        // der Index
        sch.setze("schluessel", v.schluessel()).setze("name", v.name()).setze("text", v.text()).setze("kennung", v.kennung(a.o.breite(), a.o.hoehe()));
        sch.setze("a.schluessel", sa.schluessel).setze("a.name", sa.name).setze("b.schluessel", sb.schluessel).setze("b.name", sb.name);
        sch.setze("ansicht", an != null ? an.name : "Blick");
        sch.setze("blick", String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f %.4f", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel));
        sch.setze("groesse", a.o.breite() + " " + a.o.hoehe()).setze("ausrichten", v.ausrichtung()).setze("bezug", bz.art());
        sch.setze("bezug.a", bz.a() + "; " + f3(Double.isNaN(bz.pegelA()) ? pa.bei((bz.a().toEpochMilli() - pa.start.toEpochMilli()) / 1000.0) : bz.pegelA()));
        sch.setze("bezug.b", bz.b() + "; " + f3(Double.isNaN(bz.pegelB()) ? pb.bei((bz.b().toEpochMilli() - pb.start.toEpochMilli()) / 1000.0) : bz.pegelB()));
        sch.setze("pegel.name", pegelName).setze("schritt", String.format(Locale.ROOT, "%.0f", v.schritt()));
        for (int k = 0; k < rel.size(); k++) {
            Instant ta = bz.a().plusMillis(Math.round(rel.get(k) * 1000)), tb = bz.b().plusMillis(Math.round(rel.get(k) * 1000));
            sch.setze("bild." + (k + 1), String.format(Locale.ROOT, "%.0f; %s; %s; %s; %s", rel.get(k), ta, tb, f3(pegelA[k]), f3(pegelB[k])));
        }
        for (int i = 0; i < sonden.size(); i++) sch.setze("sonde." + (i + 1), String.join("; ", sonden.get(i)));
        for (int i = 0; i < hinweise.size(); i++) sch.setze("hinweis." + (i + 1), hinweise.get(i));
        double sek = (System.nanoTime() - n0) / 1e9;
        sch.setze("erzeugt", Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS)).setze("rechenzeit_s", String.format(Locale.ROOT, "%.1f", sek))
                .setze("je_bild_s", String.format(Locale.ROOT, "%.3f", jeBild));
        sch.schliesse(true);
        for (String h : hinweise) meldung.accept("Vergleich: " + h);
        meldung.accept(String.format(Locale.GERMAN, "Vergleich „%s“ fertig: 2 × %d Bilder, %.2f s je Bild, %.0f s → %s", v.name(), rel.size(), jeBild, sek, ordner));
        return new Ergebnis(Mappe.lies(ordner), rel.size(), jeBild, sek);
    }

    /** Die Pegelreihe eines Szenarios aus seinem Archiv (ohne Bühne). */
    static Pegelreihe reihe(Szenario s, Aufnahme a, String name, double ost, double nord, double umkreis) throws Exception {
        try (Archivquelle q = new Archivquelle(Path.of(s.archiv.getPath()))) {
            return Pegelreihe.aus(q, q.start, a.b.gebiet, name, ost, nord, umkreis);
        }
    }

    /** Erster und letzter Stand (null: keiner). */
    static Instant[] spanne(Pegelreihe p) {
        double[] t = p.zeiten();
        if (t.length == 0) return null;
        return new Instant[] {p.start.plusMillis(Math.round(t[0] * 1000)), p.start.plusMillis(Math.round(t[t.length - 1] * 1000))};
    }

    static List<Vergleich.Marke> marken(Pegelreihe p) {
        List<Vergleich.Marke> l = new ArrayList<>();
        for (Pegelreihe.Marke m : p.marken())
            l.add(new Vergleich.Marke(m.art() == Pegelreihe.Art.HOCHWASSER, p.start.plusMillis(Math.round(m.t() * 1000)), m.pegel()));
        return l;
    }

    /** Der höchste Stand der Reihe als Marke (null: keiner). */
    static Vergleich.Marke hoechster(Pegelreihe p) {
        Pegelreihe.Marke m = p.hoechster();
        return m == null ? null : new Vergleich.Marke(true, p.start.plusMillis(Math.round(m.t() * 1000)), m.pegel());
    }

    static BufferedImage bild(byte[] rgb, int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] px = new int[w * h];
        for (int i = 0; i < px.length; i++) px[i] = (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | rgb[3 * i + 2] & 0xFF;
        im.setRGB(0, 0, w, h, px, 0, w);
        return im;
    }

    static double zahl(String s) { return Double.parseDouble(s.strip().replace(',', '.')); }

    static String f3(double x) { return Double.isNaN(x) ? "-" : String.format(Locale.ROOT, "%.3f", x); }
}
