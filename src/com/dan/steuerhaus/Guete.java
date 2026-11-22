package com.dan.steuerhaus;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modell gegen Messung (seit DB3, Werkbuch HalligHooge 171, F268–F271 A): der Wasserstand des Modells an den Pegeln in den
 * Läufen der Szenarien, die gemessene Reihe daneben, die Hoch- und Niedrigwasser beider, die Paare der Hochwasser und die
 * Kennzahlen (RMSE, Bias, Skill nach Willmott, Versatz der Hochwasser, Anteil des Windstaus, bei Xaver das höchste
 * Hochwasser am Tag des HThw). Eine Textdatei in zwei Stufen:
 * <ol>
 * <li>{@code guete.modell}: was die Ausgabe ({@code Ausgabe --guete}) aus den Archiven liest – Läufe, Zellen, Reihen und
 *     Marken des Modells, ohne Messung. Bleibt lokal bei den Daten.</li>
 * <li>{@code guete.datei}: was der Sammler daraus mit dem Messarchiv macht – dazu die Messung, die Paare und die Kennzahlen.
 *     Liegt neben dem Manifest (im Git und im Abbild), geschrieben nur bei Änderung; die App liest nur sie.</li>
 * </ol>
 * Zeiten UTC (ISO 8601, auf die Sekunde), Höhen in m NHN, Reihen in Tausendsteln (ganze Zahlen, „-“ fehlt oder trocken):
 * Wasserstand in mm, Wind in mm/s, Richtung in tausendstel Grad. Seit DB4 (Werkbuch HalligHooge 173, F272–F275 A) dazu der
 * Wind des Modells (ERA5 aus dem Archiv) an den Windstationen gegen den DWD und je Pegel das gemessene Mittelwasser. Nur java.*.
 * <pre>
 * lauf.sturm             = Szenarien; Archiv; Name; Beginn; von; bis; Schritt; Kennung; Antrieb; Module
 * pegel.sturm.HUSUM      = Name; Ost; Nord; Gitter; Zelle; Abstand; Grund
 * modell.sturm.HUSUM     = Beginn; Schritt; mm mm mm …
 * marken.sturm.HUSUM     = H 2026-10-25T11:20:00Z 2.150, N …
 * messung.sturm.HUSUM    = Beginn; Schritt; mm mm …
 * gemessen.sturm.HUSUM   = H … , N …
 * paare.sturm.HUSUM      = Modell Höhe Messung Höhe Gezeit Stau, …
 * kennzahlen.sturm.HUSUM = Werte; RMSE; Bias; Skill; r; HW; Zeit; Höhe; RMS; Stau-Paare; Stau-Anteil; Max; Zeit; HThw; Tag; am Tag; Zeit;
 *                          Mittelwasser; Tidemittel; Bias ohne Mittelwasser   (die letzten drei seit DB4)
 * windpunkt.sturm.03032  = Name; Ost; Nord; Geber; Gitter; Zelle; Abstand      (seit DB4)
 * wind.sturm.03032       = Beginn; Schritt; mm/s …      richtung.sturm.03032 = Beginn; Schritt; tausendstel Grad …
 * windmessung.…, richtungmessung.…                     (DWD, auf 10 m gebracht)
 * windzahlen.sturm.03032 = Werte; Faktor; Bias; RMSE; Richtung; Sturm-Werte; Faktor; Bias; Richtung; höchster Wind Modell; Messung
 * </pre>
 */
public final class Guete {

