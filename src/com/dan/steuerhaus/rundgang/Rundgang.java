package com.dan.steuerhaus.rundgang;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Ein Rundgang (seit X3, Werkbuch HalligHooge 148, F240 A): Halte an Orten der Karte, dazwischen geht die Kamera auf
 * Augenhöhe die Wege entlang. Die Quelle schreibt man von Hand; den Weg rechnet {@link RundgangBau} ({@code --wege}) und
 * legt ihn als Regiebuch daneben ({@link #wegDatei}), das die App wie ein Kapitel spielt.
 * <pre>
 * Rundgang: Von Warft zu Warft
 * Beschreibung: Mit dem Rad vom Anleger zur Westerwarft
 * szenario    = ruhig
 * zeit        = 30.10.2026 10:00          # in der Zone der App
 * raffer      = 20
 * tempo       = 6                         # m/s auf dem Weg
 * auge        = 1.9                       # m über dem Boden
 * wasser      = 3.0                       # m NHN (leer: keins): darüber schwimmt das Auge
 * boot        = 1.2                       # m über dem Wasser
 * neigung     = -3
 * bildwinkel  = 60
 * einblendung = uhr,minikarte
 * halt.1 = Backenswarft | Titel | Text | blick 200 | sonde
 * halt.2 = 470800 6046470 | Titel | Text
 * </pre>
 * Ein Halt: Ort (Name aus {@code orte.txt} oder „Ost Nord“), Titel, Text, danach wahlweise {@code blick <Grad>} (wohin
 * man am Halt schaut), {@code neigung <Grad>}, {@code sonde} (Wasserstand in der Fenne 100 m vor dem Halt) oder
 * {@code sonde <Ost> <Nord>}, {@code boden <m NHN> <Umkreis m>} (seit X3b: Boden mindestens so hoch im Umkreis des Halts –
 * ein Anleger oder Steg, den das Gelände nicht kennt; sonst stünde die Kamera darunter).
 */
public final class Rundgang {
    /** Ein Halt; Ost und Nord NaN, solange der Ort nur einen Namen hat. */
    public record Halt(String ort, double ost, double nord, String titel, String text, double blick, double neigung, boolean sonde, double sondeOst,
                       double sondeNord, double bodenMin, double bodenUmkreis) {
        public boolean hatLage() { return !Double.isNaN(ost); }

        /** Derselbe Halt an einer Lage. */
        public Halt an(double o, double n) { return new Halt(ort, o, n, titel, text, blick, neigung, sonde, sondeOst, sondeNord, bodenMin, bodenUmkreis); }
    }

    public final File quelle;
    public final String name, beschreibung;
    public final Map<String, String> werte;
    public final List<Halt> halte;
    public final List<String> fehler;

    private Rundgang(File quelle, String name, String beschreibung, Map<String, String> werte, List<Halt> halte, List<String> fehler) {
        this.quelle = quelle;
        this.name = name;
        this.beschreibung = beschreibung;
        this.werte = Collections.unmodifiableMap(werte);
        this.halte = List.copyOf(halte);
        this.fehler = List.copyOf(fehler);
    }

    /** Das Regiebuch mit dem gerechneten Weg: {@code warften.rundgang} → {@code warften.weg}. */
    public static File wegDatei(File quelle) {
        String n = quelle.getName();
        int p = n.lastIndexOf('.');
        return new File(quelle.getParentFile(), (p > 0 ? n.substring(0, p) : n) + ".weg");
    }

    public static Rundgang lies(File datei) throws IOException {
        return lies(Files.readString(datei.toPath(), StandardCharsets.UTF_8), datei);
    }

    public static Rundgang lies(String text, File quelle) {
        String name = "", beschreibung = "";
        Map<String, String> w = new LinkedHashMap<>();
        TreeMap<Integer, String> hz = new TreeMap<>();
        List<String> fehler = new ArrayList<>();
        String[] z = text.split("\r?\n", -1);
        for (int i = 0; i < z.length; i++) {
            String roh = z[i].strip();
            if (roh.isEmpty() || roh.startsWith("#")) continue;
            int gl = roh.indexOf('='), dp = roh.indexOf(':');
            if (dp > 0 && (gl < 0 || dp < gl)) {
                String k = roh.substring(0, dp).strip(), v = roh.substring(dp + 1).strip();
                if (k.equals("Rundgang")) { name = v; continue; }
                if (k.equals("Beschreibung")) { beschreibung = v; continue; }
            }
            if (gl <= 0) {
                fehler.add("Zeile " + (i + 1) + ": „Schlüssel = Wert“ erwartet");
                continue;
            }
            String k = roh.substring(0, gl).strip(), v = roh.substring(gl + 1).strip();
            int hash = v.indexOf(" #");
            if (hash >= 0 && !k.startsWith("halt.")) v = v.substring(0, hash).strip();
            if (k.startsWith("halt.")) {
                try {
                    hz.put(Integer.parseInt(k.substring(5)), v);
                } catch (NumberFormatException e) {
                    fehler.add("Zeile " + (i + 1) + ": " + k + " – nach „halt.“ eine Nummer");
                }
            } else w.put(k, v);
        }
        if (name.isEmpty()) fehler.add("kein „Rundgang: Name“");
        List<Halt> halte = new ArrayList<>();
        for (Map.Entry<Integer, String> e : hz.entrySet()) {
            try {
                halte.add(halt(e.getValue()));
            } catch (IllegalArgumentException x) {
                fehler.add("halt." + e.getKey() + ": " + x.getMessage());
            }
        }
        if (halte.size() < 2) fehler.add("mindestens zwei Halte (halt.1, halt.2 …)");
        return new Rundgang(quelle, name, beschreibung, w, halte, fehler);
    }

    static Halt halt(String v) {
        String[] t = v.split("\\s+\\|\\s+");
        if (t.length < 3) throw new IllegalArgumentException("Ort | Titel | Text erwartet");
        String ort = t[0].strip();
        double o = Double.NaN, n = Double.NaN;
        String[] on = ort.split("\\s+");
        if (on.length == 2 && zahl(on[0]) && zahl(on[1])) {
            o = Double.parseDouble(on[0].replace(',', '.'));
            n = Double.parseDouble(on[1].replace(',', '.'));
        }
        double blick = Double.NaN, neig = Double.NaN, so = Double.NaN, sn = Double.NaN, bm = Double.NaN, bu = 0;
        boolean sonde = false;
        for (int i = 3; i < t.length; i++) {
            String[] a = t[i].strip().split("\\s+");
            switch (a[0].toLowerCase(Locale.GERMAN)) {
                case "blick" -> blick = zahl(a, 1);
                case "neigung" -> neig = zahl(a, 1);
                case "boden" -> {
                    bm = zahl(a, 1);
                    bu = zahl(a, 2);
                }
                case "sonde" -> {
                    sonde = true;
                    if (a.length >= 3) {
                        so = zahl(a, 1);
                        sn = zahl(a, 2);
                    }
                }
                default -> throw new IllegalArgumentException("unbekannt „" + t[i].strip() + "“ (blick, neigung, sonde, boden)");
            }
        }
        return new Halt(ort, o, n, t[1].strip(), t[2].strip(), blick, neig, sonde, so, sn, bm, bu);
    }

    private static double zahl(String[] a, int i) {
        if (a.length <= i || !zahl(a[i])) throw new IllegalArgumentException("Zahl erwartet nach „" + a[0] + "“");
        return Double.parseDouble(a[i].replace(',', '.'));
    }

    static boolean zahl(String s) {
        try {
            Double.parseDouble(s.replace(',', '.'));
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** Ein Wert als Zahl (Komma oder Punkt); leer oder fehlt: {@code vorgabe}. */
    public double zahl(String schluessel, double vorgabe) {
        String v = werte.get(schluessel);
        if (v == null || v.isBlank()) return vorgabe;
        return Double.parseDouble(v.strip().replace(',', '.'));
    }

    public String text(String schluessel, String vorgabe) {
        String v = werte.get(schluessel);
        return v == null || v.isBlank() ? vorgabe : v.strip();
    }

    @Override public String toString() { return name + " (" + halte.size() + " Halte)"; }
}
