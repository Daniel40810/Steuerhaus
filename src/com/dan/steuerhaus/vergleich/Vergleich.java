package com.dan.steuerhaus.vergleich;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Vergleich (seit Y1, Werkbuch HalligHooge 152, F246 B): zwei Szenarien aus dem Archiv, dieselbe Kamera, gleiche
 * Phase der Tide; die Ausgabe rechnet daraus eine Bildfolge ({@link Mappe}), der {@link Spieler} zeigt sie mit Schieber.
 * Die Beschreibungen stehen in einer Textdatei im Format des Manifests ({@code vergleich.datei}, Vorgabe
 * {@code <Szenendatei>.vergleiche}):
 * <pre>
 * vergleich.1.schluessel = ruhig_sturm             (Name der Mappe; Vorgabe: aus dem Namen)
 * vergleich.1.name       = Ruhiger Tag und Sturmflut
 * vergleich.1.text       = Ein Satz für die Kachel und das Fenster.
 * vergleich.1.a          = ruhig                   (Szenario A, mit Archiv)
 * vergleich.1.b          = sturm                   (Szenario B, mit Archiv)
 * vergleich.1.ansicht    = warften                 (oder vergleich.1.blick = Ost Nord Höhe Richtung Neigung Bildwinkel)
 * vergleich.1.ausrichten = hochwasser 2026-10-30T15:00:00Z | hochwasser 2026-10-25T12:00:00Z
 * vergleich.1.bereich    = -3 2.5                  (Stunden um den Bezug)
 * vergleich.1.schritt    = 5                       (Minuten zwischen zwei Bildern)
 * vergleich.1.sonde.1    = Hooge                   (Ort aus orte.txt, „pegel“, „mitte“ oder Name; Ost; Nord)
 * </pre>
 * Nur {@code java.*} und der Kern; das Auflösen gegen die Pegelreihe ({@link Ausrichtung#bezug}) rechnet auf Listen von
 * {@link Marke}, damit es ohne Archiv prüfbar ist.
 */
public record Vergleich(String schluessel, String name, String text, String a, String b, String ansicht, double[] blick,
                        Ausrichtung ausrichtung, double von, double bis, double schritt, List<String> sonden) {

    /** Höchstens so viele Sonden [G] (wie im Abschnitt Sonden). */
    public static final int SONDEN = 4;
    /** Höchstens so viele Bilder je Seite [G]: 12 h bei 2 min. */
    public static final int BILDER = 361;

    /** Die Datei der Vergleiche: {@code vergleich.datei} im Manifest, sonst neben der Szenendatei {@code <Name>.vergleiche}. */
    public static File datei(Manifest m) {
        String t = m.text("vergleich.datei", "");
        if (!t.isEmpty()) return m.relativ(t);
        File szd = m.szenen();
        return new File(szd.getParentFile(), szd.getName().replaceFirst("\\.[^.]*$", "") + ".vergleiche");
    }

    /** Liest die Vergleiche (fehlt die Datei: keine); Fehler je Eintrag in {@code fehler}, der Eintrag fällt weg. */
    public static List<Vergleich> lies(Manifest m, List<String> fehler) {
        File f = datei(m);
        List<Vergleich> l = new ArrayList<>();
        if (!f.isFile()) return l;
        Manifest d;
        try {
            d = Manifest.lies(f);
        } catch (IOException x) {
            fehler.add(f.getName() + ": " + x.getMessage());
            return l;
        }
        for (String k : d.doppelt()) fehler.add(f.getName() + ": doppelt: " + k);
        for (int n = 1; n <= 99; n++) {
            String p = "vergleich." + n + ".";
            String name = d.text(p + "name", "");
            if (name.isEmpty()) continue;
            try {
                List<String> so = new ArrayList<>();
                for (int i = 1; i <= SONDEN; i++) {
                    String s = d.text(p + "sonde." + i, "");
                    if (!s.isEmpty()) so.add(s);
                }
                double[] be = zahlen(d.text(p + "bereich", "-3 3"));
                if (be.length != 2 || !(be[1] > be[0])) throw new IllegalArgumentException("bereich: zwei Zahlen, von < bis (Stunden)");
                double sm = Double.parseDouble(d.text(p + "schritt", "5").replace(',', '.'));
                String bl = d.text(p + "blick", "");
                double[] blick = bl.isEmpty() ? null : zahlen(bl);
                if (blick != null && blick.length != 6) throw new IllegalArgumentException("blick: sechs Zahlen (Ost Nord Höhe Richtung Neigung Bildwinkel)");
                String an = d.text(p + "ansicht", "");
                if (blick == null && an.isEmpty()) throw new IllegalArgumentException("ansicht oder blick fehlt");
                String a = d.text(p + "a", ""), b = d.text(p + "b", "");
                if (a.isEmpty() || b.isEmpty()) throw new IllegalArgumentException("a und b (Szenarien) fehlen");
                Vergleich v = new Vergleich(d.text(p + "schluessel", schluesselAus(name)), name, d.text(p + "text", ""), a, b, an.isEmpty() ? null : an, blick,
                        Ausrichtung.lies(d.text(p + "ausrichten", "uhrzeit")), be[0] * 3600, be[1] * 3600, sm * 60, List.copyOf(so));
                String warum = v.pruefe();
                if (warum != null) throw new IllegalArgumentException(warum);
                l.add(v);
            } catch (RuntimeException x) {
                fehler.add(f.getName() + ", vergleich." + n + " (" + name + "): " + x.getMessage());
            }
        }
        return l;
    }

    /** Grenzen [G]; null: in Ordnung. */
    public String pruefe() {
        if (!(schritt >= 60) || schritt > 3600) return "schritt: 1 bis 60 min";
        if (bilder() > BILDER) return "zu viele Bilder: " + bilder() + " (höchstens " + BILDER + " je Seite)";
        if (sonden.size() > SONDEN) return "höchstens " + SONDEN + " Sonden";
        return null;
    }

    /** Bilder je Seite ohne Beschnitt durch die Archive. */
    public int bilder() { return (int) Math.floor((bis - von) / schritt + 1e-9) + 1; }

    /** Kennung der Beschreibung und Bildgröße: ändert sie sich, ist eine gerechnete Mappe veraltet. */
    public String kennung(int breite, int hoehe) {
        String s = String.join("|", name, a, b, String.valueOf(ansicht), blick == null ? "-" : java.util.Arrays.toString(blick), ausrichtung.toString(),
                String.valueOf(von), String.valueOf(bis), String.valueOf(schritt), String.join(";", sonden), breite + "x" + hoehe);
        long h = 1125899906842597L;
        for (int i = 0; i < s.length(); i++) h = 31 * h + s.charAt(i);
        return Long.toHexString(h);
    }

    /** Ein Name für die Mappe: Kleinbuchstaben, Ziffern und Unterstriche, Umlaute ausgeschrieben. */
    public static String schluesselAus(String name) {
        String s = name.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        s = s.replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        return s.isEmpty() ? "vergleich" : s;
    }

    static double[] zahlen(String s) {
        String[] t = s.strip().split("[\\s;]+");
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) r[i] = Double.parseDouble(t[i].replace(',', '.'));
        return r;
    }

    // ------------------------------------------------------------------ Ausrichtung

    /** Hoch- oder Niedrigwasser einer Pegelreihe: Art, Zeit, Pegel (m NHN). */
    public record Marke(boolean hoch, Instant zeit, double pegel) { }

    /** Was A und B gleich macht: die Bezugszeit je Seite (Bild 0 des Bereichs), ihre Pegel und die Art des Bezugs für die Achse. */
    public record Bezug(Instant a, Instant b, double pegelA, double pegelB, String art) {
        /** „nach Hochwasser“ usw. für die Zeitachse (ab dem Bezug). */
        public String nach() {
            return switch (art) {
                case "Hochwasser" -> "nach Hochwasser";
                case "Niedrigwasser" -> "nach Niedrigwasser";
                case "Scheitel" -> "nach dem Scheitel";
                default -> "ab Bezug";
            };
        }
    }

    /** Art einer Seite der Ausrichtung. */
    public enum Art { ZEIT, UHRZEIT, HOCHWASSER, NIEDRIGWASSER, SCHEITEL }

    /** Eine Seite: Art und, wahlweise, eine Zeit (die genaue Zeit oder die, der die Marke am nächsten liegt). */
    public record Seite(Art art, Instant zeit) {
        static Seite lies(String s) {
            String[] t = s.strip().split("\\s+");
            Art a = switch (t[0].toLowerCase(Locale.ROOT)) {
                case "zeit" -> Art.ZEIT;
                case "uhrzeit" -> Art.UHRZEIT;
                case "hochwasser", "hw" -> Art.HOCHWASSER;
                case "niedrigwasser", "nw" -> Art.NIEDRIGWASSER;
                case "scheitel" -> Art.SCHEITEL;
                default -> throw new IllegalArgumentException("ausrichten: unbekannt „" + t[0] + "“ (zeit, uhrzeit, hochwasser, niedrigwasser, scheitel)");
            };
            Instant z = t.length > 1 ? Instant.parse(t[1]) : null;
            if (a == Art.ZEIT && z == null) throw new IllegalArgumentException("ausrichten: zeit braucht eine Zeit (UTC)");
            return new Seite(a, z);
        }

        @Override public String toString() { return art.name().toLowerCase(Locale.ROOT) + (zeit != null ? " " + zeit : ""); }
    }

    /**
     * Wie A und B zueinander liegen. Seite A: {@code zeit T} genau T; {@code hochwasser [T]} das Hochwasser, das T am
     * nächsten liegt (ohne T das erste); ebenso {@code niedrigwasser}; {@code scheitel} das höchste Hochwasser;
     * {@code uhrzeit [T]} T (ohne T der erste Stand, an dem der Bereich beginnen kann). Seite B wie A; dazu
     * {@code uhrzeit} (ohne Zeit): dieselbe Uhrzeit wie A, um ganze Tage verschoben; {@code hochwasser} ohne Zeit: das
     * Hochwasser, das dieser Uhrzeit am nächsten liegt. Steht nur eine Seite da, gilt sie für beide (mit B ohne Zeit).
     */
    public record Ausrichtung(Seite a, Seite b) {
        public static Ausrichtung lies(String s) {
            String[] t = s.split("\\|");
            Seite a = Seite.lies(t[0]);
            Seite b = t.length > 1 ? Seite.lies(t[1]) : new Seite(a.art == Art.ZEIT ? Art.UHRZEIT : a.art, null);
            return new Ausrichtung(a, b);
        }

        @Override public String toString() { return a + " | " + b; }

        /**
         * Die Bezugszeiten aus den Marken und Spannen (erster, letzter Stand) beider Reihen. Der Bereich (von, bis in s)
         * wählt bei {@code uhrzeit} die ganzen Tage, mit denen er am weitesten in B liegt. {@code hoechsterA/B}: der höchste
         * Stand der Reihe (für den Scheitel; er gilt auch am Ende der Reihe, wo das Hochwasser noch nicht als Marke zählt);
         * null: das höchste Hochwasser.
         *
         * @throws IllegalArgumentException wenn eine Seite keine passende Marke hat
         */
        public Bezug bezug(List<Marke> ma, Marke hoechsterA, Instant[] spanA, List<Marke> mb, Marke hoechsterB, Instant[] spanB, double von, double bis, ZoneId zone) {
            Marke xa = a.art == Art.SCHEITEL && hoechsterA != null ? hoechsterA : marke(a, ma, null, "A");
            Instant ta;
            if (xa != null) ta = xa.zeit;
            else if (a.zeit != null) ta = a.zeit;
            else ta = spanA[0].plusMillis(Math.round(Math.max(0, -von) * 1000));
            // B: dieselbe Uhrzeit, um ganze Tage verschoben (so, dass der Bereich am weitesten in B liegt)
            Instant tb0 = tage(ta, spanB, von, bis, zone);
            Marke xb = b.art == Art.SCHEITEL && hoechsterB != null ? hoechsterB : marke(b, mb, b.zeit != null ? b.zeit : tb0, "B");
            Instant tb;
            if (xb != null) tb = xb.zeit;
            else if (b.art == Art.ZEIT) tb = b.zeit;
            else if (b.art == Art.UHRZEIT && b.zeit != null) tb = b.zeit;
            else tb = tb0;
            String art;
            if (xa != null && xb != null && xa.hoch == xb.hoch) art = a.art == Art.SCHEITEL || b.art == Art.SCHEITEL ? "Scheitel" : xa.hoch ? "Hochwasser" : "Niedrigwasser";
            else if (a.art == Art.UHRZEIT || b.art == Art.UHRZEIT) art = "gleiche Uhrzeit";
            else art = "Zeit";
            return new Bezug(ta, tb, xa != null ? xa.pegel : Double.NaN, xb != null ? xb.pegel : Double.NaN, art);
        }

        /** Die Marke einer Seite (null bei zeit und uhrzeit). */
        static Marke marke(Seite s, List<Marke> m, Instant nahe, String name) {
            if (s.art == Art.ZEIT || s.art == Art.UHRZEIT) return null;
            Marke best = null;
            for (Marke x : m) {
                if (s.art == Art.SCHEITEL) {
                    if (x.hoch && (best == null || x.pegel > best.pegel)) best = x;
                } else if (x.hoch == (s.art == Art.HOCHWASSER)) {
                    Instant ziel = s.zeit != null ? s.zeit : nahe;
                    if (ziel == null) {
                        if (best == null) best = x;                 // ohne Zeit: die erste
                    } else if (best == null || Math.abs(x.zeit.toEpochMilli() - ziel.toEpochMilli()) < Math.abs(best.zeit.toEpochMilli() - ziel.toEpochMilli())) best = x;
                }
            }
            if (best == null) throw new IllegalArgumentException("Seite " + name + ": kein " + (s.art == Art.NIEDRIGWASSER ? "Niedrigwasser" : "Hochwasser") + " in der Pegelreihe");
            return best;
        }

        /** t um ganze Tage (in der Zone) verschoben, so dass [t + von, t + bis] am weitesten in der Spanne liegt; bei Gleichstand der frühere Tag. */
        static Instant tage(Instant t, Instant[] span, double von, double bis, ZoneId zone) {
            ZonedDateTime z = t.atZone(zone);
            long k0 = Math.floorDiv(span[0].getEpochSecond() - t.getEpochSecond(), 86400) - 1;
            long k1 = Math.floorDiv(span[1].getEpochSecond() - t.getEpochSecond(), 86400) + 1;
            Instant best = null;
            double bestUeber = -1;
            for (long k = k0; k <= k1; k++) {
                Instant c = z.plusDays(k).toInstant();
                double s0 = Math.max(c.getEpochSecond() + von, span[0].getEpochSecond()), s1 = Math.min(c.getEpochSecond() + bis, span[1].getEpochSecond());
                double u = Math.max(0, s1 - s0);
                if (best == null || u > bestUeber + 1e-6) {
                    best = c;
                    bestUeber = u;
                }
            }
            return best;
        }
    }

    /**
     * Die Bilder des Bereichs: Abstand zum Bezug (s) je Bild, beschnitten auf die Zeiten, die beide Reihen haben
     * (Spanne: erster, letzter Stand).
     */
    public static List<Double> folge(Bezug z, Instant[] spanA, Instant[] spanB, double von, double bis, double schritt) {
        List<Double> r = new ArrayList<>();
        int n = (int) Math.floor((bis - von) / schritt + 1e-9) + 1;
        for (int k = 0; k < n; k++) {
            double rel = von + k * schritt;
            long ta = z.a().toEpochMilli() + Math.round(rel * 1000), tb = z.b().toEpochMilli() + Math.round(rel * 1000);
            if (ta < spanA[0].toEpochMilli() || ta > spanA[1].toEpochMilli() || tb < spanB[0].toEpochMilli() || tb > spanB[1].toEpochMilli()) continue;
            r.add(rel);
        }
        return r;
    }

    /** „+1:20 h“ / „−0:40 h“ für einen Abstand in Sekunden. */
    public static String abstand(double s) {
        long m = Math.round(Math.abs(s) / 60);
        return (s < -30 ? "−" : s > 30 ? "+" : "±") + String.format(Locale.ROOT, "%d:%02d h", m / 60, m % 60);
    }
}
