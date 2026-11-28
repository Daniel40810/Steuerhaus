package com.dan.steuerhaus.analyse;

/**
 * Die Qualitätsstufe (seit W5, Werkbuch HalligHooge 126, F211 A): welche teuren Teile das Bild zeigen darf. Die Stufe
 * begrenzt nur – was der Benutzer ausgeschaltet hat, bleibt aus.
 * <ul>
 * <li>{@link Stufe#HOCH}: alles, was an ist.</li>
 * <li>{@link Stufe#MITTEL}: ohne Bewuchs und Kimmung; seit S9b-4 (Seegang Werkbuch 107, SF173 A) auch ohne die Spiegelung des
 * Geländes im Wasser (nur der Himmel spiegelt).</li>
 * <li>{@link Stufe#NIEDRIG}: dazu ohne Brandung und Gestalt.</li>
 * </ul>
 * Automatisch (Hysterese, damit nichts pendelt): Liegt die Bildrate {@link #RUNTER_S} s lang unter {@link #UNTEN} B/s,
 * geht es eine Stufe herunter; liegt sie {@link #HOCH_S} s lang über {@link #OBEN} B/s, eine Stufe hinauf; nach jedem
 * Wechsel ruht die Regel {@link #RUHE_S} s. Reine Logik, ohne Bild: {@link #takt} bekommt Zeit und Bildrate.
 * <p>
 * Seit C1b (Werkbuch HalligHooge 137, A53): {@link #tiefste} begrenzt, wie weit die Automatik heruntergeht (im Kino bis
 * Mittel, damit die Brandung bleibt, die ein Regiebuch zeigt).
 */
public final class Qualitaet {
    public enum Stufe {
        HOCH("Hoch"), MITTEL("Mittel"), NIEDRIG("Niedrig");

        public final String name;

        Stufe(String n) { name = n; }

        /** Darf der Teil in dieser Stufe an sein? */
        public boolean erlaubt(Teil t) {
            return switch (t) {
                case BEWUCHS, KIMMUNG, SPIEGELUNG -> this == HOCH;
                case BRANDUNG, GESTALT, SEEGANG -> this != NIEDRIG;
            };
        }
    }

    /** Seit S3d (Seegang Werkbuch 28, SF37 A): SEEGANG ist die neue Fläche des Seegangs (Nahfeld, Clipmap, Netz). */
    public enum Teil { BEWUCHS, KIMMUNG, BRANDUNG, GESTALT, SEEGANG, SPIEGELUNG }

    public static final double UNTEN = 30, OBEN = 55, RUNTER_S = 3, HOCH_S = 15, RUHE_S = 15;

    private Stufe stufe = Stufe.HOCH;
    private boolean automatisch;
    private double unterSeit = Double.NaN, ueberSeit = Double.NaN, letzterWechsel = Double.NEGATIVE_INFINITY;
    private int wechsel;
    private Stufe tiefste = Stufe.NIEDRIG;

    /** Seit C1b (A53): die tiefste Stufe, auf die die Automatik geht (Vorgabe Niedrig). */
    public synchronized void tiefste(Stufe s) { tiefste = s; }

    public synchronized Stufe tiefste() { return tiefste; }

    public synchronized Stufe stufe() { return stufe; }

    public synchronized boolean automatisch() { return automatisch; }

    /** Von Hand: feste Stufe (Automatik aus). */
    public synchronized void setze(Stufe s) {
        stufe = s;
        automatisch = false;
    }

    /** Automatisch ab der Stufe jetzt. */
    public synchronized void automatisch(boolean a) {
        automatisch = a;
        unterSeit = ueberSeit = Double.NaN;
    }

    /** Wie oft die Automatik gewechselt hat. */
    public synchronized int wechsel() { return wechsel; }

    /**
     * Ein Takt der Automatik.
     *
     * @param jetzt Sekunden (beliebiger Nullpunkt, steigend)
     * @param bilderJeS gemessene Bildrate; NaN oder ≤ 0: ohne Wirkung (Bild steht)
     * @return die Stufe nach dem Takt
     */
    public synchronized Stufe takt(double jetzt, double bilderJeS) {
        if (!automatisch || Double.isNaN(bilderJeS) || bilderJeS <= 0) return stufe;
        if (bilderJeS < UNTEN) {
            if (Double.isNaN(unterSeit)) unterSeit = jetzt;
            ueberSeit = Double.NaN;
        } else if (bilderJeS > OBEN) {
            if (Double.isNaN(ueberSeit)) ueberSeit = jetzt;
            unterSeit = Double.NaN;
        } else unterSeit = ueberSeit = Double.NaN;
        if (jetzt - letzterWechsel < RUHE_S) return stufe;
        if (!Double.isNaN(unterSeit) && jetzt - unterSeit >= RUNTER_S && stufe.ordinal() < tiefste.ordinal()) {
            stufe = Stufe.values()[stufe.ordinal() + 1];
            gewechselt(jetzt);
        } else if (!Double.isNaN(ueberSeit) && jetzt - ueberSeit >= HOCH_S && stufe != Stufe.HOCH) {
            stufe = Stufe.values()[stufe.ordinal() - 1];
            gewechselt(jetzt);
        }
        return stufe;
    }

    private void gewechselt(double jetzt) {
        letzterWechsel = jetzt;
        unterSeit = ueberSeit = Double.NaN;
        wechsel++;
    }
}
