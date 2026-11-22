package com.dan.steuerhaus.ausgabe;

import com.dan.film.Film;
import com.dan.film.Format;
import com.dan.film.Stock;

/**
 * Filmkorn für Video und Standbild (seit W4, Werkbuch HalligHooge 121, F203 A), nach {@code com.dan.film}: Selwyn,
 * Material Vision3 250D auf Kopie 2383, Bildfenster Super 35 (2,39 : 1). Das Bild der Bühne hat seine eigene Filmkurve;
 * das Korn sitzt darum dort, wo der Film es hätte, in den Dichten von Negativ und Kopie, und wird in die Helligkeit des
 * Bildschirms umgerechnet:
 * <ol>
 * <li>Je Kanal c und Helligkeit L (linear, 0 … 1) sucht eine Tafel das Grau h, für das {@link Film#develop} in Kanal c
 * dieselbe Helligkeit gibt, und nimmt dessen Dichten d_N (Negativ) und d_P (Kopie).</li>
 * <li>Die Streuung der Dichte nach Selwyn: σ_N = RMS/1000 · Schicht · √(A48 / A) · √(d_N − D_min,N), σ_P ebenso mit der
 * Kopie; A ist die Fläche eines Bildpunkts im Negativ ({@link Film#grainArea}), nie kleiner als eine Farbstoffwolke.</li>
 * <li>Die Änderung der Helligkeit je Dichte (∂L/∂d_N, ∂L/∂d_P) aus {@link Film#project} und {@link Film#printDensity},
 * mit kleinen Schritten gerechnet.</li>
 * <li>Je Bildpunkt: L' = L + Stärke · (∂L/∂d_N · σ_N · n_c + ∂L/∂d_P · σ_P · m_c); n_c = 0,88 g_c + 0,47 g_alle (Schichten
 * leicht verbunden, ρ ≈ 0,22, wie {@code film.frag}), g und m unabhängig normalverteilt, je Filmbild neu. Wolken größer
 * als ein Bildpunkt (bei 4K) bilinear über Zellen, die Streuung ausgeglichen (wie {@code film.frag}).</li>
 * </ol>
 * Das Rauschen ist eine feste Funktion von Ort, Kanal, Bildnummer und Saat: Dieselbe Folge gibt dasselbe Korn.
 */
public final class Korn {
    /** Stufen der Tafel über √L (feiner im Dunklen). */
    static final int STUFEN = 1024;
    public final Film film = new Film();
    public final double staerke;
    public final long saat;
    final int breite, hoehe;
    final double wolkeX, wolkeY;
    /** Je Kanal und Stufe: Änderung von L je Einheit des Rauschens, aus Negativ und aus Kopie. */
    final float[][] gNeg = new float[3][STUFEN], gPrt = new float[3][STUFEN];

    /**
     * @param breite Breite des Bilds (Bildpunkte); das Bildfenster Super 35 deckt sie
     * @param staerke 1 = wie Vision3 250D; 0 = aus
     */
    public Korn(int breite, int hoehe, double staerke, long saat) {
        this.breite = breite;
        this.hoehe = hoehe;
        this.staerke = staerke;
        this.saat = saat;
        film.negative = Stock.vision3_250D();
        film.print = Stock.print2383();
        film.format = Format.SUPER35_239;
        double a = film.grainArea(breite);
        double[] cl = film.grainCloudPx(breite);
        wolkeX = cl[0];
        wolkeY = cl[1];
        Stock n = film.negative, p = film.print;
        for (int c = 0; c < 3; c++)
            for (int i = 0; i < STUFEN; i++) {
                double s = (i + 0.5) / STUFEN, L = s * s;
                double lh = suche(c, L);
                double h = Math.pow(10, lh);
                double[] dn = film.negativeDensity(film.layerExposure(h, h, h));
                double[] dp = film.printDensity(dn);
                double e = 1e-3;
                double[] dn1 = dn.clone();
                dn1[c] += e;
                double dLdN = (film.project(film.printDensity(dn1))[c] - film.project(dp)[c]) / e;
                double[] dp1 = dp.clone();
                dp1[c] += e;
                double dLdP = (film.project(dp1)[c] - film.project(dp)[c]) / e;
                double sN = film.grainSigma(n, c, dn[c] - n.curves()[c].dmin(), a);
                double sP = p.rms() / 1000 * Math.sqrt(Film.A48 / a) * Math.sqrt(Math.max(0.05, dp[c] - p.curves()[c].dmin()));
                gNeg[c][i] = (float) (dLdN * sN);
                gPrt[c][i] = (float) (dLdP * sP);
            }
    }

    /** log₁₀ des Graus, für das develop in Kanal c die Helligkeit L gibt (Halbierung; develop steigt mit h). */
    private double suche(int c, double L) {
        double lo = -6, hi = 3;
        for (int k = 0; k < 60; k++) {
            double m = 0.5 * (lo + hi), h = Math.pow(10, m);
            if (film.develop(h, h, h)[c] < L) lo = m; else hi = m;
        }
        return 0.5 * (lo + hi);
    }

    /** Erwartete Streuung von L (linear) durch das Korn bei Helligkeit L in Kanal c (für Prüfstein 4). */
    public double sigma(int c, double L) {
        int i = stufe(L);
        double a = gNeg[c][i], b = gPrt[c][i];
        // n_c = 0,88 g + 0,47 g_alle: Streuung √(0,88² + 0,47²) = 0,9977
        return staerke * Math.sqrt(a * a * (0.88 * 0.88 + 0.47 * 0.47) + b * b);
    }

    private static int stufe(double L) { return Math.max(0, Math.min(STUFEN - 1, (int) (Math.sqrt(Math.max(0, L)) * STUFEN))); }

