package com.dan.steuerhaus.demo;

import com.dan.core.raum.Blick;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.ausgabe.Aufnahme;
import com.dan.steuerhaus.ausgabe.Ausgabe;
import com.dan.steuerhaus.ausgabe.Kodierer;
import com.dan.steuerhaus.ausgabe.Korn;
import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.regie.Regiebuch;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Prüfung der Ausgabe (W4, Werkbuch HalligHooge 121), mit Karte, ohne Fenster:
 * <ol>
 * <li>Prüfstein 1: dieselbe Aufnahme zweimal (48 Filmbilder, 8 Teilbilder, Korn, Einblendungen, Fahrt) – bitgleich.</li>
 * <li>Prüfstein 2: Filmzeit = Bilder / B/s genau; Szenenzeit je Teilbild gegen den Zeitraffer ± 1 ms.</li>
 * <li>Prüfstein 3: das Filmbild gegen das Mittel seiner Teilbilder (linear) ≤ 1/255; die Unschärfe wirkt.</li>
 * <li>Prüfstein 4: Korn – Streuung gegen das Modell (± 15 % [G]), Mittel unverändert (≤ 0,5/255).</li>
 * <li>Prüfstein 5: Standbild 4K gegen 1080p (4K im Linearen auf 1080p verkleinert) ≤ 3/255 im Mittel [G]; Speicher der
 * Karte bei 4K ≤ 3 GB.</li>
 * <li>Prüfstein 7: Kosten je Filmbild in 1080p mit 8 Teilbildern und Korn ≤ 0,5 s [G]; 4K zur Auskunft.</li>
 * </ol>
 * Mit {@code --datei x.mp4}: Prüfstein 6 – ffprobe gegen die Begleitdatei {@code x.mp4.txt} (Bilder, Größe, Bildrate).
 * Bilder: {@code W4_Standbild_4K.png}, {@code W4_Unschaerfe.png}.
 */
public final class AusgabeCheck {
    static int steine, verstoesse;
    static final String PROBE = """
            Regiebuch: Probe der Ausgabe
            Beschreibung: Fahrt über die Hanswarft bei steigendem Wasser
            Länge: 0:03
            0:00 szenario sturm
            0:00 zeit 25.10.2026 13:20
            0:00 raffer 60
            0:00 einblendung uhr,kompass
            0:00 fahrt Probe der Ausgabe

            Fahrt: Probe der Ausgabe
            0:00 470560 6046330 40 nhn 60 -12 50
            0:02 470690 6046390 30 nhn 90 -10 50
            Ende
            """;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null, datei = null;
        String ffmpeg = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                case "--datei" -> datei = new File(args[++i]);
                case "--ffmpeg" -> ffmpeg = args[++i];
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("AusgabeCheck – Steuerhaus W4 (Werkbuch HalligHooge 121)");
        try {
            if (datei != null) datei(datei, ffmpeg);
            else alles(Manifest.lies(manifest), bilder);
        } catch (Throwable x) {
            x.printStackTrace();
            pruefe(false, "Ablauf", x.toString(), "ohne Fehler");
        }
        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    record Lauf(List<byte[]> bilder, List<float[]> linear, List<Double> zeiten, List<byte[]> teil, double filmZeit, double jeBild, Aufnahme.Optionen o, Korn korn) { }

    /** Eine Aufnahme der Probe: n Filmbilder; mit {@code teilAus} die Teilbilder des Filmbilds {@code teilAus}. */
    static Lauf lauf(Manifest m, Aufnahme.Optionen o, int n, int teilAus) throws Exception {
        List<byte[]> bs = new ArrayList<>(), teil = new ArrayList<>();
        List<float[]> lin = new ArrayList<>();
        try (Aufnahme a = new Aufnahme(m, o, null)) {
            Regiebuch rb = Regiebuch.lies(PROBE, "Probe");
            if (!rb.fehler.isEmpty()) throw new IllegalStateException("Probe: " + rb.fehler);
            a.spiele(rb, false);
            int[] nr = {0};
            a.teilbild = p -> {
                if (nr[0] == teilAus) teil.add(p.clone());
            };
            long n0 = 0;
            for (int i = 0; i < n; i++) {
                if (i == 2) n0 = System.nanoTime();                         // die ersten zwei: Laden, Sprung
                nr[0] = i;
                bs.add(a.bild());
                lin.add(a.linear().clone());
            }
            double je = (System.nanoTime() - n0) / 1e9 / Math.max(1, n - 2);
            return new Lauf(bs, lin, new ArrayList<>(a.zeiten), teil, a.b.filmZeit(), je, o, a.korn);
        }
    }

