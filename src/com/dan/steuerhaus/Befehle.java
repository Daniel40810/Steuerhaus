package com.dan.steuerhaus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Das Register der Befehle (Werkbuch HalligHooge 110, 5.1): Menüleiste, Tasten und Befehlspalette lesen nur hier.
 * Prüft beim Start, ob eine Taste doppelt vergeben ist oder eine Taste der Kamera belegt (W A S D Q E, Umschalt;
 * {@code lauf.Steuerung}).
 */
public final class Befehle {
    /** Tasten, die die Kamera auf der Leinwand braucht; kein Befehl darf sie ohne Strg/Alt belegen. */
    public static final Set<String> KAMERA = Set.of("w", "a", "s", "d", "q", "e", "shift", "up", "down", "left", "right");

    private final Map<String, Befehl> befehle = new LinkedHashMap<>();

    /** Legt einen Befehl an (Kennung eindeutig) und gibt ihn zum Weiterbeschreiben zurück. */
    public Befehl neu(String kennung, String gruppe, String name, String taste, Runnable aktion) {
        if (befehle.containsKey(kennung)) throw new IllegalArgumentException("Befehl doppelt: " + kennung);
        Befehl b = new Befehl(kennung, gruppe, name, taste, aktion);
        befehle.put(kennung, b);
        return b;
    }

    public Befehl finde(String kennung) { return befehle.get(kennung); }

    public List<Befehl> alle() { return Collections.unmodifiableList(new ArrayList<>(befehle.values())); }

    /** Die Gruppen in der Reihenfolge, in der sie zuerst vorkommen. */
    public List<String> gruppen() {
        Set<String> g = new LinkedHashSet<>();
        for (Befehl b : befehle.values()) g.add(b.gruppe);
        return new ArrayList<>(g);
    }

    public List<Befehl> inGruppe(String gruppe) {
        List<Befehl> l = new ArrayList<>();
        for (Befehl b : befehle.values()) if (b.gruppe.equals(gruppe)) l.add(b);
        return l;
    }

    /** Doppelt vergebene Tasten und Tasten der Kamera, als lesbare Zeilen (leer: alles in Ordnung). */
    public List<String> tastenFehler() {
        Map<String, List<Befehl>> je = new LinkedHashMap<>();
        for (Befehl b : befehle.values()) if (b.taste != null) je.computeIfAbsent(b.tasteNorm(), k -> new ArrayList<>()).add(b);
        List<String> f = new ArrayList<>();
        je.forEach((t, l) -> {
            if (l.size() > 1) f.add("Taste " + l.get(0).taste + " doppelt: " + l);
            if (KAMERA.contains(t)) f.add("Taste " + l.get(0).taste + " gehört der Kamera: " + l);
        });
        return f;
    }

    /**
     * Sucht für die Befehlspalette: jedes Wort der Eingabe muss in Name oder Gruppe vorkommen. Reihenfolge: Name beginnt
     * mit der Eingabe, ein Wort des Namens beginnt mit ihr, sonst wie registriert.
     */
    public List<Befehl> suche(String eingabe) {
        String q = eingabe == null ? "" : eingabe.strip().toLowerCase(Locale.GERMAN);
        if (q.isEmpty()) return alle();
        String[] woerter = q.split("\\s+");
        List<Befehl> l = new ArrayList<>();
        for (Befehl b : befehle.values()) {
            String text = (b.name + " " + b.gruppe).toLowerCase(Locale.GERMAN);
            boolean alle = true;
            for (String w : woerter) alle &= text.contains(w);
            if (alle) l.add(b);
        }
        l.sort((a, b) -> Integer.compare(rang(a, q), rang(b, q)));
        return l;
    }

    private static int rang(Befehl b, String q) {
        String n = b.name.toLowerCase(Locale.GERMAN);
        if (n.startsWith(q)) return 0;
        for (String w : n.split("[\\s:·,()–-]+")) if (w.startsWith(q)) return 1;
        return 2;
    }
}
