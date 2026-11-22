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
 * <p>
 * Seit DB4 (Werkbuch HalligHooge 173): dazu der Wind des Modells ({@code wind/} des Archivs, ERA5 u10 und v10) an den
 * Windstationen {@code guete.wind} (Kennung beim DWD, Lage aus {@code gebiet/wind/wind.txt}) in der Zelle am Ort; liegt die
 * Station außerhalb des Gitters (Helgoland, rund 7 km südwestlich der Ecke von G2), gilt die nächste Zelle am Rand, bis
 * {@link #WIND_RAND} m: ERA5 hat Zellen von 0,25° (rund 28 × 17 km), der Wind über See ändert sich auf 7 km kaum.
 */
public final class Modellauszug {
    /** Höchster Abstand einer Zeit des Archivs zum Raster des Schritts (s) [G]. */
    static final long TOLERANZ = 30;
    /** Gültige Werte (m NHN) wie im Messarchiv (HH_MODELL_WERT_CK). */
    static final double UNTEN = -6, OBEN = 8;
    /** Seit DB4: so weit (m) darf eine Windstation außerhalb des Gitters des Winds liegen [G] (kleiner als eine Zelle von ERA5). */
    public static final double WIND_RAND = 10000;

    /** Seit DB4: eine Windstation aus {@code gebiet/wind/wind.txt} (ohne die Reihen). */
    public record Station(String name, String kennung, double laenge, double breite, double geber) { }

    private Modellauszug() { }

    public static Guete erzeugen(Manifest m, Szenen sz, Consumer<String> meldung) throws Exception {
        Guete.Auftrag ga = Guete.Auftrag.lies(m);
        if (ga == null || ga.modell() == null) throw new IllegalArgumentException("Güte: guete.datei und guete.modell fehlen im Manifest");
        if (ga.pegel().isEmpty()) throw new IllegalArgumentException("Güte: guete.pegel ist leer");
        File gebiet = sz.gebiet();
        Map<String, Pegel> pg = Pegel.lies(gebiet);
        Gebiet ge = Gebiet.lies(gebiet);
        File daten = gebiet.getParentFile();
        Map<String, Station> stationen = windstationen(gebiet);
        for (String k : ga.wind()) if (!stationen.containsKey(k)) meldung.accept("Güte: Windstation " + k + " nicht in " + gebiet + "/wind/wind.txt – übergangen");
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
        List<Guete.Wind> alleWinde = new ArrayList<>();
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
                // seit DB4: der Wind an den Stationen
                List<Guete.Wind> ws = new ArrayList<>();
                com.dan.core.gitter.Gitter wg = q.windGitter();
                if (wg == null) meldung.accept("Güte: " + laufSchl + " ohne Wind im Archiv");
                else for (String k : ga.wind()) {
                    Station st = stationen.get(k);
                    if (st == null) continue;
                    double[] u = Utm.vorwaerts(st.laenge(), st.breite(), 32);
                    int c = zelle(wg, u[0], u[1], st.laenge(), st.breite());
                    if (c < 0) {
                        meldung.accept(String.format(Locale.GERMAN, "Güte: %s · Wind %s liegt mehr als %.0f m außerhalb von %s – übergangen", laufSchl, st.name(), WIND_RAND, wg.name));
                        continue;
                    }
                    double[] zt2 = q.zeiten();
                    double[] uu = new double[zt2.length], vv = new double[zt2.length];
                    float[] uv = new float[2];
                    for (int i = 0; i < zt2.length; i++) {
                        if (q.windZelle(zt2[i], c, uv)) { uu[i] = uv[0]; vv[i] = uv[1]; }
                        else { uu[i] = Double.NaN; vv[i] = Double.NaN; }
                    }
                    Guete.Wind w = wind(laufSchl, st, u[0], u[1], wg, c, zt2, uu, vv, q.start, von, bis, schritt);
                    ws.add(w);
                    z.append(String.format(Locale.GERMAN, " Wind %s %d/%d", st.name(), w.ff().belegt(), w.ff().n()));
                }
                String archiv = relativ(daten, ar);
                Guete.Lauf l = new Guete.Lauf(laufSchl, namen, Guete.rein(archiv), Guete.rein(q.kopf.name), q.start, von, bis, schritt, "",
                        Guete.rein(q.kopf.antrieb.toString().replaceAll("^\\{|\\}$", "")), Guete.rein(q.kopf.module.toString().replaceAll("^\\{|\\}$", "")));
                l = l.mitKennung(Guete.kennung(l, es, ws));
                laeufe.add(l);
                alle.addAll(es);
                alleWinde.addAll(ws);
                meldung.accept(String.format(Locale.GERMAN, "Güte: %s (%s) %s bis %s, %d Stände je %d s, Pegel (Werte/Stände):%s; %.1f s", laufSchl, String.join(", ", namen), von, bis,
                        es.isEmpty() ? 0 : es.get(0).modell().n(), schritt, z, (System.nanoTime() - n0) / 1e9));
            }
        }
        if (laeufe.isEmpty()) throw new IllegalStateException("Güte: kein Archiv mit Ständen nach dem Einschwingen");
        Guete g = new Guete("Modell der App (Archive der Szenarien, " + ga.pegel().size() + " Pegel, tiefste Zelle im Umkreis von " + Math.round(ga.umkreis()) + " m)", Instant.now(),
                ga.einschwingen(), laeufe, alle, alleWinde);
        boolean neu = g.schreibe(ga.modell());
        meldung.accept("Güte: " + laeufe.size() + " Läufe, " + alle.size() + " Reihen des Wasserstands, " + alleWinde.size() + " des Winds → " + ga.modell()
                + (neu ? " (neu geschrieben)" : " (unverändert)"));
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

    /** Seit DB4: die Windstationen aus {@code gebiet/wind/wind.txt} nach Kennung (nur der Kopf, ohne die Reihen). */
    public static Map<String, Station> windstationen(File gebiet) throws java.io.IOException {
        Map<String, Station> m = new LinkedHashMap<>();
        File t = new File(new File(gebiet, "wind"), "wind.txt");
        if (!t.isFile()) return m;
        for (String z : java.nio.file.Files.readAllLines(t.toPath(), java.nio.charset.StandardCharsets.UTF_8)) {
            if (!z.startsWith("station ")) continue;
            String[] s = z.trim().split("\\s+", 8);
            m.put(s[2], new Station(s[1], s[2], Double.parseDouble(s[3]), Double.parseDouble(s[4]), Double.parseDouble(s[6])));
        }
        return m;
    }

    /**
     * Seit DB4: die Zelle eines Orts im Gitter des Winds; außerhalb die nächste Zelle am Rand, wenn sie höchstens
     * {@link #WIND_RAND} m entfernt ist, sonst -1. Ein Gitter in Grad nimmt Länge und Breite (ohne Grenze am Rand).
     */
    public static int zelle(Gitter g, double ost, double nord, double laenge, double breite) {
        boolean utm = g.lage == Gitter.Lage.UTM;
        double x = utm ? ost : laenge, y = utm ? nord : breite;
        int i = Math.max(0, Math.min(g.nx - 1, g.i(x))), j = Math.max(0, Math.min(g.ny - 1, g.j(y)));
        if (utm && !g.enthaelt(x, y) && Math.hypot(g.x(i) - x, g.y(j) - y) > WIND_RAND) return -1;
        return i + g.nx * j;
    }

    /** Seit DB4: Richtung, aus der der Wind kommt (Grad, 0 Nord, 90 Ost), aus u (nach Osten) und v (nach Norden). */
    public static double richtung(double u, double v) {
        double d = (Math.toDegrees(Math.atan2(-u, -v)) + 360) % 360;
        return d >= 359.9995 ? 0 : d;
    }

    /** Seit DB4: der Wind einer Station auf dem Raster wie das Wasser (öffentlich für die Prüfung ohne Archiv). */
    public static Guete.Wind wind(String lauf, Station st, double ost, double nord, Gitter g, int zelle, double[] t, double[] u, double[] v, Instant start, Instant von,
            Instant bis, int schritt) {
        int n = (int) ((bis.getEpochSecond() - von.getEpochSecond()) / schritt) + 1;
        float[] ff = new float[Math.max(0, n)], dd = new float[Math.max(0, n)];
        Arrays.fill(ff, Float.NaN);
        Arrays.fill(dd, Float.NaN);
        for (int i = 0; i < t.length; i++) {
            long s = start.getEpochSecond() + Math.round(t[i]) - von.getEpochSecond();
            if (s < -TOLERANZ) continue;
            long k = Math.round(s / (double) schritt);
            if (k < 0 || k >= n || Math.abs(s - k * schritt) > TOLERANZ || Double.isNaN(u[i]) || Double.isNaN(v[i])) continue;
            ff[(int) k] = (float) Math.hypot(u[i], v[i]);
            dd[(int) k] = (float) richtung(u[i], v[i]);
        }
        boolean utm = g.lage == Gitter.Lage.UTM;
        int ci = zelle % g.nx, cj = zelle / g.nx;
        double abstand = utm ? Math.hypot(g.x(ci) - ost, g.y(cj) - nord) : Double.NaN;
        return new Guete.Wind(lauf, st.kennung(), st.name(), ost, nord, st.geber(), g.name, zelle, abstand, new Guete.Reihe(von, schritt, ff), new Guete.Reihe(von, schritt, dd),
                Guete.Reihe.LEER, Guete.Reihe.LEER, Guete.Windzahlen.LEER);
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
