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
 * {@code messung.*} des Manifests; 7 die Importregel des Steuerhauses (wie Prüfstein 5 des SteuerhausCheck).
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
        System.out.println("MessungCheck (DB1): " + manifest);
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
        if (quellen != null) {
            int v0 = SteuerhausCheck.verstoesse;
            SteuerhausCheck.importregel(quellen);
            pruefe(SteuerhausCheck.verstoesse == v0, "Prüfstein 7: Importregel des Steuerhauses mit messung", SteuerhausCheck.verstoesse == v0 ? "wie oben" : "Verstoß, siehe oben");
        }
        System.out.println(verstoesse == 0 ? "MessungCheck: " + steine + " Prüfsteine, alles in Ordnung" : "MessungCheck: " + verstoesse + " von " + steine + " Prüfsteinen verletzt");
        System.exit(verstoesse == 0 ? 0 : 1);
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
