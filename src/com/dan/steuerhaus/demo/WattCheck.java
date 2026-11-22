package com.dan.steuerhaus.demo;

import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.ausgabe.Wattrechnung;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Watt;
import com.dan.steuerhaus.gezeiten.Wattansicht;
import com.dan.steuerhaus.gezeiten.Wattauftrag;
import com.dan.steuerhaus.gezeiten.Watttafel;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Z3 mit Gelände, Läufen und Kalender (Werkbuch HalligHooge 158): prüft das Watt-Fenster mit echten Daten, nach
 * {@code Ausgabe --watt}. Prüfstein 6 Weg und Sattel (Start und Ziel wie im Manifest, die tiefste Stelle des Profils ist
 * der Sattel, die Engpass-Suche auf den gespeicherten Höhen findet denselben, Länge plausibel), 7 die Schwellen aus dem
 * Modell (genug Kreuzungen, Median wie gespeichert, trocken ab unter dem Sattel, nass ab darüber, Streuung), 8 je Lauf mit
 * den Schwellen der anderen (Zeit der Kreuzung am Priel gegen die am Anleger), 9 die Fenster der App für die nächsten
 * sieben Tage (Anzahl, Dauer, Niedrigwasser darin, mit Windstau kürzer, mit viel Windstau unter einem Drittel). Auskunft: die
 * Fenster aus dem Kalender gegen die Läufe (mit dem Wind der Läufe), PEGELONLINE jetzt (wenn erreichbar). Bilder: das
 * Watt-Fenster jetzt ({@code Z3_Watt_Hooge.png}) und seine Kachel.
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.WattCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class WattCheck {
    /** Grenzen [G]: Kreuzungen je Richtung, Streuung der Schwellen (Quartilsabstand, m), Kreuzvalidierung (min). */
    static final int MIN_KREUZ = 8;
    static final double STREUUNG = 0.30, CV_MEDIAN = 20, CV_MAX = 90;
    static int steine, verstoesse;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("WattCheck – Z3 (Werkbuch HalligHooge 158)");
        Manifest m = Manifest.lies(manifest);
        Gezeitenauftrag ga = Gezeitenauftrag.lies(m);
        Wattauftrag wa = Wattauftrag.lies(m);
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        Watttafel t;
        Gezeitentafel gt;
        try {
            if (wa == null || ga == null) throw new IllegalStateException("watt.* oder gezeiten.* fehlt im Manifest");
            t = Watttafel.lies(wa.datei());
            gt = Gezeitentafel.lies(ga.datei());
        } catch (Exception x) {
            pruefe(false, "Prüfstein 6: Weg und Tafel vorhanden", x.getMessage(), "vorhanden");
            ende();
            return;
        }

        // ---- Prüfstein 6: Weg und Sattel
        List<String> f6 = new ArrayList<>();
        Watttafel.Punkt p0 = t.weg.get(0), pn = t.weg.get(t.weg.size() - 1);
        double dStart = Math.hypot(p0.ost() - wa.startOst(), p0.nord() - wa.startNord()), dZiel = Math.hypot(pn.ost() - wa.zielOst(), pn.nord() - wa.zielNord());
        if (dStart > t.zelle * 1.5) f6.add(String.format(Locale.ROOT, "Start %.0f m neben watt.start", dStart));
        if (dZiel > wa.zielUmkreis() + t.zelle) f6.add(String.format(Locale.ROOT, "Ziel %.0f m neben watt.ziel", dZiel));
        double minH = Double.MAX_VALUE, maxSchritt = 0;
        Watttafel.Punkt amSattel = null;
        for (int i = 0; i < t.weg.size(); i++) {
            Watttafel.Punkt p = t.weg.get(i);
            minH = Math.min(minH, p.hoehe());
            if (Math.hypot(p.ost() - t.sattelOst, p.nord() - t.sattelNord) < 0.5) amSattel = p;
            if (i > 0) maxSchritt = Math.max(maxSchritt, p.strecke() - t.weg.get(i - 1).strecke());
        }
        // Höhen in der Datei auf mm: mehrere Punkte können gleich tief sein
        if (Math.abs(minH - t.sattel) > 0.001) f6.add("tiefste Stelle des Profils " + minH + " nicht der Sattel");
        if (amSattel == null || Math.abs(amSattel.hoehe() - t.sattel) > 0.001) f6.add("Sattel nicht auf dem Weg");
        if (maxSchritt > 25 + t.zelle * 1.5) f6.add(String.format(Locale.ROOT, "Punkte bis %.0f m auseinander", maxSchritt));
        double luft = Math.hypot(pn.ost() - p0.ost(), pn.nord() - p0.nord());
        if (!(t.wegLaenge() >= luft - 1 && t.wegLaenge() <= 2 * luft)) f6.add(String.format(Locale.ROOT, "Weg %.0f m bei Luftlinie %.0f m", t.wegLaenge(), luft));
        if (!(p0.hoehe() > t.sattel + 0.3) || !(t.zielHoehe > t.sattel + 0.3)) f6.add("Start oder Ziel nicht über dem Sattel");
        float[] h = t.hoehen();
        int s = zelle(t, p0.ost(), p0.nord()), z = zelle(t, t.zielOst, t.zielNord);
        long n0 = System.nanoTime();
        float zs = s < 0 || z < 0 ? Float.NaN : Wattrechnung.engpass(h, t.nx, t.ny, s, z);
        double sek = (System.nanoTime() - n0) / 1e9;
        if (!(Math.abs(zs - t.sattel) <= 0.002)) f6.add("Engpass auf den gespeicherten Höhen " + zs + " statt " + t.sattel);
        pruefe(f6.isEmpty(), "Prüfstein 6: Weg und Sattel – Start und Ziel wie im Manifest, tiefste Stelle des Profils = Sattel, Engpass auf den gespeicherten Höhen gleich, Länge, Punkte",
                String.format(Locale.GERMAN, "%s (%+.2f m) – %s (%+.2f m), %.0f m (Luftlinie %.0f m), Sattel %+.3f m bei O %.0f, N %.0f; neu gesucht %+.3f m in %.1f s", t.start, p0.hoehe(),
                        t.ziel, t.zielHoehe, t.wegLaenge(), luft, t.sattel, t.sattelOst, t.sattelNord, zs, sek) + (f6.isEmpty() ? "" : "; " + f6), "0 Fehler");

        // ---- Prüfstein 7: die Schwellen aus dem Modell
        List<String> f7 = new ArrayList<>();
        List<Double> la = new ArrayList<>(), lb = new ArrayList<>();
        java.util.Map<String, int[]> jeLauf = new java.util.LinkedHashMap<>();
        for (Watttafel.Ereignis e : t.ereignisse) {
            (e.ab() ? la : lb).add(e.anleger());
            jeLauf.computeIfAbsent(e.lauf(), k -> new int[2])[e.ab() ? 0 : 1]++;
        }
        double[] fa = la.stream().mapToDouble(Double::doubleValue).toArray(), fb = lb.stream().mapToDouble(Double::doubleValue).toArray();
        if (fa.length < MIN_KREUZ || fb.length < MIN_KREUZ) f7.add(fa.length + " fallende, " + fb.length + " steigende Kreuzungen");
        double ma = fa.length == 0 ? Double.NaN : Wattrechnung.median(fa), mb = fb.length == 0 ? Double.NaN : Wattrechnung.median(fb);
        if (!(Math.abs(ma - t.schwelleAb) < 0.001) || !(Math.abs(mb - t.schwelleBis) < 0.001)) f7.add("Median nicht wie gespeichert");
        if (!(t.schwelleAb < t.sattel && t.sattel < t.schwelleBis)) f7.add("nicht trocken ab < Sattel < nass ab");
        double iqa = fa.length == 0 ? 9 : Wattrechnung.quantil(fa, 0.75) - Wattrechnung.quantil(fa, 0.25), iqb = fb.length == 0 ? 9 : Wattrechnung.quantil(fb, 0.75) - Wattrechnung.quantil(fb, 0.25);
        if (iqa > STREUUNG || iqb > STREUUNG) f7.add(String.format(Locale.ROOT, "Quartilsabstand %.2f / %.2f m", iqa, iqb));
        for (String k : wa.modell()) if (!jeLauf.containsKey(k)) f7.add("Lauf " + k + " ohne Kreuzung");
        StringBuilder w7 = new StringBuilder();
        for (var e : jeLauf.entrySet()) w7.append(e.getKey()).append(' ').append(e.getValue()[0]).append('/').append(e.getValue()[1]).append(", ");
        pruefe(f7.isEmpty(), "Prüfstein 7: Schwellen am Anleger aus dem Modell – je Richtung ≥ " + MIN_KREUZ + " Kreuzungen, Median wie gespeichert, trocken ab < Sattel < nass ab, Streuung",
                String.format(Locale.GERMAN, "trocken ab %+.3f m (%d, Quartilsabstand %.2f m), nass ab %+.3f m (%d, %.2f m), Sattel %+.3f m; je Lauf fallend/steigend %s", t.schwelleAb,
                        fa.length, iqa, t.schwelleBis, fb.length, iqb, t.sattel, w7) + (f7.isEmpty() ? "" : "; " + f7), "0 Fehler, ≤ " + zahl(STREUUNG) + " m");

        // ---- Prüfstein 8: je Lauf mit den Schwellen der anderen
        double[] kr = w(t, "wert.watt.kreuz");
        List<String> f8 = new ArrayList<>();
        if (kr == null || kr.length != 6) f8.add("wert.watt.kreuz fehlt");
        else {
            if (kr[0] < 3 || kr[3] < 3) f8.add("zu wenige Kreuzungen geprüft");
            if (kr[1] > CV_MEDIAN || kr[4] > CV_MEDIAN) f8.add("Median über " + CV_MEDIAN + " min");
            if (kr[2] > CV_MAX || kr[5] > CV_MAX) f8.add("höchstens über " + CV_MAX + " min");
        }
        pruefe(f8.isEmpty(), "Prüfstein 8: je Lauf mit den Schwellen der anderen Läufe – Zeit der Kreuzung am Anleger gegen die am Priel",
                kr == null || kr.length != 6 ? f8.toString() : String.format(Locale.GERMAN, "trocken ab |%.0f| min im Median, höchstens %.0f (%d); nass ab |%.0f| min, höchstens %.0f (%d)",
                        kr[1], kr[2], (int) kr[0], kr[4], kr[5], (int) kr[3]) + (f8.isEmpty() ? "" : "; " + f8), "Median ≤ " + (int) CV_MEDIAN + " min, höchstens ≤ " + (int) CV_MAX + " min");
        for (int i = 1; t.kopf.containsKey("pruef." + i); i++) info("Lauf", t.kopf.get("pruef." + i));
        double[] ka = w(t, "wert.watt.kalender");
        if (ka != null && ka.length == 6)
            info("Kalender gegen die Läufe (mit ihrem Wind, Auskunft)", String.format(Locale.GERMAN, "trocken ab |%.0f| min im Median, höchstens %.0f (%d); nass ab |%.0f| min, höchstens %.0f (%d)",
                    ka[1], ka[2], (int) ka[0], ka[4], ka[5], (int) ka[3]));

        // ---- Prüfstein 9: die Fenster der App für die nächsten sieben Tage
        Watt wt = new Watt(gt.vorhersage(), t.schwelleAb, t.schwelleBis, t.sattel, t.laenge, t.breite);
        Instant jetzt = Instant.now(), bis = jetzt.plusSeconds(7 * 86400);
        List<Watt.Fenster> f0 = wt.fenster(jetzt, bis, 0), f3 = wt.fenster(jetzt, bis, 0.3), f15 = wt.fenster(jetzt, bis, 1.5);
        List<String> f9 = new ArrayList<>();
        if (f0.size() < 12 || f0.size() > 15) f9.add(f0.size() + " Fenster in 7 Tagen");
        Instant vorher = null;
        double dmin = 99, dmax = 0, summe = 0;
        long tag = 0;
        for (Watt.Fenster f : f0) {
            double d = f.dauer().toMinutes() / 60.0;
            dmin = Math.min(dmin, d);
            dmax = Math.max(dmax, d);
            summe += d;
            tag += f.tagMinuten();
            if (d < 2 || d > 8) f9.add(String.format(Locale.ROOT, "Dauer %.1f h am %s", d, f.ab()));
            if (!f.enthaelt(f.niedrig())) f9.add("Niedrigwasser nicht im Fenster am " + f.ab());
            if (vorher != null && !f.ab().isAfter(vorher)) f9.add("Überlappung am " + f.ab());
            vorher = f.bis();
        }
        double s3 = 0;
        for (Watt.Fenster f : f3) s3 += f.dauer().toMinutes() / 60.0;
        if (f3.size() > f0.size() || !(s3 < summe)) f9.add("mit +0,30 m nicht kürzer");
        double s15 = 0;
        for (Watt.Fenster f : f15) s15 += f.dauer().toMinutes() / 60.0;
        // bei Springtide fällt die Gezeit bis gut −2,2 m: dann bleibt auch mit +1,50 m ein kurzes Fenster
        if (!(s15 < summe / 3)) f9.add(String.format(Locale.ROOT, "mit +1,50 m noch %.0f h", s15));
        DateTimeFormatter hm = DateTimeFormatter.ofPattern("EE dd.MM. HH:mm", Locale.GERMAN);
        pruefe(f9.isEmpty(), "Prüfstein 9: Fenster der App ab jetzt für 7 Tage – Anzahl, Dauer, Niedrigwasser darin, keine Überlappung, mit +0,30 m kürzer, mit +1,50 m unter einem Drittel",
                String.format(Locale.GERMAN, "%d Fenster, %.1f … %.1f h, zusammen %.0f h (davon %.0f h bei Tag); mit +0,30 m %.0f h; mit +1,50 m %d, %.0f h; erstes %s – %s", f0.size(), dmin,
                        dmax, summe, tag / 60.0, s3, f15.size(), s15, f0.isEmpty() ? "–" : hm.format(f0.get(0).ab().atZone(zone)), f0.isEmpty() ? "–" : hm.format(f0.get(0).bis().atZone(zone)))
                        + (f9.isEmpty() ? "" : "; " + f9), "0 Fehler");

        // ---- Bilder und Auskunft: jetzt, mit der Messung, wenn PEGELONLINE erreichbar ist
        if (bilder != null) {
            bilder.mkdirs();
            BufferedImage[] bi = new BufferedImage[2];
            String[] auskunft = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Thema.LABOR.anwenden();
                    var v = new Wattansicht(t, gt.vorhersage(), gt, wa.gehen(), wa.hinweis(), zone, new File(System.getProperty("java.io.tmpdir")));
                    v.ladenJetzt();
                    v.quelle(1);
                    var g = v.messung.gemessen();
                    var fn = v.watt.jetztOderNaechstes(jetzt, v.stau());
                    auskunft[0] = (g == null ? "PEGELONLINE nicht erreicht (" + v.messung.fehler() + "), ohne Wind" : String.format(Locale.GERMAN, "Windstau gemessen %+.2f m", g.meter()))
                            + (fn == null ? "; kein Fenster in 30 h" : "; jetzt oder nächstes trocken " + hm.format(fn.ab().atZone(zone)) + " – " + hm.format(fn.bis().atZone(zone)));
                    bi[0] = v.alsBild(1460, 990);
                    bi[1] = v.tagesbild(480, 270);
                } catch (Exception x) {
                    auskunft[0] = "Bild: " + x;
                }
            });
            info("Jetzt", auskunft[0]);
            if (bi[0] != null) {
                ImageIO.write(bi[0], "png", new File(bilder, "Z3_Watt_Hooge.png"));
                ImageIO.write(bi[1], "png", new File(bilder, "Z3_Watt_Hooge_Kachel.png"));
                info("Bilder", "Z3_Watt_Hooge.png, Z3_Watt_Hooge_Kachel.png (jetzt, mit der Messung, wenn erreichbar)");
            }
        }
        ende();
    }

    static int zelle(Watttafel t, double ost, double nord) {
        int i = (int) Math.floor((ost - t.karte[0]) / t.zelle), j = (int) Math.floor((nord - t.karte[1]) / t.zelle);
        return i < 0 || j < 0 || i >= t.nx || j >= t.ny ? -1 : i + t.nx * j;
    }

    static String zahl(double v) { return String.format(Locale.GERMAN, "%.2f", v); }

    static double[] w(Watttafel t, String k) {
        String s = t.kopf.get(k);
        if (s == null) return null;
        String[] x = s.strip().split("\\s+");
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = Double.parseDouble(x[i]);
        return r;
    }

    static void ende() {
        System.out.println();
        System.out.println(steine + " Prüfsteine, " + verstoesse + " Verstöße.");
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    static void info(String was, String wert) { System.out.println("  info   " + was + ": " + wert); }

    static void pruefe(boolean ok, String was, String wert, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + wert + "  [" + grenze + "]");
    }
}
