package com.dan.steuerhaus.gezeiten;

import com.dan.core.astro.Partialtide;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Konstanten eines Orts für den Tidenkalender (seit Z1, Werkbuch HalligHooge 155), als Textdatei im Projekt
 * (Vorgabe {@code <Szenendatei>.gezeiten}); einmal von der Ausgabe gerechnet ({@code --gezeiten}), danach nur gelesen.
 * <pre>
 * ort      = Anleger Hooge; 471381; 6047983          # Name; Ost; Nord (UTM 32)
 * mittel   = -0.087                                  # Z₀ (m NHN)
 * tide     = M2; 1.2345; 340.12                      # Partialtide; Amplitude (m); Phase G (Grad) – je Tide eine Zeile
 * bezug    = Text                                    # woher die Konstanten kommen
 * marke    = morpho; HW; 2026-10-14T12:20:00Z; 1.61  # Hoch-/Niedrigwasser des Modells am Ort (zum Vergleich)
 * modell   = morpho; Name des Szenarios
 * pruef.N  = Text                                    # Prüfwerte der Rechnung
 * bezug.pegel = PELLWORM_ANLEGER; 9550021; -5.027; Pellworm Anleger   # seit Z2: der gemessene Bezugspegel (Kennung; Nummer bei PEGELONLINE; Pegelnull m NHN; Name)
 * bezug.z0    = 0.074                                # seit Z2: Z₀ am Bezug
 * bezug.tide  = M2; 1.5; 347.0                       # seit Z2: seine Konstanten (für den Windstau aus der Messung)
 * bezug2.pegel, bezug2.z0, bezug2.tide                # seit Z2b: ein zweiter gemessener Pegel (der Windstau ist das Mittel)
 * herkunft    = aus 30 Tagen Messung am Pegel …       # seit DB5: die Zeile im Kalender; dazu herkunft.beginn, .bis, .faktor, .pruef
 * verlauf.N   = Stand; von; bis; Tage; Tiden; Faktor; geprüft an; Höhe; Zeit; vorher Höhe; vorher Zeit; woher
 * </pre>
 */
public final class Gezeitentafel {
    /** Eine Marke des Modells am Ort. */
    public record Marke(String szenario, Vorhersage.Art art, Instant zeit, double hoehe) { }

    /**
     * Seit Z2b: ein gemessener Pegel für den Windstau – Schlüssel in der Datei (bezug, bezug2, …), Kennung im Gebiet,
     * Nummer bei PEGELONLINE, Pegelnull (m NHN), Name, Z₀ und Konstanten.
     */
    public record Bezug(String schluessel, String kennung, String nummer, double pegelnull, String anzeige, double z0, List<Vorhersage.Konstante> tiden) {
        public Vorhersage vorhersage() { return new Vorhersage(z0, tiden); }

        /** Der Name bis zum ersten Leerzeichen oder zur Klammer: „Pellworm“, „Wittdün“. */
        public String kurz() {
            String a = anzeige.strip();
            int i = a.indexOf(' '), k = a.indexOf('(');
            int e = i < 0 ? k : k < 0 ? i : Math.min(i, k);
            return e > 0 ? a.substring(0, e).strip() : a;
        }
    }

    public final File datei;
    public final String ort;
    public final double ost, nord, mittel;
    public final List<Vorhersage.Konstante> tiden;
    public final List<Marke> marken;
    /** Szenario → Name. */
    public final Map<String, String> modelle;
    /** Alle übrigen Zeilen (bezug, uebertrag.*, pruef.*, quelle.*, gerechnet, kennung). */
    public final Map<String, String> kopf;
    /** Seit Z2: der gemessene Bezugspegel (Name, Nummer bei PEGELONLINE, Pegelnull) und seine Konstanten; leer in älteren Dateien. */
    public final String bezugName, bezugNummer, bezugAnzeige;
    public final double bezugPegelnull, bezugZ0;
    public final List<Vorhersage.Konstante> bezugTiden;
    /** Seit Z2b: alle gemessenen Pegel mit Konstanten (bezug, bezug2, …), in dieser Reihenfolge; leer vor Z2. */
    public final List<Bezug> bezuege;