    /** Ein Lauf: Schlüssel (das erste Szenario mit dem Archiv), alle Szenarien des Archivs, Archiv, Name aus lauf.txt, Beginn des Laufs, erster und letzter gewerteter Stand, Schritt (s), Kennung, Antrieb, Module. */
    public record Lauf(String schluessel, List<String> szenarien, String archiv, String name, Instant beginn, Instant von, Instant bis, int schritt, String kennung,
            String antrieb, String module) {
        public Lauf mitKennung(String k) { return new Lauf(schluessel, szenarien, archiv, name, beginn, von, bis, schritt, k, antrieb, module); }

        /** Seit DB4: das Mittel des Wasserstands im Antrieb (m NHN), aus „Mittel 0.0 m“ in {@link #antrieb}; 0, wenn es fehlt. */
        public double mittel() {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("Mittel\\s+([-+]?[0-9]+(?:\\.[0-9]+)?)\\s*m").matcher(antrieb == null ? "" : antrieb);
            return m.find() ? Double.parseDouble(m.group(1)) : 0;
        }
    }

    /** Eine Reihe auf festem Schritt: Beginn, Schritt (s) und Werte (m NHN; NaN fehlt oder trocken). */
    public record Reihe(Instant start, int schritt, float[] werte) {
        public static final Reihe LEER = new Reihe(Instant.EPOCH, 600, new float[0]);

        public int n() { return werte.length; }

        public Instant zeit(int i) { return start.plusSeconds((long) i * schritt); }

        public double wert(int i) { return werte[i]; }

        /** Der Wert genau zur Zeit t (ein Stand der Reihe), sonst NaN. */
        public double bei(Instant t) {
            long d = t.getEpochSecond() - start.getEpochSecond();
            if (d < 0 || d % schritt != 0 || d / schritt >= werte.length) return Double.NaN;
            return werte[(int) (d / schritt)];
        }

        /** Wie viele Werte da sind. */
        public int belegt() {
            int k = 0;
            for (float w : werte) if (!Float.isNaN(w)) k++;
            return k;
        }

        /** Index des höchsten Werts oder -1. */
        public int hoechster() {
            int b = -1;
            for (int i = 0; i < werte.length; i++) if (!Float.isNaN(werte[i]) && (b < 0 || werte[i] > werte[b])) b = i;
            return b;
        }
    }

    /** Ein Hoch- (H) oder Niedrigwasser (N). */
    public record Marke(char art, Instant zeit, double hoehe) { }

    /** Ein Paar Hochwasser: Modell und Messung, dazu die Gezeit und der Windstau der Messung (NaN ohne). */
    public record Paar(Instant modell, double hModell, Instant messung, double hMessung, double gezeit, double stau) {
        /** Zeit Modell minus Messung (min). */
        public double versatzMin() { return (modell.getEpochSecond() - messung.getEpochSecond()) / 60.0; }

        /** Höhe Modell minus Messung (m). */
        public double versatzM() { return hModell - hMessung; }

        /** Windstau im Modell: Hochwasser des Modells minus die Gezeit der Messung (m). */
        public double stauModell() { return hModell - gezeit; }
    }

    /**
     * Die Kennzahlen eines Pegels in einem Lauf: Werte (Paare Modell–Messung), RMSE, Bias (Modell − Messung), Skill nach
     * Willmott, Korrelation; Hochwasser in Paaren, ihr mittlerer Versatz in Zeit (min) und Höhe (m), dessen RMS; Paare mit
     * Windstau, der Anteil des Windstaus im Modell am gemessenen; das höchste Wasser im Modell; der Kennwert HThw mit seinem
     * Tag und das höchste Hochwasser des Modells an diesem Tag (nur, wenn der Lauf den Tag abdeckt).
     */
    public record Kennzahlen(int werte, double rmse, double bias, double skill, double r, int hw, double hwZeit, double hwHoehe, double hwRms, int stauPaare,
            double stauAnteil, double max, Instant maxZeit, double kennwert, LocalDate kennwertTag, double amTag, Instant amTagZeit, double mittelwasser, double tidemittel,
            double biasOhneMw) {
        public static final Kennzahlen LEER = new Kennzahlen(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN,
                Double.NaN, null, Double.NaN, null, Double.NaN, null, Double.NaN, Double.NaN, Double.NaN);

        /** Wie bisher (DB3), ohne Mittelwasser. */
        public Kennzahlen(int werte, double rmse, double bias, double skill, double r, int hw, double hwZeit, double hwHoehe, double hwRms, int stauPaare,
                double stauAnteil, double max, Instant maxZeit, double kennwert, LocalDate kennwertTag, double amTag, Instant amTagZeit) {
            this(werte, rmse, bias, skill, r, hw, hwZeit, hwHoehe, hwRms, stauPaare, stauAnteil, max, maxZeit, kennwert, kennwertTag, amTag, amTagZeit, Double.NaN, Double.NaN,
                    Double.NaN);
        }

        /** Ob der Lauf den Tag des HThw abdeckt und das Modell dort ein Hochwasser hat. */
        public boolean mitKennwert() { return !Double.isNaN(kennwert) && kennwertTag != null && !Double.isNaN(amTag); }
    }

