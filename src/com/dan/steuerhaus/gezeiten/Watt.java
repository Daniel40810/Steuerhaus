package com.dan.steuerhaus.gezeiten;

import com.dan.core.astro.Gestirne;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Rechnung des Watt-Fensters (seit Z3, Werkbuch HalligHooge 158): wann der Weg ins Watt trocken ist. Grundlage ist
 * die Gezeit des Tidenkalenders am Anleger (plus ein Windstau). Der Weg wird trocken, wenn der Wasserstand am Anleger nach
 * dem Hochwasser unter die Schwelle {@code ab} fällt, und wieder nass, wenn er nach dem Niedrigwasser über die Schwelle
 * {@code bis} steigt. Beide Schwellen kommen aus dem Modell: der Priel am Sattel des Wegs kreuzt dessen Höhe, am Anleger
 * steht das Wasser dann im Median so hoch. Das Watt läuft langsam leer ({@code ab} liegt tief) und schnell voll ({@code bis}
 * liegt wenig über der Höhe des Sattels). Modell, nicht zur Navigation.
 */
public final class Watt {
    /** Ein Fenster: trocken von – bis, das Niedrigwasser darin (Zeit, Höhe mit Windstau), wie viele Minuten davon bei Tag. */
    public record Fenster(Instant ab, Instant bis, Instant niedrig, double niedrigHoehe, long tagMinuten) {
        public Duration dauer() { return Duration.between(ab, bis); }

        public boolean ganzBeiTag() { return tagMinuten >= dauer().toMinutes() - 5; }

        public boolean ganzBeiNacht() { return tagMinuten <= 5; }

        public boolean enthaelt(Instant t) { return !t.isBefore(ab) && t.isBefore(bis); }
    }

    /** Höhe der Sonne, ab der Tag ist (Grad; Oberrand mit Brechung) [G]. */
    public static final double TAG = -0.833;

    public final Vorhersage v;
    public final double ab, bis, sattel, laenge, breite;

    public Watt(Vorhersage v, double schwelleAb, double schwelleBis, double sattel, double laenge, double breite) {
        this.v = v;
        this.ab = schwelleAb;
        this.bis = schwelleBis;
        this.sattel = sattel;
        this.laenge = laenge;
        this.breite = breite;
    }

    /** Gezeit am Anleger mit Windstau (m NHN). */
    public double wert(Instant t, double stau) { return v.wert(t) + stau; }

    /** Die Fenster, die [von, bis) berühren, je Niedrigwasser eines (ohne, wenn die Gezeit nicht unter {@code ab} fällt). */
    public List<Fenster> fenster(Instant von, Instant bis, double stau) {
        List<Fenster> l = new ArrayList<>();
        for (Vorhersage.Extrem e : v.extreme(von.minusSeconds(10 * 3600), bis.plusSeconds(10 * 3600))) {
            if (e.art() != Vorhersage.Art.NIEDRIGWASSER || e.hoehe() + stau >= ab) continue;
            Instant a = kreuzung(e.zeit(), -1, ab, stau), b = kreuzung(e.zeit(), 1, this.bis, stau);
            if (a == null || b == null || !b.isAfter(a) || !b.isAfter(von) || !a.isBefore(bis)) continue;
            l.add(new Fenster(a, b, e.zeit(), e.hoehe() + stau, tagMinuten(a, b)));
        }
        return l;
    }

    /** Ob der Weg zur Zeit t trocken ist. */
    public boolean trocken(Instant t, double stau) {
        for (Fenster f : fenster(t.minusSeconds(3600), t.plusSeconds(3600), stau)) if (f.enthaelt(t)) return true;
        return false;
    }

    /** Das Fenster zur Zeit t oder das nächste danach (in 30 h); null ohne. */
    public Fenster jetztOderNaechstes(Instant t, double stau) {
        for (Fenster f : fenster(t.minusSeconds(3600), t.plusSeconds(30 * 3600), stau)) if (f.bis().isAfter(t)) return f;
        return null;
    }

    /**
     * Der Wasserstand für die Karte (m NHN): die Gezeit, aber im Fenster höchstens knapp unter dem Sattel und außerhalb
     * mindestens knapp darüber – so steht die Karte im Einklang mit dem Fenster (das Watt läuft langsamer leer als die
     * Gezeit fällt).
     */
    public double kartenStand(Instant t, double stau) {
        double w = wert(t, stau);
        return trocken(t, stau) ? Math.min(w, sattel - 0.01) : Math.max(w, sattel + 0.01);
    }

    /** Die Zeit, zu der die Gezeit vom Niedrigwasser aus (richtung −1: davor, +1: danach) die Höhe h kreuzt; auf 1 s. */
    Instant kreuzung(Instant nw, int richtung, double h, double stau) {
        long a = nw.getEpochSecond(), b = a;
        boolean gefunden = false;
        for (int i = 1; i <= 8 * 12; i++) {
            b = a + richtung * 300L * i;
            if (wert(Instant.ofEpochSecond(b), stau) >= h) {
                gefunden = true;
                break;
            }
        }
        if (!gefunden) return null;
        long innen = b - richtung * 300L, aussen = b;
        while (Math.abs(aussen - innen) > 1) {
            long m = (innen + aussen) / 2;
            if (wert(Instant.ofEpochSecond(m), stau) >= h) aussen = m;
            else innen = m;
        }
        return Instant.ofEpochSecond(richtung < 0 ? innen : aussen);
    }

    /** Minuten mit der Sonne über {@link #TAG} in [a, b), in Schritten von 5 min. */
    public long tagMinuten(Instant a, Instant b) {
        long n = 0;
        for (Instant t = a; t.isBefore(b); t = t.plusSeconds(300)) if (sonne(t.plusSeconds(150)) > TAG) n += 5;
        return Math.min(n, Duration.between(a, b).toMinutes());
    }

    /** Höhe der Sonne über der Kimm (Grad). */
    public double sonne(Instant t) { return Gestirne.bei(t).sonne(laenge, breite).hoeheGrad(); }
}
