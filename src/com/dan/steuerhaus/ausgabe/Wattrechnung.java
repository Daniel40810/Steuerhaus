package com.dan.steuerhaus.ausgabe;

import com.dan.core.gitter.Gebiet;
import com.dan.core.raum.Utm;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Karte;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Vorhersage;
import com.dan.steuerhaus.gezeiten.Watt;
import com.dan.steuerhaus.gezeiten.Wattauftrag;
import com.dan.steuerhaus.gezeiten.Watttafel;

import java.io.File;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Consumer;

/**
 * Rechnet den Weg ins Watt für das Watt-Fenster (seit Z3, Werkbuch HalligHooge 158; Aufruf {@code Ausgabe --watt}, ohne
 * Bühne):
 * <ol>
 * <li><b>Gelände:</b> die Karte des Gebiets im Rechteck {@code watt.karte} ({@link Karte}: DGM1 und TrilaWatt, Zelle
 * 5 m).</li>
 * <li><b>Sattel:</b> von allen Wegen vom Start zur höchsten Stelle des Ziels der, dessen tiefste Stelle am höchsten liegt
 * (Engpass-Suche, acht Nachbarn). Diese Höhe ist der Sattel: darunter ist ein trockener Weg da, darüber keiner.</li>
 * <li><b>Weg:</b> der kürzeste Weg über Zellen nicht tiefer als der Sattel; gespeichert alle rund 25 m mit Höhe.</li>
 * <li><b>Schwellen:</b> in den Läufen {@code watt.modell} der Wasserstand am Priel beim Sattel (tiefste Zelle im
 * Umkreis {@code watt.priel}); wo er die Höhe des Sattels kreuzt, der Wasserstand am Anleger im selben Lauf. Der Median
 * der fallenden ist die Schwelle „trocken ab“, der der steigenden „nass ab“ [G]. Die App legt sie an die Gezeit des
 * Kalenders (plus Windstau).</li>
 * <li><b>Prüfung:</b> je Lauf mit den Schwellen der anderen Läufe am Anleger desselben Laufs, Zeit gegen den Priel;
 * zur Auskunft dasselbe mit der Gezeit des Kalenders (dann mit dem Wind der Läufe).</li>
 * </ol>
 */
public final class Wattrechnung {
    /** Abstand der gespeicherten Punkte des Wegs (m) [G]. */
    static final double PUNKT = 25;

    private Wattrechnung() { }

