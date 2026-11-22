package com.dan.steuerhaus.messung;

import java.util.ArrayList;
import java.util.List;

/**
 * Hoch- und Niedrigwasser in einer gemessenen Reihe (seit DB1, Werkbuch HalligHooge 167): Die Rohwerte je Minute tragen
 * Seegang, Schwall und Zentimeter-Rauschen. Gesucht wird darum im gleitenden Mittel über {@code 2·glatt + 1} Minuten; ein
 * Scheitel ist ein Wert dieses Mittels, der im Fenster ± {@link #fenster} Minuten der größte (beim Niedrigwasser der
 * kleinste) ist. Das Fenster ist kürzer als die halbe Tide (6,2 h), so findet sich je Tide genau ein Hoch- und ein
 * Niedrigwasser, auch wo Flut und Ebbe ungleich lang sind (Eider). Hoch und Niedrig wechseln sich ab; ein Paar, das sich um
 * weniger als {@link #abstand} unterscheidet, fällt weg (wie {@code Vorhersage.extreme}: ein Stau im Flachwasser ist kein
 * eigenes Hochwasser).
 * <p>
 * Geliefert werden nur Scheitel, deren ganzes Fenster in der Reihe liegt und dort zu {@link #gedeckt} mit Werten belegt
 * ist: Was am Rand liegt, findet der nächste Abruf, wenn die Reihe weiter ist. Dieselben Werte geben dieselben Scheitel.
 * Nur java.*.
 */
public final class Scheitelsuche {

    /** Ein Scheitel: H oder N, Zeit (Epoch-Sekunden UTC), Höhe des Mittels (m NHN). */
    public record Scheitel(char art, long zeit, double hoehe) { }

    /** Halbe Breite des gleitenden Mittels in Minuten [G]. */
    public int glatt = 15;
    /** Mindestanteil gültiger Werte im gleitenden Mittel [G]. */
    public double mindest = 0.65;
    /** Halbe Breite des Fensters in Minuten [G]. */
    public int fenster = 180;
    /** Mindestanteil gültiger Mittelwerte im Fenster [G]. */
    public double gedeckt = 0.8;
    /** Kleinster Unterschied zwischen benachbartem Hoch und Niedrig (m) [G]. */
    public double abstand = 0.1;

    /**
     * Sucht in einer Reihe mit festem Schritt ({@code schritt} Sekunden, höchstens 60 sinnvoll; NaN = fehlt), Beginn
     * {@code start} (Epoch-Sekunden).
     */
    public List<Scheitel> suche(long start, int schritt, float[] w) {
        int n = w.length;
        List<Scheitel> aus = new ArrayList<>();
        if (n == 0) return aus;
        int h = Math.max(0, Math.round(glatt * 60f / schritt)), f = Math.max(1, Math.round(fenster * 60f / schritt));
        // gleitendes Mittel über Präfixsummen
        double[] summe = new double[n + 1];
        int[] zahl = new int[n + 1];
        for (int i = 0; i < n; i++) {
            boolean ok = !Float.isNaN(w[i]);
            summe[i + 1] = summe[i] + (ok ? w[i] : 0);
            zahl[i + 1] = zahl[i] + (ok ? 1 : 0);
        }
        double[] g = new double[n];
        int[] gz = new int[n + 1];
        int noetig = (int) Math.ceil(mindest * (2 * h + 1));
        for (int i = 0; i < n; i++) {
            int a = Math.max(0, i - h), b = Math.min(n - 1, i + h);
            int c = zahl[b + 1] - zahl[a];
            g[i] = (i - h >= 0 && i + h < n && c >= noetig) ? (summe[b + 1] - summe[a]) / c : Double.NaN;
            gz[i + 1] = gz[i] + (Double.isNaN(g[i]) ? 0 : 1);
        }
        int deckung = (int) Math.ceil(gedeckt * (2 * f + 1));
        List<Scheitel> roh = new ArrayList<>();
        for (int k = f; k + f < n; k++) {
            if (Double.isNaN(g[k])) continue;
            if (gz[k + f + 1] - gz[k - f] < deckung) continue;
            boolean hoch = true, tief = true;
            for (int j = k - f; j <= k + f && (hoch || tief); j++) {
                if (j == k || Double.isNaN(g[j])) continue;
                if (j < k) {
                    if (g[j] >= g[k]) hoch = false;
                    if (g[j] <= g[k]) tief = false;
                } else {
                    if (g[j] > g[k]) hoch = false;
                    if (g[j] < g[k]) tief = false;
                }
            }
            if (hoch) roh.add(new Scheitel('H', start + (long) k * schritt, g[k]));
            else if (tief) roh.add(new Scheitel('N', start + (long) k * schritt, g[k]));
        }
        // Wechsel H/N: von zwei gleichen Arten bleibt die stärkere
        List<Scheitel> l = new ArrayList<>();
        for (Scheitel s : roh) {
            if (!l.isEmpty() && l.get(l.size() - 1).art() == s.art()) {
                Scheitel v = l.get(l.size() - 1);
                boolean neu = s.art() == 'H' ? s.hoehe() > v.hoehe() : s.hoehe() < v.hoehe();
                if (neu) l.set(l.size() - 1, s);
                continue;
            }
            l.add(s);
        }
        // Paare unter dem Abstand fallen weg
        boolean geaendert = true;
        while (geaendert) {
            geaendert = false;
            for (int i = 0; i + 1 < l.size(); i++) {
                if (Math.abs(l.get(i).hoehe() - l.get(i + 1).hoehe()) < abstand) {
                    l.remove(i + 1);
                    l.remove(i);
                    geaendert = true;
                    // wieder zusammenführen: gleiche Arten nebeneinander
                    for (int j = Math.max(0, i - 1); j + 1 < l.size(); j++) {
                        if (l.get(j).art() != l.get(j + 1).art()) continue;
                        Scheitel a = l.get(j), b = l.get(j + 1);
                        boolean erster = a.art() == 'H' ? a.hoehe() >= b.hoehe() : a.hoehe() <= b.hoehe();
                        l.remove(erster ? j + 1 : j);
                        j--;
                    }
                    break;
                }
            }
        }
        aus.addAll(l);
        return aus;
    }
}
