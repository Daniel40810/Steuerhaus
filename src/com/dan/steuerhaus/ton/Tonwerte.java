package com.dan.steuerhaus.ton;

import com.dan.lauf.Buehne;

/**
 * Seit X5 (Werkbuch HalligHooge 151): von der Bühne zu den {@link Klang.Werte}n. {@link #aus} fragt die Bühne
 * ({@link Buehne#tonWerte}: Wasser am Ort, Boden, Abstand zum Wasser, Hs, Sonne) und rechnet mit {@link #werte}; die
 * Regeln stehen dort, ohne Bühne prüfbar.
 */
public final class Tonwerte {
    private Tonwerte() { }

    /** Die Werte zum Zustand eines Bilds; ohne Blick oder Zeit Stille. */
    public static Klang.Werte aus(Buehne b, Buehne.Zustand z) {
        if (b == null || z == null || z.blick == null || z.jetzt == null) return Klang.Werte.STILL;
        double[] t = b.tonWerte(z.blick.ost, z.blick.nord, z.jetzt);
        double boden = Double.isNaN(t[1]) ? 0 : t[1];
        double wasser = !Double.isNaN(t[0]) ? t[0] : Double.isNaN(z.pegel) ? Double.NaN : z.pegel;
        return werte(z.wind, z.windU, z.windV, z.blick.richtung, z.blick.hoehe - boden, Double.isNaN(wasser) ? Double.POSITIVE_INFINITY : z.blick.hoehe - wasser, t[2], t[3], t[4]);
    }

    /**
     * Die Regeln [G]:
     * <ul>
     * <li>nah = e^(−Abstand/60 m) · e^(−max(0, Auge über dem Wasser − 2 m)/40 m)</li>
     * <li>Brandung = Hs/2 m (höchstens 1); ohne Seegang aus dem Wind: (Wind − 4)/16 · 0,6</li>
     * <li>Tag = (Sonne + 4°)/10° (0 … 1)</li>
     * <li>Möwen = Tag · (1 − (Wind − 12)/10) · (0,4 + 0,6 · e^(−Abstand/300 m)) · e^(−max(0, Auge über dem Boden − 30 m)/80 m)</li>
     * <li>Plätschern = nah · (1 − Wind/12) · e^(−max(0, Auge über dem Wasser − 2 m)/5 m), nur ohne Brandung</li>
     * </ul>
     */
    public static Klang.Werte werte(double wind, double windU, double windV, double blickRichtung, double ueberBoden, double ueberWasser, double abstand, double hs,
                                    double sonne) {
        double w = Double.isNaN(wind) ? 0 : wind;
        double von = Math.hypot(windU, windV) > 1e-6 ? (Math.toDegrees(Math.atan2(-windU, -windV)) + 360) % 360 : blickRichtung;
        double rel = ((von - blickRichtung) % 360 + 360) % 360;
        double ab = Double.isNaN(abstand) ? Double.POSITIVE_INFINITY : abstand;
        double uw = Double.isNaN(ueberWasser) ? Double.POSITIVE_INFINITY : ueberWasser, ub = Double.isNaN(ueberBoden) ? 2 : ueberBoden;
        double nah = Double.isInfinite(ab) ? 0 : Math.exp(-ab / 60) * Math.exp(-Math.max(0, uw - 2) / 40);
        double br = !Double.isNaN(hs) ? Math.min(1, hs / 2.0) : Math.max(0, Math.min(1, (w - 4) / 16)) * 0.6;
        double tag = Double.isNaN(sonne) ? 1 : Math.max(0, Math.min(1, (sonne + 4) / 10));
        double moewen = tag * Math.max(0, Math.min(1, 1 - (w - 12) / 10)) * (0.4 + 0.6 * (Double.isInfinite(ab) ? 0 : Math.exp(-ab / 300)))
                * Math.exp(-Math.max(0, ub - 30) / 80);
        double pl = nah * Math.max(0, 1 - w / 12) * Math.exp(-Math.max(0, uw - 2) / 5) * (1 - br);
        return new Klang.Werte(w, rel, br, nah, tag, Math.min(1, moewen), Math.max(0, Math.min(1, pl)));
    }
}
