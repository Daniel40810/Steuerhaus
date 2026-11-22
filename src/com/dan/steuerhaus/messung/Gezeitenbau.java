package com.dan.steuerhaus.messung;

import com.dan.core.pruef.Pegel;
import com.dan.steuerhaus.gezeiten.Gezeitenanalyse;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Vorhersage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Seit DB5 (Werkbuch HalligHooge 176, F276–F282 A): die Gezeit wächst mit der Messung. Einmal im Monat (oder mit
 * {@code Sammler --gezeiten}) je Pegel:
 * <ol>
 * <li>alle 10-Minuten-Mittel seit Beginn;</li>
 * <li><b>Prüfzeit:</b> die jüngsten 30 Tage, nie vor dem Ende der Analyse der geltenden Konstanten, mit mindestens 30 Tagen
 *     Analyse davor ({@link Gezeitenanalyse#pruefzeit});</li>
 * <li><b>Kandidat:</b> die robuste Analyse über alle Tage vor der Prüfzeit, Tiden nach Rayleigh ({@link Gezeitenanalyse#robust});</li>
 * <li><b>Vergleich ohne Faktor:</b> Kandidat und geltende Konstanten (MESSUNG, sonst die 30 Tage der Übernahme) an den Hoch-
 *     und Niedrigwassern der Prüfzeit ohne Windstau, aus den Minutenwerten;</li>
 * <li><b>angenommen</b> nach {@link Gezeitenanalyse#besser}: dann gilt der Kandidat, wie er geprüft ist (HH_GEZEIT, Herkunft
 *     MESSUNG), und die Scheitel des Pegels bekommen Gezeit und Windstau neu.</li>
 * </ol>
 * Für den Tidenkalender dasselbe am Bezug (Pellworm), mit dem Kandidaten mit und ohne Faktor auf die Kennwerte gegen die
 * geltende Tafel; der bessere geht in die Tafel (F278 A), übertragen auf den Ort mit den Verhältnissen der geltenden Tafel
 * (aus dem Modell, Z1). Die Tafel wird nur bei Annahme geschrieben. Danach je Pegel und Monat das Mittelwasser ohne Windstau.
 * Jeder Kandidat steht in HH_ANALYSE, angenommen oder nicht.
 */
public final class Gezeitenbau {
    public static final String HERKUNFT = "MESSUNG", PEGEL = "PEGEL", KALENDER = "KALENDER";
    static final String W = Uebernahme.W;
    static final int SCHRITT = 600, MINUTE = 60;
    /** Rand für das Mittel über 25 h (s). */
    static final long RAND = 13 * 3600;
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN), TAG_JAHR = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN);

    private Gezeitenbau() { }

    /**
     * Was ein Lauf ergab. {@code fehler}: der Kalender ging nicht (dann versucht es der Abruf am nächsten Tag noch einmal);
     * {@code warnungen}: einzelne Pegel gingen nicht (stehen im Protokoll, der Lauf zählt trotzdem).
     */
    public record Bilanz(int pegel, int angenommen, int abgelehnt, int uebersprungen, int scheitel, int monate, boolean kalender, String kalenderText, int fehler,
                         int warnungen, List<String> zeilen) {
        public String kurz() {
            return String.format(Locale.GERMAN, "%d Pegel: %d angenommen, %d abgelehnt, %d übersprungen; %d Scheitel neu; %d Monate Mittelwasser; Kalender: %s%s%s", pegel,
                    angenommen, abgelehnt, uebersprungen, scheitel, monate, kalenderText, fehler == 0 ? "" : "; " + fehler + " Fehler",
                    warnungen == 0 ? "" : "; " + warnungen + " Pegel mit Warnung");
        }
    }

    /** Eine gelesene Reihe auf festem Raster: t (Epoch-Sekunden) und y. */
    record Raster(double[] t, double[] y, int schritt) {
        boolean leer() { return t.length == 0; }
    }

    static Raster raster(Messarchiv ar, String tabelle, int station, long von, long bis, int schritt) throws SQLException {
        Messarchiv.Reihe r = ar.reihe(tabelle, station, W, von, bis);
        if (r.n() == 0) return new Raster(new double[0], new double[0], schritt);
        long a = r.t()[0], b = r.t()[r.n() - 1];
        float[] x = Messarchiv.raster(r, a, b, schritt);
        double[] t = new double[x.length], y = new double[x.length];
        for (int i = 0; i < x.length; i++) {
            t[i] = a + (double) i * schritt;
            y[i] = x[i];
        }
        return new Raster(t, y, schritt);
    }

    /** Die geltende Vorhersage einer Station: MESSUNG, sonst die 30 Tage der Übernahme; null ohne. */
    public static Vorhersage geltend(Messarchiv ar, int station) throws SQLException {
        Vorhersage v = ar.vorhersage(station, HERKUNFT);
        return v != null ? v : ar.vorhersage(station, Uebernahme.HERKUNFT_PEGEL);
    }

    /** Herkunft der geltenden Konstanten (MESSUNG oder PEGEL_30T) oder null. */
    static String geltendeHerkunft(Messarchiv ar, int station) throws SQLException {
        if (ar.vorhersage(station, HERKUNFT) != null) return HERKUNFT;
        return ar.vorhersage(station, Uebernahme.HERKUNFT_PEGEL) != null ? Uebernahme.HERKUNFT_PEGEL : null;
    }

    /** Ergebnis der Prüfung eines Kandidaten an einem Pegel. */
    record Pruefung(Gezeitenanalyse.Analyse kandidat, long beginn, long[] pz, double[] neu, double[] alt) { }

    /** Kandidat über [beginn, pz0) aus den 10-Minuten-Mitteln, geprüft an den Minutenwerten der Prüfzeit gegen {@code alt}. */
    static Pruefung pruefen(Messarchiv ar, int station, long beginn, long[] pz, Vorhersage alt, double grenze) throws SQLException {
        Raster m = raster(ar, "MITTEL", station, beginn, pz[0] - 1, SCHRITT);
        if (m.leer()) return null;
        Gezeitenanalyse.Analyse k = Gezeitenanalyse.robust(m.t, m.y, m.schritt, grenze, Gezeitenanalyse.kandidaten((m.t[m.t.length - 1] - m.t[0]) / 3600));
        Raster p = minuten(ar, station, pz[0] - RAND, pz[1]);
        if (p.leer()) return new Pruefung(k, beginn, pz, null, null);
        Vorhersage vk = k.vorhersage();
        double[] s = Gezeitenanalyse.stau(vk, p.t, p.y, p.schritt);
        double[] neu = Gezeitenanalyse.vergleich(vk, p.t, p.y, s, p.schritt, grenze, pz[0], pz[1]);
        double[] a = alt == null ? null : Gezeitenanalyse.vergleich(alt, p.t, p.y, s, p.schritt, grenze, pz[0], pz[1]);
        return new Pruefung(k, beginn, pz, neu, a);
    }

    /** Die Minutenwerte (HH_ROH) in [von, bis], sonst (älter als die Aufbewahrung) die 10-Minuten-Mittel. */
    static Raster minuten(Messarchiv ar, int station, long von, long bis) throws SQLException {
        Raster r = raster(ar, "ROH", station, von, bis, MINUTE);
        return r.leer() ? raster(ar, "MITTEL", station, von, bis, SCHRITT) : r;
    }

    static String tag(long t, ZoneId z) { return TAG.format(Instant.ofEpochSecond(t).atZone(z)); }

    static String tagJahr(long t, ZoneId z) { return TAG_JAHR.format(Instant.ofEpochSecond(t).atZone(z)); }

    static String vergleichText(double[] v) {
        return v == null || v[0] == 0 ? "–" : String.format(Locale.GERMAN, "%d Hoch- und Niedrigwasser, Höhe %.3f m, Zeit %.1f min", (int) v[0], v[4], v[2]);
    }

    // ------------------------------------------------------------------ der Lauf

    /**
     * Rechnet alle Pegel, den Kalender und das Mittelwasser. {@code kopien}: weitere Orte für die Tafel (das Abbild).
     * {@code wiederholt}: true, wenn derselbe Monat schon einmal lief (dann wird nichts Neues erwartet).
     */
    public static Bilanz rechnen(Messarchiv ar, Messauftrag a, Gezeitenauftrag ga, List<File> kopien, ZoneId zone, Consumer<String> log) throws Exception {
        long jetzt = Instant.now().getEpochSecond();
        List<String> zeilen = new ArrayList<>();
        int n = 0, an = 0, ab = 0, ue = 0, sch = 0, mo = 0, fehler = 0, warn = 0;
        Map<String, Messarchiv.Station> pegel = ar.stationen("PEGEL");
        for (Messarchiv.Station st : pegel.values()) {
            if (!st.aktiv()) continue;
            try {
                Long beginn = ar.erste("MITTEL", st.id(), W), ende = ar.letzte("MITTEL", st.id(), W);
                if (beginn == null || ende == null) continue;
                n++;
                String herkunft = geltendeHerkunft(ar, st.id());
                long[] gz = herkunft == null ? null : ar.gezeitZeitraum(st.id(), herkunft);
                long geltendBis = gz == null ? Long.MIN_VALUE : gz[1];
                long[] pz = Gezeitenanalyse.pruefzeit(beginn, ende, geltendBis);
                if (pz == null) {
                    ue++;
                    zeilen.add(st.schluessel() + ": übersprungen – Prüfzeit unter " + (int) Gezeitenanalyse.PRUEF_MIN_TAGE + " Tagen");
                    continue;
                }
                if (HERKUNFT.equals(herkunft) && pz[0] - geltendBis < 86_400) {
                    ue++;
                    zeilen.add(st.schluessel() + ": übersprungen – keine neuen Tage für die Analyse (geltend bis " + tagJahr(geltendBis, zone) + ")");
                    continue;
                }
                Vorhersage alt = herkunft == null ? null : ar.vorhersage(st.id(), herkunft);
                Pruefung p = pruefen(ar, st.id(), beginn, pz, alt, ga.stau());
                if (p == null) continue;
                boolean ja = Gezeitenanalyse.besser(p.neu, p.alt);
                String grund = String.format(Locale.GERMAN, "Kandidat %d Tiden aus %.1f Tagen (%s–%s, %.1f ohne Windstau); Prüfzeit %s–%s: %s; geltend (%s): %s → %s",
                        p.kandidat.analysiert(), (pz[0] - beginn) / 86_400.0, tag(beginn, zone), tag(pz[0], zone), p.kandidat.tage(), tag(pz[0], zone), tag(pz[1], zone),
                        vergleichText(p.neu), herkunft == null ? "keine" : herkunft, vergleichText(p.alt), ja ? "angenommen" : "abgelehnt");
                long id = ar.analyse(st.id(), PEGEL, jetzt, beginn, pz[0], p.kandidat, pz, p.neu, p.alt, herkunft, 1.0, ja, grund, p.kandidat.vorhersage().konstanten,
                        p.kandidat.abgeleitet());
                zeilen.add(st.schluessel() + ": " + grund);
                if (ja) {
                    an++;
                    Vorhersage v = p.kandidat.vorhersage();
                    ar.gezeit(st.id(), HERKUNFT, v.mittel, v.konstanten, Instant.ofEpochSecond(beginn), Instant.ofEpochSecond(pz[0]));
                    sch += ar.scheitelGezeit(st.id(), Sammler.MESSUNG, v, id);
                } else ab++;
            } catch (SQLException | RuntimeException x) {
                warn++;
                zeilen.add(st.schluessel() + ": WARNUNG " + x.getClass().getSimpleName() + ": " + x.getMessage());
            }
        }
        // ---- der Kalender
        String kt;
        boolean kal = false;
        try {
            Kalender k = kalender(ar, a, ga, kopien, zone, jetzt, pegel, zeilen);
            kt = k.text;
            kal = k.angenommen;
        } catch (IOException | SQLException | RuntimeException x) {
            fehler++;
            kt = "FEHLER " + x.getClass().getSimpleName() + ": " + x.getMessage();
            zeilen.add("Kalender: " + kt);
        }
        // ---- Mittelwasser je Monat ohne Windstau
        for (Messarchiv.Station st : pegel.values()) {
            if (!st.aktiv()) continue;
            try {
                mo += mittelwasser(ar, st.id(), ga.stau());
            } catch (SQLException | RuntimeException x) {
                warn++;
                zeilen.add(st.schluessel() + ": WARNUNG Mittelwasser " + x.getClass().getSimpleName() + ": " + x.getMessage());
            }
        }
        zeilen.forEach(log);
        return new Bilanz(n, an, ab, ue, sch, mo, kal, kt, fehler, warn, zeilen);
    }

    /** Das Mittelwasser je Monat einer Station mit den geltenden Konstanten; gibt die Zahl der Monate. */
    static int mittelwasser(Messarchiv ar, int station, double grenze) throws SQLException {
        Vorhersage v = geltend(ar, station);
        Long beginn = ar.erste("MITTEL", station, W), ende = ar.letzte("MITTEL", station, W);
        if (v == null || beginn == null || ende == null) return 0;
        Raster r = raster(ar, "MITTEL", station, beginn, ende, SCHRITT);
        Long analyse = ar.geltendeAnalyse(station, PEGEL);
        int n = 0;
        for (Gezeitenanalyse.Monat m : Gezeitenanalyse.monatsmittel(r.t, r.y, r.schritt, v, grenze)) {
            ar.mittelwasser(station, m.beginn(), m.mittel(), m.mittelAlle(), m.gesamt(), m.genutzt(), analyse);
            n++;
        }
        return n;
    }

    /**
     * Seit DB5 (DB4b): das Mittelwasser ohne Windstau einer Station in [von, bis) mit den geltenden Konstanten: {Mittel,
     * Mittel aller Werte, genutzt, gesamt}; NaN ohne Konstanten oder Werte.
     */
    public static double[] mittelOhneStau(Messarchiv ar, int station, long von, long bis, double grenze) throws SQLException {
        Vorhersage v = geltend(ar, station);
        if (v == null) return new double[] {Double.NaN, Double.NaN, 0, 0};
        Raster r = raster(ar, "MITTEL", station, von - RAND, bis + RAND, SCHRITT);
        if (r.leer()) return new double[] {Double.NaN, Double.NaN, 0, 0};
        return Gezeitenanalyse.mittelOhneStau(r.t, r.y, r.schritt, v, grenze, von, bis);
    }

    // ------------------------------------------------------------------ der Kalender

    record Kalender(boolean angenommen, String text) { }

    /** Eine Variante der Tafel am Bezug: Konstanten, Z₀, Faktor (1: ohne), mit Faktor? */
    record Variante(Vorhersage v, double faktor, boolean mit, double hubAnalyse, double hubKennwerte) { }

    static final Pattern BIS = Pattern.compile("bis (\\d{4}-\\d\\d-\\d\\dT[0-9:]+Z)");

    /** Das Ende der Analyse der geltenden Tafel: herkunft.bis, sonst aus dem Text des Bezugs (Z1). */
    static long tafelBis(Gezeitentafel g) {
        String b = g.kopf.get("herkunft.bis");
        if (b != null) return Instant.parse(b.strip()).getEpochSecond();
        Matcher m = BIS.matcher(g.kopf.getOrDefault("bezug", ""));
        return m.find() ? Instant.parse(m.group(1)).getEpochSecond() : Long.MIN_VALUE;
    }

    static double[] zahlen(String s) {
        if (s == null || s.isBlank()) return null;
        String[] x = s.strip().split("\\s+");
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = Double.parseDouble(x[i]);
        return r;
    }

    static final Pattern UEBERTRAG = Pattern.compile("Verhältnis ([0-9.]+), Verspätung ([+-]?[0-9.]+) min");

    /** Die Übertragung je Art aus der Tafel: wert.uebertrag (genau), sonst aus den Zeilen uebertrag.1–3. */
    static double[][] uebertrag(Gezeitentafel g) {
        double[] w = zahlen(g.kopf.get("wert.uebertrag"));
        if (w != null && w.length >= 6) return new double[][] {{w[0], w[1]}, {w[2], w[3]}, {w[4], w[5]}};
        double[][] u = new double[3][];
        for (int i = 0; i < 3; i++) {
            Matcher m = UEBERTRAG.matcher(g.kopf.getOrDefault("uebertrag." + (i + 1), ""));
            if (!m.find()) throw new IllegalStateException("Kalender: uebertrag." + (i + 1) + " fehlt in " + g.datei.getName() + " – einmal Ausgabe --gezeiten");
            u[i] = new double[] {Double.parseDouble(m.group(1)), Double.parseDouble(m.group(2)) * 60};
        }
        return u;
    }

    static Kalender kalender(Messarchiv ar, Messauftrag a, Gezeitenauftrag ga, List<File> kopien, ZoneId zone, long jetzt, Map<String, Messarchiv.Station> pegel,
                             List<String> zeilen) throws Exception {
        if (a.gezeiten() == null || !a.gezeiten().isFile()) return new Kalender(false, "keine Tafel");
        Gezeitentafel g = Gezeitentafel.lies(a.gezeiten());
        Messarchiv.Station sb = pegel.get(ga.bezug()), sw = ga.pruef() == null ? null : pegel.get(ga.pruef());
        Vorhersage alt = g.bezugVorhersage();
        if (sb == null || alt == null) return new Kalender(false, "Bezug " + ga.bezug() + " fehlt");
        Long beginn = ar.erste("MITTEL", sb.id(), W), ende = ar.letzte("MITTEL", sb.id(), W);
        if (beginn == null || ende == null) return new Kalender(false, "keine Messung am Bezug");
        long bis = tafelBis(g);
        long[] pz = Gezeitenanalyse.pruefzeit(beginn, ende, bis);
        if (pz == null) {
            zeilen.add("Kalender: übersprungen – Prüfzeit unter " + (int) Gezeitenanalyse.PRUEF_MIN_TAGE + " Tagen (Tafel bis " + tagJahr(bis, zone) + ")");
            return new Kalender(false, "übersprungen");
        }
        if (g.kopf.containsKey("herkunft.bis") && pz[0] - bis < 86_400) {
            zeilen.add("Kalender: übersprungen – keine neuen Tage für die Analyse (Tafel bis " + tagJahr(bis, zone) + ")");
            return new Kalender(false, "übersprungen, keine neuen Tage");
        }
        Pruefung p = pruefen(ar, sb.id(), beginn, pz, alt, ga.stau());
        if (p == null || p.neu == null) return new Kalender(false, "keine Prüfwerte");
        double[] kb = zahlen(g.kopf.get("wert.kennwerte.bezug"));
        double mthw = kb != null && kb.length >= 4 ? kb[2] : Double.NaN, mtnw = kb != null && kb.length >= 4 ? kb[3] : Double.NaN;
        Gezeitenanalyse.Kalibrierung kk = Gezeitenanalyse.kalibriert(p.kandidat.vorhersage().konstanten, p.kandidat.ergebnis().mittel(), mthw, mtnw);
        Variante ohne = new Variante(p.kandidat.vorhersage(), kk.faktor(), false, kk.hubAnalyse(), kk.hubKennwerte()),
                mit = new Variante(kk.vorhersage(), kk.faktor(), true, kk.hubAnalyse(), kk.hubKennwerte());
        // dieselben Minutenwerte und derselbe Windstau für beide Varianten
        Raster m = minuten(ar, sb.id(), pz[0] - RAND, pz[1]);
        double[] s = Gezeitenanalyse.stau(ohne.v, m.t, m.y, m.schritt);
        double[] hOhne = p.neu, hMit = Gezeitenanalyse.vergleich(mit.v, m.t, m.y, s, m.schritt, ga.stau(), pz[0], pz[1]), hAlt = p.alt;
        boolean okOhne = Gezeitenanalyse.besser(hOhne, hAlt), okMit = Double.isNaN(mthw) ? false : Gezeitenanalyse.besser(hMit, hAlt);
        Variante w = null;
        double[] hw = null;
        if (okOhne && (!okMit || hOhne[4] <= hMit[4])) {
            w = ohne;
            hw = hOhne;
        } else if (okMit) {
            w = mit;
            hw = hMit;
        }
        String grund = String.format(Locale.GERMAN, "Kandidat %d Tiden aus %.1f Tagen (%s–%s, %.1f ohne Windstau); Prüfzeit %s–%s: ohne Faktor %s; mit Faktor %.4f %s; Tafel %s → %s",
                p.kandidat.analysiert(), (pz[0] - beginn) / 86_400.0, tag(beginn, zone), tag(pz[0], zone), p.kandidat.tage(), tag(pz[0], zone), tag(pz[1], zone), vergleichText(hOhne), kk.faktor(),
                vergleichText(hMit), vergleichText(hAlt), w == null ? "Tafel bleibt" : "angenommen " + (w.mit ? "mit" : "ohne") + " Faktor");
        Variante gew = w == null ? ohne : w;
        ar.analyse(sb.id(), KALENDER, jetzt, beginn, pz[0], p.kandidat, pz, w == null ? hOhne : hw, hAlt, "TAFEL", gew.mit ? gew.faktor : 1.0, w != null, grund,
                gew.v.konstanten, p.kandidat.abgeleitet());
        zeilen.add("Kalender: " + grund);
        if (w == null) return new Kalender(false, "Tafel bleibt");
        // ---- der zweite Pegel für den Windstau (Wittdün): dieselbe Zeit, dieselbe Variante
        Variante w2 = null;
        if (sw != null) {
            Long b2 = ar.erste("MITTEL", sw.id(), W);
            Raster r2 = b2 == null ? null : raster(ar, "MITTEL", sw.id(), b2, pz[0] - 1, SCHRITT);
            if (r2 != null && !r2.leer()) {
                Gezeitenanalyse.Analyse k2 = Gezeitenanalyse.robust(r2.t, r2.y, r2.schritt, ga.stau(), Gezeitenanalyse.kandidaten((r2.t[r2.t.length - 1] - r2.t[0]) / 3600));
                double[] kw = zahlen(g.kopf.get("wert.kennwerte.pruef"));
                double mh = kw != null && kw.length >= 4 ? kw[2] : Double.NaN, mn = kw != null && kw.length >= 4 ? kw[3] : Double.NaN;
                Gezeitenanalyse.Kalibrierung k2k = Gezeitenanalyse.kalibriert(k2.vorhersage().konstanten, k2.ergebnis().mittel(), mh, mn);
                w2 = w.mit && !Double.isNaN(mh) ? new Variante(k2k.vorhersage(), k2k.faktor(), true, k2k.hubAnalyse(), k2k.hubKennwerte())
                        : new Variante(k2.vorhersage(), k2k.faktor(), false, k2k.hubAnalyse(), k2k.hubKennwerte());
            }
        }
        schreibeTafel(ar, a, ga, g, kopien, zone, sb, sw, w, w2, p, hw, hAlt, beginn, pz, jetzt);
        return new Kalender(true, "angenommen " + (w.mit ? "mit" : "ohne") + " Faktor");
    }

    /** Ein Pegel aus den Minutenwerten im Archiv (für den Fehler des Scheitels mit gemessenem Windstau). */
    static Pegel pegelAusArchiv(Messarchiv ar, Messarchiv.Station st, long von, long bis) throws SQLException {
        Raster r = raster(ar, "ROH", st.id(), von, bis, MINUTE);
        if (r.leer()) return null;
        float[] w = new float[r.y.length];
        for (int i = 0; i < w.length; i++) w[i] = (float) r.y[i];
        return new Pegel(st.schluessel(), st.kennung(), "", Double.NaN, Double.NaN, st.hoehe(), Instant.ofEpochSecond(Math.round(r.t[0])), MINUTE, w, 0, 0, Map.of(), null);
    }

    static String werte(double... v) {
        StringBuilder s = new StringBuilder();
        for (double x : v) s.append(s.length() > 0 ? " " : "").append(String.format(Locale.ROOT, "%.4f", x));
        return s.toString();
    }

    static String datum(long t) { return Instant.ofEpochSecond(t).toString(); }

    /** Schreibt die neue Tafel (Kopf neu, Übertragung, Marken und Modelle wie bisher) und ihre Kopien. */
    static void schreibeTafel(Messarchiv ar, Messauftrag a, Gezeitenauftrag ga, Gezeitentafel g, List<File> kopien, ZoneId zone, Messarchiv.Station sb,
                              Messarchiv.Station sw, Variante w, Variante w2, Pruefung p, double[] hw, double[] hAlt, long beginn, long[] pz, long jetzt) throws Exception {
        long t0 = System.nanoTime();
        double[][] u = uebertrag(g);
        Vorhersage vb = w.v;
        double[] uz = zahlen(g.kopf.get("uebertrag.z0"));
        double dz = uz != null ? uz[0] : g.mittel - g.bezugZ0;                          // Z₀ am Ort minus Z₀ am Bezug (Modell, Z1)
        List<Vorhersage.Konstante> ko = Gezeitenanalyse.uebertrage(vb.konstanten, u);
        Vorhersage vo = new Vorhersage(vb.mittel + dz, ko);
        Map<String, String> kopf = new LinkedHashMap<>();
        Gezeitenanalyse.Analyse an = p.kandidat;
        double[] kb = zahlen(g.kopf.get("wert.kennwerte.bezug"));
        double mthw = kb != null && kb.length >= 4 ? kb[2] : Double.NaN, mtnw = kb != null && kb.length >= 4 ? kb[3] : Double.NaN;
        // ---- Herkunft (seit DB5)
        kopf.put("herkunft", String.format(Locale.GERMAN, "aus %.0f Tagen Messung am Pegel %s (%s–%s), %s Faktor auf die Kennwerte; geprüft %s–%s an %d Hoch- und "
                        + "Niedrigwassern: Höhe ± %.2f m, Zeit ± %.0f min (vorher ± %.2f m, ± %.0f min)", (pz[0] - beginn) / 86_400.0, ga.bezugName(), tag(beginn, zone),
                tagJahr(pz[0], zone), w.mit ? "mit" : "ohne", tag(pz[0], zone), tagJahr(pz[1], zone), (int) hw[0], hw[4], hw[2], hAlt == null ? Double.NaN : hAlt[4],
                hAlt == null ? Double.NaN : hAlt[2]));
        kopf.put("herkunft.stand", datum(jetzt));
        kopf.put("herkunft.beginn", datum(beginn));
        kopf.put("herkunft.bis", datum(pz[0]));
        kopf.put("herkunft.faktor", w.mit ? "mit" : "ohne");
        kopf.put("herkunft.tiden", an.analysiert() + " " + an.abgeleitet().size());
        kopf.put("herkunft.pruef", String.format(Locale.ROOT, "%s; %s; %d; %.4f; %.2f; %.4f; %.2f", datum(pz[0]), datum(pz[1]), (int) hw[0], hw[4], hw[2],
                hAlt == null ? Double.NaN : hAlt[4], hAlt == null ? Double.NaN : hAlt[2]));
        // ---- Bezug
        kopf.put("bezug", String.format(Locale.ROOT, "%s (%s), Messarchiv %s %s bis %s, %d von %d Werten (10 min) ohne Windstau über %.2f m; Rest %.3f m", ga.bezugName(),
                g.bezugNummer, a.schema(), datum(beginn), datum(pz[0]), an.genutzt(), an.gesamt(), ga.stau(), an.ergebnis().restRms()));
        kopf.put("bezug.mittel", w.mit ? String.format(Locale.ROOT, "Analyse %.3f m; aus MThw %.3f und MTnw %.3f (Kennwerte) %.3f m", an.ergebnis().mittel(), mthw, mtnw, vb.mittel)
                : String.format(Locale.ROOT, "Analyse %.3f m (ohne Faktor)", an.ergebnis().mittel()));
        kopf.put("bezug.hub", String.format(Locale.ROOT, "Analyse %.3f m, Kennwerte %.3f m: Faktor %.4f (%s)", w.hubAnalyse, w.hubKennwerte, w.faktor,
                w.mit ? "angewandt" : "nicht angewandt"));
        kopf.put("bezug.abgeleitet", String.join(", ", an.abgeleitet()));
        kopf.put("wert.analyse.bezug", String.format(Locale.ROOT, "%d %d %.4f %d", an.genutzt(), an.gesamt(), an.ergebnis().restRms(), an.schritt()));
        kopf.put("wert.kalibrierung", String.format(Locale.ROOT, "%.5f %.4f %.4f", w.faktor, w.hubAnalyse, w.hubKennwerte));
        // ---- Prüfung
        int pn = 0;
        double[] kh = Gezeitenanalyse.kennwerte(vb, 2027);
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "kennwerte %s: MThw %.3f gegen %.3f, MTnw %.3f gegen %.3f, MThb %.3f gegen %.3f m (Vorhersage 2027 gegen Kennwerte%s)",
                ga.bezug(), kh[0], mthw, kh[1], mtnw, kh[0] - kh[1], mthw - mtnw, w.mit ? "" : "; ohne Faktor zur Auskunft"));
        kopf.put("wert.kennwerte.bezug", String.format(Locale.ROOT, "%.4f %.4f %.4f %.4f", kh[0], kh[1], mthw, mtnw));
        Raster alle = minuten(ar, sb.id(), beginn, pz[1]);
        double[] sa = Gezeitenanalyse.stau(vb, alle.t, alle.y, alle.schritt);
        double[] hb = Gezeitenanalyse.vergleich(vb, alle.t, alle.y, sa, alle.schritt, ga.stau(), Math.round(alle.t[0]), pz[1]);
        kopf.put("wert.hindcast.bezug", werte(hb));
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "hindcast %s: %d Hoch- und Niedrigwasser ohne Windstau; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m "
                + "(alle Minutenwerte im Archiv, mit der Prüfzeit)", ga.bezug(), (int) hb[0], hb[1], hb[2], hb[3], hb[4]));
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "pruefzeit %s %s bis %s: Kandidat %d; Zeit %+.1f min, |%.1f| min; Höhe %+.3f m, |%.3f| m; Tafel davor |%.1f| min, |%.3f| m",
                ga.bezug(), datum(pz[0]), datum(pz[1]), (int) hw[0], hw[1], hw[2], hw[3], hw[4], hAlt == null ? Double.NaN : hAlt[2], hAlt == null ? Double.NaN : hAlt[4]));
        // aus der Grundrechnung mit dem Modell (Z1): Übertragung und ihre Prüfung am zweiten Pegel
        for (String k : new String[] {"wert.uebertrag.pruef", "wert.kennwerte.pruef", "pruef.hub." + ga.pruef(), "wert.eigen.pruef", "pruef.uebertrag." + ga.pruef(), "wert.m2.pruef"})
            if (g.kopf.containsKey(k)) kopf.put(k, g.kopf.get(k));
        for (int i = 1; g.kopf.containsKey("pruef." + i); i++) {
            String z = g.kopf.get("pruef." + i);
            if (z.startsWith("uebertrag ") || z.startsWith("eigen ") || (ga.pruef() != null && z.startsWith("kennwerte " + ga.pruef())))
                kopf.put("pruef." + (++pn), z + (z.endsWith("(Grundrechnung)") ? "" : " (Grundrechnung)"));
        }
        double[] ko2 = Gezeitenanalyse.kennwerte(vo, 2027);
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "ort %s: MThw %.3f, MTnw %.3f, MThb %.3f m (Vorhersage 2027); Alter der Gezeit %.1f h", ga.ort(), ko2[0], ko2[1],
                ko2[0] - ko2[1], vo.alterStunden()));
        kopf.put("wert.ort", String.format(Locale.ROOT, "%.4f %.4f %.2f", ko2[0], ko2[1], vo.alterStunden()));
        double[] hm = Gezeitenanalyse.markenVergleich(vo, g.marken);
        kopf.put("wert.modell", werte(hm));
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "modell %s: %d Marken; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m (Modell mit Wind gegen Kalender)",
                ga.ort(), (int) hm[0], hm[1], hm[2], hm[3], hm[4]));
        // ---- Übertragung (aus der Grundrechnung, genau)
        for (int i = 1; i <= 3; i++) if (g.kopf.containsKey("uebertrag." + i)) kopf.put("uebertrag." + i, g.kopf.get("uebertrag." + i));
        if (g.kopf.containsKey("uebertrag.mittel")) kopf.put("uebertrag.mittel", g.kopf.get("uebertrag.mittel"));
        kopf.put("wert.uebertrag", werte(u[0][0], u[0][1], u[1][0], u[1][1], u[2][0], u[2][1]));
        kopf.put("uebertrag.z0", String.format(Locale.ROOT, "%.4f", dz));
        kopf.put("quelle.1", g.kopf.getOrDefault("quelle.1", "PEGELONLINE, Wasserstraßen- und Schifffahrtsverwaltung des Bundes (WSV), DL-DE Zero 2.0"));
        kopf.put("quelle.2", "Messarchiv " + a.schema() + " (Sammler --gezeiten, seit DB5): 10-Minuten-Mittel zur Analyse, Minutenwerte zur Prüfung");
        // ---- die gemessenen Pegel für den Windstau
        Map<String, List<Vorhersage.Konstante>> bt = new LinkedHashMap<>();
        if (g.kopf.containsKey("bezug.pegel")) kopf.put("bezug.pegel", g.kopf.get("bezug.pegel"));
        kopf.put("bezug.z0", String.format(Locale.ROOT, "%.4f", vb.mittel));
        bt.put("bezug", vb.konstanten);
        List<Pegel> stauPegel = new ArrayList<>();
        List<Vorhersage> stauV = new ArrayList<>();
        Pegel pb = pegelAusArchiv(ar, sb, beginn, pz[1]);
        if (pb != null) {
            stauPegel.add(pb);
            stauV.add(vb);
        }
        if (w2 != null && g.kopf.containsKey("bezug2.pegel")) {
            kopf.put("bezug2.pegel", g.kopf.get("bezug2.pegel"));
            kopf.put("bezug2.z0", String.format(Locale.ROOT, "%.4f", w2.v.mittel));
            kopf.put("bezug2.hub", String.format(Locale.ROOT, "Analyse %.3f m, Kennwerte %.3f m: Faktor %.4f (%s)", w2.hubAnalyse, w2.hubKennwerte, w2.faktor,
                    w2.mit ? "angewandt" : "nicht angewandt"));
            bt.put("bezug2", w2.v.konstanten);
            Pegel pw = pegelAusArchiv(ar, sw, beginn, pz[1]);
            if (pw != null) {
                stauPegel.add(pw);
                stauV.add(w2.v);
            }
        }
        if (!stauPegel.isEmpty()) {
            double[] vl = Gezeitenanalyse.STAU_VORLAUF;
            double[][] sf = Gezeitenanalyse.stauFehlerMittel(stauPegel, stauV, vl), se = Gezeitenanalyse.stauFehler(stauPegel.get(0), stauV.get(0), vl);
            kopf.put("wert.stau.vorlauf", werte(vl));
            kopf.put("wert.stau.fehler", werte(sf[0]));
            kopf.put("wert.stau.mittel", werte(sf[1]));
            kopf.put("wert.stau.ohne", String.format(Locale.ROOT, "%.4f %.0f", sf[2][0], sf[2][1]));
            kopf.put("wert.stau.fehler.bezug", werte(se[0]));
        }
        // ---- Verlauf: die angenommenen Stände, der älteste zuerst; davor die Grundrechnung (Z1)
        List<String> verlauf = new ArrayList<>();
        for (int i = 1; g.kopf.containsKey("verlauf." + i); i++) verlauf.add(g.kopf.get("verlauf." + i));
        if (verlauf.isEmpty()) {
            double[] az = zahlen(g.kopf.get("wert.analyse.bezug")), ka = zahlen(g.kopf.get("wert.kalibrierung"));
            long altBis = tafelBis(g);
            Matcher mv = Pattern.compile("PEGELONLINE (\\S+Z) bis").matcher(g.kopf.getOrDefault("bezug", ""));
            String altVon = mv.find() ? mv.group(1) : "–";
            verlauf.add(String.join("; ", g.kopf.getOrDefault("gerechnet", "–"), altVon, altBis == Long.MIN_VALUE ? "–" : datum(altBis),
                    az == null ? "–" : String.format(Locale.ROOT, "%.1f", az[1] / 1440), String.valueOf(g.bezugTiden.size()),
                    ka != null && Math.abs(ka[0] - 1) > 1e-6 ? "mit" : "ohne", "–", "–", "–", "–", "–", "Grundrechnung (Ausgabe --gezeiten, Z1)"));
        }
        verlauf.add(String.join("; ", datum(jetzt), datum(beginn), datum(pz[0]), String.format(Locale.ROOT, "%.1f", (pz[0] - beginn) / 86_400.0),
                String.valueOf(an.analysiert() + an.abgeleitet().size()), w.mit ? "mit" : "ohne", String.valueOf((int) hw[0]), String.format(Locale.ROOT, "%.4f", hw[4]),
                String.format(Locale.ROOT, "%.2f", hw[2]), hAlt == null ? "–" : String.format(Locale.ROOT, "%.4f", hAlt[4]),
                hAlt == null ? "–" : String.format(Locale.ROOT, "%.2f", hAlt[2]), "Sammler --gezeiten"));
        for (int i = 0; i < verlauf.size(); i++) kopf.put("verlauf." + (i + 1), verlauf.get(i));
        kopf.put("gerechnet", datum(jetzt));
        kopf.put("rechenzeit", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9));
        Gezeitentafel.schreibe(a.gezeiten(), g.ort, g.ost, g.nord, vo.mittel, ko, g.modelle, g.marken, kopf, bt);
        String inhalt = Files.readString(a.gezeiten().toPath(), StandardCharsets.UTF_8);
        for (File k : kopien) if (k.getParentFile() != null && k.getParentFile().isDirectory()) Files.writeString(k.toPath(), inhalt, StandardCharsets.UTF_8);
    }
}