    /** Ein Pegel in einem Lauf. */
    public record Eintrag(String lauf, String pegel, String name, double ost, double nord, String gitter, int zelle, double abstand, double grund, Reihe modell,
            List<Marke> marken, Reihe messung, List<Marke> gemessen, List<Paar> paare, Kennzahlen kennzahlen) {
        public Eintrag mit(Reihe messung, List<Marke> gemessen, List<Paar> paare, Kennzahlen k) {
            return new Eintrag(lauf, pegel, name, ost, nord, gitter, zelle, abstand, grund, modell, marken, messung, gemessen, paare, k);
        }
    }

    /**
     * Seit DB4: die Kennzahlen des Winds an einer Station in einem Lauf. Werte (Paare zur selben Zeit); Faktor = Summe der
     * Geschwindigkeit des Modells / Summe der gemessenen, über die Paare mit mindestens {@code guete.wind.min}; Bias und RMSE
     * der Geschwindigkeit (m/s); Versatz der Richtung (Modell − Messung, Grad, Kreismittel gewichtet mit der gemessenen
     * Geschwindigkeit, ab {@code guete.wind.min}); dasselbe nur bei Sturm (gemessen ab {@code guete.wind.sturm}); der höchste
     * Wind beider. Der Windschub wächst mit dem Quadrat der Geschwindigkeit: {@link #schub()}.
     */
    public record Windzahlen(int werte, double faktor, double bias, double rmse, double richtung, int sturmWerte, double sturmFaktor, double sturmBias, double sturmRichtung,
            double maxModell, double maxMessung) {
        public static final Windzahlen LEER = new Windzahlen(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);

        /** Faktor², das Verhältnis des Windschubs. */
        public double schub() { return faktor * faktor; }

        public double sturmSchub() { return sturmFaktor * sturmFaktor; }
    }

    /**
     * Seit DB4: eine Windstation in einem Lauf: Kennung beim DWD, Name, Lage, Höhe des Gebers, Zelle des Winds im Modell und
     * ihr Abstand, Geschwindigkeit (m/s, 10 m) und Richtung (Grad, woher) des Modells und der Messung (auf 10 m gebracht).
     */
    public record Wind(String lauf, String station, String name, double ost, double nord, double geber, String gitter, int zelle, double abstand, Reihe ff, Reihe dd,
            Reihe ffMessung, Reihe ddMessung, Windzahlen zahlen) {
        public Wind mit(Reihe ffM, Reihe ddM, Windzahlen z) {
            return new Wind(lauf, station, name, ost, nord, geber, gitter, zelle, abstand, ff, dd, ffM, ddM, z);
        }
    }

    public final String quelle;
    public final Instant erstellt;
    /** Stunden nach dem Beginn eines Laufs, die nicht zählen. */
    public final double einschwingen;
    public final List<Lauf> laeufe;
    public final List<Eintrag> eintraege;
    /** Seit DB4: der Wind an den Stationen je Lauf. */
    public final List<Wind> winde;

