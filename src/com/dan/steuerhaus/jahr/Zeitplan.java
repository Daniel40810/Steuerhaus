package com.dan.steuerhaus.jahr;

import java.util.List;

/**
 * Der Zeitraffer eines Jahrs (seit Y3): jeder Abschnitt bekommt einen festen Teil der Spielzeit (Manifest
 * {@code jahr.dauer}, etwa 24 24 12 Sekunden), innerhalb des Abschnitts läuft die Zeit gleichmäßig. So zeigt die Minute
 * den Sturm (fünf Tage) langsamer als die Halbjahre (je 180 Tage). Ohne Angabe: im Verhältnis der Tage.
 */
public final class Zeitplan {
    private final double[] tag0, tag1, s0, s1;
    /** Spielzeit (s) des ganzen Jahrs. */
    public final double gesamt;

    /**
     * @param a      die Abschnitte der Mappe
     * @param anteil Sekunden je Abschnitt (nur das Verhältnis zählt); null oder falsche Länge: im Verhältnis der Tage
     * @param gesamt Spielzeit des Jahrs (s)
     */
    public Zeitplan(List<Jahresmappe.Abschnitt> a, double[] anteil, double gesamt) {
        int n = a.size();
        tag0 = new double[n];
        tag1 = new double[n];
        s0 = new double[n];
        s1 = new double[n];
        this.gesamt = gesamt;
        boolean eigen = anteil != null && anteil.length == n;
        double summe = 0;
        for (int k = 0; k < n; k++) summe += eigen ? Math.max(0, anteil[k]) : a.get(k).tage();
        double s = 0;
        for (int k = 0; k < n; k++) {
            tag0[k] = a.get(k).tag0();
            tag1[k] = a.get(k).tag1();
            double d = summe > 0 ? (eigen ? Math.max(0, anteil[k]) : a.get(k).tage()) / summe * gesamt : gesamt / Math.max(1, n);
            s0[k] = s;
            s += d;
            s1[k] = s;
        }
    }

    public int abschnitte() { return tag0.length; }

    /** Beginn und Ende des Abschnitts k in der Spielzeit (s). */
    public double von(int k) { return s0[k]; }

    public double bis(int k) { return s1[k]; }

    /** Der Abschnitt zur Spielzeit sek. */
    public int abschnitt(double sek) {
        for (int k = 0; k < s1.length; k++) if (sek < s1[k]) return k;
        return Math.max(0, s1.length - 1);
    }

    /** Modelltag zur Spielzeit sek (begrenzt). */
    public double tag(double sek) {
        if (tag0.length == 0) return 0;
        if (sek <= 0) return tag0[0];
        if (sek >= gesamt) return tag1[tag1.length - 1];
        int k = abschnitt(sek);
        double d = s1[k] - s0[k];
        return tag0[k] + (d > 0 ? (sek - s0[k]) / d : 0) * (tag1[k] - tag0[k]);
    }

    /** Spielzeit (s) zum Modelltag (begrenzt). */
    public double sekunde(double tag) {
        if (tag0.length == 0) return 0;
        if (tag <= tag0[0]) return 0;
        for (int k = 0; k < tag0.length; k++) {
            if (tag > tag1[k] && k < tag0.length - 1) continue;
            double t = tag1[k] - tag0[k];
            double f = t > 0 ? Math.max(0, Math.min(1, (tag - tag0[k]) / t)) : 0;
            return s0[k] + f * (s1[k] - s0[k]);
        }
        return gesamt;
    }
}
