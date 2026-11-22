package com.dan.steuerhaus.ausgabe;

import com.dan.core.gitter.Gebiet;
import com.dan.core.gitter.Gitter;
import com.dan.core.pruef.Pegel;
import com.dan.core.raum.Utm;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Guete;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.messung.Chronikbau;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Der Wasserstand des Modells an den Pegeln (seit DB3, Werkbuch HalligHooge 171; Aufruf {@code Ausgabe --guete}): je Archiv
 * der Szenarien (ein Archiv, das mehrere Szenarien teilen, einmal) und je Pegel aus {@code guete.pegel} die Reihe an der
 * Zelle mit dem tiefsten Grund im Umkreis {@code guete.umkreis} auf dem feinsten Gitter, das den Pegel enthält – dieselbe
 * Regel wie beim Tidenkalender ({@link Pegelreihe}, Umkreis 500 m wie {@code gezeiten.bezug}). Trockene Stände fehlen. Es
 * zählt erst, was {@code guete.einschwingen} Stunden nach dem Beginn des Laufs liegt. Dazu die Hoch- und Niedrigwasser
 * des Modells ({@link Pegelreihe#marken()}, Rückgang 0,1 m). Schreibt nach {@code guete.modell} (nur bei Änderung); der
 * Sammler ({@code Sammler --guete}) bringt es mit der Messung zusammen. Ohne Bühne, liest je Stand nur die Zelle.
 */
public final class Modellauszug {
    /** Höchster Abstand einer Zeit des Archivs zum Raster des Schritts (s) [G]. */
    static final long TOLERANZ = 30;
    /** Gültige Werte (m NHN) wie im Messarchiv (HH_MODELL_WERT_CK). */
    static final double UNTEN = -6, OBEN = 8;

    private Modellauszug() { }

    public static Guete erzeugen(Manifest m, Szenen sz, Consumer<String> meldung) throws Exception {
        Guete.Auftrag ga = Guete.Auftrag.lies(m);
        if (ga == null || ga.modell() == null) throw new IllegalArgumentException("Güte: guete.datei und guete.modell fehlen im Manifest");
        if (ga.pegel().isEmpty()) throw new IllegalArgumentException("Güte: guete.pegel ist leer");
        File gebiet = sz.gebiet();
        Map<String, Pegel> pg = Pegel.lies(gebiet);
        Gebiet ge = Gebiet.lies(gebiet);
        File daten = gebiet.getParentFile();
        // je Archiv einmal, in der Folge der Szenendatei
        Map<String, List<Szenario>> archive = new LinkedHashMap<>();
        Map<String, File> ordner = new LinkedHashMap<>();
        for (Szenario s : sz.szenarien()) {
            if (s.live() || s.archiv == null) continue;
            if (!s.archiv.isDirectory()) {
                meldung.accept("Güte: Archiv von " + s.schluessel + " fehlt (" + s.archiv + ") – übergangen");
                continue;
            }
            String k = s.archiv.getCanonicalPath();
            archive.computeIfAbsent(k, x -> new ArrayList<>()).add(s);
            ordner.put(k, s.archiv);
        }
        List<Guete.Lauf> laeufe = new ArrayList<>();
        List<Guete.Eintrag> alle = new ArrayList<>();
        for (Map.Entry<String, List<Szenario>> a : archive.entrySet()) {
            long n0 = System.nanoTime();
            File ar = ordner.get(a.getKey());
            List<String> namen = a.getValue().stream().map(s -> s.schluessel).toList();
            try (Archivquelle q = new Archivquelle(ar.toPath())) {
                double[] zt = q.zeiten();
                if (zt.length < 2) {
                    meldung.accept("Güte: " + ar + " hat " + zt.length + " Stände – übergangen");
                    continue;
                }
                int schritt = schritt(zt);
                Instant ab = q.start.plusSeconds(Math.round(ga.einschwingen() * 3600));
                Instant von = null, bis = null;
                for (double t : zt) {
                    Instant x = q.start.plusSeconds(Math.round(t));
                    if (x.isBefore(ab)) continue;
                    if (von == null) von = x;
                    bis = x;
                }
                if (von == null) {
                    meldung.accept(String.format(Locale.GERMAN, "Güte: %s endet vor dem Einschwingen (%.0f h) – übergangen", ar, ga.einschwingen()));
                    continue;
                }
                String laufSchl = namen.get(0);
                List<Guete.Eintrag> es = new ArrayList<>();
                StringBuilder z = new StringBuilder();
                for (String p : ga.pegel()) {
                    Pegel pe = pg.get(p);
                    if (pe == null) {
                        meldung.accept("Güte: Pegel " + p + " nicht im Gebiet (" + gebiet + "/pegel) – übergangen");
                        continue;
                    }
                    double[] u = Utm.vorwaerts(pe.laenge, pe.breite, 32);
                    Pegelreihe r;
                    try {
                        r = Pegelreihe.aus(q, q.start, ge, p, u[0], u[1], ga.umkreis());
                    } catch (IllegalArgumentException x) {
                        meldung.accept("Güte: " + laufSchl + " · " + p + ": " + x.getMessage() + " – übergangen");
                        continue;
                    }
                    Guete.Eintrag e = eintrag(laufSchl, p, Chronikbau.name(p), u[0], u[1], r, q.start, von, bis, schritt);
                    es.add(e);
                    z.append(String.format(Locale.GERMAN, " %s %d/%d", Chronikbau.name(p), e.modell().belegt(), e.modell().n()));
                }
                String archiv = relativ(daten, ar);
                Guete.Lauf l = new Guete.Lauf(laufSchl, namen, Guete.rein(archiv), Guete.rein(q.kopf.name), q.start, von, bis, schritt, "",
                        Guete.rein(q.kopf.antrieb.toString().replaceAll("^\\{|\\}$", "")), Guete.rein(q.kopf.module.toString().replaceAll("^\\{|\\}$", "")));
                l = l.mitKennung(Guete.kennung(l, es));
                laeufe.add(l);
                alle.addAll(es);
                meldung.accept(String.format(Locale.GERMAN, "Güte: %s (%s) %s bis %s, %d Stände je %d s, Pegel (Werte/Stände):%s; %.1f s", laufSchl, String.join(", ", namen), von, bis,
                        es.isEmpty() ? 0 : es.get(0).modell().n(), schritt, z, (System.nanoTime() - n0) / 1e9));
            }
        }
        if (laeufe.isEmpty()) throw new IllegalStateException("Güte: kein Archiv mit Ständen nach dem Einschwingen");
        Guete g = new Guete("Modell der App (Archive der Szenarien, " + ga.pegel().size() + " Pegel, tiefste Zelle im Umkreis von " + Math.round(ga.umkreis()) + " m)", Instant.now(),
                ga.einschwingen(), laeufe, alle);
        boolean neu = g.schreibe(ga.modell());
        meldung.accept("Güte: " + laeufe.size() + " Läufe, " + alle.size() + " Reihen → " + ga.modell() + (neu ? " (neu geschrieben)" : " (unverändert)"));
        return g;
    }

    /** Der Schritt des Archivs (s): der Median der Abstände, auf ganze Sekunden. */
    static int schritt(double[] zt) {
        double[] d = new double[zt.length - 1];
        for (int i = 1; i < zt.length; i++) d[i - 1] = zt[i] - zt[i - 1];
        Arrays.sort(d);
        return (int) Math.max(1, Math.round(d[d.length / 2]));
    }

    /**
     * Ein Pegel in einem Lauf aus seiner Reihe: Werte auf dem Raster von {@code von} bis {@code bis} im Schritt (eine Zeit des
     * Archivs zählt, wenn sie höchstens {@link #TOLERANZ} s neben dem Raster liegt); trocken oder außerhalb −6 … 8 m fehlt.
     */
    static Guete.Eintrag eintrag(String lauf, String pegel, String name, double ost, double nord, Pegelreihe r, Instant start, Instant von, Instant bis, int schritt) {
        return eintrag(lauf, pegel, name, ost, nord, r.gitter, r.zelle, r.grund, r.zeiten(), r.pegel(), r.marken(), start, von, bis, schritt);
    }

    /** Wie oben, aus Zeiten (s seit Start), Pegeln (NaN trocken) und Marken – öffentlich für die Prüfung ohne Archiv. */
    public static Guete.Eintrag eintrag(String lauf, String pegel, String name, double ost, double nord, Gitter g, int zelle, double grund, double[] t, double[] eta,
            List<Pegelreihe.Marke> mk, Instant start, Instant von, Instant bis, int schritt) {
        int n = (int) ((bis.getEpochSecond() - von.getEpochSecond()) / schritt) + 1;
        float[] w = new float[Math.max(0, n)];
        Arrays.fill(w, Float.NaN);
        for (int i = 0; i < t.length; i++) {
            long s = start.getEpochSecond() + Math.round(t[i]) - von.getEpochSecond();
            if (s < -TOLERANZ) continue;
            long k = Math.round(s / (double) schritt);
            if (k < 0 || k >= n || Math.abs(s - k * schritt) > TOLERANZ) continue;
            double v = eta[i];
            if (Double.isNaN(v) || v < UNTEN || v > OBEN) continue;
            w[(int) k] = (float) v;
        }
        List<Guete.Marke> marken = new ArrayList<>();
        for (Pegelreihe.Marke x : mk) {
            Instant z = start.plusSeconds(Math.round(x.t()));
            if (z.isBefore(von) || z.isAfter(bis)) continue;
            marken.add(new Guete.Marke(x.art() == Pegelreihe.Art.HOCHWASSER ? 'H' : 'N', z, Math.round(x.pegel() * 1000) / 1000.0));
        }
        int ci = zelle % g.nx, cj = zelle / g.nx;
        double abstand = Math.hypot(g.x(ci) - ost, g.y(cj) - nord);
        return new Guete.Eintrag(lauf, pegel, name, ost, nord, g.name, zelle, abstand, grund, new Guete.Reihe(von, schritt, w), marken, Guete.Reihe.LEER, List.of(), List.of(),
                Guete.Kennzahlen.LEER);
    }

    /** Der Pfad des Archivs relativ zum Ordner der Daten (sonst absolut). */
    static String relativ(File daten, File archiv) {
        try {
            return daten.getCanonicalFile().toPath().relativize(archiv.getCanonicalFile().toPath()).toString();
        } catch (Exception e) {
            return archiv.getPath();
        }
    }
}