    private Gezeitentafel(File datei, String ort, double ost, double nord, double mittel, List<Vorhersage.Konstante> tiden, List<Marke> marken, Map<String, String> modelle,
                          Map<String, String> kopf, Map<String, List<Vorhersage.Konstante>> btiden) {
        this.datei = datei;
        this.ort = ort;
        this.ost = ost;
        this.nord = nord;
        this.mittel = mittel;
        this.tiden = Collections.unmodifiableList(tiden);
        this.marken = Collections.unmodifiableList(marken);
        this.modelle = Collections.unmodifiableMap(modelle);
        this.kopf = Collections.unmodifiableMap(kopf);
        this.bezugTiden = Collections.unmodifiableList(btiden.getOrDefault("bezug", List.of()));
        String[] bp = kopf.getOrDefault("bezug.pegel", "").split(";");
        bezugName = bp.length > 0 ? bp[0].strip() : "";
        bezugNummer = bp.length > 1 ? bp[1].strip() : "";
        bezugPegelnull = bp.length > 2 ? zahl(bp[2]) : Double.NaN;
        bezugAnzeige = bp.length > 3 ? bp[3].strip() : anzeige(bezugName);
        bezugZ0 = kopf.containsKey("bezug.z0") ? zahl(kopf.get("bezug.z0")) : Double.NaN;
        List<Bezug> bz = new ArrayList<>();
        for (Map.Entry<String, List<Vorhersage.Konstante>> e : btiden.entrySet()) {
            String sk = e.getKey();
            String[] q = kopf.getOrDefault(sk + ".pegel", "").split(";");
            double z0 = kopf.containsKey(sk + ".z0") ? zahl(kopf.get(sk + ".z0")) : Double.NaN;
            if (q.length < 3 || Double.isNaN(z0) || e.getValue().isEmpty()) continue;
            bz.add(new Bezug(sk, q[0].strip(), q[1].strip(), zahl(q[2]), q.length > 3 ? q[3].strip() : anzeige(q[0].strip()), z0, List.copyOf(e.getValue())));
        }
        this.bezuege = Collections.unmodifiableList(bz);
    }

    public Vorhersage vorhersage() { return new Vorhersage(mittel, tiden); }

    /** PELLWORM_ANLEGER → Pellworm Anleger. */
    static String anzeige(String kennung) {
        StringBuilder b = new StringBuilder();
        for (String w : kennung.split("[_\\s]+")) {
            if (w.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(w.charAt(0)).append(w.substring(1).toLowerCase(Locale.GERMAN));
        }
        return b.toString();
    }

    /** Seit Z2: die Vorhersage am gemessenen Bezugspegel; null, wenn die Datei sie nicht hält (vor Z2 gerechnet). */
    public Vorhersage bezugVorhersage() { return bezugTiden.isEmpty() || Double.isNaN(bezugZ0) ? null : new Vorhersage(bezugZ0, bezugTiden); }

    public static Gezeitentafel lies(File datei) throws IOException {
        if (!datei.isFile()) throw new IOException("Gezeiten: " + datei + " fehlt (einmal mit der Ausgabe --gezeiten rechnen)");
        String ort = "Ort";
        double ost = Double.NaN, nord = Double.NaN, mittel = 0;
        List<Vorhersage.Konstante> tiden = new ArrayList<>();
        Map<String, List<Vorhersage.Konstante>> btiden = new LinkedHashMap<>();
        List<Marke> marken = new ArrayList<>();
        Map<String, String> modelle = new LinkedHashMap<>(), kopf = new LinkedHashMap<>();
        int nr = 0;
        for (String z : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) {
            nr++;
            String s = z.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p <= 0) continue;
            String k = s.substring(0, p).strip(), v = s.substring(p + 1).strip();
            String[] t = v.split(";");
            for (int i = 0; i < t.length; i++) t[i] = t[i].strip();
            try {
                switch (k) {
                    case "ort" -> {
                        ort = t[0];
                        ost = zahl(t[1]);
                        nord = zahl(t[2]);
                    }
                    case "mittel" -> mittel = zahl(v);
                    case "tide" -> tiden.add(konstante(t));
                    case "marke" -> marken.add(new Marke(t[0], t[1].startsWith("H") ? Vorhersage.Art.HOCHWASSER : Vorhersage.Art.NIEDRIGWASSER, Instant.parse(t[2]), zahl(t[3])));
                    case "modell" -> modelle.put(t[0], t.length > 1 ? t[1] : t[0]);
                    default -> {
                        // seit Z2/Z2b: bezug.tide, bezug2.tide, …
                        if (k.matches("bezug\\d*\\.tide")) btiden.computeIfAbsent(k.substring(0, k.length() - 5), x -> new ArrayList<>()).add(konstante(t));
                        else kopf.put(k, v);
                    }
                }
            } catch (RuntimeException e) {
                throw new IOException("Gezeiten: " + datei.getName() + ", Zeile " + nr + ": " + e.getMessage(), e);
            }
        }
        if (tiden.isEmpty()) throw new IOException("Gezeiten: " + datei.getName() + " hat keine Tiden");
        return new Gezeitentafel(datei, ort, ost, nord, mittel, tiden, marken, modelle, kopf, btiden);
    }

