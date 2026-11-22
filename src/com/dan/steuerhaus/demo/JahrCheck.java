package com.dan.steuerhaus.demo;

import com.dan.core.gitter.Gebiet;
import com.dan.core.gitter.Gitter;
import com.dan.core.gitter.Subgrid;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.jahr.Jahresansicht;
import com.dan.steuerhaus.jahr.Jahresauftrag;
import com.dan.steuerhaus.jahr.Jahresmappe;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Y3 mit Archiven und Gebiet (Werkbuch HalligHooge 154): prüft die gerechnete Mappe des Jahrs. Prüfstein 6 fertig, passend
 * und vollständig (Abschnitte, Faktoren, Tage), 7 Übergänge zwischen den Läufen im Inneren, 8 Bilder und Grund gegen
 * Archiv ({@code bed.dz} der Läufe, ohne Wind) und Packung, 9 der Sand nach dem Jahr wie P9c (Werkbuch 34: +6,3 m nach Osten, 6,50 → 6,51 km², gewichtet +8,4 m),
 * 10 Zeit je Bild beim Abspielen ohne Fenster. Dazu die Ansicht mit den echten Daten ({@code Y3_Jahr_*.png}).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.JahrCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class JahrCheck {
    /** Grenzen [G]. */
    static final double UEBERGANG = 0.001, BODEN = 0.0005 + 1e-6, GRUND = 1e-4, JE_BILD_MS = 15;
    /** P9c (Werkbuch 34, 4.6): Schwerpunkt der Fläche, Fläche vor und nach, gewichteter Schwerpunkt – Mitte und Spanne. */
    static final double[][] P9C = {{6.3, 0.15}, {6.50, 0.006}, {6.51, 0.006}, {8.4, 0.15}};
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
        System.out.println("JahrCheck – Y3 (Werkbuch HalligHooge 154)");
        Manifest m = Manifest.lies(manifest);
        Szenen sz = Szenen.lies(m.szenen());
        Thema.zu(m.thema()).anwenden();
        Jahresauftrag ja = Jahresauftrag.lies(m);
        String[] warum = new String[1];
        Jahresmappe jm = ja == null ? null : ja.mappe(sz, warum);
        pruefe(jm != null, "Prüfstein 6: Mappe fertig und passend (Kennung aus Läufen und Einstellungen)", jm != null ? jm.ordner + ", Kennung " + jm.kennung : String.valueOf(warum[0]), "vorhanden");
        if (jm == null) {
            ende();
            return;
        }
        // 6 vollständig: Abschnitte, Faktoren, Tage
        List<String> f6 = new ArrayList<>();
        double[] faktor = {60, 60, 4}, tage = {180, 180, 5};
        if (jm.abschnitte.size() != 3) f6.add(jm.abschnitte.size() + " Abschnitte");
        for (int k = 0; k < Math.min(3, jm.abschnitte.size()); k++) {
            var a = jm.abschnitte.get(k);
            if (Math.abs(a.faktor() - faktor[k]) > 1e-9) f6.add(a.name() + ": Faktor " + a.faktor());
            if (Math.abs(a.tage() - tage[k]) > 0.01) f6.add(a.name() + ": " + a.tage() + " Tage");
        }
        int imBoden = jm.bilderImBoden();
        if (imBoden != jm.bilder.size()) f6.add(imBoden + " Bilder in " + Jahresmappe.BODEN + " gegen " + jm.bilder.size());
        if (!jm.karte().isFile() || !jm.vorschau().isFile()) f6.add("Karte oder Vorschau fehlt");
        StringBuilder ab = new StringBuilder();
        for (var a : jm.abschnitte) ab.append(String.format(Locale.GERMAN, "%s: %.1f Tage, × %.0f, Bilder %d–%d; ", a.name(), a.tage(), a.faktor(), a.erstes() + 1, a.letztes() + 1));
        pruefe(f6.isEmpty(), "Prüfstein 6: Abschnitte, Faktoren 60/60/4, Tage 180/180/5, alle Bilder da, Karte und Vorschau",
                f6.isEmpty() ? ab + String.format(Locale.GERMAN, "zusammen %.2f Tage, %d Bilder", jm.tage(), jm.bilder.size()) : f6.toString(), "0 Fehler; Tage ± 0,01");
        info("Mappe", String.format(Locale.GERMAN, "%.1f MB (%s), Gitter %d × %d zu %.0f m ab O %.0f, N %.0f; gerechnet in %.0f s", jm.kopf.containsKey("bytes") ? Double.parseDouble(jm.kopf.get("bytes")) / 1e6 : Double.NaN,
                Jahresmappe.BODEN, jm.nx, jm.ny, jm.dx, jm.x0, jm.y0, jm.rechenzeit));

        // 7 Übergänge
        List<String> f7 = new ArrayList<>();
        StringBuilder ue = new StringBuilder();
        for (int k = 1; k < jm.abschnitte.size(); k++) {
            String u = jm.kopf.get("uebergang." + k);
            double[] v = u == null ? null : Jahresmappe.zahlen(u);
            if (v == null || v.length < 2) {
                f7.add("uebergang." + k + " fehlt");
                continue;
            }
            if (!(v[0] <= UEBERGANG)) f7.add(k + ": " + v[0] + " m");
            ue.append(String.format(Locale.GERMAN, "%d → %d: innen %.2f mm, am Rand %.0f Zellen über 1 cm; ", k, k + 1, v[0] * 1000, v[1]));
        }
        pruefe(f7.isEmpty(), "Prüfstein 7: Übergänge zwischen den Läufen – der erste Stand gleicht im Inneren dem letzten des vorigen (der Boden geht weiter)",
                f7.isEmpty() ? ue.toString() : f7.toString(), "≤ 1 mm");

        // 8 Bilder gegen Archiv, Grund gegen Packung
        List<String> f8 = new ArrayList<>();
        List<File> archive = ja.archive(sz);
        int r = ja.rand();
        double groesster = 0;
        int[] proben = {jm.abschnitte.get(0).erstes() + (jm.abschnitte.get(0).letztes() - jm.abschnitte.get(0).erstes()) / 2, jm.abschnitte.get(Math.min(1, jm.abschnitte.size() - 1)).letztes(),
                jm.bilder.size() - 1};
        StringBuilder pr = new StringBuilder();
        Gitter g = null;
        for (int k : proben) {
            Jahresmappe.Bild b = jm.bilder.get(k);
            try (Archivquelle aq = new Archivquelle(archive.get(b.abschnitt()).toPath());
                 com.dan.steuerhaus.ausgabe.Jahresrechnung.Boden q = new com.dan.steuerhaus.ausgabe.Jahresrechnung.Boden(archive.get(b.abschnitt()))) {
                g = q.gitter();
                float[] dz = new float[g.zellen()];
                double ts = (b.zeit().toEpochMilli() - aq.start.toEpochMilli()) / 1000.0;
                int st = java.util.Arrays.binarySearch(q.zeiten, ts);
                if (st < 0) {
                    f8.add("Bild " + (k + 1) + ": kein Stand zur Zeit " + b.zeit());
                    continue;
                }
                q.lies(st, dz);
                short[] s = jm.boden(k);
                double mx = 0;
                for (int j = 0; j < jm.ny; j++)
                    for (int i = 0; i < jm.nx; i++) {
                        double a = dz[(i + r) + g.nx * (j + r)], c = s[i + jm.nx * j] * 0.001;
                        mx = Math.max(mx, Math.abs(a - c));
                    }
                groesster = Math.max(groesster, mx);
                pr.append(String.format(Locale.GERMAN, "Bild %d (Tag %.1f) %.3f mm; ", k + 1, b.tag(), mx * 1000));
            }
        }
        if (groesster > BODEN) f8.add(String.format(Locale.ROOT, "Boden bis %.4f mm", groesster * 1000));
        double gf = Double.NaN;
        if (g != null) {
            Gebiet ge = Gebiet.lies(sz.gebiet());
            Gitter g3 = ge.gitter("G3");
            int i0 = (int) Math.round((g.x0 - g3.x0) / g3.dx), j0 = (int) Math.round((g.y0 - g3.y0) / g3.dy);
            Subgrid.Packung p = ge.packung("G3").fenster(g3, i0, j0, g.nx, g.ny);
            float[] gr = jm.grund();
            gf = 0;
            for (int j = 0; j < jm.ny; j++)
                for (int i = 0; i < jm.nx; i++) {
                    int c = (i + r) + g.nx * (j + r);
                    double oben = p.basis()[c] + 100, vor = oben - p.volumen(c, oben) / p.zellflaeche();
                    gf = Math.max(gf, Math.abs(vor - gr[i + jm.nx * j]));
                }
            if (!(gf <= GRUND)) f8.add("Grund bis " + gf + " m");
        }
        pruefe(f8.isEmpty(), "Prüfstein 8: Bilder gegen das Archiv (bed.dz im Inneren) und Grund gegen die Packung von G3",
                f8.isEmpty() ? pr + String.format(Locale.GERMAN, "Grund %.2g m", gf) : f8.toString(), "≤ 0,5 mm (mm-Stufen); Grund ≤ 0,1 mm");

        // 9 der Sand wie P9c
        double[] ps = Jahresmappe.zahlen(jm.kopf.get("pruef.sand"));
        List<String> f9 = new ArrayList<>();
        Jahresmappe.Bild b0 = jm.bilder.get(0), b1 = jm.bilder.get(jm.bilder.size() - 1);
        double ostI = b1.ost() - b0.ost();
        if (ps == null || ps.length < 6) f9.add("pruef.sand fehlt");
        else {
            double[] w = {ps[0], ps[2], ps[3], ps[4]};
            String[] n = {"Schwerpunkt", "Fläche vor", "Fläche nach", "gewichtet"};
            for (int i = 0; i < 4; i++) if (!(Math.abs(w[i] - P9C[i][0]) <= P9C[i][1])) f9.add(n[i] + " " + w[i]);
            if (!(Math.abs(ostI - ps[0]) <= 0.05)) f9.add(String.format(Locale.ROOT, "im Inneren %.3f gegen %.3f", ostI, ps[0]));
        }
        pruefe(f9.isEmpty(), "Prüfstein 9: " + ja.sand() + " nach dem Jahr wie P9c Prüfstein 13 (Werkbuch 34: +6,3 m nach Osten, 6,50 → 6,51 km², gewichtet +8,4 m)",
                ps == null ? "–" : String.format(Locale.GERMAN, "%+.2f m nach Osten, %+.2f m nach Norden, %.3f → %.3f km², gewichtet %+.2f m, Volumen %+,.0f m³; im Inneren %+.2f m", ps[0], ps[1], ps[2], ps[3],
                        ps[4], ps[5], ostI) + (f9.isEmpty() ? "" : " – " + f9), "± 0,15 m; ± 0,006 km²");
        for (var a : jm.abschnitte) {
            Jahresmappe.Bild e = jm.bilder.get(a.letztes());
            info("Ende " + a.name(), String.format(Locale.GERMAN, "Tag %.1f: %s %+.2f m nach Osten, %.3f km²; Auftrag %.2f, Abtrag %.2f Mio. m³; größte Änderung %.2f m", e.tag(), ja.sand(),
                    e.ost() - b0.ost(), e.flaeche() / 1e6, e.auftrag() / 1e6, e.abtrag() / 1e6, e.groesste()));
        }

        // 10 Zeit je Bild beim Abspielen (ohne Fenster): Boden zum Tag, Linie der Schwelle, Differenzkarte
        double[] ms = new double[2];
        BufferedImage[] bi = new BufferedImage[3];
        Thema.NAUTIK.anwenden();
        SwingUtilities.invokeAndWait(() -> {
            Jahresansicht va = new Jahresansicht(jm, ja.dauer(), ja.hinweis(), ja.sand(), ZoneId.of(m.text("app.zone", "Europe/Berlin")), new File(System.getProperty("java.io.tmpdir")));
            int n = 360;
            long t0 = System.nanoTime();
            for (int i = 0; i <= n; i++) {
                va.zeige(va.plan().tag(i * va.plan().gesamt / n));
                va.gitterBild();
            }
            ms[0] = (System.nanoTime() - t0) / 1e6 / (n + 1);
            long t1 = System.nanoTime();
            for (int i = 0; i <= 60; i++) {
                va.zeige(va.plan().tag(i * va.plan().gesamt / 60));
                va.alsBild(1600, 1000);
            }
            ms[1] = (System.nanoTime() - t1) / 1e6 / 61;
            va.zeige(jm.tage());
            bi[0] = va.alsBild(1600, 1000);
            va.darstellung(Jahresansicht.Darstellung.HOEHE);
            bi[1] = va.alsBild(1600, 1000);
            va.darstellung(Jahresansicht.Darstellung.DIFFERENZ);
            va.zeige(jm.abschnitte.get(0).tag1());
            bi[2] = va.alsBild(1600, 1000);
            va.ende();
        });
        pruefe(ms[0] <= JE_BILD_MS, "Prüfstein 10: Zeit je Bild beim Abspielen – Boden zum Tag (zwischen zwei Bildern), Linie der Schwelle, Differenzkarte; ohne Fenster",
                String.format(Locale.GERMAN, "%.1f ms je Bild (361 Schritte über das Jahr); mit dem ganzen Fenster gemalt 1600 × 1000: %.1f ms", ms[0], ms[1]), "≤ 15 ms [G]");
        if (bilder != null) {
            bilder.mkdirs();
            String[] namen = {"Y3_Jahr_Ende.png", "Y3_Jahr_Ende_Hoehe.png", "Y3_Jahr_Tag180.png"};
            for (int i = 0; i < 3; i++) if (bi[i] != null) ImageIO.write(bi[i], "png", new File(bilder, namen[i]));
            info("Bilder", String.join(", ", namen));
        }
        ende();
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
