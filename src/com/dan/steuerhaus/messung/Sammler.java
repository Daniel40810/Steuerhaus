package com.dan.steuerhaus.messung;

import com.dan.core.pruef.Pegel;
import com.dan.core.pruef.Windstation;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Pegelonline;
import com.dan.steuerhaus.gezeiten.Vorhersage;
import com.dan.store.Db;
import com.dan.store.Einrichter;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Der Sammler des Messarchivs (seit DB1, Werkbuch HalligHooge 167). Ein Programm ohne Fenster, das die Aufgabenplanung
 * stündlich startet ({@code --abruf}); jeder Lauf holt, was seit dem letzten Wert dazugekommen ist (PEGELONLINE gibt 30
 * Tage rückwirkend her, darum schadet eine verpasste Stunde nicht), verdichtet, sucht Scheitel, schätzt den Ort ohne Pegel
 * und räumt nach der Wachstumsgrenze auf ({@link Aufbewahrung}). Nur der Sammler lässt die Datenbank wachsen, darum
 * räumt er bei jedem Lauf selbst auf – vor dem Einfügen und danach.
 * <pre>
 * java -cp … com.dan.steuerhaus.messung.Sammler --manifest src\szenen\Hooge.steuerhaus &lt;Auftrag&gt;
 *   --probe                  PEGELONLINE und DWD abrufen, nichts schreiben (ohne Datenbank)
 *   --verbinden              Verbindung prüfen; fehlt das Passwort, einmal fragen und in db.properties eintragen
 *   --einrichten             Schema über com.dan.store.Einrichter (db/sql, löscht nie)
 *   --uebernehmen --gebiet G Bestand aus dem Gebiet (Pegel, Wind, Orte, Ort), dann verdichten, Scheitel, Schätzung
 *   --abruf                  der stündliche Lauf
 *   --pflege [--nur-plan] [--grenze MB]   Aufbewahrung und Grenze
 *   --bericht                Füllstand, Fächer, Stationen, Abrufe, Hochwasser, Sturmfluten, Landunter
 *   --pruefen [--gebiet G]   Prüfsteine DB1
 *   --chronik                seit DB2: die Chronik der Hochwasser schreiben (sonst nach jedem Abruf, nur bei Änderung)
 *   --wind-nachladen         seit DB2: Lücken im Wind aus den historischen Dateien des DWD füllen
 *   --fach-weg TABELLE TAG   seit DB2: ein Fach (Partition) von Hand wegnehmen, etwa MITTEL 2009-01-01
 *   --guete                  seit DB3: Modell gegen Messung – die Datei des Modells (guete.modell, von Ausgabe --guete)
 *                            übernehmen, die Kennzahlen rechnen und guete.datei schreiben (nur bei Änderung)
 * </pre>
 * Ausgabe auf der Konsole und in {@code %USERPROFILE%\.steuerhaus\<Titel>\sammler.log} (höchstens 1 MB, dann eine Stufe
 * zurück). Ein zweiter Lauf zur selben Zeit wartet nicht, er endet mit Exit 3. Exit 0: in Ordnung, 1: Fehler, 2: Aufruf.
 */
public final class Sammler {
    public static final String MESSUNG = "MESSUNG", SCHAETZUNG = "SCHAETZUNG";
    static final String W = Uebernahme.W, FF = Uebernahme.FF, DD = Uebernahme.DD;

    private final Messauftrag a;
    private final Log log;
    /** Seit DB3: die Schlüssel guete.* (null: ohne Modell gegen Messung) und die Zone der App (Tag des HThw). */
    com.dan.steuerhaus.Guete.Auftrag guete;
    java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
    private final Scheitelsuche suche = new Scheitelsuche();
    private final Ausreisser ausreisser = new Ausreisser();

    Sammler(Messauftrag a, Log log) {
        this.a = a;
        this.log = log;
    }

    public static void main(String[] args) {
        System.exit(lauf(args));
    }

    // ------------------------------------------------------------------ Aufruf

