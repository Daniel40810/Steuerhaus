package com.dan.steuerhaus.atlas;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Der Atlas einer App (seit X1, Werkbuch HalligHooge 145): die Karte von oben in mehreren Ebenen (etwa Niedrigwasser,
 * Hochwasser, Landunter), die Orte mit ihren Werten und die Texte der Infotafeln. Liest nur Dateien im Projekt, keine
 * Daten des Gebiets – die Startseite steht darum sofort, auch wenn die Platte mit den Daten noch schläft.
 * <ul>
 * <li><b>Werte</b> {@code <Ordner der Szenendatei>/atlas/atlas.werte}: geschrieben von {@link AtlasBau} (Aufruf
 * {@code --atlas}), nicht von Hand. Format wie das Manifest.</li>
 * <li><b>Texte</b> {@code atlas.texte} im Manifest (Vorgabe {@code <Name der Szenendatei>.atlas}): von Hand, je Ort
 * {@code tafel.<schluessel>.text}, {@code .text2}, {@code .quelle}, {@code .ansicht}, {@code .lesezeichen}; dazu
 * {@code start.titel}, {@code start.text}, {@code start.text2}, {@code start.quelle}. Der Schlüssel eines Orts ist sein
 * Name klein, Umlaute ausgeschrieben, nur Buchstaben und Ziffern ({@link #schluessel}).</li>
 * </ul>
 */
public final class Atlas {
    /** Eine Ebene der Karte: Kennung, Titel, Zeit (null: ohne), Bilddatei, Anteil Wasser, Pegel am Pegelort (m NHN). */
    public record Ebene(String kennung, String titel, Instant zeit, File bild, double wasser, double pegel) { }

    /**
     * Ein Ort mit seinen Werten (NaN oder null: kein Wert): Höhe am Punkt, Kuppe (größte Höhe im Umkreis von 50 m),
     * Fuß (unteres Zehntel im Ring 40–120 m), Häuser im Umkreis von 120 m, wann das Wasser im Sturm den Fuß erreicht,
     * Anteil der Zeit trocken am ruhigen Tag.
     */
    public record Ort(String art, String name, double ost, double nord, double hoehe, double kuppe, double fuss, int haeuser, Instant wasserAmFuss,
                      double trocken) {
        public String schluessel() { return Atlas.schluessel(name); }
    }

    public final File ordner;
    /** Rechteck der Karte (Ost, Nord, Ost, Nord; UTM) und Schritt (m). */
    public final double[] karte;
    public final double zelle;
    public final List<Ebene> ebenen;
    public final List<Ort> orte;
    /** Sturm: Name des Szenarios, Scheitel am Pegel (m NHN) und seine Zeit; Landunter: Marke, Beginn, Ende (null: nicht erreicht). */
    public final String sturmName;
    public final double scheitel, marke;
    public final Instant scheitelZeit, landunterAb, landunterBis;
    public final String erzeugt;
    private final Manifest texte;

    private Atlas(File ordner, double[] karte, double zelle, List<Ebene> ebenen, List<Ort> orte, String sturmName, double scheitel, Instant scheitelZeit,
                  double marke, Instant ab, Instant bis, String erzeugt, Manifest texte) {
        this.ordner = ordner;
        this.karte = karte;
        this.zelle = zelle;
        this.ebenen = Collections.unmodifiableList(ebenen);
        this.orte = Collections.unmodifiableList(orte);
        this.sturmName = sturmName;
        this.scheitel = scheitel;
        this.scheitelZeit = scheitelZeit;
        this.marke = marke;
        this.landunterAb = ab;
        this.landunterBis = bis;
        this.erzeugt = erzeugt;
        this.texte = texte;
    }

    public static final String WERTE = "atlas.werte";

    /** Der Ordner des Atlas: {@code atlas} neben der Szenendatei. */
    public static File ordner(File szenenDatei) { return new File(szenenDatei.getAbsoluteFile().getParentFile(), "atlas"); }

    /** Die Datei der Texte nach dem Manifest. */
    public static File texte(Manifest m, File szenenDatei) {
        String t = m.text("atlas.texte", "");
        if (!t.isEmpty()) return m.relativ(t);
        return new File(szenenDatei.getAbsoluteFile().getParentFile(), szenenDatei.getName().replaceFirst("\\.[^.]*$", "") + ".atlas");
    }

    /** Gibt es einen Atlas (die Werte)? */
    public static boolean da(File szenenDatei) { return new File(ordner(szenenDatei), WERTE).isFile(); }

    /** Liest Werte und Texte; ohne Werte eine {@link IOException}. Fehlt die Datei der Texte, bleiben die Tafeln ohne Text. */
    public static Atlas lies(Manifest m, File szenenDatei) throws IOException {
        File o = ordner(szenenDatei);
        File w = new File(o, WERTE);
        if (!w.isFile()) throw new IOException("Atlas: " + w + " fehlt (einmal --atlas rechnen)");
        Manifest wm = Manifest.lies(w);
        File tf = texte(m, szenenDatei);
        Manifest tm = tf.isFile() ? Manifest.lies(tf) : null;
        double[] k = zahlen(wm.text("karte", ""));
        if (k == null || k.length < 4) throw new IOException("Atlas: " + w + " ohne karte");
        List<Ebene> eb = new ArrayList<>();
        for (String z : wm.nummern("ebene.")) {
            String[] t = z.split(";");
            if (t.length < 4) continue;
            eb.add(new Ebene(t[0].strip(), t[1].strip(), zeit(t[2]), new File(o, t[3].strip()), t.length > 4 ? zahl(t[4]) : Double.NaN, t.length > 5 ? zahl(t[5]) : Double.NaN));
        }
        List<Ort> orte = new ArrayList<>();
        for (String z : wm.nummern("ort.")) {
            String[] t = z.split(";");
            if (t.length < 10) continue;
            orte.add(new Ort(t[0].strip(), t[1].strip(), zahl(t[2]), zahl(t[3]), zahl(t[4]), zahl(t[5]), zahl(t[6]), (int) Math.round(nullWenn(zahl(t[7]))), zeit(t[8]),
                    zahl(t[9])));
        }
        String[] st = wm.text("sturm", ";;").split(";", -1);
        String[] lu = wm.text("landunter", ";;").split(";", -1);
        return new Atlas(o, k, zahl(wm.text("zelle", "NaN")), eb, orte, st.length > 0 ? st[0].strip() : "", st.length > 1 ? zahl(st[1]) : Double.NaN,
                st.length > 2 ? zeit(st[2]) : null, lu.length > 0 ? zahl(lu[0]) : Double.NaN, lu.length > 1 ? zeit(lu[1]) : null, lu.length > 2 ? zeit(lu[2]) : null,
                wm.text("erzeugt", ""), tm);
    }

    // ------------------------------------------------------------------ Texte

    /** Ein Text der Datei der Texte (null: keiner). */
    public String text(String schluessel) { return texte == null ? null : texte.text(schluessel, null); }

    /** Die Absätze einer Tafel ({@code text}, {@code text2} … {@code text9}). */
    public List<String> absaetze(String praefix) {
        List<String> l = new ArrayList<>();
        String a = text(praefix + ".text");
        if (a != null) l.add(a);
        for (int i = 2; i <= 9; i++) {
            String b = text(praefix + ".text" + i);
            if (b != null) l.add(b);
        }
        return l;
    }

    /** Die Werte von {@code praefix}1, {@code praefix}2 … der Datei der Texte. */
    public List<String> nummern(String praefix) { return texte == null ? List.of() : texte.nummern(praefix); }

    /** Hat die Datei der Texte etwas zu diesem Ort? */
    public boolean hatText(Ort o) { return text("tafel." + o.schluessel() + ".text") != null; }

    /** „Hanswarft“ → „hanswarft“, „Süderoogsand“ → „suederoogsand“, „Hallig Hooge“ → „hallighooge“. */
    public static String schluessel(String name) {
        String s = name.toLowerCase(Locale.GERMAN).replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        return s.replaceAll("[^a-z0-9]", "");
    }

    // ------------------------------------------------------------------ Lesen

    static double zahl(String s) {
        if (s == null) return Double.NaN;
        s = s.strip();
        if (s.isEmpty() || s.equals("-")) return Double.NaN;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private static double nullWenn(double v) { return Double.isNaN(v) ? 0 : v; }

    static Instant zeit(String s) {
        if (s == null) return null;
        s = s.strip();
        if (s.isEmpty() || s.equals("-")) return null;
        try {
            return Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Zahlen, durch Leerraum getrennt (null: leer oder keine Zahl). */
    public static double[] zahlenAus(String s) { return zahlen(s); }

    static double[] zahlen(String s) {
        if (s == null || s.isBlank()) return null;
        String[] t = s.strip().split("\\s+");
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) {
            r[i] = zahl(t[i]);
            if (Double.isNaN(r[i])) return null;
        }
        return r;
    }
}
