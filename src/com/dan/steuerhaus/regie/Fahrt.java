package com.dan.steuerhaus.regie;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Eine Kamerafahrt aus Schlüsselbildern (seit W3, Werkbuch HalligHooge 116, F192/F195 A). Die Zeit ist Filmzeit: Sekunden
 * seit dem Beginn der Fahrt. Zwischen den Schlüsselbildern läuft jede Größe auf einer kubischen Hermite-Kurve in der
 * Zeit, die Tangenten wie bei Catmull-Rom aus den Nachbarn ((p₊ − p₋) / (t₊ − t₋)); am Anfang und am Ende ist die
 * Tangente null, die Fahrt beginnt und endet also sanft. Die Richtung wird vorher entfaltet: von 359° nach 1° geht es
 * über 0°, nicht zurück über 180°.
 * <p>
 * Die Kurve trifft jedes Schlüsselbild genau (Prüfstein 1). Höhen „über Boden“ löst {@link #aufgeloest} einmal in m NHN
 * auf; dazwischen läuft die Höhe dann frei (die Fahrt folgt nicht jeder Bodenwelle).
 */
public final class Fahrt {
    /** Ein Schlüsselbild: Filmzeit (s), Lage (UTM, m), Höhe (m NHN oder über Boden), Richtung, Neigung, Bildwinkel (Grad). */
    public record Schluesselbild(double t, double ost, double nord, double hoehe, boolean ueberBoden, double richtung, double neigung, double bildwinkel) {
        /** Als Zeile eines Regiebuchs. */
        public String zeile() {
            return String.format(Locale.ROOT, "%s %.2f %.2f %.2f %s %.2f %.2f %.2f", Regiebuch.zeit(t), ost, nord, hoehe, ueberBoden ? "boden" : "nhn", richtung, neigung, bildwinkel);
        }
    }

    /** Höhe des Bodens an einem Ort (m NHN; NaN: unbekannt, gilt als 0). */
    @FunctionalInterface
    public interface Boden {
        double hoehe(double ost, double nord);
    }

    public final String name;
    private final List<Schluesselbild> bilder;
    private final double[] t;
    /** Je Größe (Ost, Nord, Höhe, Richtung entfaltet, Neigung, Bildwinkel) die Werte und die Tangenten. */
    private final double[][] wert, tangente;

    public Fahrt(String name, List<Schluesselbild> bilder) {
        if (bilder.isEmpty()) throw new IllegalArgumentException("Fahrt " + name + ": kein Schlüsselbild");
        List<Schluesselbild> l = new ArrayList<>(bilder);
        l.sort((a, b) -> Double.compare(a.t, b.t));
        for (int i = 1; i < l.size(); i++)
            if (l.get(i).t <= l.get(i - 1).t) throw new IllegalArgumentException("Fahrt " + name + ": zwei Schlüsselbilder zur Zeit " + Regiebuch.zeit(l.get(i).t));
        this.name = name;
        this.bilder = List.copyOf(l);
        int n = l.size();
        t = new double[n];
        wert = new double[6][n];
        double richtung = 0;
        for (int i = 0; i < n; i++) {
            Schluesselbild s = l.get(i);
            t[i] = s.t;
            wert[0][i] = s.ost;
            wert[1][i] = s.nord;
            wert[2][i] = s.hoehe;
            richtung = i == 0 ? s.richtung : richtung + kurz(s.richtung - richtung);
            wert[3][i] = richtung;
            wert[4][i] = s.neigung;
            wert[5][i] = s.bildwinkel;
        }
        tangente = new double[6][n];
        for (int k = 0; k < 6; k++)
            for (int i = 1; i + 1 < n; i++) tangente[k][i] = (wert[k][i + 1] - wert[k][i - 1]) / (t[i + 1] - t[i - 1]);
    }

    /** Der kürzeste Weg von einem Winkel zum anderen (−180 … 180). */
    static double kurz(double d) {
        d = ((d % 360) + 540) % 360 - 180;
        return d == -180 ? 180 : d;
    }

    public List<Schluesselbild> bilder() { return bilder; }

    /** Filmzeit des ersten und letzten Schlüsselbilds. */
    public double anfang() { return t[0]; }

    public double ende() { return t[t.length - 1]; }

    /** Hat die Fahrt Höhen über Boden? */
    public boolean mitBoden() {
        for (Schluesselbild s : bilder) if (s.ueberBoden) return true;
        return false;
    }

    /** Dieselbe Fahrt mit allen Höhen in m NHN. */
    public Fahrt aufgeloest(Boden boden) {
        if (!mitBoden()) return this;
        List<Schluesselbild> l = new ArrayList<>();
        for (Schluesselbild s : bilder) {
            double h = s.hoehe;
            if (s.ueberBoden) {
                double b = boden.hoehe(s.ost, s.nord);
                h += Double.isNaN(b) ? 0 : b;
            }
            l.add(new Schluesselbild(s.t, s.ost, s.nord, h, false, s.richtung, s.neigung, s.bildwinkel));
        }
        return new Fahrt(name, l);
    }

    /**
     * Der Blick zur Filmzeit tf: {Ost, Nord, Höhe, Richtung (0 … 360), Neigung, Bildwinkel}. Vor dem ersten und nach dem
     * letzten Schlüsselbild steht die Kamera dort. Höhen über Boden gelten hier als NHN; vorher {@link #aufgeloest}.
     */
    public double[] lage(double tf) {
        int n = t.length;
        double[] r = new double[6];
        if (n == 1 || tf <= t[0]) {
            for (int k = 0; k < 6; k++) r[k] = wert[k][0];
        } else if (tf >= t[n - 1]) {
            for (int k = 0; k < 6; k++) r[k] = wert[k][n - 1];
        } else {
            int i = 0;
            while (i + 2 < n && t[i + 1] <= tf) i++;
            double h = t[i + 1] - t[i], s = (tf - t[i]) / h, s2 = s * s, s3 = s2 * s;
            double h00 = 2 * s3 - 3 * s2 + 1, h10 = s3 - 2 * s2 + s, h01 = -2 * s3 + 3 * s2, h11 = s3 - s2;
            for (int k = 0; k < 6; k++) r[k] = h00 * wert[k][i] + h10 * h * tangente[k][i] + h01 * wert[k][i + 1] + h11 * h * tangente[k][i + 1];
        }
        r[3] = ((r[3] % 360) + 360) % 360;
        return r;
    }

    /** Ein Flug in {@code dauer} s von einem Blick zum anderen (je {Ost, Nord, Höhe NHN, Richtung, Neigung, Bildwinkel}). */
    public static Fahrt flug(String name, double[] von, double[] nach, double dauer) {
        return new Fahrt(name, List.of(new Schluesselbild(0, von[0], von[1], von[2], false, von[3], von[4], von[5]),
                new Schluesselbild(dauer, nach[0], nach[1], nach[2], false, nach[3], nach[4], nach[5])));
    }

    /**
     * Die Zeit bis zum nächsten Schlüsselbild beim Aufnehmen [G]: höchstens 40 m/s, höchstens 25°/s Drehung, mindestens
     * 2 s.
     */
    public static double dauer(double[] von, double[] nach) {
        double weg = Math.sqrt(Math.pow(nach[0] - von[0], 2) + Math.pow(nach[1] - von[1], 2) + Math.pow(nach[2] - von[2], 2));
        double dreh = Math.max(Math.abs(kurz(nach[3] - von[3])), Math.abs(nach[4] - von[4]));
        return Math.max(2, Math.max(weg / 40, dreh / 25));
    }

    @Override public String toString() { return name + " (" + bilder.size() + " Schlüsselbilder, " + Regiebuch.zeit(ende() - anfang()) + ")"; }
}