    static int lauf(String[] args) {
        String manifest = null, gebiet = null, auftrag = null, fachTabelle = null, fachTag = null;
        boolean nurPlan = false;
        double grenze = Double.NaN;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = args[++i];
                case "--gebiet" -> gebiet = args[++i];
                case "--nur-plan" -> nurPlan = true;
                case "--grenze" -> grenze = Double.parseDouble(args[++i]);
                case "--probe", "--verbinden", "--einrichten", "--uebernehmen", "--abruf", "--pflege", "--bericht", "--pruefen", "--chronik", "--wind-nachladen", "--guete" ->
                        auftrag = args[i].substring(2);
                case "--fach-weg" -> {
                    auftrag = "fach-weg";
                    fachTabelle = args[++i];
                    fachTag = args[++i];
                }
                default -> {
                    System.err.println("Unbekannt: " + args[i]);
                    return 2;
                }
            }
        }
        if (manifest == null || auftrag == null) {
            System.err.println("Aufruf: Sammler --manifest <datei> --probe|--verbinden|--einrichten|--uebernehmen --gebiet <ordner>|--abruf|--pflege|--bericht|--pruefen"
                    + "|--chronik|--wind-nachladen|--fach-weg <TABELLE> <JJJJ-MM-TT>|--guete");
            return 2;
        }
        Messauftrag a;
        Manifest mf;
        try {
            mf = Manifest.lies(new File(manifest));
            a = Messauftrag.lies(mf);
        } catch (IOException e) {
            System.err.println("Manifest: " + e.getMessage());
            return 2;
        }
        if (a == null) {
            System.err.println("Im Manifest fehlt messung.db – die App hat kein Messarchiv.");
            return 2;
        }
        try (Log log = new Log(a.protokoll(), System.out)) {
            Sammler s = new Sammler(a, log);
            s.guete = com.dan.steuerhaus.Guete.Auftrag.lies(mf);                      // seit DB3
            s.zone = java.time.ZoneId.of(mf.text("app.zone", "Europe/Berlin"));
            log.zeile("Sammler " + auftrag + " · " + a.name() + " · " + a.db());
            try {
                return switch (auftrag) {
                    case "probe" -> s.probe();
                    case "verbinden" -> s.verbinden();
                    case "einrichten" -> s.einrichten();
                    case "uebernehmen" -> {
                        if (gebiet == null) yield fehlt("--gebiet");
                        File g = new File(gebiet);
                        yield s.mitSperre(() -> s.uebernehmen(g));
                    }
                    case "abruf" -> s.mitSperre(s::abruf);
                    case "pflege" -> {
                        boolean np = nurPlan;
                        yield s.mitSperre(() -> s.pflegeAllein(np), grenze);
                    }
                    case "bericht" -> s.bericht();
                    case "pruefen" -> {
                        File g = gebiet == null ? null : new File(gebiet);
                        yield s.pruefen(g);
                    }
                    case "chronik" -> s.mitSperre(s::chronikAllein);
                    case "wind-nachladen" -> s.mitSperre(s::windNachladen);
                    case "guete" -> s.mitSperre(s::guete);
                    case "fach-weg" -> {
                        String t = fachTabelle, d = fachTag;
                        yield s.mitSperre(() -> s.fachWeg(t, d));
                    }
                    default -> 2;
                };
            } catch (Exception e) {
                log.zeile("FEHLER " + e.getClass().getSimpleName() + ": " + e.getMessage());
                e.printStackTrace(log.aus());
                return 1;
            }
        } catch (IOException e) {
            System.err.println("Protokoll: " + e.getMessage());
            return 1;
        }
    }

    static int fehlt(String was) {
        System.err.println("Es fehlt " + was);
        return 2;
    }

    interface Schritt { int lauf() throws Exception; }

    private int mitSperre(Schritt s) throws Exception { return mitSperre(s, Double.NaN); }

    private double grenzeAufruf = Double.NaN;

    /** Läuft nur, wenn kein anderer Sammler läuft (Sperre neben dem Protokoll); sonst Exit 3. */
    private int mitSperre(Schritt s, double grenze) throws Exception {
        grenzeAufruf = grenze;
        File f = new File(a.protokoll().getParentFile(), "sammler.lock");
        Files.createDirectories(f.getParentFile().toPath());
        try (FileChannel ch = FileChannel.open(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) {
                log.zeile("Ein anderer Sammler läuft gerade – nichts zu tun.");
                return 3;
            }
            try {
                return s.lauf();
            } finally {
                l.release();
            }
        }
    }

    Connection verbinde(boolean fragen) throws IOException, SQLException {
        Db.antwortS = 600;
        Db.Konfig k = Db.konfig(a.db(), a.schema(), a.passwortVariable());
        if (k.hatPasswort()) return Db.oeffnen(k);
        java.io.Console con = System.console();
        if (!fragen || con == null)
            throw new IOException("kein Passwort für " + k.kurz() + ": password= in " + a.db() + ", die Umgebungsvariable " + a.passwortVariable()
                    + " oder einmal Sammler --verbinden in einer Konsole");
        char[] pw = con.readPassword("Passwort für %s: ", k.kurz());
        if (pw == null || pw.length == 0) throw new IOException("kein Passwort eingegeben");
        Db.Konfig mit = k.mitPasswort(pw);
        Connection c = Db.oeffnen(mit);
        Db.passwortSpeichern(k, pw);
        log.zeile("Passwort in " + a.db() + " eingetragen (die Datei steht in .gitignore).");
        return c;
    }

    // ------------------------------------------------------------------ Aufträge ohne Sperre

    int probe() throws IOException {
        int fehler = 0;
        Instant ab = Instant.now().minusSeconds(2 * 3600);
        String url = Abruf.pegelAdresse(a.pegelonline(), "9550021", ab);
        try {
            List<Pegelonline.Messwert> w = Pegelonline.deute(Abruf.holeText(url), -5.027);
            log.zeile(String.format(Locale.GERMAN, "PEGELONLINE Pellworm Anleger: %d Werte seit %s, zuletzt %s %.2f m NHN", w.size(), ab,
                    w.isEmpty() ? "–" : w.get(w.size() - 1).zeit(), w.isEmpty() ? Double.NaN : w.get(w.size() - 1).hoehe()));
            if (w.isEmpty()) fehler++;
        } catch (IOException e) {
            log.zeile("PEGELONLINE: " + e.getMessage());
            fehler++;
        }
        try {
            List<Abruf.Wind> w = Abruf.dwd(Abruf.hole(Abruf.dwdAdresse(a.dwd(), "02115"), "application/zip"));
            log.zeile(String.format(Locale.GERMAN, "DWD Helgoland (02115): %d Werte, zuletzt %s", w.size(),
                    w.isEmpty() ? "–" : Messarchiv.zeit(w.get(w.size() - 1).ende())));
            if (w.isEmpty()) fehler++;
        } catch (IOException e) {
            log.zeile("DWD: " + e.getMessage());
            fehler++;
        }
        log.zeile(fehler == 0 ? "Probe: beide Quellen erreicht" : "Probe: " + fehler + " Quelle(n) nicht erreicht");
        return fehler == 0 ? 0 : 1;
    }

    int verbinden() throws IOException, SQLException {
        if (!a.db().isFile()) {
            File ex = new File(a.db().getParentFile(), a.db().getName() + ".example");
            if (!ex.isFile()) throw new IOException(a.db() + " fehlt, ebenso die Vorlage " + ex.getName());
            Files.copy(ex.toPath(), a.db().toPath());
            log.zeile(a.db().getName() + " aus der Vorlage angelegt.");
        }
        try (Connection c = verbinde(true)) {
            String wer = Db.einWert(c, "select user || ' auf ' || sys_context('USERENV','CON_NAME') from dual");
            log.zeile("Verbunden: " + wer + " · Oracle " + c.getMetaData().getDatabaseMajorVersion());
            Einrichter e = einrichter();
            log.zeile("Stufe des Schemas: " + e.stufe(c) + " (0: noch keine Tabellen " + a.praefix() + "*)");
            new Messarchiv(c, a.praefix(), a.srid()).quoten().forEach(q -> log.zeile("Quote " + q));
        }
        return 0;
    }

    Einrichter einrichter() {
        String p = a.praefix();
        Einrichter e = new Einrichter();
        e.praefix = p;
        e.version = 4;                                                              // seit DB4 (3: DB3, 2: DB2)
        e.sichten = a.sichten().toArray(String[]::new);
        e.metadaten = new String[] {p + "STATION.LAGE", p + "ORT.LAGE"};
        e.srid = a.srid();
        e.raeumlich = new String[] {p + "STATION_LAGE_SX", p + "ORT_LAGE_SX"};
        e.startdaten = () -> List.of(new Einrichter.Soll("Quellen", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "QUELLE", 5));
        e.rechnet = () -> List.of(
                new Einrichter.Soll("Primärschlüssel ROH, MITTEL, SCHEITEL", "SELECT TO_CHAR(COUNT(*)) FROM USER_CONSTRAINTS WHERE CONSTRAINT_TYPE = 'P' AND TABLE_NAME IN ('"
                        + p + "ROH', '" + p + "MITTEL', '" + p + "SCHEITEL')", -1),
                new Einrichter.Soll("Fächer ROH und MITTEL", "SELECT TO_CHAR(COUNT(*)) FROM USER_PART_TABLES WHERE PARTITIONING_TYPE = 'RANGE' AND \"INTERVAL\" IS NOT NULL AND TABLE_NAME IN ('"
                        + p + "ROH', '" + p + "MITTEL')", -1),
                new Einrichter.Soll("Stationen in WGS84", "SELECT TO_CHAR(COUNT(CASE WHEN LAGE_WGS84 IS NOT NULL THEN 1 END)) FROM " + p + "STATION_V", -1),
                new Einrichter.Soll("Sturmfluten", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "STURMFLUT_V", -1),
                new Einrichter.Soll("Landunter", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "LANDUNTER_V", -1),
                new Einrichter.Soll("Läufe des Modells (seit DB3)", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "LAUF", -1),
                new Einrichter.Soll("Pegel mit HThw (seit DB3)", "SELECT TO_CHAR(COUNT(HTHW)) FROM " + p + "STATION", -1),
                new Einrichter.Soll("Werte Modell gegen Messung (seit DB3)", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "VERGLEICH_V", -1),
                new Einrichter.Soll("Paare der Scheitel (seit DB3)", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "SCHEITEL_PAAR_V", -1),
                new Einrichter.Soll("Wind Modell gegen Messung (seit DB4)", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "WIND_VERGLEICH_V", -1),
                new Einrichter.Soll("Tagesmittel der Pegel (seit DB4)", "SELECT TO_CHAR(COUNT(*)) FROM " + p + "TAGESMITTEL_V", -1));
        return e;
    }

    int einrichten() throws IOException, SQLException {
        try (Connection c = verbinde(false)) {
            Einrichter.Ergebnis r = einrichter().einrichten(c, a.sql(), (t, x) -> log.zeile(String.format(Locale.ROOT, "  %3.0f %% %s", 100 * x, t)));
            r.zeilen().forEach(z -> log.zeile(z));
            // seit DB3: Der Einrichter trägt neue Spalten erst nach den Skripten nach; ihre Kommentare (COMMENT ON COLUMN) liefen
            // dann ins Leere. Hat er Spalten nachgetragen und meldet Fehler, einmal noch – jetzt gibt es die Spalten.
            if (!r.ok() && r.zeilen().stream().anyMatch(z -> z.startsWith("ok ") && z.endsWith(" nachgetragen"))) {
                log.zeile("Spalten nachgetragen: noch einmal einrichten, damit auch ihre Kommentare greifen");
                r = einrichter().einrichten(c, a.sql(), (t, x) -> { });
                r.zeilen().forEach(z -> log.zeile(z));
            }
            log.zeile("Einrichten: " + r.kurz());
            return r.ok() ? 0 : 1;
        }
    }

    // ------------------------------------------------------------------ Übernahme

    int uebernehmen(File gebiet) throws Exception {
        Instant t0 = Instant.now();
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Uebernahme.Ergebnis e = Uebernahme.uebernehmen(ar, a, gebiet, log::zeile);
            int v = verdichtenAlle(ar), s = scheitelAlle(ar), z = schaetzen(ar);
            String m = String.format(Locale.GERMAN, "Bestand: %d Pegel mit %d Minutenwerten, %d Windstationen mit %d Mitteln, %d Orte%s; %d neue Mittel, %d Scheitel, %d Schätzungen",
                    e.pegel(), e.roh(), e.wind(), e.mittel(), e.orte(), e.ort() ? ", Ort " + a.ortSchluessel() : "", v, s, z);
            ar.abruf("UEBERNAHME", t0, sekunden(t0), e.roh() + e.mittel(), 0, 0, m);
            log.zeile(m);
            chronik(ar);
            log.zeile(String.format(Locale.GERMAN, "Platz: %.1f MB", ar.platzMb()));
        }
        return 0;
    }

    static double sekunden(Instant t0) { return (Instant.now().toEpochMilli() - t0.toEpochMilli()) / 1000.0; }

    // ------------------------------------------------------------------ der stündliche Abruf

    int abruf() throws Exception {
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Instant t0 = Instant.now();
            Aufbewahrung.Plan vorher = pflege(ar, false, Double.NaN);
            if (vorher.ueber()) {
                String m = "Über der Grenze von " + a.grenzeMb() + " MB, auch nach dem Aufräumen – nichts eingefügt: " + String.join("; ", vorher.gruende());
                ar.abruf("PFLEGE", t0, sekunden(t0), 0, 0, 1, m);
                log.zeile("FEHLER " + m);
                return 1;
            }
            int fehler = pegel(ar);
            fehler += dwd(ar, false);
            int v = verdichtenAlle(ar), s = scheitelAlle(ar), z = schaetzen(ar);
            Aufbewahrung.Plan nachher = pflege(ar, false, Double.NaN);
            try {
                chronik(ar);
            } catch (IOException | SQLException x) {
                fehler++;
                log.zeile("FEHLER Chronik: " + x.getMessage());
            }
            double platz = ar.platzMb();
            String fuell = String.format(Locale.GERMAN, "%.1f MB von %.0f MB (%.0f %%)", platz, a.grenzeMb(), 100 * platz / a.grenzeMb());
            if (platz > a.warnungProzent() / 100 * a.grenzeMb()) log.zeile("WARNUNG Füllstand " + fuell);
            log.zeile(String.format(Locale.GERMAN, "Abruf fertig in %.1f s: %d neue Mittel, %d Scheitel, %d Schätzungen, %d Fächer weg; Platz %s%s", sekunden(t0), v, s, z,
                    vorher.weg().size() + nachher.weg().size(), fuell, fehler == 0 ? "" : "; " + fehler + " Fehler"));
            return fehler == 0 ? 0 : 1;
        }
    }

    /** Je aktivem Pegel: was seit dem jüngsten Wert dazugekommen ist. Gibt die Zahl der Fehler. */
    int pegel(Messarchiv ar) throws SQLException {
        Instant t0 = Instant.now();
        long jetzt = t0.getEpochSecond(), fruehest = jetzt - 30L * 86400 + 3600;
        long zeilen = 0, verworfen = 0;
        int fehler = 0;
        List<String> meldung = new ArrayList<>();
        for (Messarchiv.Station st : ar.stationen("PEGEL").values()) {
            if (!st.aktiv() || st.kennung() == null || Double.isNaN(st.hoehe())) continue;
            try {
                Long max = ar.letzte("ROH", st.id(), W);
                long ab = max == null ? fruehest : Math.max(fruehest, max - 1800);
                List<Pegelonline.Messwert> mw = Pegelonline.deute(Abruf.holeText(Abruf.pegelAdresse(a.pegelonline(), st.kennung(), Instant.ofEpochSecond(ab))), st.hoehe());
                if (mw.isEmpty()) {
                    meldung.add(st.schluessel() + ": keine Werte");
                    continue;
                }
                long t1 = Math.floorDiv(mw.get(0).zeit().getEpochSecond(), 60) * 60, t2 = Math.floorDiv(mw.get(mw.size() - 1).zeit().getEpochSecond(), 60) * 60;
                int n = (int) ((t2 - t1) / 60) + 1;
                float[] x = new float[n];
                java.util.Arrays.fill(x, Float.NaN);
                for (Pegelonline.Messwert w : mw) {
                    long s = w.zeit().getEpochSecond();
                    if (s % 60 != 0) continue;
                    x[(int) ((s - t1) / 60)] = (float) w.hoehe();
                }
                Ausreisser.Ergebnis af = ausreisser.filtern(x);
                long[] t = new long[n];
                for (int i = 0; i < n; i++) t[i] = t1 + 60L * i;
                long bis = t2 - 900;                                                     // die letzten 15 min beim nächsten Mal, mit vollem Fenster
                int neu = ar.einfuegen("ROH", st.id(), W, t, x, max, bis);
                zeilen += neu;
                verworfen += af.zusammen();
                if (max != null) {
                    long erster = Long.MAX_VALUE;
                    for (int i = 0; i < n; i++) if (t[i] > max && !Float.isNaN(x[i])) { erster = t[i]; break; }
                    if (erster != Long.MAX_VALUE && erster - max > 3600) meldung.add(String.format(Locale.GERMAN, "%s: Lücke %d min vor %s", st.schluessel(), (erster - max) / 60, Messarchiv.zeit(erster)));
                }
            } catch (IOException | RuntimeException e) {
                fehler++;
                meldung.add(st.schluessel() + ": " + e.getMessage());
            }
        }
        String m = zeilen + " Minutenwerte, " + verworfen + " verworfen" + (meldung.isEmpty() ? "" : "; " + String.join("; ", meldung));
        ar.abruf("PEGELONLINE", t0, sekunden(t0), zeilen, verworfen, fehler, m);
        log.zeile("PEGELONLINE: " + m);
        return fehler;
    }

    /** Der Wind des DWD, wenn der letzte fehlerfreie Abruf älter ist als {@code messung.dwd.stunden} (oder immer). */
    int dwd(Messarchiv ar, boolean immer) throws SQLException {
        Instant t0 = Instant.now(), zuletzt = ar.letzterAbruf("DWD");
        if (!immer && zuletzt != null && zuletzt.isAfter(t0.minusSeconds((long) (a.dwdStunden() * 3600)))) return 0;
        long zeilen = 0;
        int fehler = 0;
        List<String> meldung = new ArrayList<>();
        for (Messarchiv.Station st : ar.stationen("WIND").values()) {
            if (!st.aktiv() || st.kennung() == null) continue;
            try {
                List<Abruf.Wind> l = Abruf.dwd(Abruf.hole(Abruf.dwdAdresse(a.dwd(), st.kennung()), "application/zip"));
                long[] t = new long[l.size()];
                float[] ff = new float[t.length], dd = new float[t.length];
                for (int i = 0; i < t.length; i++) {
                    Abruf.Wind w = l.get(i);
                    t[i] = w.ende() - 300;
                    ff[i] = w.ff() >= 0 && w.ff() <= 80 ? w.ff() : Float.NaN;
                    dd[i] = w.dd() >= 0 && w.dd() <= 360 ? w.dd() : Float.NaN;
                }
                Long nf = ar.letzte("MITTEL", st.id(), FF), nd = ar.letzte("MITTEL", st.id(), DD);
                int f = ar.einfuegen("MITTEL", st.id(), FF, t, ff, nf, null), d = ar.einfuegen("MITTEL", st.id(), DD, t, dd, nd, null);
                zeilen += f + d;
                meldung.add(st.name() + " " + f + "+" + d + (t.length > 0 ? " bis " + Messarchiv.zeit(t[t.length - 1]) : ""));
            } catch (IOException | RuntimeException e) {
                fehler++;
                meldung.add(st.schluessel() + ": " + e.getMessage());
            }
        }
        String m = zeilen + " Mittel; " + String.join("; ", meldung);
        ar.abruf("DWD", t0, sekunden(t0), zeilen, 0, fehler, m);
        log.zeile("DWD: " + m);
        return fehler;
    }

    /** Verdichtet je Pegel die vollen 10-Minuten-Blöcke seit dem jüngsten Mittel. */
    int verdichtenAlle(Messarchiv ar) throws SQLException {
        int n = 0;
        for (Messarchiv.Station st : ar.stationen("PEGEL").values()) {
            Long maxRoh = ar.letzte("ROH", st.id(), W);
            if (maxRoh == null) continue;
            Long lastM = ar.letzte("MITTEL", st.id(), W), minRoh = ar.erste("ROH", st.id(), W);
            long von = lastM == null ? Messarchiv.block(minRoh) : lastM - 300 + 600;
            n += ar.verdichten(st.id(), von, Messarchiv.block(maxRoh));
        }
        return n;
    }

    private final Map<Integer, Vorhersage> vorhersagen = new HashMap<>();

    private Vorhersage vorhersage(Messarchiv ar, int id) throws SQLException {
        if (!vorhersagen.containsKey(id)) vorhersagen.put(id, ar.vorhersage(id, Uebernahme.HERKUNFT_PEGEL));
        return vorhersagen.get(id);
    }

    /** Sucht je Pegel die Scheitel nach dem jüngsten gespeicherten. */
    int scheitelAlle(Messarchiv ar) throws SQLException {
        int n = 0;
        for (Messarchiv.Station st : ar.stationen("PEGEL").values()) {
            Long maxR = ar.letzte("ROH", st.id(), W);
            if (maxR == null) continue;
            Long lastS = ar.letzterScheitel(st.id(), MESSUNG);
            char lastArt = ar.letzteArt(st.id(), MESSUNG);
            long lesen = lastS == null ? ar.erste("ROH", st.id(), W) : lastS - 4 * 3600;
            Messarchiv.Reihe r = ar.reihe("ROH", st.id(), W, lesen, maxR);
            if (r.n() == 0) continue;
            long von = Math.floorDiv(r.t()[0], 60) * 60;
            float[] x = Messarchiv.raster(r, von, maxR, 60);
            Vorhersage v = vorhersage(ar, st.id());
            List<Messarchiv.Eintrag> l = new ArrayList<>();
            for (Scheitelsuche.Scheitel s : suche.suche(von, 60, x)) {
                if (lastS != null && s.zeit() <= lastS) continue;
                if (l.isEmpty() && s.art() == lastArt) continue;                       // der Wechsel mit dem gespeicherten bleibt
                double g = v == null ? Double.NaN : v.wert(Instant.ofEpochSecond(s.zeit()));
                l.add(new Messarchiv.Eintrag(s.art(), s.zeit(), s.hoehe(), g, Double.isNaN(g) ? Double.NaN : s.hoehe() - g));
            }
            n += ar.scheitel(st.id(), MESSUNG, l);
        }
        return n;
    }

    /**
     * Das Hochwasser am Ort ohne Pegel: Gezeit aus der Tafel ({@code gezeiten.datei}) plus Windstau, gemittelt über die
     * Bezugspegel der Tafel (Messung minus deren Gezeit, ± 30 min um das Hochwasser, je mindestens 30 Werte) – wie die
     * Landunter-Uhr, nur rückblickend.
     */
    int schaetzen(Messarchiv ar) throws Exception {
        if (a.ortSchluessel() == null || a.gezeiten() == null || !a.gezeiten().isFile()) return 0;
        Messarchiv.Station ort = ar.stationen("ORT").get(a.ortSchluessel());
        if (ort == null) return 0;
        Gezeitentafel g = Gezeitentafel.lies(a.gezeiten());
        Map<String, Messarchiv.Station> pegel = ar.stationen("PEGEL");
        List<Object[]> bez = new ArrayList<>();
        long bis = Long.MAX_VALUE, erst = Long.MAX_VALUE;
        for (Gezeitentafel.Bezug b : g.bezuege) {
            Messarchiv.Station st = pegel.get(b.kennung());
            if (st == null) continue;
            Long mx = ar.letzte("ROH", st.id(), W), mn = ar.erste("ROH", st.id(), W);
            if (mx == null) continue;
            bez.add(new Object[] {st, b.vorhersage()});
            bis = Math.min(bis, mx - 1800);
            erst = Math.min(erst, mn);
        }
        if (bez.isEmpty()) return 0;
        Long lastS = ar.letzterScheitel(ort.id(), SCHAETZUNG);
        long von = lastS == null ? erst + 1800 : lastS + 60;
        if (bis <= von) return 0;
        List<Messarchiv.Reihe> reihen = new ArrayList<>();
        for (Object[] o : bez) reihen.add(ar.reihe("ROH", ((Messarchiv.Station) o[0]).id(), W, von - 3600, bis + 3600));
        List<Messarchiv.Eintrag> l = new ArrayList<>();
        for (Vorhersage.Extrem e : g.vorhersage().extreme(Instant.ofEpochSecond(von), Instant.ofEpochSecond(bis))) {
            long t = e.zeit().getEpochSecond();
            if (e.art() != Vorhersage.Art.HOCHWASSER || t < von || t > bis) continue;
            double s = 0;
            int nb = 0;
            for (int i = 0; i < bez.size(); i++) {
                Vorhersage bv = (Vorhersage) bez.get(i)[1];
                Messarchiv.Reihe r = reihen.get(i);
                double sum = 0;
                int n = 0;
                for (int k = 0; k < r.n(); k++) {
                    if (Math.abs(r.t()[k] - t) > 1800) continue;
                    sum += r.w()[k] - bv.wert(Instant.ofEpochSecond(r.t()[k]));
                    n++;
                }
                if (n >= 30) {
                    s += sum / n;
                    nb++;
                }
            }
            if (nb == 0) continue;
            double stau = s / nb;
            l.add(new Messarchiv.Eintrag('H', t, e.hoehe() + stau, e.hoehe(), stau));
        }
        return ar.scheitel(ort.id(), SCHAETZUNG, l);
    }

    // ------------------------------------------------------------------ seit DB2: Chronik, Wind nachladen, Fach von Hand

    /** Die Chronik bauen und schreiben (nur bei Änderung); gibt die Zeile fürs Protokoll. */
    String chronik(Messarchiv ar) throws SQLException, IOException {
        String z = Chronikbau.schreiben(Chronikbau.aus(ar, a), a);
        log.zeile(z + (a.chronik() == null ? "" : " (" + a.chronik().getName() + ")"));
        return z;
    }

    int chronikAllein() throws Exception {
        try (Connection c = verbinde(false)) {
            chronik(new Messarchiv(c, a.praefix(), a.srid()));
        }
        return 0;
    }

    /**
     * Seit DB3: Modell gegen Messung. Übernimmt die Datei des Modells ({@code guete.modell}) – ein Lauf mit neuer Kennung
     * wird als Ganzes ersetzt –, rechnet die Kennzahlen aller Läufe und schreibt {@code guete.datei} (nur bei Änderung).
     */
    int guete() throws Exception {
        if (guete == null) {
            log.zeile("Im Manifest fehlt guete.datei – die App hat kein Modell gegen Messung.");
            return 2;
        }
        if (guete.modell() == null || !guete.modell().isFile()) {
            log.zeile("Die Datei des Modells fehlt (" + guete.modell() + "): zuerst Ausgabe --guete.");
            return 1;
        }
        Instant t0 = Instant.now();
        com.dan.steuerhaus.Guete mo = com.dan.steuerhaus.Guete.lies(guete.modell());
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Guetebau.Bilanz b = Guetebau.einlesen(ar, mo, log::zeile);
            log.zeile("Güte: " + b.kurz());
            int n = Guetebau.rechnen(ar, guete.stau(), zone, guete.mittelwasserTage());
            int nw = Guetebau.rechnenWind(ar, guete.windMin(), guete.windSturm());                          // seit DB4
            log.zeile("Güte: Kennzahlen für " + n + " Pegel und " + nw + " Windstationen in den Läufen gerechnet");
            String quelle = "Messarchiv " + a.schema() + ": " + mo.quelle + "; Messung von PEGELONLINE (WSV, DL-DE Zero 2.0, ungeprüfte Rohdaten), Mittel über 10 Minuten, "
                    + "Hoch- und Niedrigwasser aus der Scheitelsuche; HThw aus den Kennwerten von PEGELONLINE";
            com.dan.steuerhaus.Guete g = Guetebau.aus(ar, guete, quelle, zone);
            String z = Guetebau.schreiben(g, guete);
            log.zeile(z);
            ar.abruf("GUETE", t0, sekunden(t0), b.werte(), 0, b.ohneStation().size(), b.kurz() + "; " + z);
        }
        return 0;
    }

    /**
     * Füllt Lücken im Wind: je Windstation die historischen ZIP des DWD, deren Zeitraum zwischen dem ersten und dem jüngsten
     * Wert liegt und dort zu weniger als 90 % belegt ist; eingefügt wird alles, was fehlt (vorhandene Schlüssel bleiben).
     */
    int windNachladen() throws Exception {
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Instant t0 = Instant.now();
            String verzeichnis = new String(Abruf.hole(a.dwdHistorisch() + "/", "text/html"), java.nio.charset.StandardCharsets.UTF_8);
            long zeilen = 0;
            int fehler = 0;
            List<String> meldung = new ArrayList<>();
            for (Messarchiv.Station st : ar.stationen("WIND").values()) {
                if (st.kennung() == null) continue;
                Long erst = ar.erste("MITTEL", st.id(), FF), zuletzt = ar.letzte("MITTEL", st.id(), FF);
                if (erst == null) continue;
                for (Abruf.Historisch h : Abruf.dwdHistorisch(verzeichnis, st.kennung())) {
                    long von = h.von().atStartOfDay(ZoneOffset.UTC).toEpochSecond(), bis = h.bis().plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1;
                    long v = Math.max(von, erst), b = Math.min(bis, zuletzt);
                    if (b <= v) continue;
                    long soll = (b - v) / 600, ist = ar.zahl("MITTEL", st.id(), FF, v, b);
                    if (ist >= 0.9 * soll) {
                        meldung.add(String.format(Locale.GERMAN, "%s %s: %d von %d da", st.name(), h.name(), ist, soll));
                        continue;
                    }
                    try {
                        List<Abruf.Wind> l = Abruf.dwd(Abruf.hole(a.dwdHistorisch() + "/" + h.name(), "application/zip"));
                        long[] t = new long[l.size()];
                        float[] ff = new float[t.length], dd = new float[t.length];
                        for (int i = 0; i < t.length; i++) {
                            Abruf.Wind w = l.get(i);
                            t[i] = w.ende() - 300;
                            ff[i] = w.ff() >= 0 && w.ff() <= 80 ? w.ff() : Float.NaN;
                            dd[i] = w.dd() >= 0 && w.dd() <= 360 ? w.dd() : Float.NaN;
                        }
                        int f = ar.einfuegen("MITTEL", st.id(), FF, t, ff, erst - 1, zuletzt), d = ar.einfuegen("MITTEL", st.id(), DD, t, dd, erst - 1, zuletzt);
                        long nachher = ar.zahl("MITTEL", st.id(), FF, v, b);
                        zeilen += nachher - ist;
                        meldung.add(String.format(Locale.GERMAN, "%s %s: %d Werte gelesen, belegt %d → %d von %d", st.name(), h.name(), l.size(), ist, nachher, soll));
                        if (f + d == 0) meldung.add(st.name() + ": nichts gesendet");
                    } catch (IOException | RuntimeException x) {
                        fehler++;
                        meldung.add(st.name() + " " + h.name() + ": " + x.getMessage());
                    }
                }
            }
            String m = zeilen + " neue Geschwindigkeiten; " + String.join("; ", meldung);
            ar.abruf("DWD-HISTORISCH", t0, sekunden(t0), zeilen, 0, fehler, m);
            log.zeile("Wind nachgeladen: " + m);
            return fehler == 0 ? 0 : 1;
        }
    }

    /** Nimmt ein Fach von Hand weg (nie den Anker, nie das laufende Fach); vorher die Zahl der Zeilen ins Protokoll. */
    int fachWeg(String tabelle, String tag) throws Exception {
        String t = tabelle.toUpperCase(Locale.ROOT).replaceFirst("^" + a.praefix(), "");
        if (!t.equals("ROH") && !t.equals("MITTEL")) throw new IllegalArgumentException("Fächer gibt es nur in ROH und MITTEL, nicht in " + tabelle);
        LocalDate d = LocalDate.parse(tag);
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Instant t0 = Instant.now();
            Aufbewahrung.Fach f = ar.faecher(t, t.equals("ROH") ? 1 : 12).stream().filter(x -> !x.anker() && !d.isBefore(x.ab()) && d.isBefore(x.bis())).findFirst().orElse(null);
            if (f == null) {
                log.zeile("Kein Fach in " + t + " enthält " + d + " – nichts zu tun.");
                return 0;
            }
            LocalDate heute = LocalDate.now(ZoneOffset.UTC);
            if (!heute.isBefore(f.ab()) && heute.isBefore(f.bis())) throw new IllegalArgumentException("Das laufende Fach bleibt: " + f.kurz());
            long n = ar.zeilenImFach(f);
            ar.wegnehmen(f);
            String m = "von Hand weg: " + f.kurz() + " mit " + n + " Zeilen";
            ar.abruf("PFLEGE", t0, sekunden(t0), 1, n, 0, m);
            log.zeile("Fach " + m);
            return 0;
        }
    }

    // ------------------------------------------------------------------ Pflege

    int pflegeAllein(boolean nurPlan) throws Exception {
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            Aufbewahrung.Plan p = pflege(ar, nurPlan, grenzeAufruf);
            log.zeile(String.format(Locale.GERMAN, "Pflege%s: %d Fächer %s, Platz %.1f → %.1f MB%s", nurPlan ? " (nur Plan)" : "", p.weg().size(),
                    nurPlan ? "fielen weg" : "weg", p.vorher(), p.nachher(), p.ueber() ? ", ÜBER DER GRENZE" : ""));
            return p.ueber() ? 1 : 0;
        }
    }

    /** Aufbewahrung und Grenze ({@link Aufbewahrung}); vor dem Wegnehmen eines Monats der Rohwerte wird er verdichtet. */
    Aufbewahrung.Plan pflege(Messarchiv ar, boolean nurPlan, double grenze) throws SQLException {
        Instant t0 = Instant.now();
        double g = Double.isNaN(grenze) ? a.grenzeMb() : grenze;
        Aufbewahrung.Plan p = Aufbewahrung.plan(ar.faecher("ROH", 1), ar.faecher("MITTEL", 12), LocalDate.now(ZoneOffset.UTC), a.rohMonate(), ar.platzMb(), g);
        p.gruende().forEach(x -> log.zeile((nurPlan ? "Plan: " : "Pflege: ") + x));
        if (nurPlan) return p;
        int abrufe = ar.abrufeAufraeumen(a.abrufTage());
        if (p.leer() && abrufe == 0) return p;
        List<Integer> ids = new ArrayList<>();
        for (Messarchiv.Station st : ar.stationen("PEGEL").values()) ids.add(st.id());
        for (Aufbewahrung.Fach f : p.weg()) {
            if (f.tabelle().equals("ROH"))
                for (int id : ids) ar.verdichten(id, f.ab().atStartOfDay(ZoneOffset.UTC).toEpochSecond(), f.bis().atStartOfDay(ZoneOffset.UTC).toEpochSecond());
            ar.wegnehmen(f);
        }
        ar.abruf("PFLEGE", t0, sekunden(t0), p.weg().size(), abrufe, p.ueber() ? 1 : 0,
                p.weg().size() + " Fächer weg, " + abrufe + " alte Einträge im Protokoll; " + String.join("; ", p.gruende()));
        return p;
    }

    // ------------------------------------------------------------------ Bericht

    int bericht() throws Exception {
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            String p = a.praefix();
            double platz = ar.platzMb();
            log.zeile(String.format(Locale.GERMAN, "Füllstand: %.1f MB von %.0f MB (%.0f %%), Warnung ab %.0f %%; Minutenwerte %d Monate", platz, a.grenzeMb(),
                    100 * platz / a.grenzeMb(), a.warnungProzent(), a.rohMonate()));
            ar.quoten().forEach(q -> log.zeile("  Quote " + q));
            abschnitt(ar, "Platz je Tabelle (MB · Segmente)", "SELECT TABELLE, MB, SEGMENTE FROM " + p + "FUELLSTAND_V ORDER BY MB DESC", 20);
            log.zeile("Fächer ROH (Monate):");
            for (Aufbewahrung.Fach f : ar.faecher("ROH", 1)) log.zeile("  " + f.kurz());
            log.zeile("Fächer MITTEL (Jahre):");
            for (Aufbewahrung.Fach f : ar.faecher("MITTEL", 12)) log.zeile("  " + f.kurz());
            abschnitt(ar, "Stationen (Art · Schlüssel · letzter Rohwert · letztes Mittel)",
                    "SELECT ART, SCHLUESSEL, LETZTER_ROHWERT, LETZTES_MITTEL FROM " + p + "STATION_V ORDER BY ART, SCHLUESSEL", 60);
            abschnitt(ar, "Letzte Abrufe (Beginn · Art · s · Zeilen · verworfen · Fehler · Meldung)",
                    "SELECT BEGINN, ART, DAUER_S, ZEILEN, VERWORFEN, FEHLER, SUBSTR(MELDUNG, 1, 200) FROM " + p + "ABRUF ORDER BY ABRUF_ID DESC", 12);
            abschnitt(ar, "Hochwasser am Ort (Zeit · Höhe · Gezeit · Stau · Befund), die letzten 8",
                    "SELECT ZEIT, HOEHE, GEZEIT, STAU, BEFUND FROM " + p + "SCHAETZUNG_V ORDER BY ZEIT DESC", 8);
            abschnitt(ar, "Die höchsten Hochwasser am Ort", "SELECT ZEIT, HOEHE, GEZEIT, STAU, BEFUND FROM " + p + "SCHAETZUNG_V ORDER BY HOEHE DESC", 5);
            abschnitt(ar, "Landunter am Ort (ab · bis · Tiden · Scheitel · Stau)", "SELECT AB, BIS, TIDEN, SCHEITEL, STAU_MAX FROM " + p + "LANDUNTER_V ORDER BY AB DESC", 10);
            abschnitt(ar, "Sturmfluten an den Pegeln nach BSH (Pegel · ab · bis · Tiden · Scheitel · über MThw · Klasse)",
                    "SELECT SCHLUESSEL, AB, BIS, TIDEN, SCHEITEL, UEBER_MTHW, KLASSE FROM " + p + "STURMFLUT_V ORDER BY AB DESC, SCHLUESSEL", 30);
            abschnitt(ar, "Lücken über 60 min in den Minutenwerten (Pegel · Lücken · längste in min)",
                    "SELECT s.SCHLUESSEL, COUNT(*), ROUND(MAX(l.D)) FROM (SELECT STATION_ID, (ZEIT - LAG(ZEIT) OVER (PARTITION BY STATION_ID ORDER BY ZEIT)) * 1440 D FROM "
                            + p + "ROH) l JOIN " + p + "STATION s ON s.STATION_ID = l.STATION_ID WHERE l.D > 60 GROUP BY s.SCHLUESSEL ORDER BY 3 DESC", 30);
            abschnitt(ar, "Modell gegen Messung (Lauf · Pegel · Werte · RMSE · Bias · Mittelwasser · Bias ohne MW · Skill · HW · Versatz min · m · Windstau-Anteil · gegen HThw)",
                    "SELECT LAUF, SCHLUESSEL, WERTE, RMSE, BIAS, MITTELWASSER, BIAS_OHNE_MW, SKILL, HW, HW_ZEIT, HW_HOEHE, STAU_ANTEIL, GEGEN_HTHW FROM " + p
                            + "GUETE_V ORDER BY VON, LAUF, SCHLUESSEL", 80);
            abschnitt(ar, "Wind Modell gegen Messung (Lauf · Station · Werte · Faktor · Schub · Bias m/s · RMSE · Richtung ° · Sturm: Werte · Faktor · Schub · Richtung)",
                    "SELECT LAUF, NAME, WERTE, FAKTOR, ROUND(SCHUB, 3), BIAS, RMSE, RICHTUNG, STURM_WERTE, STURM_FAKTOR, ROUND(STURM_SCHUB, 3), STURM_RICHTUNG FROM " + p
                            + "GUETE_WIND_V ORDER BY VON, LAUF, SCHLUESSEL", 30);
            Aufbewahrung.Plan pl = Aufbewahrung.plan(ar.faecher("ROH", 1), ar.faecher("MITTEL", 12), LocalDate.now(ZoneOffset.UTC), a.rohMonate(), platz, a.grenzeMb());
            log.zeile("Nächste Pflege: " + (pl.leer() ? "nichts wegzunehmen" : String.join("; ", pl.gruende())));
        }
        return 0;
    }

    private void abschnitt(Messarchiv ar, String titel, String sql, int max) throws SQLException {
        log.zeile(titel + ":");
        List<String> z = ar.zeilen(sql, max);
        if (z.isEmpty()) log.zeile("  –");
        z.forEach(x -> log.zeile("  " + x));
    }

    // ------------------------------------------------------------------ Prüfsteine DB1

    private int verstoesse;

    private void pruefe(boolean ok, String was, String ist) {
        if (!ok) verstoesse++;
        log.zeile((ok ? "ok      " : "VERSTOSS ") + was + ": " + ist);
    }

    int pruefen(File gebiet) throws Exception {
        verstoesse = 0;
        try (Connection c = verbinde(false)) {
            Messarchiv ar = new Messarchiv(c, a.praefix(), a.srid());
            String p = a.praefix();
            // 1 Schema
            Einrichter.Ergebnis e = einrichter().pruefen(c, a.sql());
            e.zeilen().forEach(z -> log.zeile("  " + z));
            pruefe(e.ok(), "Prüfstein 1: Schema (Tabellen, Spalten, Indizes, räumlich, Metadaten, Sichten, Stufe, Quellen)", e.kurz());
            Map<String, Messarchiv.Station> pegel = ar.stationen("PEGEL"), wind = ar.stationen("WIND");
            if (gebiet != null && Pegel.da(gebiet)) {
                Map<String, Pegel> bestand = Pegel.lies(gebiet);
                List<String> f2 = new ArrayList<>(), f3 = new ArrayList<>(), f5 = new ArrayList<>(), f6 = new ArrayList<>();
                long n2 = 0, n3 = 0, n5 = 0;
                int ohne = 0;
                for (Pegel pg : bestand.values()) {
                    Messarchiv.Station st = pegel.get(pg.name);
                    if (st == null) { f2.add(pg.name + " fehlt"); continue; }
                    long s0 = pg.start.getEpochSecond(), s1 = pg.ende().getEpochSecond();
                    // 2 Minutenwerte
                    long soll = 0, letzt = -1;
                    float[] mm = new float[pg.werte.length];
                    for (int i = 0; i < pg.werte.length; i++) {
                        mm[i] = Float.isNaN(pg.werte[i]) ? Float.NaN : (float) (Math.round(pg.werte[i] * 1000.0) / 1000.0);
                        if (!Float.isNaN(pg.werte[i])) { soll++; letzt = s0 + 60L * i; }
                    }
                    if (letzt < 0) { f2.add(pg.name + " ohne gültige Werte"); continue; }
                    long ist = ar.zahl("ROH", st.id(), W, s0, letzt);
                    n2 += ist;
                    if (ist != soll) f2.add(pg.name + " " + ist + " statt " + soll);
                    // 3 Mittel in den vollen Blöcken des Bestands (ohne den letzten)
                    Map<Long, Integer> bl = new HashMap<>();
                    for (int i = 0; i < pg.werte.length; i++) if (!Float.isNaN(pg.werte[i])) bl.merge(Messarchiv.block(s0 + 60L * i), 1, Integer::sum);
                    long bEnde = Messarchiv.block(letzt) - 600, mSoll = bl.entrySet().stream().filter(x -> x.getKey() <= bEnde && x.getValue() >= 5).count();
                    long mIst = ar.zahl("MITTEL", st.id(), W, Messarchiv.block(s0) + 300, bEnde + 300);
                    n3 += mIst;
                    if (mIst != mSoll) f3.add(pg.name + " " + mIst + " statt " + mSoll);
                    // 5 Scheitel wie in der Datei (auf mm gerundet, ohne die Ränder)
                    long r0 = s0 + 4 * 3600, r1 = letzt - 4 * 3600;
                    long hSoll = suche.suche(s0, 60, mm).stream().filter(x -> x.art() == 'H' && x.zeit() >= r0 && x.zeit() <= r1).count();
                    int hIst = ar.scheitelZahl(st.id(), 'H', r0, r1);
                    n5 += hIst;
                    if (hIst != hSoll) f5.add(pg.name + " " + hIst + " statt " + hSoll);
                    // 6 Anschluss an den Bestand
                    Long nach = null;
                    Messarchiv.Reihe rr = ar.reihe("ROH", st.id(), W, letzt + 60, letzt + 30L * 86400);
                    if (rr.n() > 0) nach = rr.t()[0];
                    if (nach == null) ohne++;
                    else if (nach - letzt > 3600) f6.add(String.format(Locale.GERMAN, "%s %d min (%s bis %s)", pg.name, (nach - letzt) / 60, Messarchiv.zeit(letzt), Messarchiv.zeit(nach)));
                }
                pruefe(f2.isEmpty(), "Prüfstein 2: Minutenwerte des Bestands vollständig (je Pegel gleich der Zahl gültiger Werte der Datei)",
                        f2.isEmpty() ? n2 + " Werte in " + bestand.size() + " Pegeln" : f2.toString());
                pruefe(f3.isEmpty(), "Prüfstein 3: 10-Minuten-Mittel des Bestands (je voller Block mit mindestens 5 Werten eines)",
                        f3.isEmpty() ? n3 + " Mittel" : f3.toString());
                // 4 Wind
                List<String> f4 = new ArrayList<>();
                long n4 = 0;
                for (Windstation w : Windstation.lies(gebiet).values()) {
                    Messarchiv.Station st = wind.get(w.kennung);
                    if (st == null) { f4.add(w.name + " fehlt"); continue; }
                    long sf = 0, sd = 0;
                    for (int i = 0; i < w.zeiten.length; i++) { if (!Float.isNaN(w.ff[i])) sf++; if (!Float.isNaN(w.dd[i])) sd++; }
                    long t0 = w.zeiten[0] - 300, t1 = w.zeiten[w.zeiten.length - 1] - 300;
                    long iff = ar.zahl("MITTEL", st.id(), FF, t0, t1), idd = ar.zahl("MITTEL", st.id(), DD, t0, t1);
                    n4 += iff + idd;
                    if (iff != sf || idd != sd) f4.add(w.name + " " + iff + "/" + idd + " statt " + sf + "/" + sd);
                }
                pruefe(f4.isEmpty(), "Prüfstein 4: Wind des Bestands (Geschwindigkeit und Richtung je gültigem Wert)", f4.isEmpty() ? n4 + " Mittel" : f4.toString());
                pruefe(f5.isEmpty(), "Prüfstein 5: Hochwasser je Pegel wie die Suche in der Datei (ohne 4 h an den Rändern)", f5.isEmpty() ? n5 + " Hochwasser" : f5.toString());
                pruefe(f6.isEmpty(), "Prüfstein 6: lückenloser Anschluss des Abrufs an den Bestand (höchstens 60 min)",
                        (f6.isEmpty() ? "alle " + (bestand.size() - ohne) + " Pegel mit Abruf" : f6.toString()) + (ohne > 0 ? "; " + ohne + " Pegel noch ohne Abruf" : ""));
                // 7 Schätzung am Ort
                Messarchiv.Station ort = a.ortSchluessel() == null ? null : ar.stationen("ORT").get(a.ortSchluessel());
                if (ort != null) {
                    Pegel b = bestand.values().iterator().next();
                    long r0 = b.start.getEpochSecond() + 3600, r1 = b.ende().getEpochSecond() - 3600;
                    int hw = ar.scheitelZahl(ort.id(), 'H', r0, r1);
                    List<String> hoechst = ar.zeilen("SELECT ZEIT, HOEHE, STAU, BEFUND FROM " + p + "SCHAETZUNG_V ORDER BY HOEHE DESC", 1);
                    pruefe(hw >= 50, "Prüfstein 7: Hochwasser am Ort " + a.ortSchluessel() + " geschätzt im Zeitraum des Bestands (mindestens 50)",
                            hw + "; höchstes " + (hoechst.isEmpty() ? "–" : hoechst.get(0)));
                }
            } else log.zeile("(Prüfsteine 2 bis 7 brauchen --gebiet)");
            // 8 Sichten rechnen
            List<String> f8 = new ArrayList<>();
            for (String v : a.sichten()) {
                try {
                    f8.add(v + " " + Db.einWert(c, "SELECT TO_CHAR(COUNT(*)) FROM " + v));
                } catch (SQLException x) {
                    verstoesse++;
                    f8.add(v + " FEHLER " + Db.erste(x));
                }
            }
            String wgs = Db.einWert(c, "SELECT TO_CHAR(COUNT(CASE WHEN LAGE_WGS84 IS NOT NULL THEN 1 END)) || ' von ' || TO_CHAR(COUNT(*)) FROM " + p + "STATION_V");
            pruefe(f8.stream().noneMatch(x -> x.contains("FEHLER")), "Prüfstein 8: Sichten rechnen (Zeilen)", f8 + "; Stationen in WGS84 " + wgs);
            // 9 Probe des Aufräumens
            pruefe(probeAufraeumen(ar), "Prüfstein 9: Aufräumen an einem Probewert im Januar 2001 (Fach angelegt, erkannt, weg)", probeText);
            // 10 Grenze (nur Plan)
            double platz = ar.platzMb();
            Aufbewahrung.Plan pl = Aufbewahrung.plan(ar.faecher("ROH", 1), ar.faecher("MITTEL", 12), LocalDate.now(ZoneOffset.UTC), a.rohMonate(), platz, platz / 2);
            pruefe(!pl.weg().isEmpty() || pl.ueber(), "Prüfstein 10: bei halber Grenze plant die Pflege (nur Plan, nichts weg)",
                    String.format(Locale.GERMAN, "%d Fächer, %.1f → %.1f MB%s", pl.weg().size(), pl.vorher(), pl.nachher(), pl.ueber() ? ", bliebe über der Grenze" : ""));
            log.zeile(String.format(Locale.GERMAN, "Platz %.1f MB von %.0f MB", platz, a.grenzeMb()));
        }
        log.zeile(verstoesse == 0 ? "Prüfsteine DB1: alles in Ordnung" : "Prüfsteine DB1: " + verstoesse + " Verstöße");
        return verstoesse == 0 ? 0 : 1;
    }

    private String probeText = "";

    /** Legt einen Wert im Januar 2001 an (Station −1), findet das Fach im Plan der Aufbewahrung und nimmt es weg. */
    private boolean probeAufraeumen(Messarchiv ar) throws SQLException {
        long t = LocalDate.of(2001, 1, 15).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        ar.einfuegen("ROH", -1, W, new long[] {t}, new float[] {0.5f}, null, null);
        List<Aufbewahrung.Fach> roh = ar.faecher("ROH", 1);
        Aufbewahrung.Fach probe = roh.stream().filter(f -> !f.anker() && f.ab().getYear() == 2001).findFirst().orElse(null);
        if (probe == null) { probeText = "kein Fach für 2001 gefunden (" + roh.size() + " Fächer)"; return false; }
        Aufbewahrung.Plan pl = Aufbewahrung.plan(roh, List.of(), LocalDate.now(ZoneOffset.UTC), a.rohMonate(), 0, 0);
        boolean imPlan = pl.weg().stream().anyMatch(f -> f.partition().equals(probe.partition()));
        ar.wegnehmen(probe);
        long rest = ar.zahl("ROH", -1, W, t - 86400, t + 86400);
        boolean weg = ar.faecher("ROH", 1).stream().noneMatch(f -> f.partition().equals(probe.partition()));
        probeText = "Fach " + probe.partition() + " (" + probe.ab() + "), im Plan " + (imPlan ? "ja" : "nein") + ", danach " + rest + " Werte, Fach " + (weg ? "weg" : "noch da");
        return imPlan && rest == 0 && weg;
    }

    // ------------------------------------------------------------------ Protokoll

    /** Zeilen auf die Konsole und in die Datei; über 1 MB rückt die Datei eine Stufe zurück (.1). */
    static final class Log implements AutoCloseable {
        private final PrintStream konsole, datei;

        Log(File f, PrintStream konsole) throws IOException {
            this.konsole = konsole;
            Files.createDirectories(f.getParentFile().toPath());
            if (f.isFile() && f.length() > 1_000_000) Files.move(f.toPath(), new File(f.getPath() + ".1").toPath(), StandardCopyOption.REPLACE_EXISTING);
            this.datei = new PrintStream(Files.newOutputStream(f.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND), true, StandardCharsets.UTF_8);
        }

        void zeile(String s) {
            String z = Messarchiv.zeit(Instant.now().getEpochSecond()) + " UTC  " + s;
            konsole.println(s);
            datei.println(z);
        }

        PrintStream aus() { return datei; }

        @Override
        public void close() { datei.close(); }
    }
}
