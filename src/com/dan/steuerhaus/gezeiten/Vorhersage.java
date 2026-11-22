package com.dan.steuerhaus.gezeiten;

import com.dan.core.astro.FAngle;
import com.dan.core.astro.Partialtide;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Die astronomische Vorhersage aus harmonischen Konstanten (seit Z1, Werkbuch HalligHooge 155):
 * {@code η(t) = Z₀ + Σ f·A·cos(V + u − G)} mit den Knotenfaktoren und Argumenten aus {@link Partialtide} – dieselbe
 * Rechnung wie {@code Harmonik.Ergebnis.wert}. Dazu Hoch- und Niedrigwasser (Extreme der Kurve, auf die Minute), Spring-
 * und Nipptide (wenn die Phasen von M2 und S2 zusammenfallen oder entgegengesetzt sind) und die Tage eines Kalenders.
 * Ohne Wind und Luftdruck.
 */
public final class Vorhersage {
    /** Eine Konstante: Partialtide, Amplitude (m), Phase G (Grad, Greenwich). */
    public record Konstante(Partialtide tide, double amplitude, double phaseGrad) { }

    public enum Art { HOCHWASSER, NIEDRIGWASSER }

    /** Ein Hoch- oder Niedrigwasser. */
    public record Extrem(Art art, Instant zeit, double hoehe) { }

    /** Spring- oder Nipptide (Zeitpunkt, an dem M2 und S2 in Phase bzw. gegenphasig sind). */
    public record Gang(boolean spring, Instant zeit) { }

    /** Ein Tag des Kalenders (Ortszeit): Hoch- und Niedrigwasser, mittlerer Tidenhub. */
    public record Tag(LocalDate datum, List<Extrem> extreme, double hub) { }

    /** Kleinster Unterschied zwischen benachbartem Hoch- und Niedrigwasser (m) [G], wie {@code Pegelreihe.ABSTAND}. */
    public static final double ABSTAND = 0.1;
    /** Schritt der Suche nach Extremen (s) [G]; verfeinert wird mit einer Parabel. */
    static final int SCHRITT = 300;

    public final double mittel;
    public final List<Konstante> konstanten;

    public Vorhersage(double mittel, List<Konstante> konstanten) {
        this.mittel = mittel;
        this.konstanten = Collections.unmodifiableList(new ArrayList<>(konstanten));
    }

    /** Wasserstand (m NHN) zur Zeit t. */
    public double wert(Instant t) {
        double[] g = Partialtide.grundargumente(t);
        double n = FAngle.norm360(-g[4]), y = mittel;
        for (Konstante k : konstanten) {
            double[] fu = k.tide.fu(n);
            y += fu[0] * k.amplitude * Math.cos(Math.toRadians(k.tide.argumentGrad(g) + fu[1] - k.phaseGrad));
        }
        return y;
    }

    public Konstante konstante(String name) {
        for (Konstante k : konstanten) if (k.tide.name.equalsIgnoreCase(name)) return k;
        return null;
    }

    /**
     * Hoch- und Niedrigwasser in [von, bis): Extreme der Kurve im Schritt von 5 min, mit einer Parabel durch drei Werte
     * auf die Sekunde verfeinert; Hoch und Niedrig wechseln sich ab, ein Paar unter {@link #ABSTAND} fällt weg (eine
     * Stauphase im Flachwasser ist kein eigenes Hochwasser).
     */
    public List<Extrem> extreme(Instant von, Instant bis) {
        long s0 = von.getEpochSecond() - 3 * 3600, s1 = bis.getEpochSecond() + 3 * 3600;
        int n = (int) ((s1 - s0) / SCHRITT) + 1;
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = wert(Instant.ofEpochSecond(s0 + (long) i * SCHRITT));
        List<Extrem> roh = new ArrayList<>();
        for (int i = 1; i + 1 < n; i++) {
            boolean hoch = y[i] > y[i - 1] && y[i] >= y[i + 1], tief = y[i] < y[i - 1] && y[i] <= y[i + 1];
            if (!hoch && !tief) continue;
            double a = y[i - 1], b = y[i], c = y[i + 1], d = a - 2 * b + c, x = d == 0 ? 0 : 0.5 * (a - c) / d;
            x = Math.max(-1, Math.min(1, x));
            Instant t = Instant.ofEpochSecond(s0 + Math.round((i + x) * SCHRITT));
            roh.add(new Extrem(hoch ? Art.HOCHWASSER : Art.NIEDRIGWASSER, t, wert(t)));
        }
        List<Extrem> l = bereinigt(roh);
        List<Extrem> r = new ArrayList<>();
        for (Extrem e : l) if (!e.zeit.isBefore(von) && e.zeit.isBefore(bis)) r.add(e);
        return r;
    }

