package com.dan.steuerhaus.messung;

import java.util.Arrays;

/**
 * Ausreißer in einer Reihe des Wasserstands je Minute verwerfen (seit DB1, Werkbuch HalligHooge 167), wie
 * {@code aufbereitung.Pegelonline} den Bestand gereinigt hat: außerhalb der Grenzen [G], oder mehr als {@link #spitze}
 * neben dem Median der gültigen Werte im Fenster ± {@link #fenster} Minuten. Die Grenzen sind dieselben wie die
 * Prüfregel der Spalte WERT in {@code HH_ROH} (01_tabellen.sql). Nur java.*.
 */
public final class Ausreisser {
    /** Grenzen (m NHN) [G], Spitze (m) [G], Fenster (Minuten, halbe Breite) [G]. */
    public double unten = -6, oben = 8, spitze = 0.3;
    public int fenster = 15;

    /** Verworfen: außerhalb der Grenzen, Spitzen. */
    public record Ergebnis(int grenzen, int spitzen) {
        public int zusammen() { return grenzen + spitzen; }
    }

    /** Setzt verworfene Werte auf NaN (in {@code x}, gleicher Schritt) und zählt sie. */
    public Ergebnis filtern(float[] x) {
        int n = x.length, aussen = 0, spitzen = 0;
        for (int k = 0; k < n; k++)
            if (!Float.isNaN(x[k]) && (x[k] < unten || x[k] > oben)) {
                x[k] = Float.NaN;
                aussen++;
            }
        float[] y = x.clone();
        double[] f = new double[2 * fenster + 1];
        for (int k = 0; k < n; k++) {
            if (Float.isNaN(x[k])) continue;
            int m = 0;
            for (int q = Math.max(0, k - fenster); q <= Math.min(n - 1, k + fenster); q++) if (!Float.isNaN(x[q])) f[m++] = x[q];
            Arrays.sort(f, 0, m);
            double med = m % 2 == 1 ? f[m / 2] : 0.5 * (f[m / 2 - 1] + f[m / 2]);
            if (Math.abs(x[k] - med) > spitze) {
                y[k] = Float.NaN;
                spitzen++;
            }
        }
        System.arraycopy(y, 0, x, 0, n);
        return new Ergebnis(aussen, spitzen);
    }
}
