package com.dan.steuerhaus.demo;

import com.dan.core.gitter.Gebiet;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Sonde;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.analyse.Protokoll;
import com.dan.steuerhaus.analyse.Qualitaet;
import com.dan.steuerhaus.ausgabe.Aufnahme;
import com.dan.steuerhaus.ausgabe.Ausgabe;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Locale;

/**
 * Prüfung von W5 (Werkbuch HalligHooge 126): Sonden, Vergleich, Leistung, Qualitätsstufe, Protokoll.
 * <ol>
 * <li>Prüfstein 1: die Sonde an der Zelle des Pegels gegen die Pegelreihe (gleiche Zelle) – gleich; Zeit je Sonde ≤ 2 s.</li>
 * <li>Prüfstein 2: Strömung der Sonde gegen die Quelle an derselben Zelle und Zeit (das erste Szenario mit Strömung).</li>
 * <li>Prüfstein 3: das Vergleichsbild – jede Hälfte gegen ein einzelnes Standbild bitgleich (mit Karte).</li>
 * <li>Prüfstein 4: Kosten von {@code bildzeitMerken} in der Bildschleife ≤ 0,05 ms (mit Karte).</li>
 * <li>Prüfstein 5: Qualitätsstufe – unter künstlicher Last herunter (≤ 5 s), ohne Last wieder hinauf, ohne Pendeln.</li>
 * <li>Prüfstein 6: Protokoll – alles in der Datei, Wechsel der Dateien (≤ 5 × 1 MB).</li>
 * </ol>
 * Mit {@code --ohne-karte} nur 1, 2, 5, 6.
 */
public final class AnalyseCheck {
    static int steine, verstoesse;

