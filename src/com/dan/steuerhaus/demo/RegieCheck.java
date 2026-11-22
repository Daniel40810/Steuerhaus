package com.dan.steuerhaus.demo;

import com.dan.kino.Kino;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.regie.Regie;
import com.dan.steuerhaus.regie.Regiebuch;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Prüfung von Regie, Fahrt und Lesezeichen ohne Bild und ohne Grafikkarte (Werkbuch HalligHooge 116, W3):
 * <ol>
 * <li>Prüfstein 1: die Fahrten der Regiebücher treffen ihre Schlüsselbilder (Lage ≤ 1 mm, Winkel ≤ 0,01°).</li>
 * <li>Prüfstein 2: glatt – je Abschnitt größter gegen mittleren Schritt bei 60 Bildern/s ≤ 1,5 [G]; von 350° nach 10°
 * über 0°.</li>
 * <li>Prüfstein 3: lesen, schreiben, wieder lesen ohne Verlust; falsche Zeilen mit ihrer Nummer.</li>
 * <li>Prüfstein 4: Ablauf mit einer gedachten Szene: Zeilen in der Folge der Datei, {@code warte} hält die Uhr
 * (± 1 Bild).</li>
 * <li>Prüfstein 6 (Teil ohne Karte): Überblendung 1,6 s ± 1 Bild; Balken genau 2,39 : 1 (± 1 px).</li>
 * <li>Prüfstein 8: Lesezeichen und Fahrten überleben Schreiben und Lesen.</li>
 * </ol>
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.RegieCheck --manifest src\szenen\Hooge.steuerhaus
 * </pre>
 */
public final class RegieCheck {
    static int steine, verstoesse;
    static final double DT = 1.0 / 60;

