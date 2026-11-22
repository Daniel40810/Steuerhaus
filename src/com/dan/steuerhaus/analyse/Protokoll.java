package com.dan.steuerhaus.analyse;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Das Protokoll der App (seit W5, Werkbuch HalligHooge 126, F213 A): eine Zeile je Ereignis (Zeit, Art, Text) in
 * {@code protokoll.txt}; wird sie größer als {@link #GROESSE}, rückt sie nach {@code protokoll.1.txt} … (höchstens
 * {@link #DATEIEN} Dateien, die älteste fällt weg). Die letzten {@link #ZEILEN} Einträge hält es für die Liste in der App.
 */
public final class Protokoll {
    public static final long GROESSE = 1 << 20;
    public static final int DATEIEN = 5, ZEILEN = 500;
    private static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    public record Eintrag(String zeit, String art, String text) {
        @Override public String toString() { return zeit + "  " + art + "  " + text; }
    }

    public final File ordner;
    private final ArrayDeque<Eintrag> letzte = new ArrayDeque<>();
    private OutputStream aus;
    private long groesse;
    /** Bekommt jeden neuen Eintrag (aus dem Faden des Schreibers). */
    public volatile Consumer<Eintrag> neu;

    public Protokoll(File ordner) {
        this.ordner = ordner;
        ordner.mkdirs();
    }

    public File datei() { return new File(ordner, "protokoll.txt"); }

    /** Schreibt einen Eintrag; Fehler beim Schreiben schlucken (das Protokoll darf die App nie anhalten). */
    public void schreibe(String art, String text) {
        Eintrag e = new Eintrag(LocalDateTime.now().format(ZEIT), art, text == null ? "" : text.replace('\n', ' ').replace('\r', ' '));
        synchronized (this) {
            letzte.addLast(e);
            while (letzte.size() > ZEILEN) letzte.removeFirst();
            try {
                if (aus == null) oeffne();
                byte[] b = (e + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
                if (groesse + b.length > GROESSE) {
                    weiterruecken();
                    oeffne();
                }
                aus.write(b);
                aus.flush();
                groesse += b.length;
            } catch (IOException x) {
                // still
            }
        }
        Consumer<Eintrag> n = neu;
        if (n != null) n.accept(e);
    }

    /** Die letzten Einträge, älteste zuerst. */
    public synchronized List<Eintrag> letzte() { return new ArrayList<>(letzte); }

    private void oeffne() throws IOException {
        File d = datei();
        aus = Files.newOutputStream(d.toPath(), java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        groesse = d.length();
    }

    private void weiterruecken() throws IOException {
        if (aus != null) aus.close();
        aus = null;
        File alt = new File(ordner, "protokoll." + (DATEIEN - 1) + ".txt");
        if (alt.exists()) Files.delete(alt.toPath());
        for (int i = DATEIEN - 2; i >= 1; i--) {
            File f = new File(ordner, "protokoll." + i + ".txt");
            if (f.exists()) Files.move(f.toPath(), new File(ordner, "protokoll." + (i + 1) + ".txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.move(datei().toPath(), new File(ordner, "protokoll.1.txt").toPath(), StandardCopyOption.REPLACE_EXISTING);
        groesse = 0;
    }

    public synchronized void schliessen() {
        try {
            if (aus != null) aus.close();
        } catch (IOException e) {
            // still
        }
        aus = null;
    }
}
