package com.dan.steuerhaus.regler;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Werte der Regler im Live-Lauf (seit Y2, Werkbuch HalligHooge 153; F247 A): Faktor auf den Wind aus ERA5, Drehung
 * des Winds (Grad, im Uhrzeigersinn: er dreht rechts), Aufschlag (Stau von außen, m) und Anstieg des Meeresspiegels (m).
 * Aufschlag und Meeresspiegel wirken zusammen als Wasserstand am Rand der innersten Stufe. Nur {@code java.*}.
 */
public record Reglerwerte(double windFaktor, double windDrehung, double aufschlag, double meeresspiegel) {
    public static final Reglerwerte NEUTRAL = new Reglerwerte(1, 0, 0, 0);
    /** Bereiche und Schritte der Regler [G]. */
    public static final double FAKTOR_MAX = 3, FAKTOR_SCHRITT = 0.1, DREHUNG_MAX = 180, DREHUNG_SCHRITT = 15, AUFSCHLAG_MIN = -1, AUFSCHLAG_MAX = 3,
            AUFSCHLAG_SCHRITT = 0.1, MEER_MAX = 1.5, MEER_SCHRITT = 0.1;

    public Reglerwerte {
        windFaktor = Math.max(0, Math.min(FAKTOR_MAX, windFaktor));
        windDrehung = Math.max(-DREHUNG_MAX, Math.min(DREHUNG_MAX, windDrehung));
        aufschlag = Math.max(AUFSCHLAG_MIN, Math.min(AUFSCHLAG_MAX, aufschlag));
        meeresspiegel = Math.max(0, Math.min(MEER_MAX, meeresspiegel));
    }

    public boolean neutral() { return windFaktor == 1 && windDrehung == 0 && aufschlag == 0 && meeresspiegel == 0; }

    /** Der Wasserstand, den Aufschlag und Meeresspiegel zusammen am Rand verlangen (m). */
    public double amRand() { return aufschlag + meeresspiegel; }

    public Reglerwerte mitWind(double f, double d) { return new Reglerwerte(f, d, aufschlag, meeresspiegel); }

    public Reglerwerte mitWasser(double a, double m) { return new Reglerwerte(windFaktor, windDrehung, a, m); }

    /** Kurz, nur was vom Gemessenen abweicht: „Wind × 2,0 · +30° · Aufschlag +1,00 m“; neutral: „wie gemessen“. */
    public String text() {
        List<String> t = new ArrayList<>();
        if (windFaktor != 1) t.add(String.format(Locale.GERMAN, "Wind × %.1f", windFaktor));
        if (windDrehung != 0) t.add(String.format(Locale.GERMAN, "Wind %+.0f°", windDrehung).replace('-', '−'));
        if (aufschlag != 0) t.add(String.format(Locale.GERMAN, "Aufschlag %+.2f m", aufschlag).replace('-', '−'));
        if (meeresspiegel != 0) t.add(String.format(Locale.GERMAN, "Meeresspiegel %+.1f m", meeresspiegel));
        return t.isEmpty() ? "wie gemessen" : String.join(" · ", t);
    }

    /** Für den Aufruf {@code --regler "wind=2;drehung=30;aufschlag=1;meer=0.5"} (fehlende Teile: neutral). */
    public static Reglerwerte lies(String s) {
        double f = 1, d = 0, a = 0, m = 0;
        for (String teil : s.split(";")) {
            String[] kv = teil.split("=");
            if (kv.length != 2) continue;
            double v = Double.parseDouble(kv[1].strip().replace(',', '.'));
            switch (kv[0].strip().toLowerCase(Locale.ROOT)) {
                case "wind", "faktor" -> f = v;
                case "drehung" -> d = v;
                case "aufschlag" -> a = v;
                case "meer", "meeresspiegel" -> m = v;
                default -> throw new IllegalArgumentException("Regler: unbekannt „" + kv[0] + "“ (wind, drehung, aufschlag, meer)");
            }
        }
        return new Reglerwerte(f, d, a, m);
    }
}
