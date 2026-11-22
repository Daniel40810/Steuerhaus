package com.dan.steuerhaus.messung;

import com.dan.core.pruef.Statistik;
import com.dan.steuerhaus.Guete;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Modell gegen Messung im Messarchiv (seit DB3, Werkbuch HalligHooge 171): übernimmt die Datei des Modells
 * ({@code guete.modell}, von {@code Ausgabe --guete}) in HH_LAUF, HH_MODELL, HH_MODELL_SCHEITEL und die Zellen in HH_GUETE,
 * rechnet die Kennzahlen aus den Sichten HH_VERGLEICH_V und HH_SCHEITEL_PAAR_V und baut daraus die Datei für die App
 * ({@code guete.datei}). Ein Lauf wird als Ganzes ersetzt, wenn sich seine Kennung ändert; sonst bleibt er, wie er ist.
 * Läufe, die nicht mehr in der Datei stehen, bleiben mit AKTIV = 'N' (gelöscht wird nichts davon).
 */
public final class Guetebau {
    private Guetebau() { }

    /** Was {@link #einlesen} getan hat. */
    public record Bilanz(int laeufe, int neu, int gleich, int werte, int marken, int pegel, List<String> ohneStation, int still) {
        public String kurz() {
            return String.format(Locale.GERMAN, "%d Läufe (%d neu oder ersetzt, %d unverändert), %d Werte und %d Scheitel des Modells an %d Pegeln%s%s", laeufe, neu, gleich, werte,
                    marken, pegel, ohneStation.isEmpty() ? "" : ", ohne Station im Archiv: " + ohneStation, still > 0 ? ", " + still + " Läufe stillgelegt" : "");
        }
    }

    // ------------------------------------------------------------------ einlesen