    public Guete(String quelle, Instant erstellt, double einschwingen, List<Lauf> laeufe, List<Eintrag> eintraege) {
        this(quelle, erstellt, einschwingen, laeufe, eintraege, List.of());
    }

    public Guete(String quelle, Instant erstellt, double einschwingen, List<Lauf> laeufe, List<Eintrag> eintraege, List<Wind> winde) {
        this.quelle = quelle == null ? "" : quelle;
        this.erstellt = erstellt;
        this.einschwingen = einschwingen;
        this.laeufe = Collections.unmodifiableList(new ArrayList<>(laeufe));
        this.eintraege = Collections.unmodifiableList(new ArrayList<>(eintraege));
        this.winde = Collections.unmodifiableList(new ArrayList<>(winde));
    }

    /** Seit DB4: die Windstationen eines Laufs in der Folge der Datei. */
    public List<Wind> winde(String lauf) {
        List<Wind> l = new ArrayList<>();
        for (Wind w : winde) if (w.lauf().equals(lauf)) l.add(w);
        return l;
    }

    public Wind wind(String lauf, String station) {
        for (Wind w : winde) if (w.lauf().equals(lauf) && w.station().equals(station)) return w;
        return null;
    }

    public Lauf lauf(String schluessel) {
        for (Lauf l : laeufe) if (l.schluessel().equals(schluessel)) return l;
        return null;
    }

    /** Die Pegel eines Laufs in der Folge der Datei. */
    public List<Eintrag> eintraege(String lauf) {
        List<Eintrag> l = new ArrayList<>();
        for (Eintrag e : eintraege) if (e.lauf().equals(lauf)) l.add(e);
        return l;
    }

    public Eintrag eintrag(String lauf, String pegel) {
        for (Eintrag e : eintraege) if (e.lauf().equals(lauf) && e.pegel().equals(pegel)) return e;
        return null;
    }

    /** Der Lauf, zu dem ein Szenario gehört, oder null. */
    public Lauf laufVon(String szenario) {
        for (Lauf l : laeufe) if (l.szenarien().contains(szenario)) return l;
        return null;
    }

    // ------------------------------------------------------------------ die Schlüssel im Manifest

    /**
     * Die Schlüssel {@code guete.*}: Name, Pegel (Schlüssel wie im Gebiet und im Messarchiv, durch ; getrennt), Umkreis (m, die
     * tiefste Zelle des Modells im Umkreis gilt), Einschwingen (h), Windstau für den Anteil (m), die beiden Dateien und ihre
     * Kopien (relativ zum Manifest), die Ansicht beim Sprung ins Szenario und der Hinweis unter dem Fenster.
     */
    public record Auftrag(String name, List<String> pegel, double umkreis, double einschwingen, double stau, File modell, File datei, List<File> kopien, String ansicht,
            String hinweis, List<String> wind, double windMin, double windSturm, double mittelwasserTage) {

        /** Liest die Schlüssel; null, wenn {@code guete.datei} fehlt. */
        public static Auftrag lies(Manifest m) {
            String d = m.text("guete.datei", "");
            if (d.isEmpty()) return null;
            List<String> p = new ArrayList<>();
            for (String s : m.text("guete.pegel", "").split(";")) if (!s.isBlank()) p.add(s.strip());
            List<File> k = new ArrayList<>();
            for (String s : m.text("guete.kopie", "").split(";")) if (!s.isBlank()) k.add(norm(m.relativ(s.strip())));
            String mo = m.text("guete.modell", "");
            List<String> w = new ArrayList<>();
            for (String s : m.text("guete.wind", "").split(";")) if (!s.isBlank()) w.add(s.strip());
            return new Auftrag(m.text("guete.name", "Modell gegen Messung"), List.copyOf(p), zahl(m.text("guete.umkreis", "500"), 500),
                    zahl(m.text("guete.einschwingen", m.text("gezeiten.einschwingen", "24")), 24), zahl(m.text("guete.stau", "0.5"), 0.5),
                    mo.isEmpty() ? null : norm(m.relativ(mo)), norm(m.relativ(d)), List.copyOf(k), m.text("guete.ansicht", ""), m.text("guete.hinweis", ""), List.copyOf(w),
                    zahl(m.text("guete.wind.min", "5"), 5), zahl(m.text("guete.wind.sturm", "15"), 15), zahl(m.text("guete.mittelwasser.tage", "30"), 30));
        }

        static File norm(File f) { return f.toPath().toAbsolutePath().normalize().toFile(); }
    }

