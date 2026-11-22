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
 * Sturmfluten über alle Pegel, 10 die Liste der historischen Dateien des DWD, 11 die Abdeckung eines Archivs. Seit DB3
 * (Werkbuch HalligHooge 171): 12 die Datei Modell gegen Messung (hin und zurück, Kennung), 13 die Kennzahlen an einem
 * Handbeispiel, 14 das Raster des Modells und der Messung, 15 die Schlüssel {@code guete.*}. Seit DB4 (Werkbuch HalligHooge
 * 173): 12 mit Wind und Mittelwasser (und einer Zeile im Format von DB3), 13 mit dem Bias ohne Mittelwasser, 14 mit dem Wind
 * (Richtung, Zelle am Rand), 15 mit den Windstationen, 16 die Kennzahlen des Winds an einem Handbeispiel. Seit DB5 (Werkbuch
 * HalligHooge 176): 1 mit 19 Tabellen, 17 die neuen Tiden (Auswahl nach Rayleigh, Knotenfaktoren, Analyse einer Probereihe), 18 die
 * Regeln für Prüfzeit und Annahme an Handbeispielen, 19 das Mittelwasser ohne Windstau, 20 (mit {@code --gebiet}) die Analyse nach
 * dem Umzug nach gezeiten.Gezeitenanalyse gegen die Zahlen von Z1.
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
        System.out.println("MessungCheck (DB1 bis DB5): " + manifest);
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
        gueteDatei();
        gueteKennzahlen();
        gueteRaster();
        gueteSchluessel(m);
        windzahlen();
        neueTiden();                                                                  // seit DB5
        wachsen();
        mittelwasser();
        if (gebiet != null) umzug(gebiet);
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

    // ------------------------------------------------------------------ 12–15 seit DB3

    static com.dan.steuerhaus.Guete probe(float aufschlag) {
        Instant von = Instant.parse("2026-10-25T06:00:00Z");
        float[] w = new float[13];
        for (int i = 0; i < w.length; i++) w[i] = (float) (1.5 * Math.sin(i / 2.0) + aufschlag);
        w[4] = Float.NaN;
        var l = new com.dan.steuerhaus.Guete.Lauf("sturm", List.of("sturm", "seegang"), "_p6/archiv", "Probe; mit = und Semikolon", Instant.parse("2026-10-23T00:00:00Z"), von,
                von.plusSeconds(3600), 300, "", "tide EOT20", "hydro 0.7");
        var e = new com.dan.steuerhaus.Guete.Eintrag("sturm", "WITTDÜN", "Wittdün", 460231.9, 6053989.6, "G2", 79341, 112.6, -5.392,
                new com.dan.steuerhaus.Guete.Reihe(von, 300, w), List.of(new com.dan.steuerhaus.Guete.Marke('H', von.plusSeconds(900), 1.5)),
                com.dan.steuerhaus.Guete.Reihe.LEER, List.of(), List.of(), com.dan.steuerhaus.Guete.Kennzahlen.LEER);
        l = l.mitKennung(com.dan.steuerhaus.Guete.kennung(l, List.of(e)));
        var g = new com.dan.steuerhaus.Guete("Probe", Instant.parse("2026-11-22T18:00:00Z"), 24, List.of(l), List.of(e));
        var k = new com.dan.steuerhaus.Guete.Kennzahlen(7, 0.12, -0.05, 0.9876, 0.9912, 1, 15.0, -0.2, 0.2, 1, 0.8, 2.5, von.plusSeconds(900), 4.293, LocalDate.of(2013, 12, 6), 3.752,
                von.plusSeconds(1800), 0.131, 0.45, 0.081);
        var p = new com.dan.steuerhaus.Guete.Paar(von.plusSeconds(900), 1.5, von.plusSeconds(0), 1.7, 0.6, 1.1);
        var mess = new com.dan.steuerhaus.Guete.Reihe(von.plusSeconds(300), 600, new float[] {0.1f, Float.NaN, -0.25f});
        var wi = new com.dan.steuerhaus.Guete.Wind("sturm", "03032", "List", 462429.0, 6096173.3, 10, "G2", 157422, 102.1, new com.dan.steuerhaus.Guete.Reihe(von, 300,
                new float[] {14.2f, 15.1f, Float.NaN}), new com.dan.steuerhaus.Guete.Reihe(von, 300, new float[] {250f, 359.5f, Float.NaN}), new com.dan.steuerhaus.Guete.Reihe(
                von.plusSeconds(300), 600, new float[] {16.4f}), new com.dan.steuerhaus.Guete.Reihe(von.plusSeconds(300), 600, new float[] {245f}),
                new com.dan.steuerhaus.Guete.Windzahlen(108, 0.89, -1.8, 2.0, 8.2, 94, 0.88, -2.0, 7.9, 16.0, 19.7));
        return new com.dan.steuerhaus.Guete(g.quelle, g.erstellt, g.einschwingen, g.laeufe, List.of(e.mit(mess, List.of(new com.dan.steuerhaus.Guete.Marke('H', von, 1.7)), List.of(p), k)),
                List.of(wi));
    }

    static void gueteDatei() throws Exception {
        List<String> f = new ArrayList<>();
        File d = Files.createTempDirectory("guete").toFile(), gf = new File(d, "Probe.guete");
        var g1 = probe(0);
        if (!g1.schreibe(gf)) f.add("erstes Schreiben");
        var r1 = com.dan.steuerhaus.Guete.lies(gf);
        if (!r1.inhalt().equals(g1.inhalt())) f.add("hin und zurück");
        var e = r1.eintrag("sturm", "WITTDÜN");
        if (e == null) f.add("Eintrag fehlt");
        else {
            if (!Float.isNaN(e.modell().werte()[4]) || e.modell().belegt() != 12 || Math.abs(e.modell().wert(0) - 0) > 1e-6) f.add("Reihe des Modells " + e.modell().belegt());
            if (Math.abs(e.messung().bei(Instant.parse("2026-10-25T06:25:00Z")) + 0.25) > 1e-6 || !Double.isNaN(e.messung().bei(Instant.parse("2026-10-25T06:20:00Z"))))
                f.add("Messung bei()");
            if (e.paare().size() != 1 || Math.abs(e.paare().get(0).versatzMin() - 15) > 1e-9 || Math.abs(e.paare().get(0).stauModell() - 0.9) > 1e-9) f.add("Paar " + e.paare());
            if (!e.kennzahlen().mitKennwert() || !e.kennzahlen().kennwertTag().equals(LocalDate.of(2013, 12, 6)) || Math.abs(e.kennzahlen().skill() - 0.9876) > 1e-9) f.add("Kennzahlen");
        }
        if (!r1.laeufe.get(0).name().equals("Probe, mit - und Semikolon") || !r1.laeufe.get(0).szenarien().equals(List.of("sturm", "seegang"))) f.add("Lauf " + r1.laeufe.get(0));
        if (r1.laufVon("seegang") == null || r1.laufVon("ruhig") != null) f.add("laufVon");
        // seit DB4: Wind und Mittelwasser
        var w = r1.wind("sturm", "03032");
        if (w == null || r1.winde("sturm").size() != 1) f.add("Wind fehlt");
        else {
            if (Math.abs(w.dd().wert(1) - 359.5) > 1e-6 || !Float.isNaN(w.ff().werte()[2]) || Math.abs(w.ffMessung().wert(0) - 16.4) > 1e-4 || Math.abs(w.zahlen().schub() - 0.89 * 0.89) > 1e-9
                    || w.zahlen().sturmWerte() != 94) f.add("Wind " + w);
        }
        if (e != null && (Math.abs(e.kennzahlen().mittelwasser() - 0.131) > 1e-9 || Math.abs(e.kennzahlen().biasOhneMw() - 0.081) > 1e-9)) f.add("Mittelwasser " + e.kennzahlen());
        // eine Zeile der Kennzahlen im Format von DB3 (17 Felder) bleibt lesbar
        String alt = java.nio.file.Files.readString(gf.toPath(), StandardCharsets.UTF_8).replaceAll("(?m)^(kennzahlen\\.[^=]+= (?:[^;]*;){16}[^;\n]*);[^\n]*$", "$1");
        File af = new File(d, "Alt.guete");
        java.nio.file.Files.writeString(af.toPath(), alt, StandardCharsets.UTF_8);
        var ra = com.dan.steuerhaus.Guete.lies(af).eintrag("sturm", "WITTDÜN");
        if (ra == null || !Double.isNaN(ra.kennzahlen().mittelwasser()) || Math.abs(ra.kennzahlen().skill() - 0.9876) > 1e-9) f.add("Format DB3 " + (ra == null ? "fehlt" : ra.kennzahlen()));
        long m1 = gf.lastModified();
        var g2 = new com.dan.steuerhaus.Guete(g1.quelle, Instant.parse("2026-11-22T19:00:00Z"), 24, g1.laeufe, g1.eintraege, g1.winde);
        if (g2.schreibe(gf) || gf.lastModified() != m1) f.add("gleicher Inhalt hat geschrieben");
        String k0 = probe(0).laeufe.get(0).kennung(), k1 = probe(0.001f).laeufe.get(0).kennung();
        if (k0.equals(k1) || k0.length() != 16) f.add("Kennung " + k0 + " / " + k1);
        pruefe(f.isEmpty(), "Prüfstein 12: Datei Modell gegen Messung (hin und zurück, Lücke, bei(), Paar, Kennwert, Wind, Mittelwasser, Format DB3, gleicher Inhalt schreibt nicht, Kennung je Wert)",
                f.isEmpty() ? "1 Lauf, Kennung " + k0 + ", " + gf.length() + " Byte" : f.toString());
    }

    static void gueteKennzahlen() {
        List<String> f = new ArrayList<>();
        java.time.ZoneId z = java.time.ZoneId.of("Europe/Berlin");
        double[] m = {1, 2, 3, 2}, o = {0.5, 2, 2.5, 2};
        Instant t = Instant.parse("2013-12-05T23:30:00Z");                   // in Berlin schon der 6.12.
        var reihe = new com.dan.steuerhaus.Guete.Reihe(Instant.parse("2013-12-05T06:00:00Z"), 3600, new float[] {1, 3.9f, 2, Float.NaN, 1, 2, 3, 2, 1, 2, 3, 4, 3, 2, 1, 2, 3, 2, 1, 2,
                3, 2, 1, 2, 3, 2, 1, 2, 3, 2, 1, 2, 3, 2, 1, 2, 3});
        var marken = List.of(new com.dan.steuerhaus.Guete.Marke('H', Instant.parse("2013-12-05T07:00:00Z"), 3.9), new com.dan.steuerhaus.Guete.Marke('H', t, 3.75),
                new com.dan.steuerhaus.Guete.Marke('N', Instant.parse("2013-12-06T05:00:00Z"), 0.5), new com.dan.steuerhaus.Guete.Marke('H', Instant.parse("2013-12-06T12:00:00Z"), 3.3));
        var paare = List.of(new com.dan.steuerhaus.Guete.Paar(Instant.parse("2026-10-25T12:30:00Z"), 2.0, Instant.parse("2026-10-25T12:00:00Z"), 2.2, 0.9, 1.3),
                new com.dan.steuerhaus.Guete.Paar(Instant.parse("2026-10-26T00:50:00Z"), 3.0, Instant.parse("2026-10-26T01:00:00Z"), 3.3, 1.0, 2.3),
                new com.dan.steuerhaus.Guete.Paar(Instant.parse("2026-10-26T13:00:00Z"), 1.6, Instant.parse("2026-10-26T13:00:00Z"), 1.5, 1.2, 0.3));
        var k = com.dan.steuerhaus.messung.Guetebau.kennzahlen(m, o, paare, reihe, marken, 4.293, LocalDate.of(2013, 12, 6), 0.5, z);
        if (k.werte() != 4 || Math.abs(k.rmse() - Math.sqrt(0.125)) > 1e-12 || Math.abs(k.bias() - 0.25) > 1e-12) f.add("RMSE, Bias " + k);
        if (Math.abs(k.skill() - com.dan.core.pruef.Statistik.willmott(m, o)) > 1e-12 || !(k.skill() > 0.8 && k.skill() < 1)) f.add("Skill " + k.skill());
        if (k.hw() != 3 || Math.abs(k.hwZeit() - (30 - 10 + 0) / 3.0) > 1e-9 || Math.abs(k.hwHoehe() - (-0.2 - 0.3 + 0.1) / 3) > 1e-9) f.add("Hochwasser " + k.hw() + " " + k.hwZeit() + " " + k.hwHoehe());
        // Stau ab 0,5 m: die ersten beiden Paare; Modell (2,0−0,9) + (3,0−1,0) = 3,1, gemessen 1,3 + 2,3 = 3,6
        if (k.stauPaare() != 2 || Math.abs(k.stauAnteil() - 3.1 / 3.6) > 1e-9) f.add("Windstau " + k.stauPaare() + " " + k.stauAnteil());
        if (Math.abs(k.max() - 4) > 1e-6 || !k.maxZeit().equals(Instant.parse("2013-12-05T17:00:00Z"))) f.add("Höchstes " + k.max() + " " + k.maxZeit());
        // am 6.12. (Berlin): das Hochwasser um 23:30 UTC (3,75) und 12:00 UTC (3,30), nicht das vom 5.12. mit 3,9
        if (!k.mitKennwert() || Math.abs(k.amTag() - 3.75) > 1e-9 || !k.amTagZeit().equals(t)) f.add("am Tag " + k.amTag() + " " + k.amTagZeit());
        // seit DB4: Bias ohne Mittelwasser = Bias + (Mittelwasser − Mittel im Antrieb): 0,25 + (0,13 − 0,05) = 0,33
        var k3 = com.dan.steuerhaus.messung.Guetebau.kennzahlen(m, o, paare, reihe, marken, 4.293, LocalDate.of(2013, 12, 6), 0.5, z, 0.13, 0.42, 0.05);
        if (Math.abs(k3.biasOhneMw() - 0.33) > 1e-12 || Math.abs(k3.tidemittel() - 0.42) > 1e-12 || !Double.isNaN(k.biasOhneMw())) f.add("Mittelwasser " + k3.biasOhneMw());
        var lm = new com.dan.steuerhaus.Guete.Lauf("x", List.of(), "", "", null, null, null, 300, "", "stufen-G0, tide-EOT20 und Gleichgewichtstide, Anlauf 3.0 h, Mittel -0.15 m, wetter-ERA5", "");
        if (Math.abs(lm.mittel() + 0.15) > 1e-12 || new com.dan.steuerhaus.Guete.Lauf("x", List.of(), "", "", null, null, null, 300, "", "ohne", "").mittel() != 0) f.add("Mittel im Antrieb " + lm.mittel());
        var k2 = com.dan.steuerhaus.messung.Guetebau.kennzahlen(new double[0], new double[0], List.of(), reihe, marken, 4.293, LocalDate.of(2013, 12, 9), 0.5, z);
        if (k2.mitKennwert() || !Double.isNaN(k2.kennwert()) || k2.werte() != 0 || !Double.isNaN(k2.rmse()) || !Double.isNaN(k2.stauAnteil())) f.add("Tag außerhalb " + k2);
        pruefe(f.isEmpty(), "Prüfstein 13: Kennzahlen an einem Handbeispiel (RMSE, Bias, Skill, Versatz der Hochwasser, Windstau ab 0,5 m, Höchstes, HThw am Tag in MEZ, ohne Deckung kein Kennwert, Bias ohne Mittelwasser, Mittel im Antrieb)",
                f.isEmpty() ? String.format(Locale.GERMAN, "RMSE %.2f, Bias %.2f, Skill %.4f, HW %d (%+.1f min, %+.3f m), Windstau %.0f %% aus %d, am 6.12. %.2f m", k.rmse(), k.bias(), k.skill(),
                        k.hw(), k.hwZeit(), k.hwHoehe(), 100 * k.stauAnteil(), k.stauPaare(), k.amTag()) : f.toString());
    }

    static void gueteRaster() {
        List<String> f = new ArrayList<>();
        Instant start = Instant.parse("2026-10-23T00:00:00Z"), von = Instant.parse("2026-10-25T06:00:00Z"), bis = von.plusSeconds(3600);
        double v0 = von.getEpochSecond() - start.getEpochSecond();
        // Stände: vor dem Einschwingen, genau, 12 s daneben, trocken, 40 s daneben (zählt nicht), außerhalb −6 … 8 m, nach dem Ende
        double[] t = {v0 - 300, v0, v0 + 312, v0 + 600, v0 + 940, v0 + 1200, v0 + 1500, v0 + 3600, v0 + 3900};
        double[] eta = {1, 1.1, 1.2, Double.NaN, 1.4, 9.5, 1.6, 1.7, 1.8};
        var g = com.dan.core.gitter.Gitter.utm("G2", 32, 428000, 6011000, 200, 370, 430);
        var mk = List.of(new com.dan.lauf.Pegelreihe.Marke(com.dan.lauf.Pegelreihe.Art.HOCHWASSER, v0 - 300, 2.0), new com.dan.lauf.Pegelreihe.Marke(com.dan.lauf.Pegelreihe.Art.NIEDRIGWASSER, v0 + 1500, 0.4));
        int zelle = 160 + 370 * 214;
        var e = com.dan.steuerhaus.ausgabe.Modellauszug.eintrag("sturm", "WITTDÜN", "Wittdün", 460231.9, 6053989.6, g, zelle, -5.4, t, eta, mk, start, von, bis, 300);
        float[] w = e.modell().werte();
        String ist = java.util.Arrays.toString(w);
        if (w.length != 13 || Math.abs(w[0] - 1.1) > 1e-6 || Math.abs(w[1] - 1.2) > 1e-6 || !Float.isNaN(w[2]) || !Float.isNaN(w[3]) || !Float.isNaN(w[4]) || Math.abs(w[5] - 1.6) > 1e-6
                || Math.abs(w[12] - 1.7) > 1e-6) f.add("Raster " + ist);
        if (e.marken().size() != 1 || e.marken().get(0).art() != 'N' || !e.marken().get(0).zeit().equals(von.plusSeconds(1500))) f.add("Marken " + e.marken());
        double ab = Math.hypot(g.x(160) - 460231.9, g.y(214) - 6053989.6);
        if (Math.abs(e.abstand() - ab) > 1e-9 || !e.gitter().equals("G2")) f.add("Abstand " + e.abstand());
        // seit DB4: Wind auf dem Raster; Richtung woher (u nach Osten, v nach Norden): aus Nord, Ost, Süd; 40 s daneben und ein
        // fehlendes v zählen nicht; Zelle am Rand bis 10 km
        var st = new com.dan.steuerhaus.ausgabe.Modellauszug.Station("List", "03032", 8.4125, 55.011, 10);
        double[] wu = {0, 0, -10, 0, 7, 0, 0, 0, 0}, wv = {0, -10, 0, 10, 0, Double.NaN, 0, 0, 0};
        var wi = com.dan.steuerhaus.ausgabe.Modellauszug.wind("sturm", st, 462429, 6096173, g, 157422, t, wu, wv, start, von, bis, 300);
        float[] wf = wi.ff().werte(), wd = wi.dd().werte();
        if (Math.abs(wf[0] - 10) > 1e-6 || Math.abs(wd[0] - 0) > 1e-6 || Math.abs(wd[1] - 90) > 1e-6 || Math.abs(wd[2] - 180) > 1e-6 || !Float.isNaN(wf[3]) || !Float.isNaN(wf[4])
                || wf[5] != 0 || !Float.isNaN(wf[6]) || Math.abs(wf[12]) > 1e-6)
            f.add("Wind " + java.util.Arrays.toString(wf) + " " + java.util.Arrays.toString(wd));
        if (Math.abs(com.dan.steuerhaus.ausgabe.Modellauszug.richtung(7, 0) - 270) > 1e-9) f.add("Richtung aus Westen " + com.dan.steuerhaus.ausgabe.Modellauszug.richtung(7, 0));
        int zh = com.dan.steuerhaus.ausgabe.Modellauszug.zelle(g, 427677.5, 6003559.6, 7.892, 54.175), zf = com.dan.steuerhaus.ausgabe.Modellauszug.zelle(g, 415000, 6003559.6, 0, 0);
        if (zh != 0 || zf != -1) f.add("Zelle am Rand " + zh + " / " + zf);
        long m0 = com.dan.steuerhaus.messung.Guetebau.mittelAb(von.getEpochSecond()), m1 = com.dan.steuerhaus.messung.Guetebau.mittelAb(von.getEpochSecond() + 300);
        if (m0 != von.getEpochSecond() + 300 || m1 != m0 || com.dan.steuerhaus.messung.Guetebau.mittelAb(von.getEpochSecond() + 301) != m0 + 600) f.add("Raster der Mittel");
        pruefe(f.isEmpty(), "Prüfstein 14: Raster des Modells (Einschwingen, ±30 s, trocken, −6 … 8 m, Ende), Marken im Zeitraum, Abstand der Zelle, Raster der Mittel (:05, :15 …), Wind (Richtung woher, Zelle am Rand bis 10 km)",
                f.isEmpty() ? ist + String.format(Locale.GERMAN, ", Abstand %.1f m", e.abstand()) : f.toString());
    }

    static void gueteSchluessel(Manifest m) {
        List<String> f = new ArrayList<>();
        var ga = com.dan.steuerhaus.Guete.Auftrag.lies(m);
        if (ga == null) f.add("guete.datei fehlt");
        else {
            if (ga.pegel().size() != 7) f.add(ga.pegel().size() + " Pegel statt 7: " + ga.pegel());
            if (ga.modell() == null) f.add("guete.modell fehlt");
            if (!(ga.umkreis() >= 200 && ga.umkreis() <= 1000)) f.add("Umkreis " + ga.umkreis());
            if (!(ga.einschwingen() >= 0) || !(ga.stau() > 0)) f.add("Einschwingen, Stau " + ga.einschwingen() + " " + ga.stau());
            if (!ga.datei().getParentFile().equals(m.relativ(".").getAbsoluteFile().toPath().normalize().toFile())) f.add("guete.datei nicht neben dem Manifest: " + ga.datei());
            if (ga.wind().size() != 2 || !(ga.windMin() > 0 && ga.windSturm() > ga.windMin()) || !(ga.mittelwasserTage() >= 7)) f.add("Wind " + ga.wind() + " " + ga.windMin() + "/"
                    + ga.windSturm() + ", Mittelwasser " + ga.mittelwasserTage() + " Tage");
            File gb = m.relativ("../../daten/gebiet");
            if (com.dan.core.pruef.Pegel.da(gb)) {
                try {
                    var pg = com.dan.core.pruef.Pegel.lies(gb);
                    for (String p : ga.pegel()) if (!pg.containsKey(p)) f.add("Pegel " + p + " nicht im Gebiet");
                    var ws = com.dan.steuerhaus.ausgabe.Modellauszug.windstationen(gb);
                    for (String k : ga.wind()) if (!ws.containsKey(k)) f.add("Windstation " + k + " nicht im Gebiet");
                } catch (Exception x) {
                    f.add("Gebiet: " + x.getMessage());
                }
            }
        }
        pruefe(f.isEmpty(), "Prüfstein 15: Schlüssel guete.* (7 Pegel und 2 Windstationen im Gebiet, Umkreis, Einschwingen, Windstau, Wind ab und Sturm, Mittelwasser, Dateien)",
                f.isEmpty() ? ga.pegel() + ", Wind " + ga.wind() + ", " + Math.round(ga.umkreis()) + " m, " + Math.round(ga.einschwingen()) + " h, " + ga.stau() + " m, " + ga.windMin() + "/"
                        + ga.windSturm() + " m/s, " + Math.round(ga.mittelwasserTage()) + " Tage, " + ga.datei().getName() : f.toString());
    }

    // ------------------------------------------------------------------ 17–20 seit DB5

    static List<String> namen(List<Partialtide> l) {
        List<String> n = new ArrayList<>();
        for (Partialtide p : l) n.add(p.name);
        return n;
    }

    static void neueTiden() {
        List<String> f = new ArrayList<>();
        List<Partialtide> k = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.tiden(com.dan.steuerhaus.gezeiten.Gezeitenanalyse.KANDIDATEN);
        Object[][] faelle = {{30.0, List.of("M2", "N2", "2SM2", "2MS6", "MK3", "M8"), List.of("MU2", "2N2", "L2", "K2", "P1", "NU2", "MSN6", "T2")},
                {41.0, List.of("MU2", "L2", "MSN6"), List.of("2N2", "NU2", "K2", "P1")},
                {210.0, List.of("K2", "P1", "NU2", "2N2"), List.of("T2")}};
        for (Object[] fa : faelle) {
            List<String> w = namen(com.dan.steuerhaus.gezeiten.Gezeitenanalyse.waehlen(k, (Double) fa[0] * 24, null));
            for (Object n : (List<?>) fa[1]) if (!w.contains(n)) f.add(fa[0] + " Tage ohne " + n);
            for (Object n : (List<?>) fa[2]) if (w.contains(n)) f.add(fa[0] + " Tage mit " + n);
        }
        String[] jahr = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.kandidaten(370 * 24);
        List<String> wj = namen(com.dan.steuerhaus.gezeiten.Gezeitenanalyse.waehlen(com.dan.steuerhaus.gezeiten.Gezeitenanalyse.tiden(jahr), 370 * 24, null));
        for (String n : new String[] {"T2", "SA", "SSA"}) if (!wj.contains(n)) f.add("370 Tage ohne " + n);
        if (com.dan.steuerhaus.gezeiten.Gezeitenanalyse.kandidaten(300 * 24).length != com.dan.steuerhaus.gezeiten.Gezeitenanalyse.KANDIDATEN.length) f.add("Sa, Ssa vor einem Jahr");
        // Knotenfaktoren der Flachwassertiden aus ihren Eltern
        double[] m2 = Partialtide.M2.fu(30), k1 = Partialtide.K1.fu(30), mk3 = Partialtide.MK3.fu(30), sm2 = Partialtide.ZWEI_SM2.fu(30), m8 = Partialtide.M8.fu(30);
        if (Math.abs(mk3[0] - m2[0] * k1[0]) > 1e-12 || Math.abs(mk3[1] - m2[1] - k1[1]) > 1e-12) f.add("MK3 nicht M2 · K1");
        if (Math.abs(sm2[0] - m2[0]) > 1e-12 || Math.abs(sm2[1] + m2[1]) > 1e-12) f.add("2SM2 nicht 2·S2 − M2");
        if (Math.abs(m8[0] - Math.pow(m2[0], 4)) > 1e-12) f.add("M8 nicht M2⁴");
        // Analyse einer Probereihe: 60 Tage, 10 min, mit Flachwassertiden, Rauschen ± 1 cm und einem Sturm über 2 Tage
        List<Vorhersage.Konstante> soll = List.of(new Vorhersage.Konstante(Partialtide.M2, 1.4, 350), new Vorhersage.Konstante(Partialtide.S2, 0.45, 37),
                new Vorhersage.Konstante(Partialtide.N2, 0.17, 337), new Vorhersage.Konstante(Partialtide.K1, 0.05, 20), new Vorhersage.Konstante(Partialtide.O1, 0.10, 240),
                new Vorhersage.Konstante(Partialtide.M4, 0.15, 200), new Vorhersage.Konstante(Partialtide.MS4, 0.10, 220), new Vorhersage.Konstante(Partialtide.ZWEI_MS6, 0.08, 60),
                new Vorhersage.Konstante(Partialtide.ZWEI_SM2, 0.05, 250), new Vorhersage.Konstante(Partialtide.MK3, 0.02, 100));
        Vorhersage v = new Vorhersage(0.1, soll);
        int n = 60 * 144;
        long s0 = Instant.parse("2027-01-01T00:05:00Z").getEpochSecond();
        double[] t = new double[n], y = new double[n];
        java.util.Random r = new java.util.Random(5);
        for (int i = 0; i < n; i++) {
            t[i] = s0 + 600.0 * i;
            double d = (t[i] - s0) / 86_400.0 - 20;
            y[i] = v.wert(Instant.ofEpochSecond((long) t[i])) + 0.6 * Math.exp(-d * d) + 0.01 * (2 * r.nextDouble() - 1);
        }
        var an = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.robust(t, y, 600, 0.25, com.dan.steuerhaus.gezeiten.Gezeitenanalyse.KANDIDATEN);
        StringBuilder ist = new StringBuilder();
        for (Vorhersage.Konstante kk : soll) {
            var x = an.ergebnis().tide(kk.tide().name);
            if (x == null) {
                f.add(kk.tide().name + " fehlt");
                continue;
            }
            double da = x.amplitude() - kk.amplitude(), dg = com.dan.core.astro.FAngle.norm180(x.phaseGrad() - kk.phaseGrad());
            if (Math.abs(da) > 0.005 || (kk.amplitude() >= 0.04 && Math.abs(dg) > 5)) f.add(String.format(Locale.ROOT, "%s %+.4f m %+.1f°", kk.tide().name, da, dg));
            if (kk.tide() == Partialtide.ZWEI_MS6 || kk.tide() == Partialtide.ZWEI_SM2)
                ist.append(String.format(Locale.GERMAN, "%s %.3f m %.1f° (soll %.3f m %.0f°); ", kk.tide().name, x.amplitude(), x.phaseGrad(), kk.amplitude(), kk.phaseGrad()));
        }
        if (Math.abs(an.ergebnis().mittel() - 0.1) > 0.01) f.add(String.format(Locale.ROOT, "Z0 %.3f", an.ergebnis().mittel()));
        if (an.genutzt() > n - 144) f.add("Sturm nicht ausgelassen (" + an.genutzt() + " von " + n + ")");
        pruefe(f.isEmpty(), "Prüfstein 17: neue Tiden (Auswahl nach Rayleigh mit den größeren ausgefallenen, Sa und Ssa erst ab einem Jahr, Knotenfaktoren aus den Eltern, "
                + "Analyse einer Probereihe mit Sturm)", f.isEmpty() ? ist.toString() + an.analysiert() + " Tiden, " + an.genutzt() + " von " + n + " Werten ohne Windstau" : f.toString());
    }

    static void wachsen() {
        List<String> f = new ArrayList<>();
        long d = 86_400;
        long[] a = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.pruefzeit(0, 41 * d, 30 * d), b = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.pruefzeit(0, 50 * d, 30 * d),
                c = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.pruefzeit(0, 81 * d, 30 * d), e = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.pruefzeit(0, 35 * d, 30 * d),
                g = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.pruefzeit(0, 41 * d, Long.MIN_VALUE);
        if (a == null || a[0] != 30 * d || a[1] != 41 * d) f.add("41 Tage: " + java.util.Arrays.toString(a));
        if (b == null || b[0] != 30 * d) f.add("50 Tage: " + java.util.Arrays.toString(b));
        if (c == null || c[0] != 51 * d) f.add("81 Tage: " + java.util.Arrays.toString(c));
        if (e != null) f.add("35 Tage: " + java.util.Arrays.toString(e));
        if (g == null || g[0] != 30 * d) f.add("ohne geltende: " + java.util.Arrays.toString(g));
        double[] alt = {40, 0, 18, 0, 0.158};
        Object[][] faelle = {{new double[] {40, 0, 12, 0, 0.140}, true, "Höhe und Zeit besser"}, {new double[] {40, 0, 18, 0, 0.164}, false, "Höhe 6 mm schlechter"},
                {new double[] {40, 0, 17.1, 0, 0.158}, false, "Zeit nur 0,9 min besser"}, {new double[] {40, 0, 16.9, 0, 0.162}, true, "Zeit besser, Höhe 4 mm schlechter"},
                {new double[] {40, 0, 10, 0, 0.150}, true, "geltend ohne Prüfwerte"}, {new double[] {9, 0, 10, 0, 0.10}, false, "nur 9 Hoch- und Niedrigwasser"}};
        for (int i = 0; i < faelle.length; i++) {
            Object[] fa = faelle[i];
            boolean ist = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.besser((double[]) fa[0], i == 4 ? null : alt);
            if (ist != (Boolean) fa[1]) f.add(fa[2] + ": " + ist);
        }
        pruefe(f.isEmpty(), "Prüfstein 18: Prüfzeit (die jüngsten 30 Tage, nicht vor dem Ende der geltenden Analyse, 30 Tage Analyse davor, mindestens 7) und Annahme "
                + "(0,5 cm oder 1 min besser, keins schlechter)", f.isEmpty() ? "5 Prüfzeiten, 6 Entscheidungen wie erwartet" : f.toString());
    }

    static void mittelwasser() {
        List<String> f = new ArrayList<>();
        Vorhersage v = new Vorhersage(0.15, List.of(new Vorhersage.Konstante(Partialtide.M2, 1.4, 350), new Vorhersage.Konstante(Partialtide.S2, 0.45, 37),
                new Vorhersage.Konstante(Partialtide.M4, 0.15, 200)));
        int n = 40 * 144;
        long s0 = Instant.parse("2026-10-12T14:35:00Z").getEpochSecond();
        double[] t = new double[n], y = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = s0 + 600.0 * i;
            double d = ((t[i] - s0) / 86_400.0 - 14) / 1.5;
            y[i] = v.wert(Instant.ofEpochSecond((long) t[i])) + 0.8 * Math.exp(-d * d);
            if (i % 97 == 0) y[i] = Double.NaN;
        }
        double[] m = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.mittelOhneStau(t, y, 600, v, 0.25, s0, s0 + 40 * 86_400L);
        if (Math.abs(m[0] - 0.15) > 0.01) f.add(String.format(Locale.ROOT, "ohne Windstau %.3f", m[0]));
        if (!(m[1] > 0.19)) f.add(String.format(Locale.ROOT, "mit Sturm %.3f", m[1]));
        if (!(m[2] < m[3] * 0.95)) f.add("Sturm nicht ausgelassen");
        var mo = com.dan.steuerhaus.gezeiten.Gezeitenanalyse.monatsmittel(t, y, 600, v, 0.25);
        if (mo.size() != 2) f.add(mo.size() + " Monate statt 2");
        for (var x : mo) if (Math.abs(x.mittel() - 0.15) > 0.02) f.add(String.format(Locale.ROOT, "Monat %s: %.3f", Instant.ofEpochSecond(x.beginn()), x.mittel()));
        pruefe(f.isEmpty(), "Prüfstein 19: Mittelwasser ohne Windstau (Z₀ 0,15 m, Sturm 0,8 m über 3 Tage, Lücken) – über alles auf 1 cm, je Monat auf 2 cm (die Ränder des Sturms bleiben unter 0,25 m drin)",
                f.isEmpty() ? String.format(Locale.GERMAN, "ohne Windstau %.3f m, mit Sturm %.3f m; %d von %d Werten; Monate %s", m[0], m[1], (int) m[2], (int) m[3],
                        mo.stream().map(x -> String.format(Locale.GERMAN, "%.3f", x.mittel())).toList()) : f.toString());
    }

    static void umzug(File gebiet) throws Exception {
        List<String> f = new ArrayList<>();
        java.util.Map<String, com.dan.core.pruef.Pegel> pg = com.dan.core.pruef.Pegel.lies(gebiet);
        com.dan.core.pruef.Pegel p = pg.get("PELLWORM_ANLEGER");
        String ist = "–";
        if (p == null) f.add("Pegel Pellworm fehlt im Gebiet");
        else {
            var a = com.dan.steuerhaus.ausgabe.Gezeitenrechnung.robust(p, 0.25);
            ist = String.format(Locale.GERMAN, "%d von %d Werten, Rest %.4f m, %d Tiden", a.genutzt(), a.gesamt(), a.ergebnis().restRms(), a.ergebnis().tiden().size());
            if (a.genutzt() != 21536 || a.gesamt() != 43188 || Math.abs(a.ergebnis().restRms() - 0.1696) > 0.00006 || a.ergebnis().tiden().size() != 14) f.add(ist);
        }
        pruefe(f.isEmpty(), "Prüfstein 20: Analyse der Ausgabe nach dem Umzug nach gezeiten.Gezeitenanalyse gegen Z1 (Pellworm, 30 Tage: 21 536 von 43 188, Rest 0,1696 m, 14 Tiden)",
                f.isEmpty() ? ist : f.toString());
    }

    static void windzahlen() {
        List<String> f = new ArrayList<>();
        // Modell, Messung (auf 10 m), Richtungen: zwei unter 5 m/s (nur Bias, RMSE), drei darüber, zwei davon Sturm ab 15 m/s
        double[] fm = {3, 4, 9, 14, 18, Double.NaN}, fg = {4, 3, 10, 16, 20, 12}, dm = {200, 210, 10, 250, 260, 0}, dg = {180, 300, 350, 240, 250, 0};
        var k = com.dan.steuerhaus.messung.Guetebau.windzahlen(fm, dm, fg, dg, 5, 15);
        if (k.werte() != 5 || Math.abs(k.bias() - (-1 + 1 - 1 - 2 - 2) / 5.0) > 1e-12) f.add("Werte, Bias " + k);
        if (Math.abs(k.faktor() - 41.0 / 46) > 1e-12 || Math.abs(k.schub() - Math.pow(41.0 / 46, 2)) > 1e-12) f.add("Faktor " + k.faktor());
        // Richtung gewichtet: 10 gegen 350 = +20 (Gewicht 10), +10 (16), +10 (20) → (200 + 160 + 200) / 46
        if (Math.abs(k.richtung() - 560.0 / 46) > 1e-9) f.add("Richtung " + k.richtung());
        if (k.sturmWerte() != 2 || Math.abs(k.sturmFaktor() - 32.0 / 36) > 1e-12 || Math.abs(k.sturmRichtung() - 10) > 1e-9 || Math.abs(k.sturmBias() + 2) > 1e-12) f.add("Sturm " + k);
        if (Math.abs(k.maxModell() - 18) > 1e-12 || Math.abs(k.maxMessung() - 20) > 1e-12) f.add("höchster Wind " + k.maxModell() + "/" + k.maxMessung());
        if (Math.abs(com.dan.steuerhaus.messung.Guetebau.winkel(10, 350) - 20) > 1e-9 || Math.abs(com.dan.steuerhaus.messung.Guetebau.winkel(350, 10) + 20) > 1e-9) f.add("Winkel");
        double a20 = com.dan.steuerhaus.messung.Guetebau.auf10m(10, 20), a10 = com.dan.steuerhaus.messung.Guetebau.auf10m(10, 10);
        if (Math.abs(a10 - 10) > 1e-12 || Math.abs(a20 - 10 * Math.log(10 / 2e-4) / Math.log(20 / 2e-4)) > 1e-12) f.add("auf 10 m " + a20);
        var leer = com.dan.steuerhaus.messung.Guetebau.windzahlen(new double[0], new double[0], new double[0], new double[0], 5, 15);
        if (leer.werte() != 0 || !Double.isNaN(leer.faktor()) || !Double.isNaN(leer.richtung())) f.add("leer " + leer);
        pruefe(f.isEmpty(), "Prüfstein 16: Kennzahlen des Winds an einem Handbeispiel (Faktor und Richtung ab 5 m/s, Kreismittel über Nord, Sturm ab 15 m/s, Bias, höchster Wind, auf 10 m)",
                f.isEmpty() ? String.format(Locale.GERMAN, "Faktor %.3f (Schub %.3f), Bias %.1f m/s, Richtung %+.1f°, Sturm %d Werte, Faktor %.3f; 10 m/s in 20 m sind %.2f m/s in 10 m",
                        k.faktor(), k.schub(), k.bias(), k.richtung(), k.sturmWerte(), k.sturmFaktor(), a20) : f.toString());
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
        if (tabellen.size() != 19) f.add(tabellen.size() + " Tabellen statt 19: " + tabellen);                  // seit DB5: 19 (DB4: 16, DB3: 14, DB1, DB2: 10)
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
