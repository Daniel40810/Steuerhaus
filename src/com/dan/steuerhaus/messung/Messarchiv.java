package com.dan.steuerhaus.messung;

import com.dan.core.astro.Partialtide;
import com.dan.steuerhaus.gezeiten.Vorhersage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Datenbankseite des Messarchivs (seit DB1, Werkbuch HalligHooge 167) über {@code java.sql}: Stationen, Konstanten
 * der Gezeit, Schwellen und Orte; Rohwerte ({@code ROH}, je Minute), Mittel ({@code MITTEL}, 10 Minuten, Zeit in der
 * Mitte), Scheitel; das Protokoll der Abrufe; die Fächer (Partitionen) und ihr Platz. Zeiten sind UTC und werden als Text
 * {@code JJJJ-MM-TT HH:MM:SS} mit {@code TO_DATE} gebunden – so bleibt die Spalte DATE und ihr Index nutzbar, ohne
 * Umweg über die Zeitzone des Treibers. Gelesen werden sie als Sekunden seit 1970.
 */
public final class Messarchiv {
    static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    static final String TD = "TO_DATE(?, 'YYYY-MM-DD HH24:MI:SS')";
    static final String EPOCH = "ROUND((%s - DATE '1970-01-01') * 86400)";
    /** Zeilen je Paket beim Einfügen. */
    public static int paket = 5000;

    public final Connection c;
    public final String p;
    public final int srid;

    public Messarchiv(Connection c, String praefix, int srid) {
        this.c = c;
        this.p = praefix;
        this.srid = srid;
    }

    public static String zeit(long epoch) { return LocalDateTime.ofEpochSecond(epoch, 0, ZoneOffset.UTC).format(ZEIT); }

    static String epoch(String spalte) { return String.format(EPOCH, spalte); }

    // ------------------------------------------------------------------ Stationen, Orte, Konstanten, Schwellen

    /** Eine Station, wie sie in der Tabelle steht. */
    public record Station(int id, String art, String schluessel, String name, String kennung, double hoehe, double geber, boolean aktiv) { }

    /** Alle Stationen einer Art (PEGEL, WIND, ORT) nach Schlüssel. */
    public Map<String, Station> stationen(String art) throws SQLException {
        Map<String, Station> m = new LinkedHashMap<>();
        try (PreparedStatement st = c.prepareStatement("SELECT STATION_ID, ART, SCHLUESSEL, NAME, KENNUNG, HOEHE, GEBER, AKTIV FROM " + p
                + "STATION WHERE ART = ? ORDER BY SCHLUESSEL")) {
            st.setString(1, art);
            try (ResultSet r = st.executeQuery()) {
                while (r.next()) m.put(r.getString(3), new Station(r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        zahl(r, 6), zahl(r, 7), "J".equals(r.getString(8))));
            }
        }
        return m;
    }

    static double zahl(ResultSet r, int i) throws SQLException {
        double d = r.getDouble(i);
        return r.wasNull() ? Double.NaN : d;
    }

    static void setze(PreparedStatement st, int i, double d) throws SQLException {
        if (Double.isNaN(d)) st.setNull(i, Types.NUMERIC);
        else st.setDouble(i, d);
    }

