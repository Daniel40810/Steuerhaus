package com.dan.steuerhaus;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Was sich die App merkt (Werkbuch HalligHooge 110, 5.1): Fenster, Docks, Arbeitsbereich, Thema, Szenario, Ansicht,
 * Zeitraffer. Ablage {@code %USERPROFILE%\.steuerhaus\<app>\einstellungen.properties}, sortiert und lesbar.
 */
public final class Einstellungen {
    public final File datei;
    private final Map<String, String> werte = new TreeMap<>();

    /** Die Einstellungen einer App im Ordner des Benutzers. */
    public static Einstellungen fuer(String app) {
        String name = app.replaceAll("[^A-Za-z0-9_.-]", "_");
        return new Einstellungen(new File(new File(new File(System.getProperty("user.home"), ".steuerhaus"), name), "einstellungen.properties"));
    }

    public Einstellungen(File datei) {
        this.datei = datei;
        if (datei.isFile()) {
            java.util.Properties p = new java.util.Properties();
            try (Reader r = Files.newBufferedReader(datei.toPath(), StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                System.err.println("Steuerhaus: Einstellungen nicht lesbar (" + datei + "): " + e.getMessage());
            }
            for (String k : p.stringPropertyNames()) werte.put(k, p.getProperty(k));
        }
    }

    public String text(String k, String vorgabe) {
        String w = werte.get(k);
        return w == null ? vorgabe : w;
    }

    public double zahl(String k, double vorgabe) {
        try {
            return werte.containsKey(k) ? Double.parseDouble(werte.get(k)) : vorgabe;
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    public int ganz(String k, int vorgabe) { return (int) Math.round(zahl(k, vorgabe)); }

    public boolean wahr(String k, boolean vorgabe) { return werte.containsKey(k) ? Boolean.parseBoolean(werte.get(k)) : vorgabe; }

    public boolean hat(String k) { return werte.containsKey(k); }

    public void setze(String k, Object w) {
        if (w == null) werte.remove(k);
        else werte.put(k, w instanceof Double d ? String.format(Locale.ROOT, "%.4f", d) : String.valueOf(w));
    }

    /** Schreibt die Datei (legt den Ordner an). Fehler werden gemeldet, nicht geworfen: Ohne Einstellungen läuft die App weiter. */
    public boolean speichern() {
        try {
            Files.createDirectories(datei.getParentFile().toPath());
            try (Writer w = Files.newBufferedWriter(datei.toPath(), StandardCharsets.UTF_8)) {
                w.write("# Steuerhaus – Einstellungen (von der App geschrieben)\n");
                for (Map.Entry<String, String> e : werte.entrySet()) w.write(e.getKey() + "=" + e.getValue().replace("\\", "\\\\").replace("\n", "\\n") + "\n");
            }
            return true;
        } catch (IOException e) {
            System.err.println("Steuerhaus: Einstellungen nicht geschrieben (" + datei + "): " + e.getMessage());
            return false;
        }
    }

    /** Alle Werte, für die Ausgabe zur Prüfung. */
    public Map<String, String> alle() { return java.util.Collections.unmodifiableMap(werte); }
}