    public static Bilanz einlesen(Messarchiv ar, Guete modell, Consumer<String> log) throws SQLException {
        String p = ar.p;
        Map<String, Messarchiv.Station> st = ar.stationen("PEGEL");
        int neu = 0, gleich = 0, werte = 0, marken = 0;
        Set<String> pegel = new HashSet<>();
        List<String> ohne = new ArrayList<>();
        boolean auto = ar.c.getAutoCommit();
        ar.c.setAutoCommit(false);
        try {
            for (Guete.Lauf l : modell.laeufe) {
                Integer id = null;
                String alt = null;
                try (PreparedStatement s = ar.c.prepareStatement("SELECT LAUF_ID, KENNUNG FROM " + p + "LAUF WHERE SCHLUESSEL = ?")) {
                    s.setString(1, l.schluessel());
                    try (ResultSet r = s.executeQuery()) {
                        if (r.next()) {
                            id = r.getInt(1);
                            alt = r.getString(2);
                        }
                    }
                }
                if (id == null) {
                    try (Statement s = ar.c.createStatement(); ResultSet r = s.executeQuery("SELECT " + p + "LAUF_SEQ.NEXTVAL FROM DUAL")) {
                        r.next();
                        id = r.getInt(1);
                    }
                    try (PreparedStatement s = ar.c.prepareStatement("INSERT INTO " + p + "LAUF (LAUF_ID, SCHLUESSEL, SZENARIEN, ARCHIV, NAME, BEGINN, VON, BIS, SCHRITT_S, KENNUNG, "
                            + "ANTRIEB, MODULE) VALUES (?, ?, ?, ?, ?, " + Messarchiv.TD + ", " + Messarchiv.TD + ", " + Messarchiv.TD + ", ?, ?, ?, ?)")) {
                        s.setInt(1, id);
                        s.setString(2, l.schluessel());
                        laufFelder(s, 3, l);
                        s.executeUpdate();
                    }
                } else {
                    try (PreparedStatement s = ar.c.prepareStatement("UPDATE " + p + "LAUF SET SZENARIEN = ?, ARCHIV = ?, NAME = ?, BEGINN = " + Messarchiv.TD + ", VON = "
                            + Messarchiv.TD + ", BIS = " + Messarchiv.TD + ", SCHRITT_S = ?, KENNUNG = ?, ANTRIEB = ?, MODULE = ?, AKTIV = 'J', GELESEN = SYSDATE WHERE LAUF_ID = ?")) {
                        int i = laufFelder(s, 1, l);
                        s.setInt(i, id);
                        s.executeUpdate();
                    }
                }
                if (l.kennung().equals(alt)) {
                    gleich++;
                    for (Guete.Eintrag e : modell.eintraege(l.schluessel())) if (st.containsKey(e.pegel())) pegel.add(e.pegel());
                    ar.c.commit();
                    continue;
                }
                for (String t : new String[] {"MODELL", "MODELL_SCHEITEL", "GUETE"})
                    try (PreparedStatement s = ar.c.prepareStatement("DELETE FROM " + p + t + " WHERE LAUF_ID = ?")) {
                        s.setInt(1, id);
                        s.executeUpdate();
                    }
                try (PreparedStatement sm = ar.c.prepareStatement("INSERT INTO " + p + "MODELL (LAUF_ID, STATION_ID, ZEIT, WERT) VALUES (?, ?, " + Messarchiv.TD + ", ?)");
                     PreparedStatement sk = ar.c.prepareStatement("INSERT INTO " + p + "MODELL_SCHEITEL (LAUF_ID, STATION_ID, ART, ZEIT, HOEHE) VALUES (?, ?, ?, " + Messarchiv.TD + ", ?)");
                     PreparedStatement sg = ar.c.prepareStatement("INSERT INTO " + p + "GUETE (LAUF_ID, STATION_ID, GITTER, ZELLE, ABSTAND, GRUND) VALUES (?, ?, ?, ?, ?, ?)")) {
                    for (Guete.Eintrag e : modell.eintraege(l.schluessel())) {
                        Messarchiv.Station s = st.get(e.pegel());
                        if (s == null) {
                            if (!ohne.contains(e.pegel())) ohne.add(e.pegel());
                            continue;
                        }
                        pegel.add(e.pegel());
                        Guete.Reihe r = e.modell();
                        for (int i = 0; i < r.n(); i++) {
                            if (Float.isNaN(r.werte()[i])) continue;
                            sm.setInt(1, id);
                            sm.setInt(2, s.id());
                            sm.setString(3, Messarchiv.zeit(r.zeit(i).getEpochSecond()));
                            sm.setDouble(4, Math.round(r.wert(i) * 1000.0) / 1000.0);
                            sm.addBatch();
                            werte++;
                        }
                        for (Guete.Marke m : e.marken()) {
                            sk.setInt(1, id);
                            sk.setInt(2, s.id());
                            sk.setString(3, String.valueOf(m.art()));
                            sk.setString(4, Messarchiv.zeit(m.zeit().getEpochSecond()));
                            sk.setDouble(5, m.hoehe());
                            sk.addBatch();
                            marken++;
                        }
                        sg.setInt(1, id);
                        sg.setInt(2, s.id());
                        sg.setString(3, e.gitter());
                        sg.setInt(4, e.zelle());
                        Messarchiv.setze(sg, 5, rund(e.abstand(), 10));
                        Messarchiv.setze(sg, 6, e.grund());
                        sg.addBatch();
                    }
                    sm.executeBatch();
                    sk.executeBatch();
                    sg.executeBatch();
                }
                ar.c.commit();
                neu++;
                log.accept("Güte: Lauf " + l.schluessel() + (alt == null ? " neu" : " ersetzt (Kennung " + alt + " → " + l.kennung() + ")"));
            }
            // Läufe, die nicht mehr in der Datei stehen: stillgelegt, nicht gelöscht
            List<String> da = modell.laeufe.stream().map(Guete.Lauf::schluessel).toList();
            int still = 0;
            List<String> aktiv = new ArrayList<>();
            try (Statement s = ar.c.createStatement(); ResultSet r = s.executeQuery("SELECT SCHLUESSEL FROM " + p + "LAUF WHERE AKTIV = 'J'")) {
                while (r.next()) aktiv.add(r.getString(1));
            }
            for (String a : aktiv) {
                if (da.contains(a)) continue;
                try (PreparedStatement s = ar.c.prepareStatement("UPDATE " + p + "LAUF SET AKTIV = 'N' WHERE SCHLUESSEL = ?")) {
                    s.setString(1, a);
                    still += s.executeUpdate();
                }
            }
            ar.c.commit();
            return new Bilanz(modell.laeufe.size(), neu, gleich, werte, marken, pegel.size(), ohne, still);
        } catch (SQLException e) {
            ar.c.rollback();
            throw e;
        } finally {
            ar.c.setAutoCommit(auto);
        }
    }