    public static void main(String[] args) throws Exception {
        File manifest = null;
        for (int i = 0; i < args.length; i++) if (args[i].equals("--manifest")) manifest = new File(args[++i]);
        System.out.println("RegieCheck – Steuerhaus W3 (Werkbuch HalligHooge 116), ohne Bild");
        Manifest m = Manifest.lies(manifest);
        List<Regiebuch> buecher = new ArrayList<>();
        for (String d : m.nummern("regie.")) {
            Regiebuch rb = Regiebuch.lies(m.relativ(d).toPath());
            buecher.add(rb);
            pruefe(rb.fehler.isEmpty(), "Regiebuch „" + rb.name + "“ ohne Fehler", rb.fehler.isEmpty() ? rb.toString() : rb.fehler.toString(), "0 Fehler");
        }
        if (buecher.isEmpty()) pruefe(false, "Regiebücher im Manifest", "keine", "≥ 1");
        // seit Teil C (F227 A): „folge“ aufgelöst wie in der App; der Ablauf (Prüfstein 4) läuft mit dem aufgelösten Buch
        java.util.Map<String, Regiebuch> namen = new java.util.LinkedHashMap<>();
        for (Regiebuch rb : buecher) namen.put(rb.name, rb);
        List<Regiebuch> aufgeloest = new ArrayList<>();
        for (Regiebuch rb : buecher) {
            Regiebuch a = rb.aufgeloest(namen::get);
            if (a != rb) pruefe(a.fehler.size() == rb.fehler.size(), "Regiebuch „" + rb.name + "“: folge aufgelöst", a.fehler.size() == rb.fehler.size() ? a.toString() : a.fehler.toString(), "0 Fehler");
            aufgeloest.add(a);
        }

        teil("1 und 2 Fahrten");
        for (Regiebuch rb : buecher) for (Fahrt f : rb.fahrten.values()) fahrt(f);
        Fahrt wende = new Fahrt("über Nord", List.of(new Fahrt.Schluesselbild(0, 0, 0, 10, false, 350, 0, 50), new Fahrt.Schluesselbild(4, 100, 0, 10, false, 10, 0, 50)));
        double mitte = wende.lage(2)[3], groesst = 0;
        double[] alt = wende.lage(0);
        for (double t = DT; t <= 4 + 1e-9; t += DT) {
            double[] l = wende.lage(t);
            groesst = Math.max(groesst, Math.abs(((l[3] - alt[3]) % 360 + 540) % 360 - 180));
            alt = l;
        }
        pruefe(Math.abs(mitte) < 0.01 || Math.abs(mitte - 360) < 0.01, "Prüfstein 2: Richtung von 350° nach 10° über Nord", String.format(Locale.GERMAN, "Mitte %.3f°, größter Schritt %.3f°", mitte, groesst), "0°, kein Sprung");
        pruefe(groesst < 0.5, "Prüfstein 2: Richtung ohne Sprung je Bild", String.format(Locale.GERMAN, "%.3f°", groesst), "< 0,5°");

        teil("3 Lesen und Schreiben");
        for (Regiebuch rb : buecher) {
            Regiebuch w = Regiebuch.lies(rb.text(), "wieder");
            pruefe(w.fehler.isEmpty() && gleich(rb, w), "Prüfstein 3: „" + rb.name + "“ geschrieben und wieder gelesen", w.fehler.isEmpty() ? (gleich(rb, w) ? "gleich" : "verschieden") : w.fehler.toString(), "gleich");
        }
        String falsch = "Regiebuch: Probe\nLänge: 0:10\n0:00 ansicht x\n0:01 fliege hoch\n0:0x ansicht y\n0:02 raffer schnell\nFahrt: F\n0:00 1 2 3 nhn 0 0 50\n0:01 1 2 3 oben 0 0 50\nEnde\n";
        Regiebuch fb = Regiebuch.lies(falsch, "Probe");
        List<Integer> zeilen = fb.fehler.stream().map(Regiebuch.Fehler::zeile).toList();
        pruefe(zeilen.equals(List.of(4, 5, 6, 9)), "Prüfstein 3: falsche Zeilen mit Nummer", fb.fehler.toString(), "Zeilen 4, 5, 6, 9");

        teilC();

        teil("4 Ablauf ohne Bild");
        for (Regiebuch rb : aufgeloest) ablauf(rb);
        ablauf(Regiebuch.lies("Regiebuch: Warte auf die Uhr\nLänge: 0:20\n0:00 zeit 25.10.2026 21:00\n0:00 raffer 100\n0:05 warte uhr 21:30 | warten\n0:05 sage weiter\n0:20 halt\n", "Probe"));

        teil("6 Kino ohne Karte");
        Kino k = new Kino();
        k.beginnen("Kopf", "Titel", "Unterzeile");
        for (int i = 0; i < 9 * 60; i++) k.schritt(DT);
        int bar = k.balkenPx(1920, 1080), sicht = 1080 - 2 * bar;
        pruefe(Math.abs(1920.0 / 2.39 - sicht) <= 1, "Prüfstein 6: Balken bei 1920 × 1080", bar + " px oben und unten, Bild 1920 × " + sicht + String.format(Locale.GERMAN, " (%.3f : 1)", 1920.0 / sicht), "2,39 : 1 ± 1 px");
        boolean s = k.schnitt();
        k.geschnitten();
        int n = 0;
        while (k.ueberblende() > 0 && n < 1000) {
            k.schritt(DT);
            n++;
        }
        pruefe(s && Math.abs(n - 1.6 * 60) <= 1, "Prüfstein 6: Überblendung bei 60 Bildern/s", n + " Bilder (" + String.format(Locale.GERMAN, "%.3f s", n * DT) + ")", "96 ± 1");

        teil("8 Lesezeichen und Aufnahmen");
        File tmp = File.createTempFile("lesezeichen", ".txt");
        tmp.deleteOnExit();
        List<Lesezeichen> l = List.of(new Lesezeichen("Probe A", "sturm", Instant.parse("2026-10-25T22:30:00Z"), new double[] {470800, 6046470, 6, 45, 0, 50}),
                new Lesezeichen("Probe B · 23:00", "ruhig", Instant.parse("2026-10-30T10:15:00Z"), new double[] {470650.25, 6046300.5, 120, 30.5, -22, 55}));
        Lesezeichen.schreibe(tmp, l);
        List<String> fl = new ArrayList<>();
        List<Lesezeichen> wl = Lesezeichen.lies(tmp, fl);
        pruefe(fl.isEmpty() && wl.equals(l), "Prüfstein 8: Lesezeichen geschrieben und wieder gelesen", wl.size() + " von " + l.size() + (fl.isEmpty() ? "" : "; " + fl), "gleich");
        Fahrt auf = new Fahrt("Fahrt Probe", List.of(new Fahrt.Schluesselbild(0, 470800.12, 6046470.34, 6.5, false, 45.25, -2, 50),
                new Fahrt.Schluesselbild(3.7, 470900, 6046600, 30, false, 120, -10, 45)));
        Regiebuch ab = Regiebuch.aus("Aufnahmen", "Probe", 0, List.of(), java.util.Map.of(auf.name, auf));
        Regiebuch wa = Regiebuch.lies(ab.text(), "Aufnahmen");
        pruefe(wa.fehler.isEmpty() && wa.fahrten.containsKey(auf.name) && gleich(auf, wa.fahrten.get(auf.name)), "Prüfstein 8: Aufnahme als Fahrt geschrieben und wieder gelesen",
                wa.fehler.isEmpty() ? "gleich" : wa.fehler.toString(), "gleich");

        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    /** Seit Teil C (Werkbuch HalligHooge 134, F227 A): {@code warte pegel < x} und {@code folge}. */
    static void teilC() {
        teil("C Teil C: warte pegel < x und folge (F227 A)");
        boolean p = Regiebuch.pegelErreicht("pegel 2,01", 2.1) && !Regiebuch.pegelErreicht("pegel > 2.01", 1.9) && Regiebuch.pegelErreicht("pegel < 0,5", 0.3)
                && !Regiebuch.pegelErreicht("pegel < 0,5", 0.7) && Regiebuch.pegelErreicht("pegel < 0,5", Double.NaN) && !Regiebuch.pegelErreicht("pegel > 0,5", Double.NaN);
        pruefe(p, "warte pegel: steigend (x, > x), fallend (< x), trocken", "6 Fälle", "alle richtig");
        Regiebuch ta = Regiebuch.lies("Regiebuch: Teil A\nLänge: 0:10\n0:00 szenario ruhig\n0:02 warte pegel < 0,5 | Ebbe\n0:08 fahrt FA\nFahrt: FA\n0:00 1 2 3 nhn 0 0 50\n0:02 5 2 3 nhn 0 0 50\nEnde\n", "A");
        Regiebuch tb = Regiebuch.lies("Regiebuch: Teil B\nLänge: 0:15\n0:00 szenario sturm\n0:05 warte pegel > 2 | Flut\n", "B");
        Regiebuch tn = Regiebuch.lies("Regiebuch: Teil N\nLänge: 0:05\n0:00 folge Teil A\n", "N");
        java.util.Map<String, Regiebuch> n = java.util.Map.of(ta.name, ta, tb.name, tb, tn.name, tn);
        pruefe(ta.fehler.isEmpty() && tb.fehler.isEmpty() && tn.fehler.isEmpty(), "warte pegel < x und folge lesen", ta.fehler + " " + tb.fehler + " " + tn.fehler, "0 Fehler");
        Regiebuch g = Regiebuch.lies("Regiebuch: Ganz\nLänge: 0:05\n0:00 sage Anfang\n0:00 folge Teil A\n0:10 folge Teil B\n", "Ganz");
        Regiebuch w = Regiebuch.lies(g.text(), "wieder");
        pruefe(w.fehler.isEmpty() && gleich(g, w), "Prüfstein 3: Buch mit folge geschrieben und wieder gelesen", w.fehler.isEmpty() ? (gleich(g, w) ? "gleich" : "verschieden") : w.fehler.toString(), "gleich");
        Regiebuch ga = g.aufgeloest(n::get);
        String sek = ga.zeilen.stream().map(z -> Regiebuch.zeit(z.sekunde()) + " " + z.befehl()).toList().toString();
        String soll = "[0:00 sage, 0:00 szenario, 0:02 warte, 0:08 fahrt, 0:10 szenario, 0:15 warte]";
        pruefe(ga.fehler.isEmpty() && sek.equals(soll) && Math.abs(ga.laenge - 25) < 1e-9 && ga.fahrten.containsKey("FA"), "folge: Teile verschoben, Länge und Fahrten",
                sek + ", Länge " + Regiebuch.zeit(ga.laenge) + ", Fahrten " + ga.fahrten.keySet() + (ga.fehler.isEmpty() ? "" : ", " + ga.fehler), soll + ", 0:25, [FA]");
        Regiebuch ue = Regiebuch.lies("Regiebuch: Eng\nLänge: 0:05\n0:00 folge Teil A\n0:05 folge Teil B\n0:30 folge Teil X\n0:50 folge Teil N\n", "Eng").aufgeloest(n::get);
        List<Integer> fz = ue.fehler.stream().map(Regiebuch.Fehler::zeile).toList();
        pruefe(fz.equals(List.of(4, 5, 6)), "folge: Überlappung, unbekannter Name, folge im Teil – mit Zeilennummer", ue.fehler.toString(), "Zeilen 4, 5, 6");
        ablauf(ga);
    }

    /** Prüfsteine 1 und 2 für eine Fahrt. */
    static void fahrt(Fahrt f) {
        double lage = 0, winkel = 0;
        for (Fahrt.Schluesselbild s : f.bilder()) {
            double[] l = f.lage(s.t());
            lage = Math.max(lage, Math.sqrt(Math.pow(l[0] - s.ost(), 2) + Math.pow(l[1] - s.nord(), 2) + Math.pow(l[2] - s.hoehe(), 2)));
            winkel = Math.max(winkel, Math.max(Math.abs(((l[3] - s.richtung()) % 360 + 540) % 360 - 180), Math.max(Math.abs(l[4] - s.neigung()), Math.abs(l[5] - s.bildwinkel()))));
        }
        pruefe(lage <= 0.001 && winkel <= 0.01, "Prüfstein 1: „" + f.name + "“ trifft " + f.bilder().size() + " Schlüsselbilder",
                String.format(Locale.GERMAN, "Lage %.2e m, Winkel %.2e°", lage, winkel), "≤ 1 mm, ≤ 0,01°");
        List<Fahrt.Schluesselbild> b = f.bilder();
        double schlecht = 0;
        String wo = "";
        for (int i = 0; i + 1 < b.size(); i++) {
            double t0 = b.get(i).t(), t1 = b.get(i + 1).t(), summe = 0, max = 0;
            int n = 0;
            double[] alt = f.lage(t0);
            for (double t = t0 + DT; t <= t1 + 1e-9; t += DT) {
                double[] l = f.lage(t);
                double s = Math.sqrt(Math.pow(l[0] - alt[0], 2) + Math.pow(l[1] - alt[1], 2) + Math.pow(l[2] - alt[2], 2));
                summe += s;
                max = Math.max(max, s);
                n++;
                alt = l;
            }
            if (summe <= 1e-6) continue;
            double r = max / (summe / n);
            if (r > schlecht) {
                schlecht = r;
                wo = Regiebuch.zeit(t0) + "–" + Regiebuch.zeit(t1);
            }
        }
        pruefe(schlecht <= 1.5, "Prüfstein 2: „" + f.name + "“ glatt (größter gegen mittleren Schritt je Abschnitt)", String.format(Locale.GERMAN, "%.3f (%s)", schlecht, wo), "≤ 1,5 [G]");
    }

    /** Prüfstein 4: Ablauf mit einer gedachten Szene (Szenenzeit läuft mit dem Zeitraffer; der Pegel steigt mit der Uhr). */
    static void ablauf(Regiebuch rb) {
        ZoneId zone = ZoneId.of("Europe/Berlin");
        Instant[] szene = {LocalDateTime.of(2026, 10, 25, 21, 0).atZone(zone).toInstant()};
        double[] raffer = {1};
        Instant landunter = LocalDateTime.of(2026, 10, 25, 22, 10).atZone(zone).toInstant();
        List<Integer> folge = new ArrayList<>();
        double[] warteBeginn = {Double.NaN}, warteEnde = {Double.NaN}, echt = {0}, erwartet = {Double.NaN};
        Regie[] r = new Regie[1];
        Regie.Ctx ctx = new Regie.Ctx() {
            @Override public void ausfuehren(Regiebuch.Zeile z) {
                folge.add(z.nr());
                switch (z.befehl()) {
                    case "raffer" -> raffer[0] = Double.parseDouble(z.argumente());
                    case "zeit" -> {
                        String[] t = z.argumente().split("\\s+");
                        String[] d = t[0].split("\\.");
                        String[] h = t[t.length - 1].split(":");
                        szene[0] = LocalDateTime.of(Integer.parseInt(d[2]), Integer.parseInt(d[1]), Integer.parseInt(d[0]), Integer.parseInt(h[0]), Integer.parseInt(h[1])).atZone(zone).toInstant();
                    }
                    case "warte" -> {
                        if (z.argumente().startsWith("pegel")) return;           // ohne gedachten Pegel: sofort bereit (Teil C)
                        warteBeginn[0] = echt[0];
                        // erwartet: bis die gedachte Szene die Bedingung erreicht, mit dem Zeitraffer jetzt
                        Instant ziel = landunter;
                        if (z.argumente().startsWith("uhr")) {
                            String[] h = z.argumente().split("\\s+")[1].split(":");
                            ziel = szene[0].atZone(zone).toLocalDate().atTime(Integer.parseInt(h[0]), Integer.parseInt(h[1])).atZone(zone).toInstant();
                        }
                        erwartet[0] = Math.max(0, (ziel.toEpochMilli() - szene[0].toEpochMilli()) / 1000.0 / raffer[0]);
                    }
                    default -> { }
                }
            }

            @Override public boolean bereit(Regiebuch.Zeile w) {
                boolean ja;
                if (w.argumente().startsWith("pegel")) return true;
                if (w.argumente().startsWith("landunter")) ja = !szene[0].isBefore(landunter);
                else if (w.argumente().startsWith("uhr")) {
                    String[] h = w.argumente().split("\\s+")[1].split(":");
                    ja = !szene[0].atZone(zone).toLocalTime().isBefore(java.time.LocalTime.of(Integer.parseInt(h[0]), Integer.parseInt(h[1])));
                } else ja = true;
                if (ja && Double.isNaN(warteEnde[0]) && !Double.isNaN(warteBeginn[0])) warteEnde[0] = echt[0];
                return ja;
            }
        };
        r[0] = new Regie(rb, ctx);
        double uhrBeimWarten = Double.NaN;
        for (int i = 0; i < (rb.laenge + 600) * 60 && !r[0].fertig(); i++) {           // seit C2: Länge des Buchs plus 10 min (Gesamtfilm)
            echt[0] += DT;
            szene[0] = szene[0].plusNanos(Math.round(DT * raffer[0] * 1e9));
            r[0].schritt(DT);
            if (r[0].wartet() != null) uhrBeimWarten = r[0].uhr();
        }
        List<Integer> soll = rb.zeilen.stream().map(Regiebuch.Zeile::nr).toList();
        pruefe(r[0].fertig() && folge.equals(soll), "Prüfstein 4: „" + rb.name + "“ läuft durch, Zeilen in der Folge der Datei", folge.size() + " von " + soll.size() + " Zeilen" + (r[0].fertig() ? "" : ", nicht fertig"), "alle, gleiche Folge");
        Regiebuch.Zeile w = rb.zeilen.stream().filter(z -> z.befehl().equals("warte") && !z.argumente().startsWith("pegel")).findFirst().orElse(null);
        if (w == null) return;
        // bis die Szene die Bedingung erreicht, steht die Uhr auf der Sekunde des warte
        double dauer = warteEnde[0] - warteBeginn[0];
        pruefe(!Double.isNaN(dauer) && Math.abs(uhrBeimWarten - w.sekunde()) < 1e-9 && Math.abs(dauer - erwartet[0]) <= DT + 1e-9,
                "Prüfstein 4: „" + w.argumente() + "“ hält die Uhr des Regiebuchs", String.format(Locale.GERMAN, "Uhr steht bei %s, %.3f s gewartet (erwartet %.3f s)", Regiebuch.zeit(uhrBeimWarten), dauer, erwartet[0]), "± 1 Bild");
    }

    static boolean gleich(Regiebuch a, Regiebuch b) {
        if (!a.name.equals(b.name) || !a.beschreibung.equals(b.beschreibung) || Math.abs(a.laenge - b.laenge) > 0.05 || a.zeilen.size() != b.zeilen.size()) return false;
        for (int i = 0; i < a.zeilen.size(); i++) {
            Regiebuch.Zeile x = a.zeilen.get(i), y = b.zeilen.get(i);
            if (Math.abs(x.sekunde() - y.sekunde()) > 0.05 || !x.befehl().equals(y.befehl()) || !x.argumente().equals(y.argumente()) || !x.text().equals(y.text())) return false;
        }
        if (!a.fahrten.keySet().equals(b.fahrten.keySet())) return false;
        for (String k : a.fahrten.keySet()) if (!gleich(a.fahrten.get(k), b.fahrten.get(k))) return false;
        return true;
    }

    static boolean gleich(Fahrt a, Fahrt b) {
        if (a.bilder().size() != b.bilder().size()) return false;
        for (int i = 0; i < a.bilder().size(); i++) {
            Fahrt.Schluesselbild x = a.bilder().get(i), y = b.bilder().get(i);
            double[] u = {x.t(), x.ost(), x.nord(), x.hoehe(), x.richtung(), x.neigung(), x.bildwinkel()}, v = {y.t(), y.ost(), y.nord(), y.hoehe(), y.richtung(), y.neigung(), y.bildwinkel()};
            for (int k = 0; k < u.length; k++) if (Math.abs(u[k] - v[k]) > 0.006) return false;
            if (x.ueberBoden() != y.ueberBoden()) return false;
        }
        return true;
    }

    static void teil(String t) { System.out.println("\n== " + t); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + g + ": " + w + "  [" + grenze + "]");
    }
}