    public static void main(String[] args) throws Exception {
        File manifest = null;
        boolean karte = true;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--ohne-karte" -> karte = false;
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("AnalyseCheck – Steuerhaus W5 (Werkbuch HalligHooge 126)");
        try {
            qualitaet();
            protokoll();
            if (manifest != null) {
                Manifest m = Manifest.lies(manifest);
                Szenen sz = Szenen.lies(m.szenen());
                sonden(sz);
                if (karte) karte(m);
            }
        } catch (Throwable x) {
            x.printStackTrace();
            pruefe(false, "Ablauf", x.toString(), "ohne Fehler");
        }
        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    static void sonden(Szenen sz) throws Exception {
        teil("1 Sonde gegen die Pegelreihe");
        Gebiet ge = Gebiet.lies(sz.gebiet());
        Szenario s = sz.szenario("sturm");
        double[] po = sz.zahlen("pegel.ort");
        try (Archivquelle q = new Archivquelle(s.archiv.toPath())) {
            Pegelreihe p = Pegelreihe.aus(q, q.start, ge, "Hooge", po[0], po[1], sz.zahl("pegel.umkreis", 300));
            double ox = p.gitter.x(p.zelle % p.gitter.nx), oy = p.gitter.y(p.zelle / p.gitter.nx);
            Sonde so = Sonde.aus(q, q.start, ge, "Probe", ox, oy);
            Sonde.Reihe w = so.reihe(Sonde.WASSERSTAND);
            double[] pp = p.pegel();
            boolean[] nass = p.nassAlle();
            // seit W5b lesen Sonde und Pegelreihe nur die Zelle (Archiv.wert); die Probe liest ganze Stände wie bis W5
            double[] tz = p.zeiten();
            int nz = p.gitter.zellen();
            float[] e = new float[nz], h = new float[nz], al = new float[nz];
            int gleich = 0, n = 0, nassGleich = 0;
            double groesst = 0;
            for (int i = 0; i < pp.length; i++) {
                if (!q.stand(tz[i], p.k, e, h, al)) continue;
                boolean na = al[p.zelle] > 0 || al[p.zelle] == 0 && h[p.zelle] > 0;
                if (na == nass[i]) nassGleich++;
                if (!na) continue;
                n++;
                double d = Math.max(Math.abs(e[p.zelle] - w.wert()[i]), Math.abs(e[p.zelle] - pp[i]));
                groesst = Math.max(groesst, d);
                if (d == 0) gleich++;
            }
            pruefe(so.pegel.zelle == p.zelle && gleich == n && n > 0 && nassGleich == pp.length, "Prüfstein 1: Wasserstand der Sonde und der Pegelreihe an der Zelle des Pegels gegen ganze Stände",
                    String.format(Locale.GERMAN, "%d von %d nassen Ständen gleich, nass/trocken %d von %d gleich, größte Abweichung %.6f m; Zelle %d gegen %d", gleich, n, nassGleich, pp.length, groesst, so.pegel.zelle, p.zelle), "gleich");
            pruefe(so.sekunden <= 2, "Prüfstein 1: Zeit für eine Sonde (" + w.t().length + " Stände, " + so.reihen.size() + " Reihen)",
                    String.format(Locale.GERMAN, "%.2f s", so.sekunden), "≤ 2 s [G]");
            Sonde.Reihe d = so.reihe(Sonde.TIEFE);
            info("Sonde am Pegel", String.format(Locale.GERMAN, "Grund %+.2f m NHN, Tiefe %.2f … %.2f m, Wasserstand %.2f … %.2f m; Reihen %s", so.grund, d.min(), d.max(), w.min(), w.max(),
                    so.reihen.stream().map(Sonde.Reihe::name).toList()));
        }

        teil("2 Strömung");
        boolean gefunden = false;
        for (Szenario x : sz.szenarien()) {
            if (x.archiv == null) continue;
            try (Archivquelle q = new Archivquelle(x.archiv.toPath())) {
                if (q.stroemungGitter() == null) continue;
                gefunden = true;
                var g = q.stroemungGitter();
                double[] ts = q.stroemungZeiten();
                float[] u = new float[g.zellen()], v = new float[g.zellen()];
                // seit W5b: die Zelle mit der stärksten Strömung zur Mitte der Zeit (die Mitte des Gitters lag trocken)
                int c = 0;
                if (q.stroemung(ts[ts.length / 2], u, v))
                    for (int i = 1; i < u.length; i++) if (Math.hypot(u[i], v[i]) > Math.hypot(u[c], v[c])) c = i;
                double ox = g.x(c % g.nx), oy = g.y(c / g.nx);
                Sonde so = Sonde.aus(q, q.start, ge, "Strom", ox, oy);
                Sonde.Reihe r = so.reihe(Sonde.STROEMUNG);
                double groesst = 0;
                for (int k = 0; k < ts.length; k += Math.max(1, ts.length / 20)) {
                    if (!q.stroemung(ts[k], u, v)) continue;
                    groesst = Math.max(groesst, Math.abs(Math.hypot(u[c], v[c]) - r.wert()[k]));
                }
                pruefe(r != null && groesst <= 1e-6, "Prüfstein 2: Strömung der Sonde gegen die Quelle (" + x.name + ", Zelle der stärksten Strömung)",
                        String.format(Locale.GERMAN, "größte Abweichung %.2e m/s über %d Zeiten; %.2f … %.2f m/s", groesst, ts.length, r.min(), r.max()), "≤ 10⁻⁶ m/s");
                break;
            }
        }
        if (!gefunden) info("Prüfstein 2", "kein Szenario mit Strömung im Archiv – nicht prüfbar");
    }

    static void karte(Manifest m) throws Exception {
        teil("3 Vergleichsbild");
        File tmp = Files.createTempDirectory("vergleich").toFile();
        Ausgabe.Vergleich v;
        String lz;
        try (Aufnahme a = new Aufnahme(m, new Aufnahme.Optionen(960, 540, 24, 1, 360, 0, false, 1958), null)) {
            lz = a.lesezeichen.get(0).name();
            v = Ausgabe.vergleich(a, lz, null, "ruhig", false, new File(tmp, "V.png"));
        }
        for (int k = 0; k < 2; k++)
            try (Aufnahme a = new Aufnahme(m, new Aufnahme.Optionen(960, 540, 24, 1, 360, 0, false, 1958), null)) {
                Szenario s = k == 0 ? v.sa() : v.sb();
                a.oeffne(s);
                byte[] allein = a.standbild(v.blick(), k == 0 ? v.tsa() : v.tsb());
                BufferedImage im = ImageIO.read(k == 0 ? v.a() : v.b());
                int abw = 0;
                for (int y = 0; y < 540; y++)
                    for (int x = 0; x < 960; x++) {
                        int p = im.getRGB(x, y), i = 3 * (x + 960 * y);
                        if (((p >> 16) & 0xFF) != (allein[i] & 0xFF) || ((p >> 8) & 0xFF) != (allein[i + 1] & 0xFF) || (p & 0xFF) != (allein[i + 2] & 0xFF)) abw++;
                    }
                pruefe(abw == 0, "Prüfstein 3: Vergleichsbild, Hälfte " + (k == 0 ? "A (" : "B (") + s.name + ") gegen ein einzelnes Standbild", abw + " Bildpunkte verschieden", "bitgleich");
            }
        info("Vergleich", v.sa().name + " " + String.format(Locale.GERMAN, "%.0f s", v.tsa()) + " | " + v.sb().name + " " + String.format(Locale.GERMAN, "%.0f s", v.tsb()) + " (gleiche Uhrzeit)");

        teil("4 Leistung");
        try (Aufnahme a = new Aufnahme(m, new Aufnahme.Optionen(320, 180, 24, 1, 360, 0, false, 1958), null)) {
            int n = 1_000_000;
            long n0 = System.nanoTime();
            for (int i = 0; i < n; i++) a.b.bildzeitMerken(n0 + i, 16.7f);
            double ms = (System.nanoTime() - n0) / 1e6 / n;
            float[] f = a.b.bildzeitVerlauf(1e9);
            pruefe(ms <= 0.05 && f.length == 4096, "Prüfstein 4: Kosten je Bild in der Bildschleife (bildzeitMerken)", String.format(Locale.GERMAN, "%.6f ms; Verlauf %d Werte", ms, f.length), "≤ 0,05 ms [G]");
        }
    }

    static void qualitaet() {
        teil("5 Qualitätsstufe");
        Qualitaet q = new Qualitaet();
        q.automatisch(true);
        double t = 0, runter = Double.NaN, hinauf = Double.NaN;
        int wechselLast = 0;
        // 10 s ohne Last (60 B/s), 20 s Last (20 B/s), 40 s ohne Last; je 0,2 s ein Takt
        Qualitaet.Stufe alt = q.stufe();
        double letzter = Double.NEGATIVE_INFINITY, kuerzester = Double.POSITIVE_INFINITY;
        for (; t < 70; t += 0.2) {
            double fps = t < 10 ? 60 : t < 30 ? (q.stufe() == Qualitaet.Stufe.NIEDRIG ? 33 : 20) : 60;
            Qualitaet.Stufe s = q.takt(t, fps);
            if (s != alt) {
                if (Double.isNaN(runter) && s.ordinal() > alt.ordinal()) runter = t - 10;
                if (t >= 30 && Double.isNaN(hinauf) && s.ordinal() < alt.ordinal()) hinauf = t - 30;
                if (!Double.isInfinite(letzter)) kuerzester = Math.min(kuerzester, t - letzter);
                letzter = t;
                alt = s;
            }
        }
        pruefe(runter <= 5, "Prüfstein 5: unter Last (20 B/s) eine Stufe herunter", String.format(Locale.GERMAN, "nach %.1f s", runter), "≤ 5 s");
        pruefe(!Double.isNaN(hinauf) && q.stufe() == Qualitaet.Stufe.HOCH, "Prüfstein 5: ohne Last wieder hinauf bis Hoch", String.format(Locale.GERMAN, "erste Stufe nach %.1f s, am Ende %s", hinauf, q.stufe().name), "Hoch");
        pruefe(kuerzester >= Qualitaet.RUHE_S - 1e-6, "Prüfstein 5: kein Pendeln (kürzester Abstand zweier Wechsel)", String.format(Locale.GERMAN, "%.1f s bei %d Wechseln", kuerzester, q.wechsel()), "≥ 15 s");
        Qualitaet r = new Qualitaet();
        r.automatisch(true);
        for (double u = 0; u < 120; u += 0.2) r.takt(u, (int) (u * 5) % 2 == 0 ? 20 : 60);
        pruefe(r.wechsel() == 0, "Prüfstein 5: flackernde Bildrate (20/60 B/s im Wechsel)", r.wechsel() + " Wechsel in 120 s", "0");
        // seit C1b (A53): im Kino geht die Automatik höchstens bis Mittel
        Qualitaet k = new Qualitaet();
        k.automatisch(true);
        k.tiefste(Qualitaet.Stufe.MITTEL);
        for (double u = 0; u < 120; u += 0.5) k.takt(u, 20);
        pruefe(k.stufe() == Qualitaet.Stufe.MITTEL && k.wechsel() == 1, "Prüfstein 5: im Kino (tiefste Stufe Mittel) unter Last 120 s", k.stufe().name + ", " + k.wechsel() + " Wechsel", "Mittel, 1 Wechsel");
    }

    static void protokoll() throws Exception {
        teil("6 Protokoll");
        File d = Files.createTempDirectory("protokoll").toFile();
        Protokoll p = new Protokoll(d);
        String z = "x".repeat(200);
        int n = 0;
        while (n < 40_000) p.schreibe("Probe", n++ + " " + z);
        p.schreibe("Hinweis", "das Letzte");
        p.schliessen();
        File[] fs = d.listFiles();
        long groesst = 0;
        for (File f : fs) groesst = Math.max(groesst, f.length());
        String letzte = Files.readString(p.datei().toPath());
        pruefe(fs.length <= Protokoll.DATEIEN && groesst <= Protokoll.GROESSE && letzte.contains("das Letzte"), "Prüfstein 6: Wechsel der Dateien nach rund 8 MB",
                fs.length + " Dateien, größte " + groesst / 1024 + " KB, letzter Eintrag in protokoll.txt", "≤ 5 × 1 MB");
        pruefe(p.letzte().size() == Protokoll.ZEILEN && p.letzte().get(Protokoll.ZEILEN - 1).text().equals("das Letzte"), "Prüfstein 6: die letzten Einträge für die Liste",
                p.letzte().size() + " Einträge", Protokoll.ZEILEN + "");
    }

    static void teil(String t) { System.out.println("\n== " + t); }

    static void info(String g, String w) { System.out.println("  info   " + g + ": " + w); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + g + ": " + w + "  [" + grenze + "]");
    }
}