    static void alles(Manifest m, File ordner) throws Exception {
        int w = 960, h = 540, n = 48;
        Aufnahme.Optionen mitKorn = new Aufnahme.Optionen(w, h, 24, 8, 180, 1, true, 1958), ohneKorn = new Aufnahme.Optionen(w, h, 24, 8, 180, 0, true, 1958);

        teil("1 Unabhängig von der Echtzeit");
        Lauf a = lauf(m, mitKorn, n, -1);
        Lauf b = lauf(m, mitKorn, n, -1);
        int gleich = 0;
        long abw = 0;
        for (int i = 0; i < n; i++) {
            if (Arrays.equals(a.bilder.get(i), b.bilder.get(i))) gleich++;
            else for (int k = 0; k < a.bilder.get(i).length; k++) if (a.bilder.get(i)[k] != b.bilder.get(i)[k]) abw++;
        }
        pruefe(gleich == n, "Prüfstein 1: zweimal dieselbe Aufnahme (" + w + " × " + h + ", 8 Teilbilder, Korn, Uhr und Kompass, Fahrt)",
                gleich + " von " + n + " Filmbildern bitgleich" + (abw > 0 ? ", " + abw + " Werte verschieden" : ""), "bitgleich");

        teil("2 Bildzahl und Zeit");
        pruefe(Math.abs(a.filmZeit - n / 24.0) < 1e-9, "Prüfstein 2: Filmzeit nach " + n + " Bildern", String.format(Locale.GERMAN, "%.9f s (soll %.9f s)", a.filmZeit, n / 24.0), "genau");
        double T = 1 / 24.0, offen = T / 2, groesst = 0;
        List<Double> z = a.zeiten;
        for (int k = 9; k < z.size(); k++) {                                // ab dem zweiten Filmbild: der Sprung ist gesetzt
            double soll = 60 * (k % 8 == 0 ? (T - offen) + offen / 8 : offen / 8);
            groesst = Math.max(groesst, Math.abs((z.get(k) - z.get(k - 1)) - soll));
        }
        pruefe(groesst <= 0.001, "Prüfstein 2: Szenenzeit je Teilbild gegen den Zeitraffer 60", String.format(Locale.GERMAN, "größte Abweichung %.6f s über %d Teilbilder", groesst, z.size() - 9), "± 1 ms");

        teil("3 Teilbilder");
        Lauf d = lauf(m, ohneKorn, 30, 29);
        float[] lin = new float[256];
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            lin[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        float[] mittel = new float[3 * w * h];
        for (byte[] p : d.teil)
            for (int i = 0, j = 0; i < mittel.length; i += 3, j += 4)
                for (int c = 0; c < 3; c++) mittel[i + c] += lin[p[j + c] & 0xFF] / d.teil.size();
        byte[] soll = Aufnahme.srgb(mittel), ist = d.bilder.get(29);
        int gr = 0;
        for (int i = 0; i < soll.length; i++) gr = Math.max(gr, Math.abs((soll[i] & 0xFF) - (ist[i] & 0xFF)));
        pruefe(d.teil.size() == 8 && gr <= 1, "Prüfstein 3: Filmbild 30 gegen das Mittel seiner Teilbilder", d.teil.size() + " Teilbilder, größte Abweichung " + gr + "/255", "≤ 1/255");
        byte[] erstes = Aufnahme.srgb(lin(d.teil.get(0), lin)), letztes = Aufnahme.srgb(lin(d.teil.get(d.teil.size() - 1), lin));
        double weg = mittel(erstes, letztes);
        pruefe(weg > 0.5, "Prüfstein 3: die Unschärfe wirkt (erstes gegen letztes Teilbild)", String.format(Locale.GERMAN, "%.2f/255 im Mittel", weg), "> 0,5/255");
        if (ordner != null) {
            byte[] streifen = new byte[3 * w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) {
                    byte[] q = x < w / 3 ? erstes : x < 2 * w / 3 ? ist : letztes;
                    System.arraycopy(q, 3 * (x + w * y), streifen, 3 * (x + w * y), 3);
                }
            Ausgabe.schreibePng(streifen, w, h, new File(ordner, "W4_Unschaerfe.png"));
            info("Bild", "W4_Unschaerfe.png (links erstes Teilbild, Mitte das Filmbild, rechts letztes Teilbild)");
        }

        teil("4 Korn");
        // dieselbe Folge mit und ohne Korn: der Unterschied ist das Korn
        Lauf e = lauf(m, ohneKorn, n, -1);
        float[] mk = a.linear.get(n - 1), ok = e.linear.get(n - 1);
        double sq = 0, sumD = 0;
        long zahl = 0;
        for (int i = 0; i < ok.length; i++) {
            double L = ok[i];
            if (L < 0.01 || L > 0.9) continue;
            double s = a.korn.sigma(i % 3, L);
            if (s <= 0) continue;
            double dd = mk[i] - L;
            sq += dd * dd / (s * s);
            sumD += dd;
            zahl++;
        }
        double verh = Math.sqrt(sq / Math.max(1, zahl));
        pruefe(Math.abs(verh - 1) <= 0.15, "Prüfstein 4: Streuung des Korns gegen das Modell (Selwyn, Vision3 250D, " + w + " px breit)",
                String.format(Locale.GERMAN, "%.3f (über %d Werte)", verh, zahl), "1 ± 0,15 [G]");
        double ma = Math.abs(mittel(Aufnahme.srgb(mk), Aufnahme.srgb(ok), true));
        pruefe(ma <= 0.5, "Prüfstein 4: Mittel mit gegen ohne Korn", String.format(Locale.GERMAN, "%.3f/255", ma), "≤ 0,5/255");
        info("Korn", String.format(Locale.GERMAN, "Streuung im Linearen bei L = 0,18: R %.4f, G %.4f, B %.4f (%.1f %%, %.1f %%, %.1f %%)", a.korn.sigma(0, 0.18), a.korn.sigma(1, 0.18),
                a.korn.sigma(2, 0.18), 100 * a.korn.sigma(0, 0.18) / 0.18, 100 * a.korn.sigma(1, 0.18) / 0.18, 100 * a.korn.sigma(2, 0.18) / 0.18));

        teil("5 Standbild 4K");
        Lesezeichen lz = null;
        byte[] voll, k4;
        long spitze;
        try (Aufnahme x = new Aufnahme(m, new Aufnahme.Optionen(1920, 1080, 24, 1, 360, 0, false, 1958), null)) {
            lz = x.lesezeichen.isEmpty() ? null : x.lesezeichen.get(0);
            if (lz == null) throw new IllegalStateException("kein Lesezeichen im Projekt");
            x.oeffne(x.sz.szenario(lz.szenario()));
            voll = x.standbild(blick(lz, 1920, 1080), sek(x, lz));
        }
        try (Aufnahme x = new Aufnahme(m, new Aufnahme.Optionen(3840, 2160, 24, 1, 360, 0, false, 1958), null)) {
            x.oeffne(x.sz.szenario(lz.szenario()));
            k4 = x.standbild(blick(lz, 3840, 2160), sek(x, lz));
            spitze = x.b.speicher()[1];
            if (ordner != null) Ausgabe.schreibePng(k4, 3840, 2160, new File(ordner, "W4_Standbild_4K.png"));
        }
        float[] klein = new float[3 * 1920 * 1080];
        for (int y = 0; y < 1080; y++)
            for (int x = 0; x < 1920; x++)
                for (int c = 0; c < 3; c++) {
                    double s = 0;
                    for (int dy = 0; dy < 2; dy++) for (int dx = 0; dx < 2; dx++) s += lin[k4[3 * ((2 * x + dx) + 3840 * (2 * y + dy)) + c] & 0xFF];
                    klein[3 * (x + 1920 * y) + c] = (float) (s / 4);
                }
        double m4 = mittel(Aufnahme.srgb(klein), voll);
        pruefe(m4 <= 3, "Prüfstein 5: Standbild 4K (verkleinert) gegen 1080p, „" + lz.name() + "“", String.format(Locale.GERMAN, "%.2f/255 im Mittel", m4), "≤ 3/255 [G]");
        pruefe(spitze <= 3L << 30, "Prüfstein 5: Speicher der Karte bei 4K", String.format(Locale.GERMAN, "Spitze %.0f MB", spitze / 1048576.0), "≤ 3 GB");

        teil("7 Kosten");
        Lauf k = lauf(m, new Aufnahme.Optionen(1920, 1080, 24, 8, 180, 1, true, 1958), 14, -1);
        pruefe(k.jeBild <= 0.5, "Prüfstein 7: Kosten je Filmbild 1920 × 1080, 8 Teilbilder, Korn", String.format(Locale.GERMAN, "%.3f s (Mittel über 12)", k.jeBild), "≤ 0,5 s [G]");
        Lauf k4l = lauf(m, new Aufnahme.Optionen(3840, 2160, 24, 8, 180, 1, true, 1958), 5, -1);
        info("Kosten je Filmbild 3840 × 2160, 8 Teilbilder, Korn", String.format(Locale.GERMAN, "%.3f s (Mittel über 3)", k4l.jeBild));
    }

    static float[] lin(byte[] rgba, float[] lin) {
        float[] r = new float[rgba.length / 4 * 3];
        for (int i = 0, j = 0; i < r.length; i += 3, j += 4) for (int c = 0; c < 3; c++) r[i + c] = lin[rgba[j + c] & 0xFF];
        return r;
    }

    static double mittel(byte[] a, byte[] b) { return mittel(a, b, false); }

    /** Mittlere Abweichung (Betrag, oder mit Vorzeichen) in 1/255. */
    static double mittel(byte[] a, byte[] b, boolean vorzeichen) {
        double s = 0;
        for (int i = 0; i < a.length; i++) {
            int d = (a[i] & 0xFF) - (b[i] & 0xFF);
            s += vorzeichen ? d : Math.abs(d);
        }
        return s / a.length;
    }

    static Blick blick(Lesezeichen lz, int w, int h) {
        double[] v = lz.blick();
        Blick b = new Blick();
        b.ost = v[0];
        b.nord = v[1];
        b.hoehe = v[2];
        b.richtung = v[3];
        b.neigung = v[4];
        b.bildwinkel = v[5];
        return b.groesse(w, h);
    }

    static double sek(Aufnahme x, Lesezeichen lz) { return (lz.zeit().toEpochMilli() - x.b.uhr().start.toEpochMilli()) / 1000.0; }

    /** Prüfstein 6: ffprobe gegen die Begleitdatei. */
    static void datei(File f, String ffmpeg) throws Exception {
        teil("6 Die Datei " + f.getName());
        Map<String, String> soll = new HashMap<>();
        for (String l : Files.readAllLines(new File(f.getPath() + ".txt").toPath())) {
            int i = l.indexOf('=');
            if (i > 0) soll.put(l.substring(0, i).strip(), l.substring(i + 1).strip());
        }
        String ff = Kodierer.ffmpeg(ffmpeg);
        String probe = ff == null ? "ffprobe" : ff.replaceFirst("ffmpeg(\\.exe)?$", "ffprobe$1");
        Process p = new ProcessBuilder(probe, "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=nb_read_frames,width,height,r_frame_rate,pix_fmt,codec_name",
                "-of", "default=noprint_wrappers=1", f.getAbsolutePath()).redirectErrorStream(true).start();
        String aus = new String(p.getInputStream().readAllBytes());
        p.waitFor();
        Map<String, String> ist = new HashMap<>();
        for (String l : aus.split("\\R")) {
            int i = l.indexOf('=');
            if (i > 0) ist.put(l.substring(0, i).strip(), l.substring(i + 1).strip());
        }
        info("ffprobe", aus.strip().replace("\n", ", ").replace("\r", ""));
        pruefe(soll.get("bilder") != null && soll.get("bilder").equals(ist.get("nb_read_frames")), "Prüfstein 6: Bilder", ist.get("nb_read_frames") + " gegen " + soll.get("bilder"), "genau");
        pruefe((soll.get("breite") + "x" + soll.get("hoehe")).equals(ist.get("width") + "x" + ist.get("height")), "Prüfstein 6: Größe", ist.get("width") + " × " + ist.get("height"), soll.get("breite") + " × " + soll.get("hoehe"));
        String r = ist.getOrDefault("r_frame_rate", "?");
        double rate = Double.NaN;
        if (r.contains("/")) rate = Double.parseDouble(r.split("/")[0]) / Double.parseDouble(r.split("/")[1]);
        pruefe(Math.abs(rate - Double.parseDouble(soll.getOrDefault("bilder_je_s", "NaN"))) < 1e-6, "Prüfstein 6: Bildrate", r, soll.get("bilder_je_s"));
        info("Kodierung", ist.get("codec_name") + ", " + ist.get("pix_fmt") + "; Rechenzeit " + soll.get("rechenzeit_s") + " s, je Bild " + soll.get("je_bild_s") + " s, Speicher Spitze " + soll.get("speicher_spitze_mb") + " MB");
    }

    static void teil(String t) { System.out.println("\n== " + t); }

    static void info(String g, String w) { System.out.println("  info   " + g + ": " + w); }

    static void pruefe(boolean ok, String g, String w, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + g + ": " + w + "  [" + grenze + "]");
    }
}
