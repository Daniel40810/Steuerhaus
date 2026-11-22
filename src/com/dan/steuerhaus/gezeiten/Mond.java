package com.dan.steuerhaus.gezeiten;

import com.dan.core.astro.FAngle;
import com.dan.core.astro.FAstroTime;
import com.dan.core.astro.FMoonPosition;
import com.dan.core.astro.FSun;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Mondphasen (seit Z1): Neumond, erstes Viertel, Vollmond, letztes Viertel, wenn die scheinbare Länge des Monds der
 * der Sonne um 0°, 90°, 180°, 270° vorausläuft – aus den Ephemeriden des Kerns ({@link FMoonPosition} nach Meeus,
 * {@link FSun}). Gesucht stündlich, dann mit Halbieren auf die Sekunde.
 */
public final class Mond {
    public enum Phase {
        NEUMOND("Neumond", 0), ERSTES_VIERTEL("Erstes Viertel", 90), VOLLMOND("Vollmond", 180), LETZTES_VIERTEL("Letztes Viertel", 270);
        public final String name;
        public final double winkel;

        Phase(String n, double w) {
            name = n;
            winkel = w;
        }
    }

    public record Ereignis(Phase phase, Instant zeit) { }

    private Mond() { }

    /** Länge des Monds minus Länge der Sonne (Grad, 0 … 360). */
    public static double elongation(Instant t) {
        double jde = FAstroTime.jdeFromMillis(t.toEpochMilli());
        return FAngle.norm360(FMoonPosition.at(jde).longitude - FSun.at(jde).apparentLongitude);
    }

    /** Beleuchteter Anteil der Scheibe (0 … 1) aus der Elongation (genähert, ohne Abstand der Gestirne). */
    public static double beleuchtet(Instant t) { return (1 - Math.cos(Math.toRadians(elongation(t)))) / 2; }

    /** Die Hauptphasen in [von, bis). */
    public static List<Ereignis> phasen(Instant von, Instant bis) {
        List<Ereignis> r = new ArrayList<>();
        long sc = 6 * 3600;
        double vor = elongation(von);
        for (long s = von.getEpochSecond() + sc; s <= bis.getEpochSecond() + sc; s += sc) {
            double e = elongation(Instant.ofEpochSecond(s));
            for (Phase p : Phase.values()) {
                double a = FAngle.norm180(vor - p.winkel), b = FAngle.norm180(e - p.winkel);
                if (a < 0 && b >= 0 && b - a < 90) {
                    long lo = s - sc, hi = s;
                    while (hi - lo > 1) {
                        long mi = (lo + hi) / 2;
                        if (FAngle.norm180(elongation(Instant.ofEpochSecond(mi)) - p.winkel) < 0) lo = mi;
                        else hi = mi;
                    }
                    Instant t = Instant.ofEpochSecond(hi);
                    if (!t.isBefore(von) && t.isBefore(bis)) r.add(new Ereignis(p, t));
                }
            }
            vor = e;
        }
        return r;
    }
}
