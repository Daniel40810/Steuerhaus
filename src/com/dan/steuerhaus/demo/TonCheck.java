package com.dan.steuerhaus.demo;

import com.dan.lauf.Buehne;
import com.dan.steuerhaus.ton.Ereignisklang;

import java.util.Locale;

/**
 * Prüfstein 38, Teil 2 (Seegang Werkbuch, Datei 104, S8c): Laufzeit und Pegel der Stimmen des {@link Ereignisklang}. Ein Brecher,
 * ein Überlauf und das Typhon geradeaus vor dem Ohr in verschiedenen Abständen, je mit derselben Saat neu gerechnet (48 kHz):
 * <ul>
 * <li><b>Laufzeit:</b> der Versatz, bei dem das Signal im Abstand d mit dem im nächsten Abstand d₁ am besten übereinstimmt –
 * Kreuzkorrelation der Abtastwerte ± 50 ms um die Erwartung; beim Typhon (ein periodischer Ton, 220 Hz) erst grob, wo die
 * Hüllkurve 10 % ihrer Spitze erreicht, dann die Abtastwerte ± 2 ms –
 * gegen (d − d₁) / c, ± 1 ms.</li>
 * <li><b>Pegel:</b> Energie unter 1 kHz (Butterworth 2. Ordnung; darüber dämpft die Luft in soundscape mit dem Abstand, das ist
 * gewollt und steht als Auskunft dabei) über {@link #FENSTER} s ab dem Einsatz gegen 1/r – der Unterschied zum nächsten Abstand
 * gegen 20 lg(d / d₁), ± 0,5 dB. Abstände so, dass die nächste Stimme unter dem Begrenzer bleibt.</li>
 * </ul>
 * Je Bild höchstens {@link Ereignisklang#STIMMEN} Ereignisse und nur im Umkreis: zehn Brecher zugleich, davon spielen acht.
 * Der Teil 1 (je Brecher genau ein Ereignis) steht in {@code com.dan.lauf.demo.SeegangS8cdCheck}. Ohne Karte, ohne Bühne.
 */
public final class TonCheck {
    static int steine, verstoesse;
    static final int RATE = 48000;
    static final double FENSTER = 2.0;

