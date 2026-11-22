package com.dan.steuerhaus.regie;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ein Regiebuch (seit W3, Werkbuch HalligHooge 116, F193 A), im Format aus Rungholt (aus Glacier):
 * <pre>
 * # Kommentar
 * Regiebuch: Landunter an der Hanswarft
 * Beschreibung: Sturmflut am 25. Oktober 2026 …
 * Länge: 1:40
 * 0:00 szenario sturm
 * 0:00 zeit 25.10.2026 21:30 | 25. Oktober, 21:30: der Wind steht aus West
 * 0:12 warte landunter | Das Wasser läuft über den Sommerdeich
 * 0:20 fahrt Um die Hanswarft
 *
 * Fahrt: Um die Hanswarft
 * 0:00 470800 6046210 12 nhn 0 -2 50
 * 0:08 …
 * Ende
 * </pre>
 * Eine Zeile je Befehl: Zeit in echten Sekunden seit dem Start ({@code m:ss} oder {@code m:ss.s}), Befehl, Argumente,
 * nach „ | “ der Text für die Untertitel. Mehrere Befehle zur selben Zeit sind ein Stichwort. Fahrten stehen als Block:
 * je Schlüsselbild Zeit, Ost, Nord, Höhe, {@code nhn} oder {@code boden}, Richtung, Neigung, Bildwinkel.
 * <p>
 * Lesen meldet jede falsche Zeile mit ihrer Nummer ({@link #fehler}); {@link #text} schreibt das Buch so, dass es wieder
 * gleich gelesen wird (Prüfstein 3). Was ein Befehl tut, entscheidet die Anwendung ({@link Regie.Ctx}).
 */
public final class Regiebuch {
    /** Eine Zeile: Sekunde, Befehl, Argumente, Text (ohne „|“), Nummer in der Datei (1 …). */
    public record Zeile(double sekunde, String befehl, String argumente, String text, int nr) {
        public String ausgabe() {
            return zeit(sekunde) + " " + befehl + (argumente.isEmpty() ? "" : " " + argumente) + (text.isEmpty() ? "" : " | " + text);
        }
    }

    /** Ein Fehler beim Lesen. */
    public record Fehler(int zeile, String meldung) {
        @Override public String toString() { return "Zeile " + zeile + ": " + meldung; }
    }

    /** Die Befehle und ihre Form (für die Prüfung beim Lesen und die Hilfe). */
    public static final Map<String, String> BEFEHLE;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("szenario", "szenario <Schlüssel>");
        m.put("ansicht", "ansicht <Schlüssel>");
        m.put("fahrt", "fahrt <Name>");
        m.put("zeit", "zeit [TT.MM.JJJJ] hh:mm");
        m.put("raffer", "raffer <Zahl>");
        m.put("halt", "halt");
        m.put("weiter", "weiter");
        m.put("ebene", "ebene <häuser|gestalt|bewuchs|salzwiese|brandung> an|aus");
        m.put("messblick", "messblick aus | messblick <partikel,lic,linien>");
        m.put("linien", "linien wasserstand|tiefe|trübung");
        m.put("einblendung", "einblendung alle|aus|<teil,teil>");
        m.put("thema", "thema labor|nautik");
        m.put("warte", "warte uhr [TT.MM.] hh:mm | warte pegel [>|<] <m NHN> | warte landunter | warte fahrt | warte klick | warte szene");
        m.put("sage", "sage <Text>");
        m.put("lesezeichen", "lesezeichen <Name>");
        // seit Teil C (Werkbuch HalligHooge 134, F227 A): ein Buch spielt an dieser Stelle ein anderes ({@link #aufgeloest})
        m.put("folge", "folge <Name eines Regiebuchs>");
        // seit X2 (Werkbuch HalligHooge 146, F244 A): Kapitel – eine Tafel mit Titel und Text, eine Sonde am Ort
        m.put("tafel", "tafel <Titel> | <Text>");
        m.put("sonde", "sonde <Ost> <Nord> [Name]");
        // seit X3 (Werkbuch HalligHooge 148): eine Zwischentafel unterwegs (Rundgang); sie zählt nicht als Halt
        m.put("unterwegs", "unterwegs <Titel> | <Text>");
        BEFEHLE = Collections.unmodifiableMap(m);
    }

    public final String name, beschreibung;
    public final double laenge;
    public final List<Zeile> zeilen;
    public final Map<String, Fahrt> fahrten;
    public final List<Fehler> fehler;
    /** Woher (Datei oder Name). */
    public final String quelle;

    private Regiebuch(String name, String beschreibung, double laenge, List<Zeile> zeilen, Map<String, Fahrt> fahrten, List<Fehler> fehler, String quelle) {
        this.name = name;
        this.beschreibung = beschreibung;
        this.laenge = laenge;
        this.zeilen = List.copyOf(zeilen);
        this.fahrten = Collections.unmodifiableMap(new LinkedHashMap<>(fahrten));
        this.fehler = List.copyOf(fehler);
        this.quelle = quelle;
    }

    /** Ein Buch aus Teilen (etwa nur mit Fahrten, für die Aufnahmen). */
    public static Regiebuch aus(String name, String beschreibung, double laenge, List<Zeile> zeilen, Map<String, Fahrt> fahrten) {
        return new Regiebuch(name, beschreibung, laenge, zeilen, fahrten, List.of(), name);
    }

    public static Regiebuch lies(Path datei) throws IOException {
        return lies(Files.readString(datei, StandardCharsets.UTF_8), datei.toString());
    }

    /** Liest ein Buch aus Text; Fehler stehen in {@link #fehler}, die übrigen Zeilen gelten. */
    public static Regiebuch lies(String text, String quelle) {
        String name = "", beschreibung = "";
        double laenge = 0;
        List<Zeile> zeilen = new ArrayList<>();
        Map<String, Fahrt> fahrten = new LinkedHashMap<>();
        List<Fehler> fehler = new ArrayList<>();
        String fahrtName = null;
        int fahrtZeile = 0;
        List<Fahrt.Schluesselbild> bilder = new ArrayList<>();
        String[] z = text.split("\r?\n", -1);
        for (int i = 0; i < z.length; i++) {
            int nr = i + 1;
            String roh = z[i].strip();
            if (roh.isEmpty() || roh.startsWith("#")) continue;
            if (fahrtName != null) {
                if (roh.equalsIgnoreCase("Ende")) {
                    fahrtSchliessen(fahrtName, fahrtZeile, bilder, fahrten, fehler);
                    fahrtName = null;
                    continue;
                }
                try {
                    bilder.add(schluesselbild(roh));
                } catch (IllegalArgumentException e) {
                    fehler.add(new Fehler(nr, e.getMessage()));
                }
                continue;
            }
            int doppel = roh.indexOf(':');
            String kopf = doppel > 0 ? roh.substring(0, doppel).strip() : "";
            if (kopf.equals("Regiebuch")) { name = roh.substring(doppel + 1).strip(); continue; }
            if (kopf.equals("Beschreibung")) { beschreibung = roh.substring(doppel + 1).strip(); continue; }
            if (kopf.equals("Länge")) {
                try {
                    laenge = sekunden(roh.substring(doppel + 1).strip());
                } catch (IllegalArgumentException e) {
                    fehler.add(new Fehler(nr, "Länge: " + e.getMessage()));
                }
                continue;
            }
            if (kopf.equals("Fahrt")) {
                fahrtName = roh.substring(doppel + 1).strip();
                fahrtZeile = nr;
                bilder = new ArrayList<>();
                if (fahrtName.isEmpty()) fehler.add(new Fehler(nr, "Fahrt ohne Namen"));
                continue;
            }
            try {
                zeilen.add(zeile(roh, nr));
            } catch (IllegalArgumentException e) {
                fehler.add(new Fehler(nr, e.getMessage()));
            }
        }
        if (fahrtName != null) {
            fehler.add(new Fehler(z.length, "Fahrt „" + fahrtName + "“ ohne „Ende“"));
            fahrtSchliessen(fahrtName, fahrtZeile, bilder, fahrten, fehler);
        }
        if (name.isEmpty()) fehler.add(new Fehler(1, "kein „Regiebuch: Name“"));
        // eine Fahrt muss es geben (im Buch oder, mit Vorsicht, in einem anderen: das prüft die Anwendung)
        List<Zeile> sortiert = new ArrayList<>(zeilen);
        sortiert.sort((a, b) -> Double.compare(a.sekunde, b.sekunde));      // stabil: gleiche Sekunde in der Reihenfolge der Datei
        double ende = 0;
        for (Zeile x : sortiert) ende = Math.max(ende, x.sekunde);
        return new Regiebuch(name, beschreibung, Math.max(laenge, ende), sortiert, fahrten, fehler, quelle);
    }

    private static void fahrtSchliessen(String name, int nr, List<Fahrt.Schluesselbild> bilder, Map<String, Fahrt> fahrten, List<Fehler> fehler) {
        try {
            if (fahrten.containsKey(name)) throw new IllegalArgumentException("Fahrt „" + name + "“ steht zweimal");
            fahrten.put(name, new Fahrt(name, bilder));
        } catch (IllegalArgumentException e) {
            fehler.add(new Fehler(nr, e.getMessage()));
        }
    }

    static Zeile zeile(String roh, int nr) {
        String text = "";
        int bar = roh.indexOf(" | ");
        if (bar < 0 && roh.endsWith(" |")) bar = roh.length() - 2;
        if (bar >= 0) {
            text = roh.substring(Math.min(roh.length(), bar + 3)).strip();
            roh = roh.substring(0, bar).strip();
        }
        String[] t = roh.split("\\s+", 3);
        if (t.length < 2) throw new IllegalArgumentException("Zeit und Befehl erwartet: „" + roh + "“");
        double s = sekunden(t[0]);
        String befehl = t[1].toLowerCase(Locale.GERMAN), arg = t.length > 2 ? t[2].strip() : "";
        if (!BEFEHLE.containsKey(befehl)) throw new IllegalArgumentException("unbekannter Befehl „" + t[1] + "“ (bekannt: " + String.join(", ", BEFEHLE.keySet()) + ")");
        pruefe(befehl, arg);
        return new Zeile(s, befehl, arg, text, nr);
    }

    /** Prüft die Argumente eines Befehls; wirft mit der erwarteten Form. */
    static void pruefe(String befehl, String arg) {
        String[] a = arg.isEmpty() ? new String[0] : arg.split("\\s+");
        boolean ok = switch (befehl) {
            case "szenario", "ansicht", "fahrt", "sage", "lesezeichen", "folge", "tafel", "unterwegs" -> a.length >= 1;
            case "sonde" -> a.length >= 2 && zahl(a[0]) && zahl(a[1]);
            case "halt", "weiter" -> a.length == 0;
            case "raffer" -> a.length == 1 && zahl(a[0]);
            case "zeit" -> a.length == 1 && uhr(a[0]) || a.length == 2 && datum(a[0], true) && uhr(a[1]);
            case "ebene" -> a.length == 2 && List.of("häuser", "haeuser", "gestalt", "bewuchs", "salzwiese", "brandung").contains(a[0].toLowerCase(Locale.GERMAN))
                    && (a[1].equals("an") || a[1].equals("aus"));
            case "messblick", "einblendung" -> a.length == 1;
            case "linien" -> a.length == 1 && List.of("wasserstand", "tiefe", "trübung", "truebung").contains(a[0].toLowerCase(Locale.GERMAN));
            case "thema" -> a.length == 1 && List.of("labor", "nautik").contains(a[0].toLowerCase(Locale.GERMAN));
            case "warte" -> a.length >= 1 && switch (a[0]) {
                case "uhr" -> a.length == 2 && uhr(a[1]) || a.length == 3 && datum(a[1], false) && uhr(a[2]);
                case "pegel" -> a.length == 2 && zahl(a[1]) || a.length == 3 && (a[1].equals(">") || a[1].equals("<")) && zahl(a[2]);
                case "landunter", "fahrt", "klick", "szene" -> a.length == 1;      // szene seit X3b: das Szenario ist offen
                default -> false;
            };
            default -> false;
        };
        if (!ok) throw new IllegalArgumentException("Form: " + BEFEHLE.get(befehl) + " – nicht „" + befehl + " " + arg + "“");
    }

    /**
     * Seit Teil C (F227 A): ob ein {@code warte pegel …} erfüllt ist. {@code pegel x} und {@code pegel > x}: das Wasser
     * steht über x; {@code pegel < x}: unter x, und ein trockener Pegel (NaN) gilt dann als darunter.
     *
     * @param argumente die Argumente des warte, etwa „pegel < 0,5“
     * @param pegel     Pegel jetzt (m NHN; NaN: trocken oder kein Pegel)
     */
    public static boolean pegelErreicht(String argumente, double pegel) {
        String[] a = argumente.strip().split("\\s+");
        boolean unter = a.length == 3 && a[1].equals("<");
        double x = Double.parseDouble(a[a.length - 1].replace(',', '.'));
        if (Double.isNaN(pegel)) return unter;
        return unter ? pegel < x : pegel > x;
    }

    /**
     * Seit Teil C (Werkbuch HalligHooge 134, F227 A): das Buch mit aufgelöstem {@code folge}. Jede Zeile
     * {@code m:ss folge <Name>} wird durch die Zeilen des genannten Buchs ersetzt, um m:ss verschoben; seine Fahrten
     * kommen dazu. So spielt ein Gesamtfilm seine Teile hintereinander. Die Uhr des Regiebuchs ist Filmzeit ohne die
     * Zeit der {@code warte} (F195 A); darum beginnt ein Teil genau dort, wo er steht, auch wenn der Teil davor warten
     * musste. Fehler (Name unbekannt, Teile überlappen, ein Teil enthält selbst {@code folge}, eine Fahrt gleichen Namens
     * mit anderem Inhalt) stehen in {@link #fehler}; die übrigen Zeilen gelten. Ohne {@code folge} kommt das Buch selbst
     * zurück.
     *
     * @param finde ein Buch zu seinem Namen (null: unbekannt)
     */
    public Regiebuch aufgeloest(java.util.function.Function<String, Regiebuch> finde) {
        boolean hat = false;
        for (Zeile z : zeilen) if (z.befehl.equals("folge")) hat = true;
        if (!hat) return this;
        List<Zeile> neu = new ArrayList<>();
        Map<String, Fahrt> f = new LinkedHashMap<>(fahrten);
        List<Fehler> fe = new ArrayList<>(fehler);
        double ende = laenge, frei = 0;
        String vorher = null;
        for (Zeile z : zeilen) {
            if (!z.befehl.equals("folge")) {
                neu.add(z);
                continue;
            }
            Regiebuch teil = finde.apply(z.argumente);
            if (teil == null) {
                fe.add(new Fehler(z.nr, "folge: Regiebuch „" + z.argumente + "“ gibt es nicht"));
                continue;
            }
            if (z.sekunde < frei - 1e-9) fe.add(new Fehler(z.nr, "folge: „" + teil.name + "“ beginnt bei " + zeit(z.sekunde) + ", „" + vorher + "“ läuft bis " + zeit(frei)));
            for (Zeile t : teil.zeilen) {
                if (t.befehl.equals("folge")) {
                    fe.add(new Fehler(z.nr, "folge: „" + teil.name + "“ enthält selbst folge (Zeile " + t.nr + ")"));
                    continue;
                }
                neu.add(new Zeile(z.sekunde + t.sekunde, t.befehl, t.argumente, t.text, z.nr));
            }
            for (Fahrt x : teil.fahrten.values()) {
                Fahrt da = f.get(x.name);
                if (da == null) f.put(x.name, x);
                else if (!da.bilder().equals(x.bilder())) fe.add(new Fehler(z.nr, "folge: Fahrt „" + x.name + "“ steht in „" + teil.name + "“ anders als zuvor"));
            }
            frei = z.sekunde + teil.laenge;
            vorher = teil.name;
            ende = Math.max(ende, frei);
        }
        neu.sort((a, b) -> Double.compare(a.sekunde, b.sekunde));        // stabil
        return new Regiebuch(name, beschreibung, ende, neu, f, fe, quelle);
    }

    private static boolean zahl(String s) {
        try {
            Double.parseDouble(s.replace(',', '.'));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static boolean uhr(String s) { return s.matches("([01]?\\d|2[0-3]):[0-5]\\d"); }

    static boolean datum(String s, boolean mitJahr) { return s.matches(mitJahr ? "\\d{1,2}\\.\\d{1,2}\\.\\d{4}" : "\\d{1,2}\\.\\d{1,2}\\.(\\d{4})?"); }

    static Fahrt.Schluesselbild schluesselbild(String roh) {
        String[] t = roh.split("\\s+");
        if (t.length != 8) throw new IllegalArgumentException("Schlüsselbild: Zeit Ost Nord Höhe nhn|boden Richtung Neigung Bildwinkel – nicht „" + roh + "“");
        String art = t[4].toLowerCase(Locale.GERMAN);
        if (!art.equals("nhn") && !art.equals("boden")) throw new IllegalArgumentException("Schlüsselbild: Höhe „nhn“ oder „boden“, nicht „" + t[4] + "“");
        try {
            double[] v = new double[7];
            int[] spalte = {1, 2, 3, 5, 6, 7};
            for (int k = 0; k < 6; k++) v[k] = Double.parseDouble(t[spalte[k]].replace(',', '.'));
            if (v[5] <= 0 || v[5] >= 180) throw new IllegalArgumentException("Schlüsselbild: Bildwinkel " + v[5] + "° (0 … 180)");
            return new Fahrt.Schluesselbild(sekunden(t[0]), v[0], v[1], v[2], art.equals("boden"), v[3], v[4], v[5]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Schlüsselbild: Zahl erwartet in „" + roh + "“");
        }
    }

    /** {@code m:ss}, {@code m:ss.s} oder {@code h:mm:ss} in Sekunden. */
    public static double sekunden(String s) {
        String[] t = s.split(":");
        try {
            if (t.length == 2) return Integer.parseInt(t[0]) * 60 + Double.parseDouble(t[1]);
            if (t.length == 3) return Integer.parseInt(t[0]) * 3600 + Integer.parseInt(t[1]) * 60 + Double.parseDouble(t[2]);
        } catch (NumberFormatException e) {
            // unten
        }
        throw new IllegalArgumentException("Zeit „" + s + "“ (m:ss)");
    }

    /** Sekunden als {@code m:ss} (mit Zehnteln, wenn nötig). */
    public static String zeit(double s) {
        long zehntel = Math.round(s * 10);
        long m = zehntel / 600;
        double r = (zehntel - m * 600) / 10.0;
        return zehntel % 10 == 0 ? String.format(Locale.ROOT, "%d:%02d", m, (long) r) : String.format(Locale.ROOT, "%d:%04.1f", m, r);
    }

    /** Das Buch als Text, wieder lesbar (Prüfstein 3). */
    public String text() {
        StringBuilder b = new StringBuilder();
        b.append("Regiebuch: ").append(name).append('\n');
        if (!beschreibung.isEmpty()) b.append("Beschreibung: ").append(beschreibung).append('\n');
        b.append("Länge: ").append(zeit(laenge)).append('\n');
        for (Zeile z : zeilen) b.append(z.ausgabe()).append('\n');
        for (Fahrt f : fahrten.values()) {
            b.append('\n').append("Fahrt: ").append(f.name).append('\n');
            for (Fahrt.Schluesselbild s : f.bilder()) b.append(s.zeile()).append('\n');
            b.append("Ende").append('\n');
        }
        return b.toString();
    }

    @Override public String toString() { return name + " (" + zeit(laenge) + ", " + zeilen.size() + " Zeilen, " + fahrten.size() + " Fahrten)"; }
}
