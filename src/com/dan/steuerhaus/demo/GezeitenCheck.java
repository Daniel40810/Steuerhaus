package com.dan.steuerhaus.demo;

import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Kalenderansicht;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Z1 mit Pegeln und Archiven (Werkbuch HalligHooge 155): prüft die gerechneten Konstanten des Tidenkalenders
 * ({@code gezeiten.datei}, nach {@code Ausgabe --gezeiten}). Prüfstein 6 die Tafel (Ort, Tiden, Marken des Modells),
 * 7 die Analyse am Bezug (Tage ohne Windstau, Rest), 8 die Kennwerte am Bezug getroffen, 9 die Zeiten am Bezug gegen die
 * Messung ohne Windstau, 10 die Übertragung an den Prüfpegel (Zeiten, M2, Kennwerte). Auskunft: der Ort, das Modell.
 * Dazu der Kalender mit den echten Konstanten ({@code Z1_Kalender_Hooge.png}).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.GezeitenCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class GezeitenCheck {
    /** Grenzen [G]. */
    static final double TAGE = 10, REST = 0.25, KENNWERT_BEZUG = 0.02, ZEIT = 20, ZEIT_MITTEL = 10, ZEIT_EIGEN = 3, M2_AMP = 0.03, M2_PHASE = 5, KENNWERT_PRUEF = 0.15;
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
        System.out.println("GezeitenCheck – Z1 (Werkbuch HalligHooge 155)");
        Manifest m = Manifest.lies(manifest);
        Gezeitenauftrag ga = Gezeitenauftrag.lies(m);
        Gezeitentafel t;
        try {
            t = Gezeitentafel.lies(ga.datei());
        } catch (Exception x) {
            pruefe(false, "Prüfstein 6: Tafel vorhanden", x.getMessage(), "vorhanden");
            ende();
            return;
        }
        List<String> f6 = new ArrayList<>();
        if (!t.ort.equals(ga.ort()) || Math.abs(t.ost - ga.ost()) > 1 || Math.abs(t.nord - ga.nord()) > 1) f6.add("Ort " + t.ort);
        if (t.tiden.size() < 12) f6.add(t.tiden.size() + " Tiden");
        if (t.marken.size() < 10) f6.add(t.marken.size() + " Marken");
        for (String k : new String[] {"wert.analyse.bezug", "wert.kennwerte.bezug", "wert.hindcast.bezug", "wert.ort"}) if (!t.kopf.containsKey(k)) f6.add(k + " fehlt");
        pruefe(f6.isEmpty(), "Prüfstein 6: Tafel – Ort wie im Manifest, Tiden, Marken des Modells, Prüfwerte", f6.isEmpty() ? String.format(Locale.GERMAN, "%s: %d Tiden, %d Marken aus %s; Z₀ %+.3f m",
                t.ort, t.tiden.size(), t.marken.size(), t.modelle.keySet(), t.mittel) : f6.toString(), "0 Fehler");
        info("Bezug", t.kopf.getOrDefault("bezug", "–"));
        info("Abgeleitet", t.kopf.getOrDefault("bezug.abgeleitet", "–"));

        double[] an = w(t, "wert.analyse.bezug"), kb = w(t, "wert.kennwerte.bezug"), hb = w(t, "wert.hindcast.bezug"), ka = w(t, "wert.kalibrierung");
        pruefe(an != null && an[0] / 1440 >= TAGE && an[2] <= REST, "Prüfstein 7: Analyse am Bezug ohne Zeiten mit Windstau",
                an == null ? "–" : String.format(Locale.GERMAN, "%.1f von %.1f Tagen, Rest %.3f m", an[0] / 1440, an[1] / 1440, an[2]), "≥ 10 Tage; Rest ≤ 0,25 m");
        pruefe(kb != null && Math.abs(kb[0] - kb[2]) <= KENNWERT_BEZUG && Math.abs(kb[1] - kb[3]) <= KENNWERT_BEZUG && ka != null && ka[0] > 0.9 && ka[0] < 1.25,
                "Prüfstein 8: Kennwerte am Bezug getroffen (Vorhersage 2027 gegen MThw, MTnw 2011–2020)", kb == null || ka == null ? "–" : String.format(Locale.GERMAN,
                        "MThw %+.3f gegen %+.3f, MTnw %+.3f gegen %+.3f m; Tidenhub der 30 Tage %.3f gegen %.3f m: Amplituden mal %.4f", kb[0], kb[2], kb[1], kb[3], ka[1], ka[2], ka[0]),
                "± 0,02 m; Faktor 0,9–1,25");
        pruefe(hb != null && hb[0] >= 40 && hb[2] <= ZEIT && Math.abs(hb[1]) <= ZEIT_MITTEL, "Prüfstein 9: Zeiten am Bezug gegen die Messung (Hoch- und Niedrigwasser ohne Windstau)",
                hb == null ? "–" : String.format(Locale.GERMAN, "%.0f Extreme; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m", hb[0], hb[1], hb[2], hb[3], hb[4]),
                "|Zeit| ≤ 20 min, Mittel ≤ ± 10 min; Höhe zur Auskunft");
        double[] up = w(t, "wert.uebertrag.pruef"), ep = w(t, "wert.eigen.pruef"), mp = w(t, "wert.m2.pruef"), kp = w(t, "wert.kennwerte.pruef");
        boolean ok10 = up != null && ep != null && mp != null && kp != null && up[0] >= 40 && up[2] <= ZEIT && up[2] <= ep[2] + ZEIT_EIGEN && Math.abs(mp[0] - mp[2]) <= M2_AMP
                && Math.abs(com.dan.core.astro.FAngle.norm180(mp[1] - mp[3])) <= M2_PHASE && Math.abs(kp[0] - kp[2]) <= KENNWERT_PRUEF && Math.abs(kp[1] - kp[3]) <= KENNWERT_PRUEF;
        pruefe(ok10, "Prüfstein 10: Übertragung mit dem Modell an " + ga.pruefName() + " – Zeiten gegen die Messung, M2 gegen die eigene Analyse, Kennwerte",
                up == null || ep == null || mp == null || kp == null ? "–" : String.format(Locale.GERMAN,
                        "|Zeit| %.1f min (eigene Analyse %.1f), Höhe |%.3f| m; M2 %.3f m %.1f° gegen %.3f m %.1f°; MThw %+.3f gegen %+.3f, MTnw %+.3f gegen %+.3f m", up[2], ep[2], up[4], mp[0], mp[1],
                        mp[2], mp[3], kp[0], kp[2], kp[1], kp[3]), "|Zeit| ≤ 20 min und ≤ eigene + 3 min; M2 ± 3 cm, ± 5°; Kennwerte ± 0,15 m");
        info("Hub der 30 Tage am Prüfpegel", t.kopf.getOrDefault("pruef.hub." + ga.pruef(), "–"));
        double[] wo = w(t, "wert.ort"), wm = w(t, "wert.modell");
        if (wo != null) info("Ort " + t.ort, String.format(Locale.GERMAN, "MThw %+.3f, MTnw %+.3f, MThb %.3f m; Alter der Gezeit %.1f h", wo[0], wo[1], wo[0] - wo[1], wo[2]));
        for (int i = 1; i <= 3; i++) if (t.kopf.containsKey("uebertrag." + i)) info("Übertragung", t.kopf.get("uebertrag." + i));
        if (wm != null) info("Modell (mit Wind) gegen Kalender", String.format(Locale.GERMAN, "%.0f Marken; Zeit %+.1f min, |%.1f| min; Höhe %+.3f m, |%.3f| m", wm[0], wm[1], wm[2], wm[3], wm[4]));

        // der Kalender mit den echten Konstanten
        if (bilder != null) {
            bilder.mkdirs();
            BufferedImage[] bi = new BufferedImage[1];
            Thema.zu(m.thema()).anwenden();
            Gezeitentafel tt = t;
            SwingUtilities.invokeAndWait(() -> {
                var k = new Kalenderansicht(tt, ga.marke(), ga.markeName(), ga.hinweis(), ZoneId.of(m.text("app.zone", "Europe/Berlin")), new File(System.getProperty("java.io.tmpdir")));
                k.jetzt = Instant::now;
                k.zeige(k.heute());
                bi[0] = k.alsBild(1460, 960);
            });
            ImageIO.write(bi[0], "png", new File(bilder, "Z1_Kalender_Hooge.png"));
            info("Bild", "Z1_Kalender_Hooge.png");
        }
        ende();
    }

    static double[] w(Gezeitentafel t, String k) {
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
