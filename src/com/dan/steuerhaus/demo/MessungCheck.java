package com.dan.steuerhaus.demo;

import com.dan.core.astro.Partialtide;
import com.dan.core.pruef.Harmonik;
import com.dan.core.pruef.Pegel;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.gezeiten.Pegelonline;
import com.dan.steuerhaus.gezeiten.Vorhersage;
import com.dan.steuerhaus.messung.Abruf;
import com.dan.steuerhaus.messung.Aufbewahrung;
import com.dan.steuerhaus.messung.Ausreisser;
import com.dan.steuerhaus.messung.Messarchiv;
import com.dan.steuerhaus.messung.Messauftrag;
import com.dan.steuerhaus.messung.Scheitelsuche;
import com.dan.store.Skript;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * DB1 ohne Datenbank und ohne Netz (Werkbuch HalligHooge 167): Prüfstein 1 die Skripte in {@code db/sql} so, wie der
 * Einrichter sie zerlegt (Tabellen, Spalten, Sequenzen, Indizes, Sichten); 2 die Scheitelsuche an einer reinen Tide und, mit
 * {@code --gebiet}, an den Pegeln des Bestands gegen deren Vorhersage; 3 der Filter der Ausreißer; 4 der Plan der
 * Aufbewahrung und der Grenze; 5 das Lesen der Antworten von DWD und PEGELONLINE und die Adressen; 6 die Schlüssel
 * {@code messung.*} des Manifests; 7 die Importregel des Steuerhauses (wie Prüfstein 5 des SteuerhausCheck). Seit DB2 (Werkbuch
 * HalligHooge 169): 8 die Datei der Chronik (hin und zurück, unverändert schreibt nicht), 9 die Ereignisse am Ort und die
 * Sturmfluten über alle Pegel, 10 die Liste der historischen Dateien des DWD, 11 die Abdeckung eines Archivs.
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.MessungCheck --manifest src\szenen\Hooge.steuerhaus [--gebiet daten\gebiet] [--quellen ..\Steuerhaus\src]
 * </pre>
 */
public final class MessungCheck {
    private MessungCheck() { }

    static int steine, verstoesse;