    public static void main(String[] args) {
        System.out.println("TonCheck – Prüfstein 38, Laufzeit und Pegel (Seegang Werkbuch 104, S8c)");
        try {
            for (String art : new String[] {"Brecher", "Überlauf", "Typhon"}) laufzeitUndPegel(art);
            stimmen();
        } catch (Throwable x) {
            x.printStackTrace();
            pruefe(false, "Ablauf", x.toString(), "ohne Fehler");
        }
        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    /** Ein Ereignis der Art in d m geradeaus (Nord) vor dem Ohr (1,5 m NHN, Blick nach Nord); Summe links + rechts. */
    static float[] eins(String art, double d, double sekunden) {
        Ereignisklang k = new Ereignisklang(1885, RATE);
        if (!k.passt()) throw new IllegalStateException("soundscape rechnet nicht mit " + RATE + " Hz");
        k.setze(null, 0, 0, 1.5, 0);
        k.spiele(new Buehne.Ton(1, art, 0, d, 1.5, 0.8, 0));
        int n = (int) (sekunden * RATE), block = 960;
        float[] l = new float[n], r = new float[n];
        for (int ab = 0; ab < n; ab += block) k.rechne(l, r, ab, Math.min(block, n - ab));
        for (int i = 0; i < n; i++) l[i] += r[i];
        return l;
    }

    /** Einsatz des nächsten Signals: erster Abtastwert über 10⁻² der Spitze (es liegt hoch über dem Grund von soundscape). */
    static int einsatz(float[] x) {
        double sp = 0;
        for (float v : x) sp = Math.max(sp, Math.abs(v));
        for (int i = 0; i < x.length; i++) if (Math.abs(x[i]) > 1e-2 * sp) return i;
        return -1;
    }

    /** Versatz (Abtastwerte), bei dem y ab e0 + v am besten mit x ab e0 übereinstimmt; tonal: grob nach der Hüllkurve, dann ± 2 ms. */
    static int versatz(float[] x, float[] y, int e0, int erwartet, boolean tonal) {
        if (!tonal) return korrelation(x, y, e0, erwartet, (int) (0.05 * RATE));
        int grob = anstieg(huelle(y)) - anstieg(huelle(x));
        return korrelation(x, y, e0, grob, (int) (0.002 * RATE));
    }

    /** Erster Abtastwert, an dem die Hüllkurve 10 % ihrer Spitze erreicht. */
    static int anstieg(float[] h) {
        double sp = 0;
        for (float v : h) sp = Math.max(sp, v);
        for (int i = 0; i < h.length; i++) if (h[i] >= 0.1 * sp) return i;
        return 0;
    }

    static int korrelation(float[] x, float[] y, int e0, int mitte, int such) {
        int w = (int) (0.5 * RATE), best = mitte;
        double bestK = Double.NEGATIVE_INFINITY;
        for (int v = mitte - such; v <= mitte + such; v++) {
            if (e0 + v < 0 || e0 + v + w > y.length || e0 + w > x.length) continue;
            double k = 0;
            for (int i = 0; i < w; i++) k += (double) x[e0 + i] * y[e0 + v + i];
            if (k > bestK) { bestK = k; best = v; }
        }
        return best;
    }

    /** Hüllkurve: Betrag, geglättet über 1 ms (vor- und rückwärts, ohne Versatz). */
    static float[] huelle(float[] x) {
        float[] h = new float[x.length];
        double a = Math.exp(-1.0 / (0.001 * RATE)), z = 0;
        for (int i = 0; i < x.length; i++) h[i] = (float) (z = a * z + (1 - a) * Math.abs(x[i]));
        z = 0;
        for (int i = x.length - 1; i >= 0; i--) h[i] = (float) (z = a * z + (1 - a) * h[i]);
        return h;
    }

    /** Tiefpass 2. Ordnung (Butterworth, Bilinear) bei fc Hz. */
    static float[] tief(float[] x, double fc) {
        double k = Math.tan(Math.PI * fc / RATE), q = Math.sqrt(0.5), n = 1 / (1 + k / q + k * k);
        double b0 = k * k * n, b1 = 2 * b0, a1 = 2 * (k * k - 1) * n, a2 = (1 - k / q + k * k) * n;
        float[] y = new float[x.length];
        double x1 = 0, x2 = 0, y1 = 0, y2 = 0;
        for (int i = 0; i < x.length; i++) {
            double v = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2;
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v;
            y[i] = (float) v;
        }
        return y;
    }

    static double energie(float[] x, int ab) {
        double e = 0;
        for (int i = Math.max(0, ab); i < Math.min(x.length, ab + (int) (FENSTER * RATE)); i++) e += (double) x[i] * x[i];
        return e;
    }

    static void laufzeitUndPegel(String art) {
        double c = new Ereignisklang(1, RATE).s.soundSpeed();
        double[] ab = art.equals("Typhon") ? new double[] {300, 600, 1200, 2400} : new double[] {20, 40, 80, 160, 280};
        double dauer = (ab[ab.length - 1] - ab[0]) / c + 4;
        float[][] w = new float[ab.length][];
        for (int i = 0; i < ab.length; i++) w[i] = eins(art, ab[i], dauer);
        int e0 = einsatz(w[0]);
        double sp0 = 0;
        for (float v : w[0]) sp0 = Math.max(sp0, Math.abs(v));
        double lzMax = 0, pgMax = 0;
        float[] t0 = tief(w[0], 1000);
        StringBuilder sb = new StringBuilder(), sbL = new StringBuilder();
        for (int i = 1; i < ab.length; i++) {
            int v = versatz(w[0], w[i], e0, (int) Math.round((ab[i] - ab[0]) / c * RATE), art.equals("Typhon"));
            double lz = (double) v / RATE - (ab[i] - ab[0]) / c;
            double pg = 10 * Math.log10(energie(t0, e0) / energie(tief(w[i], 1000), e0 + v)) - 20 * Math.log10(ab[i] / ab[0]);
            double luft = 10 * Math.log10(energie(w[0], e0) / energie(w[i], e0 + v)) - 20 * Math.log10(ab[i] / ab[0]) - pg;
            sbL.append(String.format(Locale.GERMAN, "%s%.0f m %+.2f dB", sbL.length() > 0 ? ", " : "", ab[i], luft));
            lzMax = Math.max(lzMax, Math.abs(lz));
            pgMax = Math.max(pgMax, Math.abs(pg));
            sb.append(String.format(Locale.GERMAN, "%s%.0f m: %+.2f ms, %+.2f dB", sb.length() > 0 ? "; " : "", ab[i], 1000 * lz, pg));
        }
        info(art + ": Einsatz im nächsten Abstand", String.format(Locale.GERMAN, "%.0f m: %.1f ms (Laufzeit %.1f ms, c = %.1f m/s); Spitze %.3f", ab[0], 1000.0 * e0 / RATE, 1000 * ab[0] / c, c, sp0));
        pruefe(lzMax <= 0.001, art + ": Laufzeit gegen Abstand / c (Prüfstein 38)", sb.toString().replaceAll(", [+-][0-9,]+ dB", ""), "± 1 ms");
        pruefe(pgMax <= 0.5, art + ": Pegel unter 1 kHz gegen 1/r (Prüfstein 38)", sb.toString().replaceAll("[+-][0-9,]+ ms, ", ""), "± 0,5 dB");
        info(art + ": dazu die Luft über alle Frequenzen", sbL.toString());
    }

    /** Zehn Brecher zugleich im Umkreis, einer außerhalb: acht spielen, zwei zu viele, einer weit. */
    static void stimmen() {
        Ereignisklang k = new Ereignisklang(7, RATE);
        k.setze(null, 0, 0, 1.5, 0);
        java.util.List<Buehne.Ton> l = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) l.add(new Buehne.Ton(i + 1, "Brecher", 30 * i - 135, 60 + 10 * i, 1.5, 0.5, 0));
        l.add(new Buehne.Ton(11, "Brecher", 0, 400, 1.5, 1, 0));
        k.setzeFuerPruefung(l, 0, 0, 1.5);
        pruefe(k.gespielt == Ereignisklang.STIMMEN && k.zuViele == 2 && k.weit == 1, "Stimmen: höchstens acht je Bild, nur im Umkreis (R22)",
                String.format(Locale.GERMAN, "gespielt %d, zu viele %d, weit %d", k.gespielt, k.zuViele, k.weit), "8, 2, 1");
    }

    static void info(String g, String w) { System.out.println("         " + g + ": " + w); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  VERSTOSS ") + g + ": " + w + "  [" + grenze + "]");
    }
}