    /**
     * Legt eine Station an oder schreibt ihre Stammdaten neu (Schlüssel: Art und Schlüssel); Lage aus Ost und Nord im
     * SRID des Archivs. Gibt die Nummer zurück.
     */
    public int station(String art, String schluessel, String name, String kennung, String gewaesser, double laenge, double breite, double ost, double nord,
            double hoehe, double geber, double mthw, String quelle) throws SQLException {
        Integer id = null;
        try (PreparedStatement st = c.prepareStatement("SELECT STATION_ID FROM " + p + "STATION WHERE ART = ? AND SCHLUESSEL = ?")) {
            st.setString(1, art);
            st.setString(2, schluessel);
            try (ResultSet r = st.executeQuery()) { if (r.next()) id = r.getInt(1); }
        }
        String lage = "MDSYS.SDO_GEOMETRY(2001, " + srid + ", MDSYS.SDO_POINT_TYPE(?, ?, NULL), NULL, NULL)";
        String q = "(SELECT QUELLE_ID FROM " + p + "QUELLE WHERE KURZ = ?)";
        if (id == null) {
            try (Statement s = c.createStatement(); ResultSet r = s.executeQuery("SELECT " + p + "STATION_SEQ.NEXTVAL FROM DUAL")) {
                r.next();
                id = r.getInt(1);
            }
            try (PreparedStatement st = c.prepareStatement("INSERT INTO " + p + "STATION (STATION_ID, ART, SCHLUESSEL, NAME, KENNUNG, GEWAESSER, LAENGE, BREITE, OST, NORD, "
                    + "HOEHE, GEBER, MTHW, QUELLE_ID, LAGE) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " + q + ", " + lage + ")")) {
                st.setInt(1, id);
                st.setString(2, art);
                st.setString(3, schluessel);
                stammdaten(st, 4, name, kennung, gewaesser, laenge, breite, ost, nord, hoehe, geber, mthw, quelle);
                st.executeUpdate();
            }
        } else {
            try (PreparedStatement st = c.prepareStatement("UPDATE " + p + "STATION SET NAME = ?, KENNUNG = ?, GEWAESSER = ?, LAENGE = ?, BREITE = ?, OST = ?, NORD = ?, "
                    + "HOEHE = ?, GEBER = ?, MTHW = ?, QUELLE_ID = " + q + ", LAGE = " + lage + " WHERE STATION_ID = ?")) {
                int i = stammdaten(st, 1, name, kennung, gewaesser, laenge, breite, ost, nord, hoehe, geber, mthw, quelle);
                st.setInt(i, id);
                st.executeUpdate();
            }
        }
        return id;
    }

    private static int stammdaten(PreparedStatement st, int i, String name, String kennung, String gewaesser, double laenge, double breite, double ost,
            double nord, double hoehe, double geber, double mthw, String quelle) throws SQLException {
        st.setString(i++, name);
        st.setString(i++, kennung);
        st.setString(i++, gewaesser);
        setze(st, i++, laenge);
        setze(st, i++, breite);
        setze(st, i++, ost);
        setze(st, i++, nord);
        setze(st, i++, hoehe);
        setze(st, i++, geber);
        setze(st, i++, mthw);
        st.setString(i++, quelle);
        setze(st, i++, ost);
        setze(st, i++, nord);
        return i;
    }

