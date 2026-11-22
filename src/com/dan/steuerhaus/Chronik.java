package com.dan.steuerhaus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Die Chronik der Hochwasser (seit DB2, Werkbuch HalligHooge 169): was das Messarchiv an Ereignissen gefunden hat –
 * Landunter und Wasser über der Krone am Ort ohne Pegel (geschätzt), Sturmfluten nach BSH an den Pegeln. Der Sammler
 * ({@code messung.Sammler}) schreibt sie als Textdatei neben das Manifest ({@code chronik.datei}), aber nur, wenn sich ein
 * Ereignis ändert oder dazukommt: So hat auch ein Clone ohne Datenbank den letzten Stand, und die Datei ändert sich im
 * Git nur bei einem echten Ereignis. Die App liest nur die Datei. Nur java.*.
 * <pre>
 * erstellt   = 2026-11-22T17:07:12Z
 * ort        = Anleger Hooge
 * krone      = 2.01; Krone des Sommerdeichs
 * nass       = 2.50; Fennen nass
 * seit       = 2026-10-12T15:10:00Z
 * quelle     = …
 * ereignis.1 = LANDUNTER; ab; bis; höchst; Tiden; Höhe; Stau; Ort; Text
 * </pre>
 * Zeiten UTC (ISO 8601, auf die Sekunde), Höhen in m NHN. Beim Sturmflut-Ereignis ist die Höhe der Scheitel am Pegel mit
 * dem höchsten Wasser über dem mittleren Hochwasser (MThw), {@code stau} dieser Abstand zum MThw, {@code ort} der Pegel.
 */
public final class Chronik {

    public enum Art {
        LANDUNTER("Landunter"), KRONE("Über der Krone"), STURMFLUT("Sturmflut");

        public final String anzeige;

        Art(String anzeige) { this.anzeige = anzeige; }
    }

    /** Ein Ereignis: Art, erstes und letztes Hochwasser, Zeit des höchsten, Zahl der Tiden, Höhe, Stau, Ort, Text. */
    public record Ereignis(Art art, Instant ab, Instant bis, Instant hoechst, int tiden, double hoehe, double stau, String ort, String text) { }

    public final String ort, kroneText, nassText, quelle;
    public final double krone, nass;
    public final Instant seit, erstellt;
    public final List<Ereignis> ereignisse;

    public Chronik(String ort, double krone, String kroneText, double nass, String nassText, Instant seit, String quelle, List<Ereignis> ereignisse, Instant erstellt) {
        this.ort = ort == null ? "" : ort;
        this.krone = krone;
        this.kroneText = kroneText == null ? "" : kroneText;
        this.nass = nass;
        this.nassText = nassText == null ? "" : nassText;
        this.seit = seit;
        this.quelle = quelle == null ? "" : quelle;
        List<Ereignis> l = new ArrayList<>(ereignisse);
        l.sort(Comparator.comparing(Ereignis::ab).thenComparing(e -> e.art().ordinal()));
        this.ereignisse = Collections.unmodifiableList(l);
        this.erstellt = erstellt;
    }

    /** Das jüngste Ereignis einer Art oder null. */
    public Ereignis letztes(Art art) {
        Ereignis x = null;
        for (Ereignis e : ereignisse) if (e.art() == art) x = e;
        return x;
    }