    public static Watttafel erzeugen(Manifest m, Szenen sz, Consumer<String> meldung) throws Exception {
        long n0 = System.nanoTime();
        Wattauftrag wa = Wattauftrag.lies(m);
        Gezeitenauftrag ga = Gezeitenauftrag.lies(m);
        if (wa == null || wa.karte() == null) throw new IllegalArgumentException("Watt: watt.start, watt.ziel und watt.karte im Manifest");
        if (ga == null) throw new IllegalArgumentException("Watt: gezeiten.* fehlt (die Gezeit kommt vom Tidenkalender)");
        Gezeitentafel gt = Gezeitentafel.lies(ga.datei());
        Vorhersage vo = gt.vorhersage();
        File gebiet = sz.gebiet();

        // ---- Gelände
        double[] r = wa.karte();
        double z = wa.zelle();
        Karte k = Karte.aus(gebiet, r, z);
        int nx = k.nx, ny = k.ny;
        float[] h = new float[nx * ny];
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) h[i + nx * j] = k.hoehe(r[0] + (i + 0.5) * z, r[1] + (j + 0.5) * z);
        meldung.accept(String.format(Locale.GERMAN, "Watt: Karte %d × %d (%.0f m) in %.1f s", nx, ny, z, (System.nanoTime() - n0) / 1e9));
        int s = zelle(wa.startOst(), wa.startNord(), r, z, nx, ny), t0 = zelle(wa.zielOst(), wa.zielNord(), r, z, nx, ny);
        if (s < 0 || t0 < 0) throw new IllegalArgumentException("Watt: Start oder Ziel außerhalb von watt.karte");
        int t = t0, rz = (int) Math.ceil(wa.zielUmkreis() / z);
        for (int dj = -rz; dj <= rz; dj++)
            for (int di = -rz; di <= rz; di++) {
                int ii = t0 % nx + di, jj = t0 / nx + dj;
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny || Math.hypot(di * z, dj * z) > wa.zielUmkreis()) continue;
                int c = ii + nx * jj;
                if (!Float.isNaN(h[c]) && (Float.isNaN(h[t]) || h[c] > h[t])) t = c;
            }

        // ---- Sattel: Engpass-Suche (der Weg, dessen tiefste Stelle am höchsten liegt)
        float zs = engpass(h, nx, ny, s, t);
        // ---- Weg: kürzester über Zellen nicht tiefer als der Sattel
        List<Integer> weg = kuerzester(h, nx, ny, s, t, zs - 1e-4f, z);
        if (weg == null) throw new IllegalStateException("Watt: kein Weg vom Start zum Ziel");
        List<Watttafel.Punkt> punkte = new ArrayList<>();
        double strecke = 0, letzte = -1e9;
        int iSattel = 0;
        for (int i = 0; i < weg.size(); i++) if (h[weg.get(i)] < h[weg.get(iSattel)]) iSattel = i;
        for (int i = 0; i < weg.size(); i++) {
            int c = weg.get(i);
            if (i > 0) {
                int p = weg.get(i - 1);
                strecke += Math.hypot((c % nx - p % nx) * z, (c / nx - p / nx) * z);
            }
            if (i == 0 || i == weg.size() - 1 || i == iSattel || strecke - letzte >= PUNKT) {
                punkte.add(new Watttafel.Punkt(strecke, r[0] + (c % nx + 0.5) * z, r[1] + (c / nx + 0.5) * z, h[c]));
                letzte = strecke;
            }
        }
        int cs = weg.get(iSattel);
        double so = r[0] + (cs % nx + 0.5) * z, sn = r[1] + (cs / nx + 0.5) * z;
        double[] ll = Utm.rueckwaerts(so, sn, 32);
        meldung.accept(String.format(Locale.GERMAN, "Watt: Sattel %+.3f m NHN bei O %.0f, N %.0f; Weg %.0f m von %s (%+.2f m) nach %s (%+.2f m)", zs, so, sn, strecke, wa.start(),
                h[s], wa.ziel(), h[t]));

        // ---- Schwellen aus dem Modell
        Gebiet ge = Gebiet.lies(gebiet);
        List<Watttafel.Ereignis> er = new ArrayList<>();
        Map<String, Instant[]> spanne = new LinkedHashMap<>();
        Map<String, double[][]> anleger = new LinkedHashMap<>();
        Map<String, Instant> startLauf = new LinkedHashMap<>();
        Map<String, String> modelle = new LinkedHashMap<>();
        StringBuilder priel = new StringBuilder();
        for (String key : wa.modell()) {
            Szenario sc = sz.szenario(key);
            if (sc == null || sc.archiv == null) {
                meldung.accept("Watt: Lauf " + key + " fehlt oder ist live – übergangen");
                continue;
            }
            try (Archivquelle q = new Archivquelle(sc.archiv.toPath())) {
                Pegelreihe pr = Pegelreihe.aus(q, q.start, ge, "Priel", so, sn, wa.priel());
                Pegelreihe pa = Pegelreihe.aus(q, q.start, ge, ga.ort(), ga.ost(), ga.nord(), ga.umkreis());
                double[] zt = pr.zeiten(), va = new double[zt.length];
                for (int j = 0; j < zt.length; j++) va[j] = pa.bei(zt[j]);
                anleger.put(key, new double[][] {zt, va});
                startLauf.put(key, q.start);
                double ab0 = ga.einschwingen() * 3600;
                int n = 0;
                for (int j = 1; j < zt.length; j++) {
                    if (zt[j - 1] < ab0) continue;
                    double v0 = stand(pr, zt[j - 1]), v1 = stand(pr, zt[j]);
                    if ((v0 - zs) * (v1 - zs) >= 0) continue;
                    double f = (zs - v0) / (v1 - v0), tc = zt[j - 1] + f * (zt[j] - zt[j - 1]);
                    Instant ti = q.start.plusMillis(Math.round(tc * 1000));
                    er.add(new Watttafel.Ereignis(key, v1 < v0, ti, pa.bei(tc), vo.wert(ti)));
                    n++;
                }
                spanne.put(key, new Instant[] {q.start.plusMillis(Math.round(Math.max(ab0, zt[0]) * 1000)), q.start.plusMillis(Math.round(zt[zt.length - 1] * 1000))});
                modelle.put(key, sc.name);
                if (priel.length() == 0) priel.append(String.format(Locale.ROOT, "Gitter %s, Zelle %d, Grund %.2f m", pr.gitter.name, pr.zelle, pr.grund));
                meldung.accept(String.format(Locale.GERMAN, "Watt: Lauf %s – %d Stände am Priel (Grund %+.2f m), %d Kreuzungen des Sattels", key, zt.length, pr.grund, n));
            }
        }
        double[] fa = anlegerStaende(er, true, null), fb = anlegerStaende(er, false, null);
        if (fa.length < 3 || fb.length < 3) throw new IllegalStateException("Watt: zu wenig Kreuzungen im Modell (" + fa.length + " fallend, " + fb.length + " steigend)");
        double zAb = median(fa), zBis = median(fb);
        meldung.accept(String.format(Locale.GERMAN, "Watt: Schwellen am Anleger – trocken ab %+.3f m (%d, Quartile %+.2f … %+.2f), nass ab %+.3f m (%d, %+.2f … %+.2f)", zAb, fa.length,
                quantil(fa, 0.25), quantil(fa, 0.75), zBis, fb.length, quantil(fb, 0.25), quantil(fb, 0.75)));

        // ---- Prüfung: je Lauf mit den Schwellen der anderen, am Anleger desselben Laufs; zur Auskunft mit dem Kalender
        Map<String, String> kopf = new LinkedHashMap<>();
        List<Double> dAb = new ArrayList<>(), dBis = new ArrayList<>(), eAb = new ArrayList<>(), eBis = new ArrayList<>();
        int pn = 0;
        Watt wk = new Watt(vo, zAb, zBis, zs, ll[0], ll[1]);
        for (String key : spanne.keySet()) {
            double[] ra = anlegerStaende(er, true, key), rb = anlegerStaende(er, false, key);
            if (ra.length == 0 || rb.length == 0) continue;
            double[][] an = anleger.get(key);
            Instant st = startLauf.get(key), sp[] = spanne.get(key);
            List<Watt.Fenster> fk = wk.fenster(sp[0], sp[1], 0);
            List<Double> la = new ArrayList<>(), lb = new ArrayList<>();
            for (Watttafel.Ereignis e : er) {
                if (!e.lauf().equals(key)) continue;
                double te = (e.zeit().toEpochMilli() - st.toEpochMilli()) / 1000.0;
                double tp = kreuzung(an[0], an[1], e.ab() ? median(ra) : median(rb), e.ab(), te);
                if (!Double.isNaN(tp)) {
                    double d = (te - tp) / 60;
                    (e.ab() ? la : lb).add(d);
                    (e.ab() ? dAb : dBis).add(d);
                }
                Double dk = abstand(fk, e);
                if (dk != null) (e.ab() ? eAb : eBis).add(dk);
            }
            kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "%s: Schwellen der anderen Läufe %.3f / %.3f m; trocken ab %s, nass ab %s (Minuten, Priel gegen Anleger im Lauf)", key,
                    median(ra), median(rb), liste(la), liste(lb)));
        }
        kopf.put("wert.watt.weg", String.format(Locale.ROOT, "%.1f %.3f %.3f %.3f", strecke, zs, h[s], h[t]));
        kopf.put("wert.watt.schwellen", String.format(Locale.ROOT, "%d %d %.3f %.3f %.3f %.3f", fa.length, fb.length, quantil(fa, 0.25), quantil(fa, 0.75), quantil(fb, 0.25),
                quantil(fb, 0.75)));
        kopf.put("wert.watt.kreuz", String.format(Locale.ROOT, "%d %.1f %.1f %d %.1f %.1f", dAb.size(), medianBetrag(dAb), maxBetrag(dAb), dBis.size(), medianBetrag(dBis),
                maxBetrag(dBis)));
        kopf.put("wert.watt.kalender", String.format(Locale.ROOT, "%d %.1f %.1f %d %.1f %.1f", eAb.size(), medianBetrag(eAb), maxBetrag(eAb), eBis.size(), medianBetrag(eBis),
                maxBetrag(eBis)));
        kopf.put("priel", priel.toString());
        for (Map.Entry<String, String> e : modelle.entrySet()) kopf.put("modell." + e.getKey(), e.getValue());
        kopf.put("quelle.1", "Gelände: DGM1 © GeoBasis-DE/LVermGeo SH/CC BY 4.0; Watt: TrilaWatt 2021, Bundesanstalt für Wasserbau (CC BY 4.0); nicht für die Navigation");
        kopf.put("gerechnet", Instant.now().toString());
        for (int i = 1; i <= pn; i++) meldung.accept("Watt: " + kopf.get("pruef." + i));
        meldung.accept(String.format(Locale.GERMAN, "Watt: je Lauf mit den Schwellen der anderen – trocken ab |%.0f| min im Median (höchstens %.0f), nass ab |%.0f| min (höchstens %.0f)",
                medianBetrag(dAb), maxBetrag(dAb), medianBetrag(dBis), maxBetrag(dBis)));
        meldung.accept(String.format(Locale.GERMAN, "Watt: Fenster aus dem Kalender gegen die Läufe (mit ihrem Wind) – trocken ab |%.0f| min (höchstens %.0f), nass ab |%.0f| min (höchstens %.0f)",
                medianBetrag(eAb), maxBetrag(eAb), medianBetrag(eBis), maxBetrag(eBis)));
        er.sort(Comparator.comparing(Watttafel.Ereignis::zeit));
        Watttafel.schreibe(wa.datei(), wa.start(), wa.startOst(), wa.startNord(), wa.ziel(), r[0] + (t % nx + 0.5) * z, r[1] + (t / nx + 0.5) * z, h[t], so, sn, zs, ll[0], ll[1],
                zAb, zBis, r, z, nx, ny, punkte, er, kopf, h, k.bild(null));
        meldung.accept(String.format(Locale.GERMAN, "Watt: %s geschrieben (%d Punkte, %d Ereignisse) in %.1f s", wa.datei(), punkte.size(), er.size(), (System.nanoTime() - n0) / 1e9));
        return Watttafel.lies(wa.datei());
    }

    // ------------------------------------------------------------------ Weg

    static int zelle(double ost, double nord, double[] r, double z, int nx, int ny) {
        int i = (int) Math.floor((ost - r[0]) / z), j = (int) Math.floor((nord - r[1]) / z);
        return i < 0 || j < 0 || i >= nx || j >= ny ? -1 : i + nx * j;
    }

    static final int[] DI = {1, -1, 0, 0, 1, 1, -1, -1}, DJ = {0, 0, 1, -1, 1, -1, 1, -1};

    /** Die höchste tiefste Stelle aller Wege von s nach t (acht Nachbarn); NaN-Zellen sind gesperrt. */
    public static float engpass(float[] h, int nx, int ny, int s, int t) {
        float[] b = new float[h.length];
        Arrays.fill(b, Float.NEGATIVE_INFINITY);
        PriorityQueue<long[]> q = new PriorityQueue<>((x, y) -> Float.compare(Float.intBitsToFloat((int) y[1]), Float.intBitsToFloat((int) x[1])));
        b[s] = h[s];
        q.add(new long[] {s, Float.floatToIntBits(h[s])});
        while (!q.isEmpty()) {
            long[] e = q.poll();
            int c = (int) e[0];
            float v = Float.intBitsToFloat((int) e[1]);
            if (v < b[c]) continue;
            if (c == t) return v;
            int ci = c % nx, cj = c / nx;
            for (int d = 0; d < 8; d++) {
                int ii = ci + DI[d], jj = cj + DJ[d];
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                int nn = ii + nx * jj;
                if (Float.isNaN(h[nn])) continue;
                float nb = Math.min(v, h[nn]);
                if (nb > b[nn]) {
                    b[nn] = nb;
                    q.add(new long[] {nn, Float.floatToIntBits(nb)});
                }
            }
        }
        return b[t];
    }

    /** Der kürzeste Weg von s nach t über Zellen nicht tiefer als {@code min}; null ohne. */
    public static List<Integer> kuerzester(float[] h, int nx, int ny, int s, int t, float min, double z) {
        double[] dist = new double[h.length];
        int[] von = new int[h.length];
        Arrays.fill(dist, Double.MAX_VALUE);
        dist[s] = 0;
        PriorityQueue<double[]> p = new PriorityQueue<>(Comparator.comparingDouble(x -> x[1]));
        p.add(new double[] {s, 0});
        while (!p.isEmpty()) {
            double[] e = p.poll();
            int c = (int) e[0];
            if (e[1] > dist[c]) continue;
            if (c == t) break;
            int ci = c % nx, cj = c / nx;
            for (int d = 0; d < 8; d++) {
                int ii = ci + DI[d], jj = cj + DJ[d];
                if (ii < 0 || jj < 0 || ii >= nx || jj >= ny) continue;
                int nn = ii + nx * jj;
                if (Float.isNaN(h[nn]) || h[nn] < min) continue;
                double nd = dist[c] + (d < 4 ? z : z * Math.sqrt(2));
                if (nd < dist[nn]) {
                    dist[nn] = nd;
                    von[nn] = c;
                    p.add(new double[] {nn, nd});
                }
            }
        }
        if (dist[t] == Double.MAX_VALUE) return null;
        List<Integer> w = new ArrayList<>();
        for (int c = t; ; c = von[c]) {
            w.add(c);
            if (c == s) break;
        }
        Collections.reverse(w);
        return w;
    }

    // ------------------------------------------------------------------ Modell

    /** Wasserstand der Zelle; trocken zählt als knapp unter ihrem Grund. */
    static double stand(Pegelreihe p, double t) {
        double v = p.bei(t);
        return Double.isNaN(v) ? p.grund - 0.05 : v;
    }

    /** Der Wasserstand am Anleger im Modell zu den Ereignissen einer Art (ohne den Lauf {@code ohne}, null: alle). */
    public static double[] anlegerStaende(List<Watttafel.Ereignis> er, boolean ab, String ohne) {
        return er.stream().filter(e -> e.ab() == ab && (ohne == null || !e.lauf().equals(ohne)) && !Double.isNaN(e.anleger())).mapToDouble(Watttafel.Ereignis::anleger).toArray();
    }

    /** Die Zeit (s), zu der die Reihe die Höhe h fallend (oder steigend) kreuzt, nächst {@code nahe} (höchstens 4 h weg); NaN ohne. */
    public static double kreuzung(double[] z, double[] v, double h, boolean fallend, double nahe) {
        double best = Double.NaN;
        for (int j = 1; j < z.length; j++) {
            if (Double.isNaN(v[j - 1]) || Double.isNaN(v[j]) || (v[j] < v[j - 1]) != fallend || (v[j - 1] - h) * (v[j] - h) > 0 || v[j] == v[j - 1]) continue;
            double tc = z[j - 1] + (h - v[j - 1]) / (v[j] - v[j - 1]) * (z[j] - z[j - 1]);
            if (Math.abs(tc - nahe) < 4 * 3600 && (Double.isNaN(best) || Math.abs(tc - nahe) < Math.abs(best - nahe))) best = tc;
        }
        return best;
    }

    /** Minuten vom nächsten Rand gleicher Art eines Fensters bis zum Ereignis (positiv: das Modell ist später); null ohne Fenster in 3 h. */
    static Double abstand(List<Watt.Fenster> f, Watttafel.Ereignis e) {
        Double best = null;
        for (Watt.Fenster x : f) {
            Instant rand = e.ab() ? x.ab() : x.bis();
            double d = (e.zeit().getEpochSecond() - rand.getEpochSecond()) / 60.0;
            if (Math.abs(d) <= 180 && (best == null || Math.abs(d) < Math.abs(best))) best = d;
        }
        return best;
    }

    public static double median(double[] a) { return quantil(a, 0.5); }

    public static double quantil(double[] a, double q) {
        if (a.length == 0) return Double.NaN;
        double[] s = a.clone();
        Arrays.sort(s);
        double p = q * (s.length - 1);
        int i = (int) Math.floor(p);
        return i + 1 < s.length ? s[i] + (p - i) * (s[i + 1] - s[i]) : s[i];
    }

    static double medianBetrag(List<Double> l) { return median(l.stream().mapToDouble(Math::abs).toArray()); }

    static double maxBetrag(List<Double> l) { return l.stream().mapToDouble(Math::abs).max().orElse(Double.NaN); }

    static String liste(List<Double> l) {
        StringBuilder b = new StringBuilder();
        for (double d : l) b.append(b.length() > 0 ? " " : "").append(String.format(Locale.ROOT, "%+.0f", d));
        return b.length() == 0 ? "–" : b.toString();
    }
}