    /** Hoch und Niedrig im Wechsel; von zwei gleichen das stärkere; Paare unter {@link #ABSTAND} weg. */
    static List<Extrem> bereinigt(List<Extrem> roh) {
        List<Extrem> l = new ArrayList<>(roh);
        boolean geaendert = true;
        while (geaendert) {
            geaendert = false;
            for (int i = 0; i + 1 < l.size(); i++) {
                Extrem a = l.get(i), b = l.get(i + 1);
                if (a.art == b.art) {
                    boolean ersterBleibt = a.art == Art.HOCHWASSER ? a.hoehe >= b.hoehe : a.hoehe <= b.hoehe;
                    l.remove(ersterBleibt ? i + 1 : i);
                    geaendert = true;
                    break;
                }
                if (Math.abs(a.hoehe - b.hoehe) < ABSTAND && i + 2 < l.size()) {
                    l.remove(i + 1);
                    l.remove(i);
                    geaendert = true;
                    break;
                }
            }
        }
        return l;
    }

    /** Die Tage [erster, erster + n) in der Zone: ihre Hoch- und Niedrigwasser, Tidenhub (Mittel aus benachbarten Paaren). */
    public List<Tag> tage(LocalDate erster, int n, ZoneId zone) {
        Instant von = erster.atStartOfDay(zone).toInstant(), bis = erster.plusDays(n).atStartOfDay(zone).toInstant();
        List<Extrem> alle = extreme(von.minusSeconds(12 * 3600), bis.plusSeconds(12 * 3600));
        List<Tag> r = new ArrayList<>();
        for (int d = 0; d < n; d++) {
            LocalDate dat = erster.plusDays(d);
            Instant a = dat.atStartOfDay(zone).toInstant(), b = dat.plusDays(1).atStartOfDay(zone).toInstant();
            List<Extrem> e = new ArrayList<>();
            double hub = 0;
            int z = 0;
            for (int i = 0; i < alle.size(); i++) {
                Extrem x = alle.get(i);
                if (x.zeit.isBefore(a) || !x.zeit.isBefore(b)) continue;
                e.add(x);
                if (x.art == Art.HOCHWASSER) {
                    for (int j : new int[] {i - 1, i + 1})
                        if (j >= 0 && j < alle.size()) {
                            hub += x.hoehe - alle.get(j).hoehe;
                            z++;
                        }
                }
            }
            r.add(new Tag(dat, e, z > 0 ? hub / z : Double.NaN));
        }
        return r;
    }

    /**
     * Spring- und Nipptiden in [von, bis): Zeitpunkte, an denen die Phase von S2 die von M2 einholt (Spring) oder ihr
     * gegenübersteht (Nipp); mit den Konstanten des Orts, also einschließlich des Alters der Gezeit. Ohne M2 oder S2: leer.
     */
    public List<Gang> gaenge(Instant von, Instant bis) {
        Konstante m2 = konstante("M2"), s2 = konstante("S2");
        List<Gang> r = new ArrayList<>();
        if (m2 == null || s2 == null) return r;
        long sc = 3600;
        double vor = Double.NaN;
        for (long s = von.getEpochSecond() - sc; s <= bis.getEpochSecond() + sc; s += sc) {
            double p = differenz(Instant.ofEpochSecond(s), m2, s2);
            if (!Double.isNaN(vor)) {
                for (int k = 0; k < 2; k++) {
                    double ziel = k == 0 ? 0 : 180;
                    double a = FAngle.norm180(vor - ziel), b = FAngle.norm180(p - ziel);
                    if (a < 0 && b >= 0 && b - a < 90) {
                        Instant t = Instant.ofEpochSecond(Math.round(s - sc + (-a / (b - a)) * sc));
                        if (!t.isBefore(von) && t.isBefore(bis)) r.add(new Gang(k == 0, t));
                    }
                }
            }
            vor = p;
        }
        return r;
    }

    /** Phase von S2 minus Phase von M2 am Ort (Grad); 0 bei Springtide. */
    static double differenz(Instant t, Konstante m2, Konstante s2) {
        double[] g = Partialtide.grundargumente(t);
        double n = FAngle.norm360(-g[4]);
        double pm = m2.tide.argumentGrad(g) + m2.tide.fu(n)[1] - m2.phaseGrad, ps = s2.tide.argumentGrad(g) + s2.tide.fu(n)[1] - s2.phaseGrad;
        return FAngle.norm360(ps - pm);
    }

    /** Das Alter der Gezeit (h): Verspätung der Springtide gegen Neu- und Vollmond, aus den Phasen von S2 und M2. */
    public double alterStunden() {
        Konstante m2 = konstante("M2"), s2 = konstante("S2");
        if (m2 == null || s2 == null) return Double.NaN;
        double d = FAngle.norm360(s2.phaseGrad - m2.phaseGrad);
        return d / (s2.tide.gradJeStunde() - m2.tide.gradJeStunde());
    }
}
