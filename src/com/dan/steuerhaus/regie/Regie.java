package com.dan.steuerhaus.regie;

import java.util.ArrayList;
import java.util.List;

/**
 * Der Ablauf eines {@link Regiebuch}s (seit W3, Werkbuch HalligHooge 116; nach Rungholt). Die Uhr des Regiebuchs ist
 * Filmzeit: echte Sekunden seit dem Start, ohne die Zeit, in der ein {@code warte} steht (F195 A). Was ein Befehl tut,
 * führt die Anwendung über {@link Ctx} aus; so läuft dasselbe Buch auch ohne Bild (Prüfstein 4).
 * <ul>
 * <li>Je {@link #schritt} werden alle Zeilen ausgeführt, deren Sekunde erreicht ist, in der Folge der Datei.</li>
 * <li>Ein {@code warte} hält die Uhr an, bis {@link Ctx#bereit} ja sagt; was danach in derselben Sekunde steht, kommt
 * dann. Nach {@link #WARTE_HOECHSTENS} echten Sekunden geht es mit einer Meldung weiter.</li>
 * <li>Fertig ist das Buch, wenn alle Zeilen gelaufen sind und die Länge erreicht ist.</li>
 * </ul>
 */
public final class Regie {
    /** Was ein Regiebuch braucht; die Anwendung führt es aus. */
    public interface Ctx {
        /** Führt eine Zeile aus (auch {@code warte}: dann nur ihr Text). */
        void ausfuehren(Regiebuch.Zeile z);

        /** Ob die Bedingung eines {@code warte} erfüllt ist. */
        boolean bereit(Regiebuch.Zeile warte);
    }

    /** Länger als so viele echte Sekunden wartet ein {@code warte} nicht [G]. */
    public static final double WARTE_HOECHSTENS = 120;

    public final Regiebuch buch;
    private final Ctx ctx;
    private double uhr, warteSeit;
    private int naechste;
    private Regiebuch.Zeile wartet;
    private final List<String> meldungen = new ArrayList<>();

    public Regie(Regiebuch buch, Ctx ctx) {
        this.buch = buch;
        this.ctx = ctx;
    }

    /** Um dt echte Sekunden weiter. */
    public void schritt(double dt) {
        if (wartet != null) {
            warteSeit += dt;
            if (ctx.bereit(wartet)) wartet = null;
            else if (warteSeit >= WARTE_HOECHSTENS && !wartet.argumente().strip().equals("klick")) {        // auf einen Klick wartet es ohne Grenze
                meldungen.add("Zeile " + wartet.nr() + ": nach " + (int) WARTE_HOECHSTENS + " s weiter ohne „" + wartet.argumente() + "“");
                wartet = null;
            } else return;
            ausfuehrenBis(uhr);                                             // was in derselben Sekunde folgt
            return;
        }
        uhr += dt;
        ausfuehrenBis(uhr);
    }

    private void ausfuehrenBis(double t) {
        List<Regiebuch.Zeile> z = buch.zeilen;
        while (naechste < z.size() && z.get(naechste).sekunde() <= t + 1e-9 && wartet == null) {
            Regiebuch.Zeile x = z.get(naechste++);
            ctx.ausfuehren(x);
            if (x.befehl().equals("warte")) {
                if (ctx.bereit(x)) continue;
                wartet = x;
                warteSeit = 0;
                uhr = x.sekunde();                                          // die Uhr bleibt auf der Sekunde des warte
            }
        }
    }

    // ------------------------------------------------------------------ Abschnitte (seit X2, Werkbuch HalligHooge 146)

    /** Der Beginn der Abschnitte: die erste Zeile und jede Zeile nach einem {@code warte klick} (Nummern in {@code buch.zeilen}). */
    public List<Integer> abschnitte() {
        List<Integer> a = new ArrayList<>();
        a.add(0);
        List<Regiebuch.Zeile> z = buch.zeilen;
        for (int i = 0; i < z.size(); i++)
            if (z.get(i).befehl().equals("warte") && z.get(i).argumente().strip().equals("klick") && i + 1 < z.size()) a.add(i + 1);
        return a;
    }

    /** Der Abschnitt der zuletzt gelaufenen Zeile (0 …). */
    public int abschnitt() {
        List<Integer> a = abschnitte();
        int k = 0, letzte = Math.max(0, naechste - 1);
        for (int i = 0; i < a.size(); i++) if (a.get(i) <= letzte) k = i;
        return k;
    }

    /** Springt an den Beginn eines Abschnitts: die Uhr auf seine Sekunde, ein Warten endet. */
    public void springe(int abschnitt) {
        List<Integer> a = abschnitte();
        int k = Math.max(0, Math.min(a.size() - 1, abschnitt));
        naechste = a.get(k);
        uhr = naechste < buch.zeilen.size() ? buch.zeilen.get(naechste).sekunde() : buch.laenge;
        wartet = null;
        warteSeit = 0;
        ausfuehrenBis(uhr);
    }

    /** Filmzeit des Buchs (s). */
    public double uhr() { return uhr; }

    /** Die Zeile, auf die gewartet wird (null: keine). */
    public Regiebuch.Zeile wartet() { return wartet; }

    public boolean fertig() { return wartet == null && naechste >= buch.zeilen.size() && uhr >= buch.laenge; }

    /** Wie viele Zeilen gelaufen sind. */
    public int gelaufen() { return naechste; }

    /** Meldungen (Wartezeit überschritten). */
    public List<String> meldungen() { return List.copyOf(meldungen); }
}