    // ------------------------------------------------------------------ Text

    static String z(double d) { return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.3f", d); }

    static String z1(double d) { return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.1f", d); }

    static String z4(double d) { return Double.isNaN(d) ? "" : String.format(Locale.ROOT, "%.4f", d); }

    static String t(Instant t) { return t == null ? "" : t.truncatedTo(ChronoUnit.SECONDS).toString(); }

    public static String rein(String s) { return s == null ? "" : s.replace(';', ',').replace('=', '-').replace('\n', ' ').strip(); }

    static String reihe(Reihe r) {
        StringBuilder s = new StringBuilder();
        s.append(t(r.start())).append("; ").append(r.schritt()).append(';');
        for (float w : r.werte()) s.append(' ').append(Float.isNaN(w) ? "-" : Long.toString(Math.round(w * 1000.0)));
        return s.toString();
    }

    static String marken(List<Marke> l) {
        StringBuilder s = new StringBuilder();
        for (Marke m : l) s.append(s.length() > 0 ? ", " : "").append(m.art()).append(' ').append(t(m.zeit())).append(' ').append(z(m.hoehe()));
        return s.toString();
    }

    static String paare(List<Paar> l) {
        StringBuilder s = new StringBuilder();
        for (Paar p : l)
            s.append(s.length() > 0 ? ", " : "").append(t(p.modell())).append(' ').append(z(p.hModell())).append(' ').append(t(p.messung())).append(' ').append(z(p.hMessung()))
                    .append(' ').append(Double.isNaN(p.gezeit()) ? "-" : z(p.gezeit())).append(' ').append(Double.isNaN(p.stau()) ? "-" : z(p.stau()));
        return s.toString();
    }

    static String kennzahlen(Kennzahlen k) {
        return k.werte() + "; " + z(k.rmse()) + "; " + z(k.bias()) + "; " + z4(k.skill()) + "; " + z4(k.r()) + "; " + k.hw() + "; " + z1(k.hwZeit()) + "; " + z(k.hwHoehe()) + "; "
                + z(k.hwRms()) + "; " + k.stauPaare() + "; " + z(k.stauAnteil()) + "; " + z(k.max()) + "; " + t(k.maxZeit()) + "; " + z(k.kennwert()) + "; "
                + (k.kennwertTag() == null ? "" : k.kennwertTag().toString()) + "; " + z(k.amTag()) + "; " + t(k.amTagZeit()) + "; " + z(k.mittelwasser()) + "; "
                + z(k.tidemittel()) + "; " + z(k.biasOhneMw());
    }

    static String windzahlen(Windzahlen k) {
        return k.werte() + "; " + z(k.faktor()) + "; " + z(k.bias()) + "; " + z(k.rmse()) + "; " + z1(k.richtung()) + "; " + k.sturmWerte() + "; " + z(k.sturmFaktor()) + "; "
                + z(k.sturmBias()) + "; " + z1(k.sturmRichtung()) + "; " + z(k.maxModell()) + "; " + z(k.maxMessung());
    }

    static String windpunktZeile(Wind w) {
        return rein(w.name()) + "; " + z1(w.ost()) + "; " + z1(w.nord()) + "; " + z1(w.geber()) + "; " + rein(w.gitter()) + "; " + w.zelle() + "; " + z1(w.abstand());
    }

    static String laufZeile(Lauf l) {
        return String.join(", ", l.szenarien()) + "; " + rein(l.archiv()) + "; " + rein(l.name()) + "; " + t(l.beginn()) + "; " + t(l.von()) + "; " + t(l.bis()) + "; " + l.schritt()
                + "; " + rein(l.kennung()) + "; " + rein(l.antrieb()) + "; " + rein(l.module());
    }

    static String pegelZeile(Eintrag e) {
        return rein(e.name()) + "; " + z1(e.ost()) + "; " + z1(e.nord()) + "; " + rein(e.gitter()) + "; " + e.zelle() + "; " + z1(e.abstand()) + "; " + z(e.grund());
    }

    /** Die Zeilen eines Laufs (ohne seine Kennung) – das, worüber die Kennung rechnet. */
    static String laufInhalt(Lauf l, List<Eintrag> es, List<Wind> ws) {
        StringBuilder s = new StringBuilder(laufZeile(l.mitKennung(""))).append('\n');
        for (Eintrag e : es) s.append(pegelZeile(e)).append('\n').append(reihe(e.modell())).append('\n').append(marken(e.marken())).append('\n');
        for (Wind w : ws) s.append(windpunktZeile(w)).append('\n').append(reihe(w.ff())).append('\n').append(reihe(w.dd())).append('\n');
        return s.toString();
    }

    /** Die Kennung eines Laufs: SHA-256 (die ersten 16 Zeichen) über Kopf, Zellen, Reihen und Marken des Modells. */
    public static String kennung(Lauf l, List<Eintrag> es) { return kennung(l, es, List.of()); }

    /** Seit DB4 mit dem Wind des Laufs. */
    public static String kennung(Lauf l, List<Eintrag> es, List<Wind> ws) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(laufInhalt(l, es, ws).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 8; i++) s.append(String.format("%02x", h[i]));
            return s.toString();
        } catch (NoSuchAlgorithmException x) {
            throw new IllegalStateException(x);
        }
    }