    static void pruefe(boolean ok, String was, String ist) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + ist);
    }

    public static void main(String[] args) throws Exception {
        File manifest = null, gebiet = null, quellen = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--gebiet" -> gebiet = new File(args[++i]);
                case "--quellen" -> quellen = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        if (manifest == null) throw new IllegalArgumentException("--manifest fehlt");
        Manifest m = Manifest.lies(manifest);
        Messauftrag a = Messauftrag.lies(m);
        System.out.println("MessungCheck (DB1, DB2): " + manifest);
        // 6 Schlüssel
        List<String> f6 = new ArrayList<>();
        if (a == null) f6.add("messung.db fehlt");
        else {
            if (!a.db().getParentFile().isDirectory()) f6.add("Ordner von messung.db fehlt: " + a.db().getParentFile());
            if (!a.sql().isDirectory()) f6.add("messung.sql fehlt: " + a.sql());
            if (a.srid() != 25832) f6.add("SRID " + a.srid());
            if (a.rohMonate() < 2) f6.add("messung.roh.monate " + a.rohMonate());
            if (!(a.grenzeMb() > 0)) f6.add("messung.grenze.mb " + a.grenzeMb());
            if (a.ortSchluessel() == null || a.gezeiten() == null || !a.gezeiten().isFile()) f6.add("Ort ohne Gezeitentafel");
            if (Double.isNaN(a.krone()) || Double.isNaN(a.nass()) || a.nass() < a.krone()) f6.add("Schwellen " + a.krone() + " / " + a.nass());
        }
        pruefe(f6.isEmpty(), "Prüfstein 6: Schlüssel messung.* (Datenbank, Skripte, SRID 25832, Aufbewahrung, Grenze, Ort, Schwellen)",
                f6.isEmpty() ? a.schema() + ", " + a.praefix() + ", Minutenwerte " + a.rohMonate() + " Monate, Grenze " + a.grenzeMb() + " MB, Ort " + a.ortSchluessel()
                        + " mit " + a.krone() + "/" + a.nass() + " m" : f6.toString());
        if (a != null) skripte(a);
        scheitel(gebiet);
        ausreisser();
        aufbewahrung();
        lesen();
        chronikDatei();
        chronikGruppen();
        dwdListe();
        abdeckung();
        if (quellen != null) {
            int v0 = SteuerhausCheck.verstoesse;
            SteuerhausCheck.importregel(quellen);
            pruefe(SteuerhausCheck.verstoesse == v0, "Prüfstein 7: Importregel des Steuerhauses mit messung", SteuerhausCheck.verstoesse == v0 ? "wie oben" : "Verstoß, siehe oben");
        }
        System.out.println(verstoesse == 0 ? "MessungCheck: " + steine + " Prüfsteine, alles in Ordnung" : "MessungCheck: " + verstoesse + " von " + steine + " Prüfsteinen verletzt");
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ 8–11 seit DB2

    static com.dan.steuerhaus.Chronik.Ereignis ev(com.dan.steuerhaus.Chronik.Art a, String ab, String bis, int tiden, double h, double stau) {
        return new com.dan.steuerhaus.Chronik.Ereignis(a, Instant.parse(ab), Instant.parse(bis), Instant.parse(ab), tiden, h, stau, "Anleger Hooge", "Probe; mit Semikolon");
    }

    static void chronikDatei() throws Exception {
        List<String> f = new ArrayList<>();
        File d = Files.createTempDirectory("chronik").toFile(), cf = new File(d, "Probe.chronik");
        List<com.dan.steuerhaus.Chronik.Ereignis> l = new ArrayList<>(List.of(
                ev(com.dan.steuerhaus.Chronik.Art.LANDUNTER, "2026-10-25T23:34:04Z", "2026-10-26T11:48:40Z", 2, 3.512, 1.755),
                ev(com.dan.steuerhaus.Chronik.Art.KRONE, "2026-10-28T13:01:24Z", "2026-10-28T13:01:24Z", 1, 2.441, 0.414)));
        var c1 = new com.dan.steuerhaus.Chronik("Anleger Hooge", 2.01, "Krone des Sommerdeichs", 2.50, "Fennen nass", Instant.parse("2026-10-12T15:10:00Z"), "Probe", l,
                Instant.parse("2026-11-22T17:00:00Z"));
        boolean w1 = c1.schreibe(cf);
        var r1 = com.dan.steuerhaus.Chronik.lies(cf);
        if (!w1) f.add("erstes Schreiben");
        if (!r1.inhalt().equals(c1.inhalt())) f.add("hin und zurück");
        if (r1.ereignisse.size() != 2 || r1.letztes(com.dan.steuerhaus.Chronik.Art.LANDUNTER).tiden() != 2 || Math.abs(r1.nass - 2.5) > 1e-9) f.add("Felder " + r1.ereignisse);
        var c2 = new com.dan.steuerhaus.Chronik("Anleger Hooge", 2.01, "Krone des Sommerdeichs", 2.50, "Fennen nass", Instant.parse("2026-10-12T15:10:00Z"), "Probe", l,
                Instant.parse("2026-11-22T18:00:00Z"));
        long m1 = cf.lastModified();
        if (c2.schreibe(cf)) f.add("gleicher Inhalt hat geschrieben");
        if (cf.lastModified() != m1) f.add("Datei berührt");
        l.add(ev(com.dan.steuerhaus.Chronik.Art.STURMFLUT, "2026-10-25T21:14:00Z", "2026-10-26T11:01:00Z", 2, 3.896, 2.336));
        var c3 = new com.dan.steuerhaus.Chronik("Anleger Hooge", 2.01, "Krone des Sommerdeichs", 2.50, "Fennen nass", Instant.parse("2026-10-12T15:10:00Z"), "Probe", l,
                Instant.parse("2026-11-22T19:00:00Z"));
        if (!c3.schreibe(cf)) f.add("neues Ereignis nicht geschrieben");
        var r3 = com.dan.steuerhaus.Chronik.lies(cf);
        if (r3.ereignisse.size() != 3 || r3.ereignisse.get(0).art() != com.dan.steuerhaus.Chronik.Art.STURMFLUT) f.add("Folge nach dem Beginn " + r3.ereignisse);
        if (!r3.erstellt.equals(Instant.parse("2026-11-22T19:00:00Z"))) f.add("erstellt " + r3.erstellt);
        String z = r3.zuletzt(java.time.ZoneId.of("Europe/Berlin"));
        if (!z.startsWith("Zuletzt Landunter am 26.10.2026 (3,51 m, Windstau 1,76 m); über der Krone am 28.10.2026")) f.add("Zeile: " + z);
        if (r3.ereignisse.get(1).text().contains(";")) f.add("Semikolon im Text");
        pruefe(f.isEmpty(), "Prüfstein 8: Datei der Chronik (hin und zurück, gleicher Inhalt schreibt nicht, neues Ereignis schon, Folge, Zeile der Uhr)",
                f.isEmpty() ? "3 Ereignisse; " + z : f.toString());
    }

    static void chronikGruppen() {
        List<String> f = new ArrayList<>();
        long t = Instant.parse("2026-10-20T00:00:00Z").getEpochSecond(), tide = 44_700;
        double[] h = {1.5, 2.2, 1.6, 2.6, 2.3, 1.4, 1.3, 2.05};
        List<com.dan.steuerhaus.messung.Chronikbau.Hochwasser> hw = new ArrayList<>();
        for (int i = 0; i < h.length; i++) hw.add(new com.dan.steuerhaus.messung.Chronikbau.Hochwasser(t + i * tide, h[i], h[i] - 1.4));
        hw.add(new com.dan.steuerhaus.messung.Chronikbau.Hochwasser(t + 20 * tide, 2.1, 0.7));               // nach einer Lücke: eigenes Ereignis
        var e = com.dan.steuerhaus.messung.Chronikbau.amOrt(hw, 2.01, 2.50, "Anleger Hooge");
        String arten = e.stream().map(x -> x.art().name() + "/" + x.tiden()).toList().toString();
        if (!arten.equals("[KRONE/1, LANDUNTER/2, KRONE/1, KRONE/1]")) f.add("am Ort " + arten);
        else if (Math.abs(e.get(1).hoehe() - 2.6) > 1e-9 || e.get(1).hoechst().getEpochSecond() != t + 3 * tide || Math.abs(e.get(1).stau() - 1.2) > 1e-9) f.add("Landunter " + e.get(1));
        long s = Instant.parse("2026-10-25T21:00:00Z").getEpochSecond();
        var fl = List.of(new com.dan.steuerhaus.messung.Chronikbau.Pegelflut("HUSUM", s + 9000, s + 9000, 1, 3.83, 2.11),
                new com.dan.steuerhaus.messung.Chronikbau.Pegelflut("EIDER-SPERRWERK_AP", s + 4000, s + 50000, 2, 3.90, 2.34),
                new com.dan.steuerhaus.messung.Chronikbau.Pegelflut("LIST_AUF_SYLT", s + 13000, s + 13000, 1, 2.93, 2.02),
                new com.dan.steuerhaus.messung.Chronikbau.Pegelflut("BÜSUM", s + 30L * 86400, s + 30L * 86400, 1, 4.6, 2.7));
        var sf = com.dan.steuerhaus.messung.Chronikbau.sturmfluten(fl);
        if (sf.size() != 2) f.add(sf.size() + " Sturmfluten statt 2");
        else {
            if (!sf.get(0).ort().equals("Eider-Sperrwerk AP") || !sf.get(0).text().contains("an 3 Pegeln") || sf.get(0).tiden() != 2 || sf.get(0).ab().getEpochSecond() != s + 4000)
                f.add("erste " + sf.get(0));
            if (!sf.get(1).text().startsWith("Schwere Sturmflut") || !sf.get(1).ort().equals("Büsum")) f.add("zweite " + sf.get(1));
        }
        if (!com.dan.steuerhaus.messung.Chronikbau.name("LIST_AUF_SYLT").equals("List auf Sylt")) f.add("Name " + com.dan.steuerhaus.messung.Chronikbau.name("LIST_AUF_SYLT"));
        pruefe(f.isEmpty(), "Prüfstein 9: Ereignisse am Ort (Kette über der Krone, Landunter ab der Höhe NASS, Lücke trennt) und Sturmfluten über alle Pegel",
                f.isEmpty() ? arten + "; " + sf.size() + " Sturmfluten, „" + sf.get(0).text() + "“" : f.toString());
    }

    static void dwdListe() {
        String html = "<a href=\"10minutenwerte_wind_02115_20100101_20191231_hist.zip\">10minutenwerte_wind_02115_20100101_20191231_hist.zip</a> 22-Nov-2021\n"
                + "<a href=\"10minutenwerte_wind_02115_20200101_20251231_hist.zip\">10minutenwerte_wind_02115_20200101_20251231_hist.zip</a> 09-Apr-2026\n"
                + "<a href=\"10minutenwerte_wind_02116_20200101_20251231_hist.zip\">x</a>\n";
        var l = com.dan.steuerhaus.messung.Abruf.dwdHistorisch(html, "02115");
        boolean ok = l.size() == 2 && l.get(1).von().toString().equals("2020-01-01") && l.get(1).bis().toString().equals("2025-12-31")
                && l.get(1).name().equals("10minutenwerte_wind_02115_20200101_20251231_hist.zip");
        pruefe(ok, "Prüfstein 10: Liste der historischen Dateien des DWD (je Datei einmal, Zeitraum aus dem Namen, nur die Station)",
                l.stream().map(x -> x.von() + "–" + x.bis()).toList().toString());
    }

    static void abdeckung() throws Exception {
        File a = Files.createTempDirectory("archiv").toFile(), g = new File(a, "G2");
        g.mkdirs();
        Files.writeString(new File(g, "lauf.txt").toPath(), "lauf = Probe\nstart = 2026-10-23T00:00:00Z\nseed = 1\n", StandardCharsets.UTF_8);
        java.nio.ByteBuffer b = java.nio.ByteBuffer.allocate(8 * 217).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < 217; i++) b.putDouble(54 * 3600 + i * 300.0);
        Files.write(new File(g, "zeiten.f64").toPath(), b.array());
        Instant[] ab = com.dan.steuerhaus.chronik.Abdeckung.lies(a);
        boolean ok = ab != null && ab[0].equals(Instant.parse("2026-10-25T06:00:00Z")) && ab[1].equals(Instant.parse("2026-10-26T00:00:00Z"))
                && com.dan.steuerhaus.chronik.Abdeckung.lies(new File(a, "fehlt")) == null;
        pruefe(ok, "Prüfstein 11: Abdeckung eines Archivs (Beginn aus lauf.txt, erster und letzter Stand aus zeiten.f64, Unterordner G2)",
                ab == null ? "nichts gelesen" : ab[0] + " bis " + ab[1]);
    }

    // ------------------------------------------------------------------ 1 Skripte

    static void skripte(Messauftrag a) throws Exception {
        String[] namen = {"01_tabellen.sql", "02_spatial.sql", "03_sichten.sql", "04_startdaten.sql"};
        List<String> f = new ArrayList<>();
        Set<String> tabellen = new LinkedHashSet<>(), sequenzen = new LinkedHashSet<>(), indizes = new LinkedHashSet<>(), sichten = new LinkedHashSet<>();
        int anweisungen = 0, spalten = 0;
        Set<String> typen = Set.of("NUMBER", "VARCHAR2", "CHAR", "DATE", "MDSYS.SDO_GEOMETRY");
        for (String n : namen) {
            File d = new File(a.sql(), n);
            if (!d.isFile()) { f.add(n + " fehlt"); continue; }
            List<Skript.Anweisung> as = Skript.zerlegen(Files.readString(d.toPath(), StandardCharsets.UTF_8));
            anweisungen += as.size();
            for (Skript.Anweisung x : as) {
                if (x.sql().contains(";")) f.add(n + " Zeile " + x.zeile() + ": ; mitten in der Anweisung");
                if (x.plsql()) f.add(n + " Zeile " + x.zeile() + ": PL/SQL erwartet keines");
                String o = Skript.objekt(x.sql());
                if (o != null) {
                    String[] t = o.split(" ");
                    switch (t[0]) {
                        case "TABLE" -> tabellen.add(t[1]);
                        case "SEQUENCE" -> sequenzen.add(t[1]);
                        case "INDEX" -> indizes.add(t[1]);
                        case "VIEW" -> sichten.add(t[1]);
                        default -> { }
                    }
                    if (!t[1].startsWith(a.praefix())) f.add(n + ": " + o + " ohne Präfix " + a.praefix());
                }
            }
            if (n.startsWith("01")) for (Skript.Spalte s : Skript.spalten(as)) {
                spalten++;
                String typ = s.typ().replaceAll("\\(.*$", "");
                if (!typen.contains(typ)) f.add("Spalte " + s.tabelle() + "." + s.name() + " mit Typ " + s.typ());
                if (s.definition().contains("--")) f.add("Kommentar in der Spalte " + s.tabelle() + "." + s.name());
            }
        }
        if (tabellen.size() != 10) f.add(tabellen.size() + " Tabellen statt 10: " + tabellen);
        if (!sichten.equals(new LinkedHashSet<>(a.sichten()))) f.add("Sichten " + sichten + " statt " + a.sichten());
        pruefe(f.isEmpty(), "Prüfstein 1: Skripte für den Einrichter (zerlegt wie SQL Developer, Spalten mit bekanntem Typ, Präfix)",
                f.isEmpty() ? anweisungen + " Anweisungen, " + tabellen.size() + " Tabellen mit " + spalten + " Spalten, " + sequenzen.size() + " Sequenzen, " + indizes.size()
                        + " Indizes (ohne die eindeutigen), " + sichten.size() + " Sichten" : f.toString());
    }

    // ------------------------------------------------------------------ 2 Scheitel

    static void scheitel(File gebiet) throws Exception {
        // reine Tide M2 + M4 mit Rauschen ±2 cm: je Tide ein Hoch- und ein Niedrigwasser, Zeit auf wenige Minuten
        Vorhersage v = new Vorhersage(0.1, List.of(new Vorhersage.Konstante(Partialtide.M2, 1.4, 350), new Vorhersage.Konstante(Partialtide.M4, 0.15, 210)));
        Instant t0 = Instant.parse("2026-12-01T00:00:00Z");
        int n = 10 * 1440;
        float[] w = new float[n];
        java.util.Random r = new java.util.Random(7);
        for (int i = 0; i < n; i++) w[i] = (float) (v.wert(t0.plusSeconds(60L * i)) + 0.02 * (2 * r.nextDouble() - 1));
        for (int i = 3000; i < 3020; i++) w[i] = Float.NaN;                            // Lücke von 20 min
        List<Scheitelsuche.Scheitel> l = new Scheitelsuche().suche(t0.getEpochSecond(), 60, w);
        List<Vorhersage.Extrem> e = v.extreme(t0.plusSeconds(4 * 3600), t0.plusSeconds(60L * n - 4 * 3600));
        double dt = 0, dh = 0;
        int paar = 0;
        for (Scheitelsuche.Scheitel s : l) {
            for (Vorhersage.Extrem x : e) {
                if ((x.art() == Vorhersage.Art.HOCHWASSER) != (s.art() == 'H')) continue;
                long d = Math.abs(x.zeit().getEpochSecond() - s.zeit());
                if (d > 3 * 3600) continue;
                dt = Math.max(dt, d / 60.0);
                dh = Math.max(dh, Math.abs(x.hoehe() - s.hoehe()));
                paar++;
            }
        }
        boolean wechsel = true;
        for (int i = 1; i < l.size(); i++) if (l.get(i).art() == l.get(i - 1).art()) wechsel = false;
        pruefe(Math.abs(l.size() - e.size()) <= 2 && paar == l.size() && dt <= 10 && dh <= 0.03 && wechsel,
                "Prüfstein 2a: Scheitel einer reinen Tide (M2 + M4, Rauschen ±2 cm, Lücke 20 min, 10 Tage)",
                String.format(Locale.GERMAN, "%d gefunden, %d vorhergesagt; Zeit höchstens %.1f min, Höhe höchstens %.3f m daneben; H und N im Wechsel: %s", l.size(), e.size(), dt, dh,
                        wechsel ? "ja" : "nein"));
        if (gebiet == null || !Pegel.da(gebiet)) return;
        List<String> zeilen = new ArrayList<>();
        int gut = 0, alle = 0;
        for (Pegel p : Pegel.lies(gebiet).values()) {
            alle++;
            List<Scheitelsuche.Scheitel> s = new Scheitelsuche().suche(p.start.getEpochSecond(), p.schritt, p.werte);
            List<Vorhersage.Konstante> k = new ArrayList<>();
            for (Harmonik.Tide t : p.analyse.tiden()) k.add(new Vorhersage.Konstante(t.tide(), t.amplitude(), t.phaseGrad()));
            Vorhersage pv = new Vorhersage(p.analyse.mittel(), k);
            List<Vorhersage.Extrem> pe = pv.extreme(p.start.plusSeconds(4 * 3600), p.ende().minusSeconds(4 * 3600));
            long hw = s.stream().filter(x -> x.art() == 'H').count(), hwSoll = pe.stream().filter(x -> x.art() == Vorhersage.Art.HOCHWASSER).count();
            double[] d = new double[s.size()];
            int c = 0;
            for (Scheitelsuche.Scheitel x : s) {
                long best = Long.MAX_VALUE;
                for (Vorhersage.Extrem y : pe) if ((y.art() == Vorhersage.Art.HOCHWASSER) == (x.art() == 'H')) best = Math.min(best, Math.abs(y.zeit().getEpochSecond() - x.zeit()));
                if (best < 4 * 3600) d[c++] = best / 60.0;
            }
            double[] dd = Arrays.copyOf(d, c);
            Arrays.sort(dd);
            double med = c == 0 ? Double.NaN : dd[c / 2];
            boolean ok = hw >= hwSoll - 3 && hw <= hwSoll + 1 && med <= 30;
            if (ok) gut++;
            zeilen.add(String.format(Locale.GERMAN, "%s %d/%d HW, Median %.0f min", p.name, hw, hwSoll, med));
        }
        pruefe(gut >= alle - 4, "Prüfstein 2b: Hochwasser des Bestands gegen die Vorhersage je Pegel (höchstens 3 weniger, Median der Zeit bis 30 min; Lücken und Sperrwerke dürfen fehlen)",
                gut + " von " + alle + " passen; " + String.join("; ", zeilen));
    }

    // ------------------------------------------------------------------ 3 Ausreißer

    static void ausreisser() {
        float[] x = new float[200];
        for (int i = 0; i < x.length; i++) x[i] = (float) (1.0 + 0.01 * i);
        x[50] = 3.0f;
        x[120] = -9f;
        x[121] = 9.5f;
        x[150] = Float.NaN;
        Ausreisser.Ergebnis e = new Ausreisser().filtern(x);
        boolean ok = e.grenzen() == 2 && e.spitzen() == 1 && Float.isNaN(x[50]) && Float.isNaN(x[120]) && !Float.isNaN(x[49]) && !Float.isNaN(x[199]);
        pruefe(ok, "Prüfstein 3: Ausreißer (Grenzen −6 … 8 m, Spitze 0,3 m neben dem Median über ±15 min)",
                e.grenzen() + " außerhalb der Grenzen, " + e.spitzen() + " Spitze; Rand und Anstieg bleiben");
    }

    // ------------------------------------------------------------------ 4 Aufbewahrung

    static Aufbewahrung.Fach monat(String t, int j, int mo, double mb) {
        LocalDate ab = LocalDate.of(j, mo, 1);
        return new Aufbewahrung.Fach(t, "SYS_P" + j + mo, ab, ab.plusMonths(1), mb);
    }

    static void aufbewahrung() {
        List<String> f = new ArrayList<>();
        Aufbewahrung.Fach anker = new Aufbewahrung.Fach("ROH", "HH_ROH_ANKER", null, LocalDate.of(2000, 1, 1), 0);
        List<Aufbewahrung.Fach> roh = new ArrayList<>(List.of(anker));
        for (int i = 0; i < 9; i++) roh.add(monat("ROH", 2026 + (9 + i) / 12, (9 + i) % 12 + 1, 40));   // Oktober 2026 bis Juni 2027
        List<Aufbewahrung.Fach> mittel = new ArrayList<>();
        for (int j = 2010; j <= 2027; j++) mittel.add(new Aufbewahrung.Fach("MITTEL", "SYS_M" + j, LocalDate.of(j, 1, 1), LocalDate.of(j + 1, 1, 1), 8));
        LocalDate heute = LocalDate.of(2027, 6, 15);
        // a) nur die Aufbewahrung: Oktober und November 2026 fallen, Dezember bleibt
        Aufbewahrung.Plan a = Aufbewahrung.plan(roh, mittel, heute, 6, 9 * 40 + 18 * 8, 1500);
        List<String> wa = a.weg().stream().map(x -> x.ab().toString()).toList();
        if (!wa.equals(List.of("2026-10-01", "2026-11-01")) || a.ueber()) f.add("a) " + wa);
        // b) Grenze 300 MB: weitere Monate (nicht Mai und Juni 2027), dann Jahre der Mittel (nicht 2027)
        Aufbewahrung.Plan b = Aufbewahrung.plan(roh, mittel, heute, 6, 9 * 40 + 18 * 8, 300);
        boolean keinNeuer = b.weg().stream().noneMatch(x -> x.tabelle().equals("ROH") && !x.ab().isBefore(LocalDate.of(2027, 5, 1)))
                && b.weg().stream().noneMatch(x -> x.tabelle().equals("MITTEL") && x.ab().getYear() == 2027);
        if (!(b.nachher() <= 270 + 1e-9) || !keinNeuer || b.ueber()) f.add(String.format(Locale.ROOT, "b) nachher %.0f, %d weg, über %s", b.nachher(), b.weg().size(), b.ueber()));
        // c) Grenze 50 MB: reicht nicht, der Plan bleibt über der Grenze
        Aufbewahrung.Plan c = Aufbewahrung.plan(roh, mittel, heute, 6, 9 * 40 + 18 * 8, 50);
        if (!c.ueber()) f.add("c) nicht über der Grenze");
        // d) der Anker fällt nie
        if (a.weg().contains(anker) || b.weg().contains(anker) || c.weg().contains(anker)) f.add("d) Anker im Plan");
        pruefe(f.isEmpty(), "Prüfstein 4: Aufbewahrung (6 Monate) und Grenze (laufender und voriger Monat, laufendes Jahr bleiben; Anker bleibt)",
                f.isEmpty() ? String.format(Locale.GERMAN, "a) %s; b) %d Fächer, %.0f → %.0f MB; c) bleibt über der Grenze (%.0f MB)", wa, b.weg().size(), b.vorher(), b.nachher(),
                        c.nachher()) : f.toString());
    }

    // ------------------------------------------------------------------ 5 Lesen

    static void lesen() throws Exception {
        List<String> f = new ArrayList<>();
        String text = "STATIONS_ID;MESS_DATUM;  QN;FF_10;DD_10;eor\n 2115;202611220000;    3;   8.8; 310;eor\n 2115;202611220010;    3;-999; 320;eor\n"
                + " 2115;202611220020;    3;   9.7;-999;eor\n";
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(b)) {
            z.putNextEntry(new ZipEntry("Metadaten_Geographie_02115.txt"));
            z.write("x".getBytes(StandardCharsets.ISO_8859_1));
            z.putNextEntry(new ZipEntry("produkt_zehn_min_ff_20250101_20261121_02115.txt"));
            z.write(text.getBytes(StandardCharsets.ISO_8859_1));
        }
        List<Abruf.Wind> w = Abruf.dwd(b.toByteArray());
        if (w.size() != 3 || w.get(0).ende() != Instant.parse("2026-11-22T00:00:00Z").getEpochSecond() || w.get(0).ff() != 8.8f || !Float.isNaN(w.get(1).ff())
                || !Float.isNaN(w.get(2).dd()) || w.get(2).ff() != 9.7f)
            f.add("DWD " + w);
        String json = "[{\"timestamp\":\"2026-11-22T15:00:00+01:00\",\"value\":549.0},{\"timestamp\":\"2026-11-22T15:01:00+01:00\",\"value\":-12.0}]";
        List<Pegelonline.Messwert> p = Pegelonline.deute(json, -5.027);
        if (p.size() != 2 || !p.get(0).zeit().equals(Instant.parse("2026-11-22T14:00:00Z")) || Math.abs(p.get(0).hoehe() - 0.463) > 1e-9 || Math.abs(p.get(1).hoehe() + 5.147) > 1e-9)
            f.add("PEGELONLINE " + p);
        String u = Abruf.pegelAdresse("https://www.pegelonline.wsv.de/webservices/rest-api/v2", "9550021", Instant.parse("2026-11-22T14:07:31Z"));
        if (!u.equals("https://www.pegelonline.wsv.de/webservices/rest-api/v2/stations/9550021/W/measurements.json?start=2026-11-22T14%3A07%2B00%3A00")) f.add("Adresse " + u);
        String zt = Messarchiv.zeit(Instant.parse("2026-11-22T14:05:00Z").getEpochSecond());
        if (!zt.equals("2026-11-22 14:05:00") || Messarchiv.block(Instant.parse("2026-11-22T14:09:59Z").getEpochSecond()) != Instant.parse("2026-11-22T14:00:00Z").getEpochSecond())
            f.add("Zeit " + zt);
        pruefe(f.isEmpty(), "Prüfstein 5: Antworten lesen (DWD-ZIP mit −999, PEGELONLINE in m NHN), Adresse mit start als Zeitpunkt, Zeiten und Blöcke",
                f.isEmpty() ? "3 Windwerte, 2 Pegelwerte, " + u.substring(u.indexOf("start=")) : f.toString());
    }
}
