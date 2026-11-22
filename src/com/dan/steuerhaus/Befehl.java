package com.dan.steuerhaus;

import java.util.function.BooleanSupplier;

/**
 * Ein Befehl der App (Werkbuch HalligHooge 110, 5.1): Kennung, Name, Gruppe (Menü), Taste, was er tut, ob er gerade
 * geht und – bei Schaltern – ob er an ist. Jeder Knopf, jeder Menüpunkt und jeder Eintrag der Befehlspalette ist ein
 * Befehl. Tasten im Format von {@code javax.swing.KeyStroke}: {@code "F2"}, {@code "ctrl K"}, {@code "ctrl shift F"}.
 */
public final class Befehl {
    public final String kennung, gruppe, name;
    /** Taste oder null. */
    public final String taste;
    private final Runnable aktion;
    private BooleanSupplier geht = () -> true;
    private BooleanSupplier an;
    private boolean nurPalette;
    private String hilfe = "";

    Befehl(String kennung, String gruppe, String name, String taste, Runnable aktion) {
        this.kennung = kennung;
        this.gruppe = gruppe;
        this.name = name;
        this.taste = taste == null || taste.isBlank() ? null : taste.strip();
        this.aktion = aktion;
    }

    /** Geht der Befehl nur unter einer Bedingung (sonst grau)? */
    public Befehl wenn(BooleanSupplier b) {
        geht = b;
        return this;
    }

    /** Ein Schalter: Menü und Palette zeigen, ob er an ist. */
    public Befehl schalter(BooleanSupplier b) {
        an = b;
        return this;
    }

    /** Nur in der Befehlspalette, nicht im Menü (Szenarien, Ansichten). */
    public Befehl nurPalette() {
        nurPalette = true;
        return this;
    }

    public Befehl hilfe(String text) {
        hilfe = text == null ? "" : text;
        return this;
    }

    public void ausfuehren() {
        if (geht()) aktion.run();
    }

    public boolean geht() { return geht.getAsBoolean(); }

    public boolean istSchalter() { return an != null; }

    public boolean an() { return an != null && an.getAsBoolean(); }

    public boolean imMenue() { return !nurPalette; }

    public String hilfe() { return hilfe; }

    /** Die Taste in einer festen Schreibweise (klein, Teile sortiert), für den Vergleich. */
    public String tasteNorm() { return norm(taste); }

    static String norm(String t) {
        if (t == null) return null;
        String[] p = t.strip().toLowerCase(java.util.Locale.ROOT).replace("control", "ctrl").split("\\s+");
        String haupt = p[p.length - 1];
        java.util.List<String> mod = new java.util.ArrayList<>(java.util.Arrays.asList(p).subList(0, p.length - 1));
        mod.remove("pressed");
        java.util.Collections.sort(mod);
        mod.add(haupt);
        return String.join(" ", mod);
    }

    @Override public String toString() { return gruppe + " › " + name + (taste != null ? " (" + taste + ")" : ""); }
}
