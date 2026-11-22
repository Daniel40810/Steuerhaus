package com.dan.steuerhaus.regie;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ein Lesezeichen (seit W3, Werkbuch HalligHooge 116, F196 A): Name, Szenario, Szenenzeit und Blick. Die Liste steht im
 * Projekt neben der Szenendatei ({@code Hooge.lesezeichen}), im Format der Szenendatei:
 * <pre>
 * lesezeichen.1 = Name; Szenario; 2026-10-25T22:10:00Z; Ost Nord Höhe Richtung Neigung Bildwinkel
 * </pre>
 */
public record Lesezeichen(String name, String szenario, Instant zeit, double[] blick) {
    /** Liest die Liste; fehlt die Datei, ist sie leer. Falsche Zeilen landen in {@code fehler} (darf null sein). */
    public static List<Lesezeichen> lies(File datei, List<String> fehler) throws IOException {
        List<Lesezeichen> l = new ArrayList<>();
        if (datei == null || !datei.isFile()) return l;
        int nr = 0;
        for (String z : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) {
            nr++;
            String s = z.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int gl = s.indexOf('=');
            if (gl < 0 || !s.substring(0, gl).strip().startsWith("lesezeichen.")) continue;
            String[] t = s.substring(gl + 1).split(";");
            try {
                if (t.length != 4) throw new IllegalArgumentException("vier Teile erwartet");
                String[] b = t[3].strip().split("\\s+");
                if (b.length != 6) throw new IllegalArgumentException("Blick: sechs Zahlen erwartet");
                double[] v = new double[6];
                for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(b[i]);
                l.add(new Lesezeichen(t[0].strip(), t[1].strip(), Instant.parse(t[2].strip()), v));
            } catch (RuntimeException e) {
                if (fehler != null) fehler.add(datei.getName() + ", Zeile " + nr + ": " + e.getMessage());
            }
        }
        return l;
    }

    /** Schreibt die Liste (überschreibt die Datei). */
    public static void schreibe(File datei, List<Lesezeichen> l) throws IOException {
        StringBuilder b = new StringBuilder();
        b.append("# Lesezeichen (Steuerhaus W3): Name; Szenario; Szenenzeit (UTC); Ost Nord Höhe Richtung Neigung Bildwinkel\n");
        int i = 0;
        for (Lesezeichen z : l) b.append("lesezeichen.").append(++i).append(" = ").append(z.zeile()).append('\n');
        if (datei.getParentFile() != null) datei.getParentFile().mkdirs();
        Files.writeString(datei.toPath(), b.toString(), StandardCharsets.UTF_8);
    }

    public String zeile() {
        return String.format(Locale.ROOT, "%s; %s; %s; %.2f %.2f %.2f %.2f %.2f %.2f", name, szenario, zeit, blick[0], blick[1], blick[2], blick[3], blick[4], blick[5]);
    }

    @Override public boolean equals(Object o) {
        return o instanceof Lesezeichen z && z.name.equals(name) && z.szenario.equals(szenario) && z.zeit.equals(zeit) && java.util.Arrays.equals(z.blick, blick);
    }

    @Override public int hashCode() { return name.hashCode(); }
}