    /** Ersetzt die Konstanten einer Station aus einer Herkunft (Z0 als Zeile mit TIDE = 'Z0'; Zeitraum der Analyse oder null). */
    public void gezeit(int station, String herkunft, double z0, List<Vorhersage.Konstante> k, Instant von, Instant bis) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("DELETE FROM " + p + "GEZEIT WHERE STATION_ID = ? AND HERKUNFT = ?")) {
            st.setInt(1, station);
            st.setString(2, herkunft);
            st.executeUpdate();
        }
        try (PreparedStatement st = c.prepareStatement("INSERT INTO " + p + "GEZEIT (STATION_ID, HERKUNFT, TIDE, AMPLITUDE, PHASE, VON, BIS) VALUES (?, ?, ?, ?, ?, "
                + TD + ", " + TD + ")")) {
            List<Object[]> z = new ArrayList<>();
            z.add(new Object[] {"Z0", z0, 0.0});
            for (Vorhersage.Konstante x : k) z.add(new Object[] {x.tide().name, x.amplitude(), x.phaseGrad()});
            for (Object[] o : z) {
                st.setInt(1, station);
                st.setString(2, herkunft);
                st.setString(3, (String) o[0]);
                st.setDouble(4, (Double) o[1]);
                st.setDouble(5, (Double) o[2]);
                st.setString(6, von == null ? null : zeit(von.getEpochSecond()));
                st.setString(7, bis == null ? null : zeit(bis.getEpochSecond()));
                st.addBatch();
            }
            st.executeBatch();
        }
    }

    /** Die Vorhersage aus den Konstanten einer Station und Herkunft; null, wenn keine da sind. */
    public Vorhersage vorhersage(int station, String herkunft) throws SQLException {
        double z0 = Double.NaN;
        List<Vorhersage.Konstante> k = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("SELECT TIDE, AMPLITUDE, PHASE FROM " + p + "GEZEIT WHERE STATION_ID = ? AND HERKUNFT = ? ORDER BY TIDE")) {
            st.setInt(1, station);
            st.setString(2, herkunft);
            try (ResultSet r = st.executeQuery()) {
                while (r.next()) {
                    if ("Z0".equals(r.getString(1))) { z0 = r.getDouble(2); continue; }
                    Partialtide t = Partialtide.nach(r.getString(1));
                    if (t != null) k.add(new Vorhersage.Konstante(t, r.getDouble(2), r.getDouble(3)));
                }
            }
        }
        return Double.isNaN(z0) ? null : new Vorhersage(z0, k);
    }

    /** Eine Schwelle (Höhe m NHN) an einer Station anlegen oder neu schreiben. */
    public void schwelle(int station, String art, double hoehe, String text) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("MERGE INTO " + p + "SCHWELLE s USING (SELECT ? STATION_ID, ? ART FROM DUAL) n "
                + "ON (s.STATION_ID = n.STATION_ID AND s.ART = n.ART) WHEN MATCHED THEN UPDATE SET s.HOEHE = ?, s.TEXT = ? "
                + "WHEN NOT MATCHED THEN INSERT (STATION_ID, ART, HOEHE, TEXT) VALUES (n.STATION_ID, n.ART, ?, ?)")) {
            st.setInt(1, station);
            st.setString(2, art);
            st.setDouble(3, hoehe);
            st.setString(4, text);
            st.setDouble(5, hoehe);
            st.setString(6, text);
            st.executeUpdate();
        }
    }

    /** Ersetzt alle Orte einer Quelle (Art, Name, Ost, Nord, Kennung bei OSM); gibt die Zahl zurück. */
    public int orteErsetzen(String quelle, List<String[]> orte) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("DELETE FROM " + p + "ORT WHERE QUELLE_ID = (SELECT QUELLE_ID FROM " + p + "QUELLE WHERE KURZ = ?)")) {
            st.setString(1, quelle);
            st.executeUpdate();
        }
        int n = 0;
        try (PreparedStatement st = c.prepareStatement("INSERT INTO " + p + "ORT (ORT_ID, ART, NAME, OST, NORD, KENNUNG, QUELLE_ID, LAGE) VALUES (" + p
                + "ORT_SEQ.NEXTVAL, ?, ?, ?, ?, ?, (SELECT QUELLE_ID FROM " + p + "QUELLE WHERE KURZ = ?), MDSYS.SDO_GEOMETRY(2001, " + srid
                + ", MDSYS.SDO_POINT_TYPE(?, ?, NULL), NULL, NULL))")) {
            for (String[] o : orte) {
                double ost = Double.parseDouble(o[2]), nord = Double.parseDouble(o[3]);
                st.setString(1, o[0]);
                st.setString(2, o[1]);
                st.setDouble(3, ost);
                st.setDouble(4, nord);
                st.setString(5, o.length > 4 ? o[4] : null);
                st.setString(6, quelle);
                st.setDouble(7, ost);
                st.setDouble(8, nord);
                st.addBatch();
                n++;
            }
            st.executeBatch();
        }
        return n;
    }

    // ------------------------------------------------------------------ Reihen

    /** Die jüngste Zeit (Epoch-Sekunden) einer Station und Größe in ROH oder MITTEL; null ohne Werte. */
    public Long letzte(String tabelle, int station, String groesse) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT " + epoch("MAX(ZEIT)") + " FROM " + p + tabelle + " WHERE STATION_ID = ? AND GROESSE = ?")) {
            st.setInt(1, station);
            st.setString(2, groesse);
            try (ResultSet r = st.executeQuery()) {
                if (!r.next()) return null;
                long v = r.getLong(1);
                return r.wasNull() ? null : v;
            }
        }
    }

    /** Die älteste Zeit (Epoch-Sekunden) einer Station und Größe; null ohne Werte. */
    public Long erste(String tabelle, int station, String groesse) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT " + epoch("MIN(ZEIT)") + " FROM " + p + tabelle + " WHERE STATION_ID = ? AND GROESSE = ?")) {
            st.setInt(1, station);
            st.setString(2, groesse);
            try (ResultSet r = st.executeQuery()) {
                if (!r.next()) return null;
                long v = r.getLong(1);
                return r.wasNull() ? null : v;
            }
        }
    }

    /**
     * Fügt Werte ein, die nach {@code nach} liegen (null: alle) und gültig sind; Zeiten in Epoch-Sekunden. Doppelte
     * Schlüssel überspringt die Datenbank (Hinweis {@code IGNORE_ROW_ON_DUPKEY_INDEX}). Gibt die Zahl der gesendeten Zeilen.
     */
    public int einfuegen(String tabelle, int station, String groesse, long[] t, float[] w, Long nach, Long bis) throws SQLException {
        String pk = p + tabelle + "_PK";
        String sql = "INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(" + p + tabelle + ", " + pk + ") */ INTO " + p + tabelle + " (STATION_ID, GROESSE, ZEIT, WERT) VALUES (?, ?, "
                + TD + ", ?)";
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        int n = 0, imPaket = 0;
        try (PreparedStatement st = c.prepareStatement(sql)) {
            for (int i = 0; i < t.length; i++) {
                if (Float.isNaN(w[i]) || (nach != null && t[i] <= nach) || (bis != null && t[i] > bis)) continue;
                st.setInt(1, station);
                st.setString(2, groesse);
                st.setString(3, zeit(t[i]));
                st.setDouble(4, Math.round(w[i] * 1000.0) / 1000.0);
                st.addBatch();
                n++;
                if (++imPaket == paket) {
                    st.executeBatch();
                    c.commit();
                    imPaket = 0;
                }
            }
            if (imPaket > 0) st.executeBatch();
            c.commit();
        } catch (SQLException e) {
            c.rollback();
            throw e;
        } finally {
            c.setAutoCommit(auto);
        }
        return n;
    }

    /** Wie viele Werte einer Station und Größe zwischen zwei Zeiten (einschließlich) stehen. */
    public long zahl(String tabelle, int station, String groesse, long von, long bis) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT COUNT(*) FROM " + p + tabelle + " WHERE STATION_ID = ? AND GROESSE = ? AND ZEIT BETWEEN " + TD + " AND " + TD)) {
            st.setInt(1, station);
            st.setString(2, groesse);
            st.setString(3, zeit(von));
            st.setString(4, zeit(bis));
            try (ResultSet r = st.executeQuery()) { r.next(); return r.getLong(1); }
        }
    }

    /** Eine gelesene Reihe: Zeiten (Epoch-Sekunden) und Werte. */
    public record Reihe(long[] t, float[] w) {
        public int n() { return t.length; }
    }

    /** Werte einer Station und Größe in [von, bis], nach der Zeit. */
    public Reihe reihe(String tabelle, int station, String groesse, long von, long bis) throws SQLException {
        List<long[]> l = new ArrayList<>();
        List<Float> v = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("SELECT " + epoch("ZEIT") + ", WERT FROM " + p + tabelle + " WHERE STATION_ID = ? AND GROESSE = ? AND ZEIT BETWEEN "
                + TD + " AND " + TD + " ORDER BY ZEIT")) {
            st.setFetchSize(5000);
            st.setInt(1, station);
            st.setString(2, groesse);
            st.setString(3, zeit(von));
            st.setString(4, zeit(bis));
            try (ResultSet r = st.executeQuery()) {
                while (r.next()) {
                    l.add(new long[] {r.getLong(1)});
                    v.add(r.getFloat(2));
                }
            }
        }
        long[] t = new long[l.size()];
        float[] w = new float[l.size()];
        for (int i = 0; i < t.length; i++) { t[i] = l.get(i)[0]; w[i] = v.get(i); }
        return new Reihe(t, w);
    }

    /** Eine Reihe auf festem Schritt in [von, bis] (fehlende Werte NaN). */
    public static float[] raster(Reihe r, long von, long bis, int schritt) {
        int n = (int) ((bis - von) / schritt) + 1;
        float[] x = new float[Math.max(0, n)];
        java.util.Arrays.fill(x, Float.NaN);
        for (int i = 0; i < r.n(); i++) {
            long d = r.t()[i] - von;
            if (d < 0 || d % schritt != 0) continue;
            int k = (int) (d / schritt);
            if (k < n) x[k] = r.w()[i];
        }
        return x;
    }

    /**
     * Verdichtet die Minutenwerte W einer Station zu Mitteln über 10 Minuten (Zeit in der Mitte, mindestens 5 Werte) für
     * die Blöcke in [von, bis); was schon in MITTEL steht, bleibt. Gibt die Zahl der neuen Mittel.
     */
    public int verdichten(int station, long von, long bis) throws SQLException {
        if (bis <= von) return 0;
        String block = "TRUNC(ZEIT, 'HH24') + (FLOOR(TO_NUMBER(TO_CHAR(ZEIT, 'MI')) / 10) * 10 + 5) / 1440";
        String sql = "INSERT INTO " + p + "MITTEL (STATION_ID, GROESSE, ZEIT, WERT, ANZAHL) SELECT b.STATION_ID, b.GROESSE, b.Z, b.WERT, b.ANZAHL FROM ("
                + "SELECT STATION_ID, GROESSE, " + block + " AS Z, ROUND(AVG(WERT), 3) AS WERT, COUNT(*) AS ANZAHL FROM " + p + "ROH "
                + "WHERE STATION_ID = ? AND GROESSE = 'W' AND ZEIT >= " + TD + " AND ZEIT < " + TD + " GROUP BY STATION_ID, GROESSE, " + block
                + " HAVING COUNT(*) >= 5) b WHERE NOT EXISTS (SELECT 1 FROM " + p + "MITTEL m WHERE m.STATION_ID = b.STATION_ID AND m.GROESSE = b.GROESSE AND m.ZEIT = b.Z)";
        try (PreparedStatement st = c.prepareStatement(sql)) {
            st.setInt(1, station);
            st.setString(2, zeit(von));
            st.setString(3, zeit(bis));
            return st.executeUpdate();
        }
    }

    /** Beginn eines 10-Minuten-Blocks (Epoch-Sekunden), der t enthält. */
    public static long block(long t) { return Math.floorDiv(t, 600) * 600; }

    // ------------------------------------------------------------------ Scheitel

    /** Die jüngste Zeit eines Scheitels einer Station und Herkunft; null ohne. */
    public Long letzterScheitel(int station, String herkunft) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT " + epoch("MAX(ZEIT)") + " FROM " + p + "SCHEITEL WHERE STATION_ID = ? AND HERKUNFT = ?")) {
            st.setInt(1, station);
            st.setString(2, herkunft);
            try (ResultSet r = st.executeQuery()) {
                if (!r.next()) return null;
                long v = r.getLong(1);
                return r.wasNull() ? null : v;
            }
        }
    }

    /** Die Art des jüngsten Scheitels (H, N) oder 0. */
    public char letzteArt(int station, String herkunft) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT ART FROM " + p + "SCHEITEL WHERE STATION_ID = ? AND HERKUNFT = ? ORDER BY ZEIT DESC FETCH FIRST 1 ROWS ONLY")) {
            st.setInt(1, station);
            st.setString(2, herkunft);
            try (ResultSet r = st.executeQuery()) { return r.next() ? r.getString(1).charAt(0) : 0; }
        }
    }

    /** Ein Scheitel mit Gezeit und Windstau (m). */
    public record Eintrag(char art, long zeit, double hoehe, double gezeit, double stau) { }

    /** Fügt Scheitel ein (Herkunft MESSUNG oder SCHAETZUNG); vorhandene Schlüssel bleiben. Gibt die Zahl. */
    public int scheitel(int station, String herkunft, List<Eintrag> l) throws SQLException {
        if (l.isEmpty()) return 0;
        try (PreparedStatement st = c.prepareStatement("INSERT /*+ IGNORE_ROW_ON_DUPKEY_INDEX(" + p + "SCHEITEL, " + p + "SCHEITEL_PK) */ INTO " + p
                + "SCHEITEL (STATION_ID, ART, ZEIT, HOEHE, GEZEIT, STAU, HERKUNFT) VALUES (?, ?, " + TD + ", ?, ?, ?, ?)")) {
            for (Eintrag e : l) {
                st.setInt(1, station);
                st.setString(2, String.valueOf(e.art()));
                st.setString(3, zeit(e.zeit()));
                st.setDouble(4, Math.round(e.hoehe() * 1000.0) / 1000.0);
                setze(st, 5, Double.isNaN(e.gezeit()) ? Double.NaN : Math.round(e.gezeit() * 1000.0) / 1000.0);
                setze(st, 6, Double.isNaN(e.stau()) ? Double.NaN : Math.round(e.stau() * 1000.0) / 1000.0);
                st.setString(7, herkunft);
                st.addBatch();
            }
            st.executeBatch();
        }
        return l.size();
    }

    /** Wie viele Scheitel einer Art eine Station in [von, bis] hat. */
    public int scheitelZahl(int station, char art, long von, long bis) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT COUNT(*) FROM " + p + "SCHEITEL WHERE STATION_ID = ? AND ART = ? AND ZEIT BETWEEN " + TD + " AND " + TD)) {
            st.setInt(1, station);
            st.setString(2, String.valueOf(art));
            st.setString(3, zeit(von));
            st.setString(4, zeit(bis));
            try (ResultSet r = st.executeQuery()) { r.next(); return r.getInt(1); }
        }
    }

    // ------------------------------------------------------------------ Protokoll der Abrufe

    /** Ein Eintrag im Protokoll. */
    public void abruf(String art, Instant beginn, double dauerS, long zeilen, long verworfen, int fehler, String meldung) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("INSERT INTO " + p + "ABRUF (ABRUF_ID, BEGINN, DAUER_S, ART, ZEILEN, VERWORFEN, FEHLER, MELDUNG) VALUES ("
                + p + "ABRUF_SEQ.NEXTVAL, " + TD + ", ?, ?, ?, ?, ?, ?)")) {
            st.setString(1, zeit(beginn.getEpochSecond()));
            st.setDouble(2, Math.round(dauerS * 10) / 10.0);
            st.setString(3, art);
            st.setLong(4, zeilen);
            st.setLong(5, verworfen);
            st.setInt(6, fehler);
            st.setString(7, meldung == null ? null : meldung.length() > 1000 ? meldung.substring(0, 997) + " …" : meldung);
            st.executeUpdate();
        }
    }

    /** Beginn des jüngsten fehlerfreien Abrufs einer Art; null ohne. */
    public Instant letzterAbruf(String art) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("SELECT " + epoch("MAX(BEGINN)") + " FROM " + p + "ABRUF WHERE ART = ? AND FEHLER = 0")) {
            st.setString(1, art);
            try (ResultSet r = st.executeQuery()) {
                if (!r.next()) return null;
                long v = r.getLong(1);
                return r.wasNull() ? null : Instant.ofEpochSecond(v);
            }
        }
    }

    /** Löscht Einträge des Protokolls, die älter als {@code tage} sind; gibt die Zahl. */
    public int abrufeAufraeumen(int tage) throws SQLException {
        try (PreparedStatement st = c.prepareStatement("DELETE FROM " + p + "ABRUF WHERE BEGINN < SYSDATE - ?")) {
            st.setInt(1, tage);
            return st.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ Fächer und Platz

    static final Pattern DATUM = Pattern.compile("(\\d{4})-(\\d{2})-(\\d{2})");

    /**
     * Die Fächer (Partitionen) einer Tabelle mit Zeitraum und Platz (Tabelle und lokaler Index). Das erste Fach ist der
     * Anker (ab null); die übrigen sind Intervalle von {@code monate} Monaten.
     */
    public List<Aufbewahrung.Fach> faecher(String tabelle, int monate) throws SQLException {
        String t = p + tabelle;
        Map<String, Double> mb = new HashMap<>();
        try (PreparedStatement st = c.prepareStatement("SELECT s.PARTITION_NAME, SUM(s.BYTES) FROM USER_SEGMENTS s LEFT JOIN USER_INDEXES i ON i.INDEX_NAME = s.SEGMENT_NAME "
                + "WHERE s.PARTITION_NAME IS NOT NULL AND (s.SEGMENT_NAME = ? OR i.TABLE_NAME = ?) GROUP BY s.PARTITION_NAME")) {
            st.setString(1, t);
            st.setString(2, t);
            try (ResultSet r = st.executeQuery()) { while (r.next()) mb.put(r.getString(1), r.getDouble(2) / 1048576.0); }
        }
        List<Aufbewahrung.Fach> l = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("SELECT PARTITION_NAME, PARTITION_POSITION, HIGH_VALUE FROM USER_TAB_PARTITIONS WHERE TABLE_NAME = ? ORDER BY PARTITION_POSITION")) {
            st.setString(1, t);
            try (ResultSet r = st.executeQuery()) {
                while (r.next()) {
                    String name = r.getString(1);
                    int pos = r.getInt(2);
                    String hv = r.getString(3);
                    Matcher m = DATUM.matcher(hv == null ? "" : hv);
                    if (!m.find()) continue;
                    LocalDate bis = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)));
                    LocalDate ab = pos == 1 ? null : bis.minusMonths(monate);
                    l.add(new Aufbewahrung.Fach(tabelle, name, ab, bis, mb.getOrDefault(name, 0.0)));
                }
            }
        }
        return l;
    }

    /** Nimmt ein Fach weg (ALTER TABLE … DROP PARTITION; die lokalen Indizes gehen mit). */
    public void wegnehmen(Aufbewahrung.Fach f) throws SQLException {
        if (f.anker()) throw new IllegalArgumentException("Das Anker-Fach bleibt: " + f.kurz());
        if (!f.partition().matches("[A-Z0-9_$#]+")) throw new IllegalArgumentException("Name der Partition: " + f.partition());
        try (Statement st = c.createStatement()) { st.execute("ALTER TABLE " + p + f.tabelle() + " DROP PARTITION " + f.partition()); }
    }

    /** Der ganze Platz des Schemas in MB (USER_SEGMENTS). */
    public double platzMb() throws SQLException {
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT NVL(SUM(BYTES), 0) FROM USER_SEGMENTS")) {
            r.next();
            return r.getDouble(1) / 1048576.0;
        }
    }

    /** Die Quoten des Schemas: „USERS 412,0 MB von unbegrenzt“. */
    public List<String> quoten() throws SQLException {
        List<String> l = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery("SELECT TABLESPACE_NAME, BYTES, MAX_BYTES FROM USER_TS_QUOTAS ORDER BY 1")) {
            while (r.next()) {
                double b = r.getDouble(2) / 1048576.0, mx = r.getDouble(3);
                l.add(String.format(Locale.GERMAN, "%s %.1f MB von %s", r.getString(1), b, mx < 0 ? "unbegrenzt" : String.format(Locale.GERMAN, "%.0f MB", mx / 1048576.0)));
            }
        }
        return l;
    }

    /** Die Zeilen einer Abfrage als Text (Spalten mit „ · “ getrennt), höchstens {@code max} Zeilen. */
    public List<String> zeilen(String sql, int max) throws SQLException {
        List<String> l = new ArrayList<>();
        try (Statement st = c.createStatement(); ResultSet r = st.executeQuery(sql)) {
            int n = r.getMetaData().getColumnCount();
            while (r.next() && l.size() < max) {
                StringBuilder s = new StringBuilder();
                for (int i = 1; i <= n; i++) {
                    if (i > 1) s.append(" · ");
                    Object o = r.getObject(i);
                    if (o instanceof java.sql.Timestamp ts) s.append(ts.toLocalDateTime().format(ZEIT));
                    else if (o instanceof java.math.BigDecimal b) s.append(b.stripTrailingZeros().toPlainString());
                    else s.append(o == null ? "–" : String.valueOf(o));
                }
                l.add(s.toString());
            }
        }
        return l;
    }
}