    private static int laufFelder(PreparedStatement s, int i, Guete.Lauf l) throws SQLException {
        s.setString(i++, kurz(String.join(", ", l.szenarien()), 200));
        s.setString(i++, kurz(l.archiv(), 260));
        s.setString(i++, kurz(l.name(), 160));
        s.setString(i++, Messarchiv.zeit(l.beginn().getEpochSecond()));
        s.setString(i++, Messarchiv.zeit(l.von().getEpochSecond()));
        s.setString(i++, Messarchiv.zeit(l.bis().getEpochSecond()));
        s.setInt(i++, l.schritt());
        s.setString(i++, l.kennung());
        s.setString(i++, kurz(l.antrieb(), 400));
        s.setString(i++, kurz(l.module(), 400));
        return i;
    }

    /** Höchstens {@code bytes} Byte in UTF-8 (VARCHAR2 zählt Byte), am Ende mit „…“. */
    static String kurz(String s, int bytes) {
        if (s == null || s.getBytes(StandardCharsets.UTF_8).length <= bytes) return s;
        StringBuilder b = new StringBuilder();
        int n = 3;
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            int l = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
            if (n + l > bytes) break;
            b.appendCodePoint(cp);
            n += l;
            i += Character.charCount(cp);
        }
        return b.append('…').toString();
    }

    // ------------------------------------------------------------------ rechnen

    /**
     * Die Kennzahlen eines Pegels in einem Lauf (rein, ohne Datenbank): Paare Modell–Messung, Paare der Hochwasser, Reihe und
     * Marken des Modells, HThw mit Tag. Der Kennwert zählt nur, wenn die Reihe des Modells den Tag (in {@code zone}) berührt;
     * das Hochwasser am Tag ist das höchste Hochwasser des Modells, das an diesem Tag liegt. Für den Anteil des Windstaus
     * zählen die Paare, deren gemessener Windstau mindestens {@code stauMin} beträgt.
     */
    public static Guete.Kennzahlen kennzahlen(double[] modell, double[] messung, List<Guete.Paar> paare, Guete.Reihe reihe, List<Guete.Marke> marken, double hthw,
            LocalDate tag, double stauMin, ZoneId zone) {
        int n = Math.min(modell.length, messung.length);
        double[] m = Arrays.copyOf(modell, n), g = Arrays.copyOf(messung, n);
        double rmse = n > 0 ? Statistik.rmse(m, g) : Double.NaN, bias = n > 0 ? Statistik.bias(m, g) : Double.NaN;
        double skill = n > 1 ? Statistik.willmott(m, g) : Double.NaN, r = n > 2 ? Statistik.korrelation(m, g) : Double.NaN;
        double dt = 0, dh = 0, q = 0;
        for (Guete.Paar p : paare) {
            dt += p.versatzMin();
            dh += p.versatzM();
            q += p.versatzM() * p.versatzM();
        }
        int hw = paare.size();
        double sm = 0, sg = 0;
        int sp = 0;
        for (Guete.Paar p : paare) {
            if (Double.isNaN(p.stau()) || Double.isNaN(p.gezeit()) || p.stau() < stauMin) continue;
            sm += p.stauModell();
            sg += p.stau();
            sp++;
        }
        int i = reihe.hoechster();
        double max = i < 0 ? Double.NaN : reihe.wert(i);
        Instant maxZeit = i < 0 ? null : reihe.zeit(i);
        double kennwert = Double.NaN, amTag = Double.NaN;
        Instant amTagZeit = null;
        LocalDate kt = null;
        if (tag != null && !Double.isNaN(hthw) && reihe.n() > 0) {
            LocalDate a = reihe.start().atZone(zone).toLocalDate(), b = reihe.zeit(reihe.n() - 1).atZone(zone).toLocalDate();
            if (!tag.isBefore(a) && !tag.isAfter(b)) {
                kennwert = hthw;
                kt = tag;
                for (Guete.Marke mk : marken)
                    if (mk.art() == 'H' && mk.zeit().atZone(zone).toLocalDate().equals(tag) && (Double.isNaN(amTag) || mk.hoehe() > amTag)) {
                        amTag = mk.hoehe();
                        amTagZeit = mk.zeit();
                    }
            }
        }
        return new Guete.Kennzahlen(n, rmse, bias, skill, r, hw, hw > 0 ? dt / hw : Double.NaN, hw > 0 ? dh / hw : Double.NaN, hw > 0 ? Math.sqrt(q / hw) : Double.NaN, sp,
                sp > 0 && sg > 0 ? sm / sg : Double.NaN, max, maxZeit, kennwert, kt, amTag, amTagZeit);
    }

    /** Rechnet die Kennzahlen aller aktiven Läufe und Pegel und schreibt sie in HH_GUETE; gibt die Zahl der Zeilen. */
    public static int rechnen(Messarchiv ar, double stauMin, ZoneId zone) throws SQLException {
        String p = ar.p;
        List<Object[]> zeilen = new ArrayList<>();
        try (Statement s = ar.c.createStatement(); ResultSet r = s.executeQuery("SELECT g.LAUF_ID, g.STATION_ID, " + Messarchiv.epoch("l.VON") + ", " + Messarchiv.epoch("l.BIS")
                + ", l.SCHRITT_S, s.HTHW, TO_CHAR(s.HTHW_TAG, 'YYYY-MM-DD') FROM " + p + "GUETE g JOIN " + p + "LAUF l ON l.LAUF_ID = g.LAUF_ID JOIN " + p
                + "STATION s ON s.STATION_ID = g.STATION_ID WHERE l.AKTIV = 'J' ORDER BY g.LAUF_ID, g.STATION_ID")) {
            while (r.next()) zeilen.add(new Object[] {r.getInt(1), r.getInt(2), r.getLong(3), r.getLong(4), r.getInt(5), Messarchiv.zahl(r, 6), r.getString(7)});
        }
        int n = 0;
        try (PreparedStatement u = ar.c.prepareStatement("UPDATE " + p + "GUETE SET WERTE = ?, RMSE = ?, BIAS = ?, SKILL = ?, KORRELATION = ?, HW = ?, HW_ZEIT = ?, HW_HOEHE = ?, "
                + "HW_RMS = ?, STAU_PAARE = ?, STAU_ANTEIL = ?, MODELL_MAX = ?, MODELL_MAX_ZEIT = " + Messarchiv.TD + ", MODELL_AM_TAG = ?, MODELL_AM_TAG_ZEIT = " + Messarchiv.TD
                + ", BERECHNET = SYSDATE WHERE LAUF_ID = ? AND STATION_ID = ?")) {
            for (Object[] z : zeilen) {
                int lauf = (Integer) z[0], station = (Integer) z[1];
                double[][] mg = vergleich(ar, lauf, station);
                Guete.Reihe reihe = modellReihe(ar, lauf, station, (Long) z[2], (Long) z[3], (Integer) z[4]);
                Guete.Kennzahlen k = kennzahlen(mg[0], mg[1], paare(ar, lauf, station), reihe, modellMarken(ar, lauf, station), (Double) z[5],
                        z[6] == null ? null : LocalDate.parse((String) z[6]), stauMin, zone);
                u.setInt(1, k.werte());
                Messarchiv.setze(u, 2, r3(k.rmse()));
                Messarchiv.setze(u, 3, r3(k.bias()));
                Messarchiv.setze(u, 4, rund(k.skill(), 1e4));
                Messarchiv.setze(u, 5, rund(k.r(), 1e4));
                u.setInt(6, k.hw());
                Messarchiv.setze(u, 7, rund(k.hwZeit(), 10));
                Messarchiv.setze(u, 8, r3(k.hwHoehe()));
                Messarchiv.setze(u, 9, r3(k.hwRms()));
                u.setInt(10, k.stauPaare());
                Messarchiv.setze(u, 11, r3(k.stauAnteil()));
                Messarchiv.setze(u, 12, r3(k.max()));
                u.setString(13, k.maxZeit() == null ? null : Messarchiv.zeit(k.maxZeit().getEpochSecond()));
                Messarchiv.setze(u, 14, r3(k.amTag()));
                u.setString(15, k.amTagZeit() == null ? null : Messarchiv.zeit(k.amTagZeit().getEpochSecond()));
                u.setInt(16, lauf);
                u.setInt(17, station);
                n += u.executeUpdate();
            }
        }
        return n;
    }

    static double r3(double d) { return rund(d, 1000); }

    /** Gerundet auf 1/f (Math.round(NaN) wäre 0). */
    static double rund(double d, double f) { return Double.isNaN(d) || Double.isInfinite(d) ? Double.NaN : Math.round(d * f) / f; }

    /** Modell und Messung zu gleichen Zeiten (HH_VERGLEICH_V). */
    static double[][] vergleich(Messarchiv ar, int lauf, int station) throws SQLException {
        List<double[]> l = new ArrayList<>();
        try (PreparedStatement s = ar.c.prepareStatement("SELECT MODELL, MESSUNG FROM " + ar.p + "VERGLEICH_V WHERE LAUF_ID = ? AND STATION_ID = ? ORDER BY ZEIT")) {
            s.setFetchSize(2000);
            s.setInt(1, lauf);
            s.setInt(2, station);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) l.add(new double[] {r.getDouble(1), r.getDouble(2)});
            }
        }
        double[] m = new double[l.size()], g = new double[l.size()];
        for (int i = 0; i < m.length; i++) { m[i] = l.get(i)[0]; g[i] = l.get(i)[1]; }
        return new double[][] {m, g};
    }

    /** Die Paare der Hochwasser (HH_SCHEITEL_PAAR_V, ART H). */
    static List<Guete.Paar> paare(Messarchiv ar, int lauf, int station) throws SQLException {
        List<Guete.Paar> l = new ArrayList<>();
        try (PreparedStatement s = ar.c.prepareStatement("SELECT " + Messarchiv.epoch("MODELL_ZEIT") + ", MODELL_HOEHE, " + Messarchiv.epoch("MESSUNG_ZEIT")
                + ", MESSUNG_HOEHE, GEZEIT, STAU FROM " + ar.p + "SCHEITEL_PAAR_V WHERE LAUF_ID = ? AND STATION_ID = ? AND ART = 'H' ORDER BY MODELL_ZEIT")) {
            s.setInt(1, lauf);
            s.setInt(2, station);
            try (ResultSet r = s.executeQuery()) {
                while (r.next())
                    l.add(new Guete.Paar(Instant.ofEpochSecond(r.getLong(1)), r.getDouble(2), Instant.ofEpochSecond(r.getLong(3)), r.getDouble(4), Messarchiv.zahl(r, 5),
                            Messarchiv.zahl(r, 6)));
            }
        }
        return l;
    }

    /** Die Reihe des Modells (HH_MODELL) auf dem Raster des Laufs. */
    static Guete.Reihe modellReihe(Messarchiv ar, int lauf, int station, long von, long bis, int schritt) throws SQLException {
        List<long[]> t = new ArrayList<>();
        List<Float> w = new ArrayList<>();
        try (PreparedStatement s = ar.c.prepareStatement("SELECT " + Messarchiv.epoch("ZEIT") + ", WERT FROM " + ar.p + "MODELL WHERE LAUF_ID = ? AND STATION_ID = ? ORDER BY ZEIT")) {
            s.setFetchSize(2000);
            s.setInt(1, lauf);
            s.setInt(2, station);
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) {
                    t.add(new long[] {r.getLong(1)});
                    w.add(r.getFloat(2));
                }
            }
        }
        long[] tt = new long[t.size()];
        float[] ww = new float[t.size()];
        for (int i = 0; i < tt.length; i++) { tt[i] = t.get(i)[0]; ww[i] = w.get(i); }
        return new Guete.Reihe(Instant.ofEpochSecond(von), schritt, Messarchiv.raster(new Messarchiv.Reihe(tt, ww), von, bis, schritt));
    }

    static List<Guete.Marke> modellMarken(Messarchiv ar, int lauf, int station) throws SQLException {
        return marken(ar, "SELECT ART, " + Messarchiv.epoch("ZEIT") + ", HOEHE FROM " + ar.p + "MODELL_SCHEITEL WHERE LAUF_ID = ? AND STATION_ID = ? ORDER BY ZEIT", lauf, station);
    }

    private static List<Guete.Marke> marken(Messarchiv ar, String sql, Object... werte) throws SQLException {
        List<Guete.Marke> l = new ArrayList<>();
        try (PreparedStatement s = ar.c.prepareStatement(sql)) {
            for (int i = 0; i < werte.length; i++) {
                if (werte[i] instanceof Integer x) s.setInt(i + 1, x);
                else s.setString(i + 1, String.valueOf(werte[i]));
            }
            try (ResultSet r = s.executeQuery()) {
                while (r.next()) l.add(new Guete.Marke(r.getString(1).charAt(0), Instant.ofEpochSecond(r.getLong(2)), r.getDouble(3)));
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ die Datei für die App

    /** Die erste Zeit im Raster der 10-Minuten-Mittel (Mitte der Blöcke, :05, :15 …) ab t. */
    public static long mittelAb(long t) {
        long s = Math.floorDiv(t - 300, 600) * 600 + 300;
        return s < t ? s + 600 : s;
    }

    /** Baut die Datei aus dem Archiv: alle aktiven Läufe, je Pegel (in der Folge von {@code guete.pegel}) Modell, Messung, Marken, Paare und Kennzahlen. */
    public static Guete aus(Messarchiv ar, Guete.Auftrag ga, String quelle, ZoneId zone) throws SQLException {
        String p = ar.p;
        List<Guete.Lauf> laeufe = new ArrayList<>();
        List<Integer> ids = new ArrayList<>();
        try (Statement s = ar.c.createStatement(); ResultSet r = s.executeQuery("SELECT LAUF_ID, SCHLUESSEL, SZENARIEN, ARCHIV, NAME, " + Messarchiv.epoch("BEGINN") + ", "
                + Messarchiv.epoch("VON") + ", " + Messarchiv.epoch("BIS") + ", SCHRITT_S, KENNUNG, ANTRIEB, MODULE FROM " + p + "LAUF WHERE AKTIV = 'J' ORDER BY LAUF_ID")) {
            while (r.next()) {
                List<String> sz = new ArrayList<>();
                for (String x : (r.getString(3) == null ? "" : r.getString(3)).split(",")) if (!x.isBlank()) sz.add(x.strip());
                ids.add(r.getInt(1));
                laeufe.add(new Guete.Lauf(r.getString(2), List.copyOf(sz), r.getString(4), r.getString(5), Instant.ofEpochSecond(r.getLong(6)),
                        Instant.ofEpochSecond(r.getLong(7)), Instant.ofEpochSecond(r.getLong(8)), r.getInt(9), r.getString(10), r.getString(11), r.getString(12)));
            }
        }
        List<Guete.Eintrag> es = new ArrayList<>();
        for (int k = 0; k < laeufe.size(); k++) {
            Guete.Lauf l = laeufe.get(k);
            int lauf = ids.get(k);
            List<Object[]> zeilen = new ArrayList<>();
            try (PreparedStatement s = ar.c.prepareStatement("SELECT g.STATION_ID, s.SCHLUESSEL, s.OST, s.NORD, g.GITTER, g.ZELLE, g.ABSTAND, g.GRUND, g.WERTE, g.RMSE, g.BIAS, "
                    + "g.SKILL, g.KORRELATION, g.HW, g.HW_ZEIT, g.HW_HOEHE, g.HW_RMS, g.STAU_PAARE, g.STAU_ANTEIL, g.MODELL_MAX, " + Messarchiv.epoch("g.MODELL_MAX_ZEIT")
                    + ", s.HTHW, TO_CHAR(s.HTHW_TAG, 'YYYY-MM-DD'), g.MODELL_AM_TAG, " + Messarchiv.epoch("g.MODELL_AM_TAG_ZEIT") + ", g.BERECHNET FROM " + p + "GUETE g JOIN " + p
                    + "STATION s ON s.STATION_ID = g.STATION_ID WHERE g.LAUF_ID = ?")) {
                s.setInt(1, lauf);
                try (ResultSet r = s.executeQuery()) {
                    while (r.next()) {
                        Object[] z = new Object[26];
                        for (int i = 1; i <= 26; i++) z[i - 1] = r.getObject(i);
                        zeilen.add(z);
                    }
                }
            }
            List<Guete.Eintrag> je = new ArrayList<>();
            for (Object[] z : zeilen) {
                int station = ganz(z[0]);
                String schl = (String) z[1];
                Guete.Kennzahlen kz = Guete.Kennzahlen.LEER;
                if (z[25] != null) {
                    String tag = (String) z[22];
                    boolean mitTag = tag != null && deckt(l, LocalDate.parse(tag), zone);
                    kz = new Guete.Kennzahlen(ganz(z[8]), zahl(z[9]), zahl(z[10]), zahl(z[11]), zahl(z[12]), ganz(z[13]), zahl(z[14]), zahl(z[15]), zahl(z[16]), ganz(z[17]),
                            zahl(z[18]), zahl(z[19]), zeit(z[20]), mitTag ? zahl(z[21]) : Double.NaN, mitTag ? LocalDate.parse(tag) : null, zahl(z[23]), zeit(z[24]));
                }
                long von = l.von().getEpochSecond(), bis = l.bis().getEpochSecond();
                Guete.Reihe modell = modellReihe(ar, lauf, station, von, bis, l.schritt());
                long m0 = mittelAb(von);
                Messarchiv.Reihe g = ar.reihe("MITTEL", station, "W", m0, bis);
                Guete.Reihe messung = g.n() == 0 || m0 > bis ? Guete.Reihe.LEER : new Guete.Reihe(Instant.ofEpochSecond(m0), 600, Messarchiv.raster(g, m0, bis, 600));
                List<Guete.Marke> gem = marken(ar, "SELECT ART, " + Messarchiv.epoch("ZEIT") + ", HOEHE FROM " + p + "SCHEITEL WHERE STATION_ID = ? AND HERKUNFT = 'MESSUNG' AND ZEIT "
                        + "BETWEEN " + Messarchiv.TD + " AND " + Messarchiv.TD + " ORDER BY ZEIT", station, Messarchiv.zeit(von), Messarchiv.zeit(bis));
                je.add(new Guete.Eintrag(l.schluessel(), schl, Chronikbau.name(schl), zahl(z[2]), zahl(z[3]), (String) z[4], ganz(z[5]), zahl(z[6]), zahl(z[7]), modell,
                        modellMarken(ar, lauf, station), messung, gem, paare(ar, lauf, station), kz));
            }
            je.sort((a, b) -> Integer.compare(rang(ga, a.pegel()), rang(ga, b.pegel())));
            es.addAll(je);
        }
        return new Guete(quelle, Instant.now(), ga.einschwingen(), laeufe, es);
    }

    static double zahl(Object o) { return o instanceof Number n ? n.doubleValue() : Double.NaN; }

    static int ganz(Object o) { return o instanceof Number n ? n.intValue() : 0; }

    static Instant zeit(Object o) { return o instanceof Number n ? Instant.ofEpochSecond(n.longValue()) : null; }

    /** Ob die gewertete Zeit des Laufs den Tag (in der Zone der App) berührt. */
    static boolean deckt(Guete.Lauf l, LocalDate t, ZoneId z) {
        return !t.isBefore(l.von().atZone(z).toLocalDate()) && !t.isAfter(l.bis().atZone(z).toLocalDate());
    }

    static int rang(Guete.Auftrag ga, String pegel) {
        int i = ga.pegel().indexOf(pegel);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    /** Schreibt die Datei nach {@code guete.datei} und in die Kopien, deren Ordner es gibt; gibt eine Zeile fürs Protokoll. */
    public static String schreiben(Guete g, Guete.Auftrag ga) throws IOException {
        boolean neu = g.schreibe(ga.datei());
        int kopien = 0;
        for (File k : ga.kopien()) if (k.getParentFile() != null && k.getParentFile().isDirectory() && g.schreibe(k)) kopien++;
        long mit = g.eintraege.stream().filter(e -> e.kennzahlen().werte() > 0).count(), kw = g.eintraege.stream().filter(e -> e.kennzahlen().mitKennwert()).count();
        return String.format(Locale.GERMAN, "Güte %s: %d Läufe, %d Reihen, %d mit Messung, %d mit HThw (%s)%s", neu ? "neu geschrieben" : "unverändert", g.laeufe.size(),
                g.eintraege.size(), mit, kw, ga.datei().getName(), kopien > 0 ? ", " + kopien + (kopien == 1 ? " Kopie" : " Kopien") : "");
    }
}
