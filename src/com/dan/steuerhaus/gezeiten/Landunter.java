package com.dan.steuerhaus.gezeiten;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Die Rechnung der Landunter-Uhr (seit Z2, Werkbuch HalligHooge 156): die astronomische Gezeit am Ort plus ein Windstau
 * (gemessen, gewählt oder null) gegen zwei Höhen. Über der <b>Krone</b> (der niedrigsten Stelle des Sommerdeichs) läuft
 * Wasser über; die Fennen werden davon kaum nass. <b>Landunter</b> heißt: außen steht das Wasser über der Höhe, ab der die
 * Fennen an den Warften nass werden ({@code nass}, im Modell abgelesen: in drei Stürmen frühestens bei 2,52 m außen). Dann
 * steht das Wasser beim Scheitel auf der Hallig so hoch wie außen (im Modell ± 5 cm; LandunterCheck), danach bleibt es,
 * bis die Siele es ablassen.
 */
public final class Landunter {
    /** Eine Warft: Name, Kuppe und Fuß (m NHN), Häuser. */
    public record Warft(String name, double kuppe, double fuss, int haeuser) { }

    /**
     * Ein Hochwasser: Zeit und Höhe der Gezeit, Scheitel mit Windstau; Zeit über der Krone und Zeit über der Höhe des
     * Landunters (je null, wenn nicht erreicht).
     */
    public record Hochwasser(Instant zeit, double gezeit, double scheitel, double krone, double nass, Instant ueberAb, Instant ueberBis, Instant nassAb, Instant nassBis) {
        /** Wasser über der Krone. */
        public boolean ueberlauf() { return ueberAb != null; }

        /** Landunter: über der Höhe, ab der die Fennen nass werden. */
        public boolean landunter() { return nassAb != null; }

        /** Bis zur Krone fehlt (negativ: so weit darüber). */
        public double fehltKrone() { return krone - scheitel; }

        /** Bis zum Landunter fehlt (negativ: so weit darüber). */
        public double fehltNass() { return nass - scheitel; }

        /** Der Windstau, ab dem Wasser über die Krone läuft. */
        public double noetigKrone() { return krone - gezeit; }

        /** Der Windstau, ab dem Landunter ist. */
        public double noetigNass() { return nass - gezeit; }
    }

    /** Eine Warft beim Scheitel: wie hoch die Kuppe über dem Wasser steht, wie tief das Wasser am Fuß (0: trocken). */
    public record Lage(Warft warft, double kuppeUeber, double amFuss) { }

    public final Vorhersage v;
    public final double krone, nass;
    public final List<Warft> warften;

    public Landunter(Vorhersage v, double krone, double nass, List<Warft> warften) {
        this.v = v;
        this.krone = krone;
        this.nass = Double.isNaN(nass) ? krone : Math.max(krone, nass);
        this.warften = List.copyOf(warften);
    }

    /** Wasserstand mit Windstau (m NHN). */
    public double wert(Instant t, double stau) { return v.wert(t) + stau; }

    /** Die Hochwasser in [von, bis) mit dem Windstau. */
    public List<Hochwasser> hochwasser(Instant von, Instant bis, double stau) {
        List<Hochwasser> l = new ArrayList<>();
        for (Vorhersage.Extrem e : v.extreme(von, bis)) {
            if (e.art() != Vorhersage.Art.HOCHWASSER) continue;
            double sch = e.hoehe() + stau;
            Instant[] u = sch > krone ? ueber(e.zeit(), stau, krone) : null, n = sch > nass ? ueber(e.zeit(), stau, nass) : null;
            l.add(new Hochwasser(e.zeit(), e.hoehe(), sch, krone, nass, u == null ? null : u[0], u == null ? null : u[1], n == null ? null : n[0], n == null ? null : n[1]));
        }
        return l;
    }

    /** Das nächste Hochwasser nach t (mit Windstau). */
    public Hochwasser naechstes(Instant t, double stau) {
        List<Hochwasser> l = hochwasser(t, t.plusSeconds(16 * 3600), stau);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Beginn und Ende der Zeit über einer Höhe um ein Hochwasser: die erste und die letzte Sekunde darüber (durch Halbieren). */
    Instant[] ueber(Instant hw, double stau, double hoehe) {
        return new Instant[] {grenze(hw, -1, stau, hoehe), grenze(hw, 1, stau, hoehe)};
    }

    private Instant grenze(Instant hw, int richtung, double stau, double hoehe) {
        long a = hw.getEpochSecond(), b = a;
        for (int i = 0; i < 7 * 12; i++) {
            b = a + richtung * 300L * (i + 1);
            if (wert(Instant.ofEpochSecond(b), stau) <= hoehe) break;
        }
        long innen = a, aussen = b;
        while (Math.abs(aussen - innen) > 1) {
            long m = (innen + aussen) / 2;
            if (wert(Instant.ofEpochSecond(m), stau) > hoehe) innen = m;
            else aussen = m;
        }
        return Instant.ofEpochSecond(innen);
    }

    /** Die Warften beim Scheitel, am knappsten zuerst; Wasser am Fuß nur bei Landunter (dann so hoch wie außen). */
    public List<Lage> lagen(double scheitel) {
        List<Lage> l = new ArrayList<>();
        for (Warft w : warften) l.add(new Lage(w, w.kuppe() - scheitel, scheitel > nass ? Math.max(0, scheitel - w.fuss()) : 0));
        l.sort(Comparator.comparingDouble(Lage::kuppeUeber));
        return l;
    }

    /**
     * Die Stellung der Uhr zur Zeit t (Grad, 0 … 360): 0 beim letzten Hochwasser, 180 beim Niedrigwasser danach, 360 beim
     * nächsten Hochwasser; dazwischen linear in der Zeit.
     */
    public double winkel(Instant t) {
        List<Vorhersage.Extrem> e = v.extreme(t.minusSeconds(14 * 3600), t.plusSeconds(14 * 3600));
        Vorhersage.Extrem a = null, b = null;
        for (int i = 0; i + 1 < e.size(); i++)
            if (!e.get(i).zeit().isAfter(t) && e.get(i + 1).zeit().isAfter(t)) {
                a = e.get(i);
                b = e.get(i + 1);
            }
        if (a == null) return 0;
        double f = (t.getEpochSecond() - a.zeit().getEpochSecond()) / (double) (b.zeit().getEpochSecond() - a.zeit().getEpochSecond());
        return a.art() == Vorhersage.Art.HOCHWASSER ? 180 * f : 180 + 180 * f;
    }

    /**
     * Der Fehler des Scheitels mit gemessenem Windstau, je nach Vorlauf (h bis zum Hochwasser): linear zwischen den
     * Stützstellen, davor und danach wie die erste und letzte; NaN ohne Werte.
     */
    public static double fehler(double[] vorlauf, double[] rms, double stunden) {
        if (vorlauf == null || rms == null || vorlauf.length == 0) return Double.NaN;
        if (stunden <= vorlauf[0]) return rms[0];
        for (int i = 1; i < vorlauf.length; i++)
            if (stunden <= vorlauf[i]) return rms[i - 1] + (rms[i] - rms[i - 1]) * (stunden - vorlauf[i - 1]) / (vorlauf[i] - vorlauf[i - 1]);
        return rms[rms.length - 1];
    }
}