    /** Der Inhalt ohne die Zeile {@code erstellt} – das, was zwei Fassungen gleich macht. */
    public String inhalt() {
        StringBuilder s = new StringBuilder();
        s.append("quelle = ").append(rein(quelle)).append('\n');
        s.append("einschwingen = ").append(z1(einschwingen)).append('\n');
        for (Lauf l : laeufe) s.append("lauf.").append(l.schluessel()).append(" = ").append(laufZeile(l)).append('\n');
        for (Eintrag e : eintraege) {
            String k = e.lauf() + "." + e.pegel() + " = ";
            s.append("pegel.").append(k).append(pegelZeile(e)).append('\n');
            s.append("modell.").append(k).append(reihe(e.modell())).append('\n');
            s.append("marken.").append(k).append(marken(e.marken())).append('\n');
            if (e.messung().n() > 0) s.append("messung.").append(k).append(reihe(e.messung())).append('\n');
            if (!e.gemessen().isEmpty()) s.append("gemessen.").append(k).append(marken(e.gemessen())).append('\n');
            if (!e.paare().isEmpty()) s.append("paare.").append(k).append(paare(e.paare())).append('\n');
            if (e.kennzahlen() != Kennzahlen.LEER) s.append("kennzahlen.").append(k).append(kennzahlen(e.kennzahlen())).append('\n');
        }
        for (Wind w : winde) {
            String k = w.lauf() + "." + w.station() + " = ";
            s.append("windpunkt.").append(k).append(windpunktZeile(w)).append('\n');
            s.append("wind.").append(k).append(reihe(w.ff())).append('\n');
            s.append("richtung.").append(k).append(reihe(w.dd())).append('\n');
            if (w.ffMessung().n() > 0) s.append("windmessung.").append(k).append(reihe(w.ffMessung())).append('\n');
            if (w.ddMessung().n() > 0) s.append("richtungmessung.").append(k).append(reihe(w.ddMessung())).append('\n');
            if (w.zahlen() != Windzahlen.LEER) s.append("windzahlen.").append(k).append(windzahlen(w.zahlen())).append('\n');
        }
        return s.toString();
    }

