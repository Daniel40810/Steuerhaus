package com.dan.steuerhaus.messung;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.util.List;
import java.util.Locale;

/**
 * Die Schlüssel {@code messung.*} des Manifests (seit DB1, Werkbuch HalligHooge 167): wo die Datenbank und ihre Skripte
 * liegen, die Wachstumsgrenze, die Quellen und der geschätzte Ort (bei HalligHooge der Anleger Hooge, der keinen eigenen
 * Pegel hat: Gezeit aus {@code gezeiten.datei}, Windstau aus den Bezugspegeln, Höhen aus {@code landunter.krone} und
 * {@code landunter.nass}). Seit DB2 die Chronik ({@code chronik.datei}, {@code chronik.kopie}) und der Ordner der historischen
 * Windwerte des DWD ({@code messung.dwd.historisch}). Pfade relativ zum Manifest.
 */
public record Messauftrag(String name, File db, File sql, String schema, String praefix, String passwortVariable, int srid,
        int rohMonate, int abrufTage, double grenzeMb, double warnungProzent, String pegelonline, String dwd, double dwdStunden,
        String ortSchluessel, String ortName, File gezeiten, double krone, String kroneText, double nass, String nassText, File protokoll,
        File chronik, List<File> chronikKopien, String dwdHistorisch) {

    /** Liest die Schlüssel; null, wenn {@code messung.db} fehlt (die App hat kein Messarchiv). */
    public static Messauftrag lies(Manifest m) {
        String db = m.text("messung.db", "");
        if (db.isEmpty()) return null;
        String[] ort = m.text("messung.ort", "").split(";");
        String gz = m.text("gezeiten.datei", "");
        String[] kr = m.text("landunter.krone", "").split(";"), na = m.text("landunter.nass", "").split(";");
        File home = new File(System.getProperty("user.home"), ".steuerhaus" + File.separator + m.titel());
        return new Messauftrag(m.text("messung.name", "Messarchiv"), norm(m.relativ(db)), norm(m.relativ(m.text("messung.sql", "db/sql"))),
                m.text("messung.schema", m.titel().toUpperCase(Locale.ROOT)), m.text("messung.praefix", "HH_"),
                m.text("messung.passwort", m.titel() + "_DB_PASSWORD"), ganz(m.text("messung.srid", "25832"), 25832),
                ganz(m.text("messung.roh.monate", "6"), 6), ganz(m.text("messung.abruf.tage", "400"), 400),
                zahl(m.text("messung.grenze.mb", "1500"), 1500), zahl(m.text("messung.warnung", "80"), 80),
                m.text("messung.pegelonline", "https://www.pegelonline.wsv.de/webservices/rest-api/v2"),
                m.text("messung.dwd", "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/wind/recent"),
                zahl(m.text("messung.dwd.stunden", "20"), 20),
                ort.length > 0 && !ort[0].isBlank() ? ort[0].strip() : null, ort.length > 1 ? ort[1].strip() : null,
                gz.isEmpty() ? null : norm(m.relativ(gz)),
                kr.length > 0 ? zahl(kr[0], Double.NaN) : Double.NaN, kr.length > 1 ? kr[1].strip() : "Krone",
                na.length > 0 ? zahl(na[0], Double.NaN) : Double.NaN, na.length > 1 ? na[1].strip() : "Landunter",
                new File(home, "sammler.log"),
                m.text("chronik.datei", "").isEmpty() ? null : norm(m.relativ(m.text("chronik.datei", ""))), kopien(m),
                m.text("messung.dwd.historisch", m.text("messung.dwd", "https://opendata.dwd.de/climate_environment/CDC/observations_germany/climate/10_minutes/wind/recent")
                        .replaceFirst("/recent/?$", "/historical")));
    }

    /** Seit DB2: {@code chronik.kopie} – weitere Orte der Chronik (durch ; getrennt, relativ zum Manifest), etwa im Abbild. */
    static List<File> kopien(Manifest m) {
        List<File> l = new java.util.ArrayList<>();
        for (String k : m.text("chronik.kopie", "").split(";")) if (!k.isBlank()) l.add(norm(m.relativ(k.strip())));
        return List.copyOf(l);
    }

    /** Die Sichten, die das Schema tragen muss (03_sichten.sql). */
    public List<String> sichten() {
        return List.of(praefix + "STATION_V", praefix + "FUELLSTAND_V", praefix + "HOCHWASSER_V", praefix + "STURMFLUT_V", praefix + "SCHAETZUNG_V",
                praefix + "LANDUNTER_V");
    }

    static File norm(File f) { return f.toPath().toAbsolutePath().normalize().toFile(); }

    static double zahl(String s, double vorgabe) {
        try {
            return Double.parseDouble(s.strip().replace(',', '.'));
        } catch (RuntimeException e) {
            return vorgabe;
        }
    }

    static int ganz(String s, int vorgabe) {
        try {
            return Integer.parseInt(s.strip());
        } catch (RuntimeException e) {
            return vorgabe;
        }
    }
}