    static Vorhersage.Konstante konstante(String[] t) {
        Partialtide pt = Partialtide.nach(t[0]);
        if (pt == null) throw new IllegalArgumentException("unbekannte Tide " + t[0]);
        return new Vorhersage.Konstante(pt, zahl(t[1]), zahl(t[2]));
    }

    /**
     * Schreibt die Datei (Kopfzeilen in der gegebenen Reihenfolge); {@code bezugTiden}: je Schlüssel (bezug, bezug2, …) die
     * Konstanten des gemessenen Pegels (null: keine).
     */
    public static void schreibe(File datei, String ort, double ost, double nord, double mittel, List<Vorhersage.Konstante> tiden, Map<String, String> modelle, List<Marke> marken,
                                Map<String, String> kopf, Map<String, List<Vorhersage.Konstante>> bezugTiden) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# Gezeiten für den Tidenkalender (Z1, Werkbuch HalligHooge 155; seit DB5, Werkbuch 176, wächst sie mit der Messung) – gerechnet von der Ausgabe\n");
        s.append("# (--gezeiten, mit dem Modell) oder vom Sammler (--gezeiten, aus dem Messarchiv, nur wenn die Prüfung besser ist); nicht von Hand ändern.\n");
        s.append("# Astronomische Gezeit ohne Wind und Luftdruck. Nicht zur Navigation.\n");
        s.append(String.format(Locale.ROOT, "ort    = %s; %.1f; %.1f%n", ort, ost, nord));
        s.append(String.format(Locale.ROOT, "mittel = %.4f%n", mittel));
        for (Vorhersage.Konstante k : tiden) s.append(String.format(Locale.ROOT, "tide   = %s; %.5f; %.3f%n", k.tide().name, k.amplitude(), k.phaseGrad()));
        for (Map.Entry<String, String> e : kopf.entrySet()) s.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        if (bezugTiden != null)
            for (Map.Entry<String, List<Vorhersage.Konstante>> e : bezugTiden.entrySet())
                for (Vorhersage.Konstante k : e.getValue()) s.append(String.format(Locale.ROOT, "%s.tide = %s; %.5f; %.3f%n", e.getKey(), k.tide().name, k.amplitude(), k.phaseGrad()));
        for (Map.Entry<String, String> e : modelle.entrySet()) s.append("modell = ").append(e.getKey()).append("; ").append(e.getValue()).append('\n');
        for (Marke m : marken)
            s.append(String.format(Locale.ROOT, "marke  = %s; %s; %s; %.3f%n", m.szenario(), m.art() == Vorhersage.Art.HOCHWASSER ? "HW" : "NW", m.zeit(), m.hoehe()));
        File d = datei.getAbsoluteFile().getParentFile();
        if (d != null && !d.isDirectory() && !d.mkdirs()) throw new IOException("Gezeiten: Ordner nicht anlegbar: " + d);
        Files.writeString(datei.toPath(), s.toString(), StandardCharsets.UTF_8);
    }

    static double zahl(String s) { return Double.parseDouble(s.strip().replace(',', '.')); }
}