    /**
     * Eine Zeile für die Landunter-Uhr: „Zuletzt Landunter am 26.10.2026 (3,51 m, Windstau 1,76 m); über der Krone am …“
     * (das zweite nur, wenn es jünger ist), ohne Ereignis „Seit … kein Wasser über der Krone“.
     */
    public String zuletzt(java.time.ZoneId zone) {
        java.time.format.DateTimeFormatter df = java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN);
        Ereignis lu = letztes(Art.LANDUNTER), kr = letztes(Art.KRONE);
        StringBuilder z = new StringBuilder();
        if (lu != null) z.append("Zuletzt Landunter am ").append(df.format(lu.hoechst().atZone(zone))).append(String.format(Locale.GERMAN, " (%.2f m, Windstau %.2f m)", lu.hoehe(), lu.stau()));
        if (kr != null && (lu == null || kr.hoechst().isAfter(lu.hoechst())))
            z.append(z.length() > 0 ? "; über der Krone am " : "Zuletzt über der Krone am ").append(df.format(kr.hoechst().atZone(zone))).append(String.format(Locale.GERMAN, " (%.2f m)", kr.hoehe()));
        if (z.length() == 0) z.append("Seit ").append(seit == null ? "Beginn des Messarchivs" : df.format(seit.atZone(zone))).append(" kein Wasser über der Krone");
        return z.toString();
    }

    /** Die Ereignisse, das jüngste zuerst. */
    public List<Ereignis> neuesteZuerst() {
        List<Ereignis> l = new ArrayList<>(ereignisse);
        Collections.reverse(l);
        return l;
    }

    // ------------------------------------------------------------------ Text

    static String z(double d) { return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.3f", d); }

    static String t(Instant t) { return t == null ? "" : t.truncatedTo(ChronoUnit.SECONDS).toString(); }

    static String rein(String s) { return s == null ? "" : s.replace(';', ',').replace('\n', ' ').strip(); }

    /** Der Inhalt ohne die Zeile {@code erstellt} – das, was zwei Fassungen gleich macht. */
    public String inhalt() {
        StringBuilder s = new StringBuilder();
        s.append("ort        = ").append(rein(ort)).append('\n');
        s.append("krone      = ").append(z(krone)).append("; ").append(rein(kroneText)).append('\n');
        s.append("nass       = ").append(z(nass)).append("; ").append(rein(nassText)).append('\n');
        s.append("seit       = ").append(t(seit)).append('\n');
        s.append("quelle     = ").append(rein(quelle)).append('\n');
        int i = 0;
        for (Ereignis e : ereignisse)
            s.append("ereignis.").append(++i).append(i < 10 ? "  = " : " = ").append(e.art().name()).append("; ").append(t(e.ab())).append("; ").append(t(e.bis()))
                    .append("; ").append(t(e.hoechst())).append("; ").append(e.tiden()).append("; ").append(z(e.hoehe())).append("; ").append(z(e.stau())).append("; ")
                    .append(rein(e.ort())).append("; ").append(rein(e.text())).append('\n');
        return s.toString();
    }

    static final String KOPF = "# Chronik der Hochwasser (DB2, Werkbuch HalligHooge 169): geschrieben vom Sammler des Messarchivs\n"
            + "# (com.dan.steuerhaus.messung), nur wenn ein Ereignis dazukommt oder sich ändert - nicht von Hand ändern.\n"
            + "# Zeiten UTC, Höhen m NHN. Am Ort geschätzt, an den Pegeln gemessen (ungeprüfte Rohdaten); nicht amtlich, amtlich warnen BSH und LKN.SH.\n";

    /**
     * Schreibt die Chronik, wenn sich ihr Inhalt gegenüber der Datei geändert hat (die Zeile {@code erstellt} zählt nicht);
     * gibt zurück, ob geschrieben wurde. Über eine Zwischendatei, damit die App nie eine halbe Datei liest.
     */
    public boolean schreibe(File f) throws IOException {
        String neu = inhalt();
        if (f.isFile()) {
            try {
                if (lies(f).inhalt().equals(neu)) return false;
            } catch (IOException | RuntimeException e) {
                // unlesbar: neu schreiben
            }
        }
        if (f.getParentFile() != null) Files.createDirectories(f.getParentFile().toPath());
        File tmp = new File(f.getPath() + ".neu");
        Files.writeString(tmp.toPath(), KOPF + "erstellt   = " + t(erstellt == null ? Instant.now() : erstellt) + "\n" + neu, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    public static boolean da(File f) { return f != null && f.isFile(); }

    public static Chronik lies(File f) throws IOException {
        Map<String, String> w = new TreeMap<>();
        Map<Integer, String> er = new TreeMap<>();
        for (String zeile : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String s = zeile.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p <= 0) continue;
            String k = s.substring(0, p).strip(), v = s.substring(p + 1).strip();
            if (k.startsWith("ereignis.")) {
                try {
                    er.put(Integer.parseInt(k.substring(9)), v);
                } catch (NumberFormatException x) {
                    throw new IOException(f.getName() + ": Schlüssel " + k);
                }
            } else w.put(k, v);
        }
        List<Ereignis> l = new ArrayList<>();
        for (Map.Entry<Integer, String> e : er.entrySet()) {
            String[] t = e.getValue().split(";", -1);
            if (t.length < 9) throw new IOException(f.getName() + ": ereignis." + e.getKey() + " mit " + t.length + " statt 9 Feldern");
            for (int i = 0; i < t.length; i++) t[i] = t[i].strip();
            l.add(new Ereignis(Art.valueOf(t[0]), zeit(t[1]), zeit(t[2]), zeit(t[3]), Integer.parseInt(t[4]), zahl(t[5]), zahl(t[6]), t[7], t[8]));
        }
        String[] kr = w.getOrDefault("krone", "").split(";", 2), na = w.getOrDefault("nass", "").split(";", 2);
        return new Chronik(w.get("ort"), zahl(kr[0]), kr.length > 1 ? kr[1].strip() : "", zahl(na[0]), na.length > 1 ? na[1].strip() : "", zeit(w.get("seit")),
                w.get("quelle"), l, zeit(w.get("erstellt")));
    }

    static Instant zeit(String s) { return s == null || s.isBlank() ? null : Instant.parse(s.strip()); }

    static double zahl(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        return Double.parseDouble(s.strip());
    }
}
