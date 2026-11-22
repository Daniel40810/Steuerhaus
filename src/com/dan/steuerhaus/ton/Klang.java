package com.dan.steuerhaus.ton;

import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Der Klang der Hallig (seit X5, Werkbuch HalligHooge 151, F245 A): synthetisch aus Rauschen und Filtern, ohne
 * Aufnahmen. Vier Stimmen, gesteuert aus {@link Werte}:
 * <ul>
 * <li><b>Wind:</b> weißes Rauschen durch einen Bandpass, dessen Mitte mit dem Wind steigt (180 Hz + 40 Hz je m/s), dazu
 * ein tiefes Rumpeln (Böen am Ohr); Böen als langsamer Zufallsgang (Ornstein-Uhlenbeck, 2,5 s); der Wind kommt von der
 * Seite, von der er weht (Stereo).</li>
 * <li><b>Brandung:</b> braunes Rauschen, tiefpassgefiltert, in Wellen alle 6–10 s (schneller Anstieg, langsames
 * Auslaufen) mit Gischt im Brechen; laut nur nah am Wasser.</li>
 * <li><b>Plätschern:</b> kleine Blasen am ruhigen Wasser, die platzen: je eine Sinusschwingung (500–2500 Hz), deren Ton
 * steigt und die in 6–20 ms abklingt.</li>
 * <li><b>Möwen:</b> Rufe aus vier Obertönen, die Tonhöhe fällt (kjau), mit Vibrato; zufällig, am Tag, weniger im
 * Sturm.</li>
 * </ul>
 * Die Werte gleiten (Zeitkonstante {@link #GLEITEN} s), damit ein Sprung der Kamera nicht knackt. Derselbe Saatwert gibt
 * dieselben Abtastwerte (für die Filme). Rechnen ohne Ausgabe; {@link Ausgang} spielt, {@link WavDatei} schreibt.
 */
public final class Klang {
    /**
     * Die Steuerung: Wind am Auge (m/s), woher er kommt gegen die Blickrichtung (Grad; 90: von rechts), Brandung,
     * Nähe zum Wasser, Tag, Möwen, Plätschern (je 0 … 1).
     */
    public record Werte(double wind, double windVon, double brandung, double nah, double tag, double moewen, double plaetschern) {
        public static final Werte STILL = new Werte(0, 0, 0, 0, 0, 0, 0);
    }

    /** Zeitkonstante, mit der die Werte gleiten (s) [G]. */
    public static final double GLEITEN = 0.6;

    public final int rate;
    private final SplittableRandom rnd;
    private volatile Werte ziel = Werte.STILL;
    // gleitende Werte
    private double wind, pan, brandung, nah, tag, moewen, plaetschern;
    // Wind
    private final Svf windL = new Svf(), windR = new Svf(), rumpelL = new Svf(), rumpelR = new Svf();
    private double boe;
    // Brandung
    private double braunL, braunR, phase = 0.3, periode = 8;
    private final Svf brandL = new Svf(), brandR = new Svf(), gischtL = new Svf(), gischtR = new Svf();
    // Plätschern
    private final List<double[]> tropfen = new ArrayList<>();
    // Möwen
    private final List<Ruf> rufe = new ArrayList<>();
    private int rufZahl;
    private long t;

    public Klang(long saat, int rate) {
        this.rate = rate;
        this.rnd = new SplittableRandom(saat);
        periode = 6 + 4 * rnd.nextDouble();
    }

    /** Neue Werte; sie gelten gleitend ab dem nächsten Rechnen. */
    public void setze(Werte w) { ziel = w == null ? Werte.STILL : w; }

    /** Wie viele Möwenrufe bisher (für die Prüfung). */
    public int rufe() { return rufZahl; }

    /** Rechnet n Abtastwerte je Kanal (−1 … 1) in links und rechts ab {@code ab}. */
    public void rechne(float[] links, float[] rechts, int ab, int n) {
        double dt = 1.0 / rate;
        for (int s = 0; s < n; s += 64) {
            int m = Math.min(64, n - s);
            gleiten(m * dt);
            double fWind = 180 + 40 * wind + 140 * boe, qWind = 0.7;
            double wLaut = Math.pow(Math.max(0, Math.min(1, (wind - 1.5) / 16)), 1.4) * 0.55;
            double gBoe = Math.max(0.2, Math.min(1.4, 0.65 + 0.35 * boe));
            double gl = Math.sqrt(0.5 * (1 - 0.6 * pan)), gr = Math.sqrt(0.5 * (1 + 0.6 * pan));
            double bLaut = brandung * nah * 0.7, pLaut = plaetschern * 0.5;
            for (int k = 0; k < m; k++) {
                int i = ab + s + k;
                t++;
                // ---- Wind
                boe += -boe * dt / 2.5 + 0.6 * Math.sqrt(dt) * gauss();
                double nl = weiss(), nr = weiss();
                double wl = windL.bandpass(nl, fWind, qWind, rate) + 0.8 * rumpelL.tiefpass(nl, 90, rate);
                double wr = windR.bandpass(nr, fWind, qWind, rate) + 0.8 * rumpelR.tiefpass(nr, 90, rate);
                double l = wLaut * gBoe * gl * wl * 2.2, r = wLaut * gBoe * gr * wr * 2.2;
                // ---- Brandung
                if (bLaut > 1e-4) {
                    phase += dt / periode;
                    if (phase >= 1) {
                        phase -= 1;
                        periode = 6 + 4 * rnd.nextDouble();
                    }
                    double welle = phase < 0.15 ? phase / 0.15 : Math.exp(-(phase - 0.15) * 4);
                    double env = 0.3 + 0.7 * welle;
                    braunL = 0.996 * braunL + 0.06 * weiss();
                    braunR = 0.996 * braunR + 0.06 * weiss();
                    double bl = brandL.tiefpass(braunL, 450 + 500 * welle, rate), br = brandR.tiefpass(braunR, 450 + 500 * welle, rate);
                    double gs = welle * welle * 0.45;
                    bl += gs * gischtL.bandpass(weiss(), 2500, 0.5, rate);
                    br += gs * gischtR.bandpass(weiss(), 2500, 0.5, rate);
                    l += bLaut * env * bl * 1.6;
                    r += bLaut * env * br * 1.6;
                }
                // ---- Plätschern: Blasen, die platzen (eine Sinusschwingung, deren Ton steigt und die rasch abklingt), 2 … 8 je s
                if (pLaut > 1e-3) {
                    if (rnd.nextDouble() < (2 + 6 * plaetschern) * dt)
                        tropfen.add(new double[] {0, 0.006 + 0.014 * rnd.nextDouble(), 0.2 + 0.8 * rnd.nextDouble(), rnd.nextDouble() * 1.6 - 0.8, 500 + 2000 * Math.pow(rnd.nextDouble(), 2), 0});
                    double tl = 0, tr = 0;
                    for (int j = tropfen.size() - 1; j >= 0; j--) {
                        double[] d = tropfen.get(j);
                        d[0] += dt;
                        if (d[0] > 6 * d[1]) {
                            tropfen.remove(j);
                            continue;
                        }
                        d[5] += 2 * Math.PI * d[4] * (1 + 0.15 * d[0] / d[1]) * dt;
                        double e = d[2] * Math.exp(-d[0] / d[1]) * Math.sin(d[5]);
                        tl += e * (1 - d[3]) * 0.5;
                        tr += e * (1 + d[3]) * 0.5;
                    }
                    l += pLaut * 0.35 * tl;
                    r += pLaut * 0.35 * tr;
                }
                // ---- Möwen
                if (moewen * tag > 1e-3 && rnd.nextDouble() < moewen * tag / 9.0 * dt) {
                    int z = 1 + rnd.nextInt(4);
                    double rp = rnd.nextDouble() * 1.4 - 0.7, laut = 0.10 + 0.18 * rnd.nextDouble(), f0 = 1500 + 400 * rnd.nextDouble();
                    for (int q = 0; q < z; q++) rufe.add(new Ruf(-q * (0.33 + 0.08 * rnd.nextDouble()), 0.26 + 0.18 * rnd.nextDouble(), f0 * (1 - 0.04 * q), rp, laut));
                    rufZahl += z;
                }
                for (int j = rufe.size() - 1; j >= 0; j--) {
                    Ruf u = rufe.get(j);
                    double v = u.schritt(dt, this);
                    if (u.fertig()) {
                        rufe.remove(j);
                        continue;
                    }
                    l += v * (1 - u.pan) * 0.5;
                    r += v * (1 + u.pan) * 0.5;
                }
                links[i] = (float) weich(l);
                rechts[i] = (float) weich(r);
            }
        }
    }

    private void gleiten(double dt) {
        Werte w = ziel;
        double a = 1 - Math.exp(-dt / GLEITEN);
        wind += a * (w.wind() - wind);
        double p = Math.sin(Math.toRadians(w.windVon()));
        pan += a * (p - pan);
        brandung += a * (w.brandung() - brandung);
        nah += a * (w.nah() - nah);
        tag += a * (w.tag() - tag);
        moewen += a * (w.moewen() - moewen);
        plaetschern += a * (w.plaetschern() - plaetschern);
    }

    /** Weich begrenzt auf −1 … 1. */
    static double weich(double x) { return x / Math.sqrt(1 + x * x); }

    double weiss() { return rnd.nextDouble() * 2 - 1; }

    private double gauss() {
        double u = Math.max(1e-12, rnd.nextDouble()), v = rnd.nextDouble();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }

    /** Ein Ruf einer Möwe: Start (s, negativ: wartet), Dauer, Grundton, Seite, Lautstärke. */
    private static final class Ruf {
        double t, phase;
        final double dauer, f0, pan, laut;

        Ruf(double t, double dauer, double f0, double pan, double laut) {
            this.t = t;
            this.dauer = dauer;
            this.f0 = f0;
            this.pan = pan;
            this.laut = laut;
        }

        boolean fertig() { return t > dauer; }

        double schritt(double dt, Klang k) {
            t += dt;
            if (t < 0) return 0;
            double x = t / dauer;
            // kjau: erst hoch, dann fallend auf gut die Hälfte, am Ende ein wenig zurück
            double f = f0 * (x < 0.15 ? 1 + 0.12 * x / 0.15 : 1.12 - 0.55 * Math.min(1, (x - 0.15) / 0.7) + (x > 0.85 ? 0.1 * (x - 0.85) / 0.15 : 0));
            f *= 1 + 0.03 * Math.sin(2 * Math.PI * 30 * t);
            phase += 2 * Math.PI * f * dt;
            double h = Math.sin(phase) + 0.5 * Math.sin(2 * phase) + 0.25 * Math.sin(3 * phase) + 0.12 * Math.sin(4 * phase);
            double env = Math.pow(Math.max(0, Math.sin(Math.PI * Math.min(1, x))), 0.6);
            return laut * env * h * (0.8 + 0.2 * k.weiss());
        }
    }

    /** Ein Filter mit Zustand (State Variable, Chamberlin): Tiefpass und Bandpass. */
    static final class Svf {
        private double lp, bp;

        double bandpass(double x, double fc, double q, int rate) {
            schritt(x, fc, q, rate);
            return bp;
        }

        double tiefpass(double x, double fc, int rate) {
            schritt(x, fc, 0.7, rate);
            return lp;
        }

        private void schritt(double x, double fc, double q, int rate) {
            double f = 2 * Math.sin(Math.PI * Math.min(fc, rate / 6.0) / rate);
            double damp = 1 / Math.max(0.3, q);
            lp += f * bp;
            double hp = x - lp - damp * bp;
            bp += f * hp;
            if (!Double.isFinite(lp) || !Double.isFinite(bp)) lp = bp = 0;
        }
    }
}