    static final String KOPF = "# Modell gegen Messung (DB3, Werkbuch HalligHooge 171): der Wasserstand des Modells an den Pegeln in den Läufen der Szenarien,\n"
            + "# gegen die Messung (PEGELONLINE, ungeprüfte Rohdaten, 10-Minuten-Mittel) - geschrieben von der Ausgabe (--guete) und vom Sammler (--guete),\n"
            + "# nur bei Änderung; nicht von Hand ändern. Zeiten UTC, Höhen m NHN, Reihen in mm (- fehlt oder trocken).\n";

    /** Schreibt die Datei, wenn sich ihr Inhalt geändert hat (die Zeile {@code erstellt} zählt nicht); über eine Zwischendatei. */
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
        Files.writeString(tmp.toPath(), KOPF + "erstellt = " + t(erstellt == null ? Instant.now() : erstellt) + "\n" + neu, StandardCharsets.UTF_8);
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    public static Guete lies(File f) throws IOException {
        Map<String, String> w = new LinkedHashMap<>();
        for (String zeile : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
            String s = zeile.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p <= 0) continue;
            w.put(s.substring(0, p).strip(), s.substring(p + 1).strip());
        }
        try {
            List<Lauf> laeufe = new ArrayList<>();
            Map<String, Map<String, String>> je = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : w.entrySet()) {
                String k = e.getKey();
                if (k.startsWith("lauf.")) {
                    String[] t = felder(e.getValue(), 10, k);
                    List<String> sz = new ArrayList<>();
                    for (String x : t[0].split(",")) if (!x.isBlank()) sz.add(x.strip());
                    laeufe.add(new Lauf(k.substring(5), List.copyOf(sz), t[1], t[2], zeit(t[3]), zeit(t[4]), zeit(t[5]), Integer.parseInt(t[6]), t[7], t[8], t[9]));
                    continue;
                }
                int a = k.indexOf('.'), b = a < 0 ? -1 : k.indexOf('.', a + 1);
                if (b < 0) continue;
                String art = k.substring(0, a), schl = k.substring(a + 1);
                je.computeIfAbsent(schl, x -> new LinkedHashMap<>()).put(art, e.getValue());
            }
            List<Eintrag> es = new ArrayList<>();
            List<Wind> ws = new ArrayList<>();
            for (Map.Entry<String, Map<String, String>> e : je.entrySet()) {
                Map<String, String> v = e.getValue();
                if (v.containsKey("windpunkt")) {
                    int a = e.getKey().indexOf('.');
                    String[] p = felder(v.get("windpunkt"), 7, "windpunkt." + e.getKey());
                    ws.add(new Wind(e.getKey().substring(0, a), e.getKey().substring(a + 1), p[0], zahl(p[1]), zahl(p[2]), zahl(p[3]), p[4], Integer.parseInt(p[5]),
                            zahl(p[6]), reihe(v.get("wind")), reihe(v.get("richtung")), reihe(v.get("windmessung")), reihe(v.get("richtungmessung")),
                            windzahlen(v.get("windzahlen"))));
                    continue;
                }
                if (!v.containsKey("pegel")) throw new IOException("pegel." + e.getKey() + " fehlt");
                int a = e.getKey().indexOf('.');
                String[] p = felder(v.get("pegel"), 7, "pegel." + e.getKey());
                es.add(new Eintrag(e.getKey().substring(0, a), e.getKey().substring(a + 1), p[0], zahl(p[1]), zahl(p[2]), p[3], Integer.parseInt(p[4]), zahl(p[5]), zahl(p[6]),
                        reihe(v.get("modell")), marken(v.get("marken")), reihe(v.get("messung")), marken(v.get("gemessen")), paare(v.get("paare")), kennzahlen(v.get("kennzahlen"))));
            }
            return new Guete(w.get("quelle"), zeit(w.get("erstellt")), zahl(w.getOrDefault("einschwingen", "24")), laeufe, es, ws);
        } catch (RuntimeException x) {
            throw new IOException(f.getName() + ": " + x.getMessage(), x);
        }
    }

    static String[] felder(String v, int n, String k) {
        String[] t = v.split(";", -1);
        if (t.length < n) throw new IllegalArgumentException(k + " mit " + t.length + " statt " + n + " Feldern");
        for (int i = 0; i < t.length; i++) t[i] = t[i].strip();
        return t;
    }

    static Reihe reihe(String v) {
        if (v == null || v.isBlank()) return Reihe.LEER;
        String[] t = v.split(";", 3);
        String[] w = t.length > 2 ? t[2].strip().split("\\s+") : new String[0];
        int n = w.length == 1 && w[0].isEmpty() ? 0 : w.length;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) x[i] = w[i].equals("-") ? Float.NaN : (float) (Long.parseLong(w[i]) / 1000.0);
        return new Reihe(Instant.parse(t[0].strip()), Integer.parseInt(t[1].strip()), x);
    }

    static List<Marke> marken(String v) {
        List<Marke> l = new ArrayList<>();
        if (v == null || v.isBlank()) return l;
        for (String m : v.split(",")) {
            String[] t = m.strip().split("\\s+");
            l.add(new Marke(t[0].charAt(0), Instant.parse(t[1]), Double.parseDouble(t[2])));
        }
        return l;
    }

    static List<Paar> paare(String v) {
        List<Paar> l = new ArrayList<>();
        if (v == null || v.isBlank()) return l;
        for (String m : v.split(",")) {
            String[] t = m.strip().split("\\s+");
            l.add(new Paar(Instant.parse(t[0]), Double.parseDouble(t[1]), Instant.parse(t[2]), Double.parseDouble(t[3]), t[4].equals("-") ? Double.NaN : Double.parseDouble(t[4]),
                    t[5].equals("-") ? Double.NaN : Double.parseDouble(t[5])));
        }
        return l;
    }

    static Kennzahlen kennzahlen(String v) {
        if (v == null || v.isBlank()) return Kennzahlen.LEER;
        String[] t = felder(v, 17, "kennzahlen");
        return new Kennzahlen(Integer.parseInt(t[0]), zahl(t[1]), zahl(t[2]), zahl(t[3]), zahl(t[4]), Integer.parseInt(t[5]), zahl(t[6]), zahl(t[7]), zahl(t[8]),
                Integer.parseInt(t[9]), zahl(t[10]), zahl(t[11]), zeit(t[12]), zahl(t[13]), t[14].isEmpty() ? null : LocalDate.parse(t[14]), zahl(t[15]), zeit(t[16]),
                t.length > 17 ? zahl(t[17]) : Double.NaN, t.length > 18 ? zahl(t[18]) : Double.NaN, t.length > 19 ? zahl(t[19]) : Double.NaN);
    }

    static Windzahlen windzahlen(String v) {
        if (v == null || v.isBlank()) return Windzahlen.LEER;
        String[] t = felder(v, 11, "windzahlen");
        return new Windzahlen(Integer.parseInt(t[0]), zahl(t[1]), zahl(t[2]), zahl(t[3]), zahl(t[4]), Integer.parseInt(t[5]), zahl(t[6]), zahl(t[7]), zahl(t[8]), zahl(t[9]),
                zahl(t[10]));
    }

    static Instant zeit(String s) { return s == null || s.isBlank() ? null : Instant.parse(s.strip()); }

    static double zahl(String s) {
        if (s == null || s.isBlank()) return Double.NaN;
        return Double.parseDouble(s.strip());
    }

    static double zahl(String s, double vorgabe) {
        try {
            return Double.parseDouble(s.strip().replace(',', '.'));
        } catch (RuntimeException e) {
            return vorgabe;
        }
    }
}