    /** Legt das Korn des Filmbilds {@code bild} auf das lineare Bild (RGB je Bildpunkt, zeilenweise); Zeilen parallel. */
    public void anwenden(float[] rgb, long bild) {
        if (staerke <= 0) return;
        boolean zellen = wolkeX > 1.05 || wolkeY > 1.05;
        // Wolken größer als ein Bildpunkt: das Rauschen der Zellen vorher, je Kanal (0–2) und für alle (3)
        int zx = zellen ? (int) Math.ceil(breite / Math.max(1, wolkeX)) + 2 : 0, zy = zellen ? (int) Math.ceil(hoehe / Math.max(1, wolkeY)) + 2 : 0;
        float[][] z = zellen ? new float[4][zx * zy] : null;
        if (zellen)
            java.util.stream.IntStream.range(0, zy).parallel().forEach(j -> {
                for (int i = 0; i < zx; i++)
                    for (int ch = 0; ch < 4; ch++) z[ch][i + zx * j] = (float) gauss(hash(i, j, bild * 8 + ch));
            });
        java.util.stream.IntStream.range(0, hoehe).parallel().forEach(y -> {
            double[] n = new double[4];
            for (int x = 0; x < breite; x++) {
                int i = 3 * (x + breite * y);
                if (zellen) zellenRauschen(z, zx, x, y, n);
                else for (int ch = 0; ch < 4; ch++) n[ch] = gauss(hash(x, y, bild * 8 + ch));
                for (int c = 0; c < 3; c++) {
                    float L = rgb[i + c];
                    int s = stufe(L);
                    double nc = 0.88 * n[c] + 0.47 * n[3], m = gauss(hash(x + 7919, y + 104729, bild * 8 + 4 + c));
                    rgb[i + c] = (float) (L + staerke * (gNeg[c][s] * nc + gPrt[c][s] * m));
                }
            }
        });
    }

    /** Rauschen mit Streuung 1 auf Zellen der Größe der Farbstoffwolke (bilinear, ausgeglichen), wie {@code film.frag}. */
    private void zellenRauschen(float[][] z, int zx, int x, int y, double[] n) {
        double cx = Math.max(1, wolkeX), cy = Math.max(1, wolkeY);
        double qx = (x + 0.5) / cx, qy = (y + 0.5) / cy;
        int ix = (int) Math.floor(qx), iy = (int) Math.floor(qy);
        double tx = wolkeX <= 1.05 ? 0 : qx - ix, ty = wolkeY <= 1.05 ? 0 : qy - iy;
        double f = (wolkeX > 1.05 ? 1.2247449 : 1) * (wolkeY > 1.05 ? 1.2247449 : 1);
        int k = ix + zx * iy;
        for (int ch = 0; ch < 4; ch++) {
            float[] g = z[ch];
            double a = g[k], b = g[k + 1], c = g[k + zx], d = g[k + zx + 1];
            n[ch] = ((a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty) * f;
        }
    }

    private long hash(long x, long y, long k) {
        long h = saat * 0x9E3779B97F4A7C15L ^ x * 0xBF58476D1CE4E5B9L ^ y * 0x94D049BB133111EBL ^ k * 0xD6E8FEB86659FD93L;
        h ^= h >>> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >>> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return h;
    }

    /** Normalverteilt aus 64 Bit: Tafel der Umkehrung der Normalverteilung, 2¹⁶ Stufen, linear zwischen zwei Stufen. */
    private static double gauss(long h) {
        int i = (int) (h >>> 48);
        double t = ((h >>> 24) & 0xFFFFFF) / (double) (1 << 24);
        return NORMAL[i] + (NORMAL[i + 1] - NORMAL[i]) * t;
    }

    private static final double[] NORMAL = new double[(1 << 16) + 1];

    static {
        for (int i = 0; i <= 1 << 16; i++) NORMAL[i] = umkehr((Math.min(i, (1 << 16) - 1) + 0.5) / (1 << 16));
    }

    /** Umkehrung der Normalverteilung (Acklam, Fehler unter 1,2 · 10⁻⁹). */
    static double umkehr(double p) {
        double[] a = {-3.969683028665376e+01, 2.209460984245205e+02, -2.759285104469687e+02, 1.383577518672690e+02, -3.066479806614716e+01, 2.506628277459239e+00};
        double[] b = {-5.447609879822406e+01, 1.615858368580409e+02, -1.556989798598866e+02, 6.680131188771972e+01, -1.328068155288572e+01};
        double[] c = {-7.784894002430293e-03, -3.223964580411365e-01, -2.400758277161838e+00, -2.549732539343734e+00, 4.374664141464968e+00, 2.938163982698783e+00};
        double[] d = {7.784695709041462e-03, 3.224671290700398e-01, 2.445134137142996e+00, 3.754408661907416e+00};
        double q, r;
        if (p < 0.02425) {
            q = Math.sqrt(-2 * Math.log(p));
            return (((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        if (p > 1 - 0.02425) {
            q = Math.sqrt(-2 * Math.log(1 - p));
            return -(((((c[0] * q + c[1]) * q + c[2]) * q + c[3]) * q + c[4]) * q + c[5]) / ((((d[0] * q + d[1]) * q + d[2]) * q + d[3]) * q + 1);
        }
        q = p - 0.5;
        r = q * q;
        return (((((a[0] * r + a[1]) * r + a[2]) * r + a[3]) * r + a[4]) * r + a[5]) * q / (((((b[0] * r + b[1]) * r + b[2]) * r + b[3]) * r + b[4]) * r + 1);
    }
}
