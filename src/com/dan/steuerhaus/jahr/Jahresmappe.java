package com.dan.steuerhaus.jahr;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Die Mappe eines Jahrs (seit Y3, Werkbuch HalligHooge 154): der Boden eines Modelljahrs aus mehreren Läufen der
 * Morphologie, einmal von der Ausgabe gerechnet ({@code --jahr}), danach nur gelesen. Allgemein: kein Ort, keine Szene;
 * Abschnitte, Kasten und Profil gibt das Manifest.
 * <p>
 * <b>Ordner:</b>
 * <ul>
 * <li>{@value #DATEI} – Kopf ({@code schluessel = wert}): Gitter (Südwestecke, Zelle, Zahl der Zellen; ohne den
 *     Randstreifen der Läufe), Abschnitte, Kasten, Schwelle, Profil, Orte, Kennung, Rechenzeit, Prüfwerte;</li>
 * <li>{@value #GRUND} – die Höhe des Bodens zu Beginn (m NHN, float, little endian, Zeilen von Süd);</li>
 * <li>{@value #BODEN} – je Bild die Änderung seit Beginn ({@code bed.dz}) in mm als short, je Bild mit Deflate gepackt;</li>
 * <li>{@value #BILDER} – je Bild Abschnitt, Modelltag, Zeit im Archiv und die Kennzahlen;</li>
 * <li>{@value #KARTE} – das Gelände von oben ohne Wasser über demselben Rechteck; {@value #VORSCHAU} – die Kachel.</li>
 * </ul>
 * <b>Zeit:</b> Modelltage zählen ab dem ersten Stand des Bodens im ersten Abschnitt; im Abschnitt k ist
 * Tag = Tag₀(k) + (t − t₀(k)) · Faktor(k) / 86 400 s. Zwischen zwei Bildern gilt die Gerade.
 */
public final class Jahresmappe {
    public static final String DATEI = "jahr.txt", GRUND = "grund.f32", BODEN = "dz.bin", BILDER = "bilder.csv", KARTE = "karte.jpg", VORSCHAU = "vorschau.jpg";
    static final byte[] MAGIE = "HHJAHR01".getBytes(StandardCharsets.US_ASCII);
    /** Wert eines Bildpunkts ohne Boden (im short). */
    public static final short LEER = Short.MIN_VALUE;
    /** Bilder im Speicher (je rund 0,5 MB) [G]. */
    static final int VORRAT = 32;

    /** Ein Abschnitt: Szenario, Titel, Faktor des Bodens, Zeit im Archiv, Modelltage, erstes und letztes Bild. */
    public record Abschnitt(String szenario, String name, double faktor, Instant von, Instant bis, double tag0, double tag1, int erstes, int letztes) {
        public double tage() { return tag1 - tag0; }
    }

    /**
     * Ein Bild: Nummer, Abschnitt (0 …), Modelltag, Zeit im Archiv, Auftrag und Abtrag im Fenster seit Beginn (m³), Japsand
     * (Fläche über der Schwelle im Kasten m², Schwerpunkt der Fläche Ost, Nord m, Volumen über der Schwelle m³, Schwerpunkt
     * gewichtet mit der Höhe über der Schwelle Ost, Nord), größte Änderung |dz| (m).
     */
    public record Bild(int nr, int abschnitt, double tag, Instant zeit, double auftrag, double abtrag, double flaeche, double ost, double nord, double volumen,
                       double ostV, double nordV, double groesste) {
        /** Die Kennzahlen als Feld (gleiche Reihenfolge wie {@link Kennzahl}). */
        double[] werte() { return new double[] {auftrag, abtrag, flaeche, ost, nord, volumen, ostV, nordV, groesste}; }
    }

    /** Die Kennzahlen je Bild. */
    public enum Kennzahl {
        AUFTRAG, ABTRAG, FLAECHE, OST, NORD, VOLUMEN, OST_V, NORD_V, GROESSTE
    }

    /** Ein Ort für die Beschriftung der Karte. */
    public record Ort(String art, String name, double ost, double nord) { }

    public final File ordner;
    public final String name, kennung;
    /** Gitter (Südwestecke, Zelle, Zahl): Zelle (i, j) hat die Mitte bei x0 + (i + ½)·dx, y0 + (j + ½)·dy. */
    public final double x0, y0, dx, dy;
    public final int nx, ny;
    public final double[] kasten, profil;
    public final double schwelle, karteZelle;
    public final List<Abschnitt> abschnitte;
    public final List<Bild> bilder;
    public final List<Ort> orte;
    public final boolean fertig;
    public final double rechenzeit;
    /** Alle Zeilen des Kopfs (auch {@code pruef.*}, {@code uebergang.*}). */
    public final Map<String, String> kopf;

    private float[] grund;
    private long[] versatz;
    private int[] laenge;
    private final Map<Integer, short[]> vorrat = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, short[]> e) { return size() > VORRAT; }
    };

    private Jahresmappe(File ordner, Map<String, String> k, List<Bild> bilder) {
        this.ordner = ordner;
        this.kopf = Collections.unmodifiableMap(k);
        name = k.getOrDefault("name", "Ein Jahr");
        kennung = k.getOrDefault("kennung", "");
        double[] g = zahlen(k.get("gitter"));
        if (g == null || g.length < 6) throw new IllegalArgumentException("Jahr: Gitter fehlt in " + new File(ordner, DATEI));
        x0 = g[0];
        y0 = g[1];
        dx = g[2];
        dy = g[3];
        nx = (int) g[4];
        ny = (int) g[5];
        kasten = zahlen(k.get("kasten"));
        profil = zahlen(k.get("profil"));
        schwelle = zahl(k.get("schwelle"), 1.0);
        karteZelle = zahl(k.get("karte.zelle"), 10);
        List<Abschnitt> ab = new ArrayList<>();
        for (int n = 1; k.containsKey("abschnitt." + n); n++) {
            String[] t = k.get("abschnitt." + n).split(";");
            if (t.length < 9) continue;
            ab.add(new Abschnitt(t[0].strip(), t[1].strip(), zahl(t[2], 1), Instant.parse(t[3].strip()), Instant.parse(t[4].strip()), zahl(t[5], 0), zahl(t[6], 0),
                    (int) zahl(t[7], 0), (int) zahl(t[8], 0)));
        }
        abschnitte = Collections.unmodifiableList(ab);
        List<Ort> o = new ArrayList<>();
        for (int n = 1; k.containsKey("ort." + n); n++) {
            String[] t = k.get("ort." + n).split(";");
            if (t.length >= 4) o.add(new Ort(t[0].strip(), t[1].strip(), zahl(t[2], Double.NaN), zahl(t[3], Double.NaN)));
        }
        orte = Collections.unmodifiableList(o);
        this.bilder = Collections.unmodifiableList(bilder);
        fertig = "ja".equals(k.get("fertig"));
        rechenzeit = zahl(k.get("rechenzeit"), Double.NaN);
    }

    /** Liest Kopf und Liste der Bilder; Grund und Boden erst bei Bedarf. */
    public static Jahresmappe lies(File ordner) throws IOException {
        File d = new File(ordner, DATEI);
        if (!d.isFile()) throw new IOException("Jahr: " + d + " fehlt");
        Map<String, String> k = new LinkedHashMap<>();
        for (String z : Files.readAllLines(d.toPath(), StandardCharsets.UTF_8)) {
            String s = z.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p > 0) k.put(s.substring(0, p).strip(), s.substring(p + 1).strip());
        }
        List<Bild> b = new ArrayList<>();
        File c = new File(ordner, BILDER);
        if (c.isFile()) {
            for (String z : Files.readAllLines(c.toPath(), StandardCharsets.UTF_8)) {
                if (z.isBlank() || z.startsWith("nr")) continue;
                String[] t = z.split(";");
                if (t.length < 13) continue;
                b.add(new Bild(Integer.parseInt(t[0].strip()), Integer.parseInt(t[1].strip()), zahl(t[2], 0), Instant.parse(t[3].strip()), zahl(t[4], 0), zahl(t[5], 0),
                        zahl(t[6], 0), zahl(t[7], Double.NaN), zahl(t[8], Double.NaN), zahl(t[9], 0), zahl(t[10], Double.NaN), zahl(t[11], Double.NaN), zahl(t[12], 0)));
            }
        }
        return new Jahresmappe(ordner, k, b);
    }

    // ------------------------------------------------------------------ Gitter

    public int zellen() { return nx * ny; }

    public double ost(int i) { return x0 + (i + 0.5) * dx; }

    public double nord(int j) { return y0 + (j + 0.5) * dy; }

    public double ost1() { return x0 + nx * dx; }

    public double nord1() { return y0 + ny * dy; }

    /** Die Zelle am Ort; −1 außerhalb. */
    public int zelle(double ost, double nord) {
        int i = (int) Math.floor((ost - x0) / dx), j = (int) Math.floor((nord - y0) / dy);
        return i < 0 || j < 0 || i >= nx || j >= ny ? -1 : i + nx * j;
    }

    // ------------------------------------------------------------------ Zeit

    public double tage() { return bilder.isEmpty() ? 0 : bilder.get(bilder.size() - 1).tag(); }

    public double tag(int k) { return bilder.get(Math.max(0, Math.min(bilder.size() - 1, k))).tag(); }

    /** Das letzte Bild mit Tag ≤ tag (0 … n − 1). */
    public int vor(double tag) {
        int lo = 0, hi = bilder.size() - 1;
        if (hi < 0) return 0;
        if (tag <= bilder.get(0).tag()) return 0;
        if (tag >= bilder.get(hi).tag()) return hi;
        while (hi - lo > 1) {
            int mi = (lo + hi) >>> 1;
            if (bilder.get(mi).tag() <= tag) lo = mi;
            else hi = mi;
        }
        return lo;
    }

    /** Der Abschnitt des Tags (der letzte, dessen Beginn ≤ tag). */
    public int abschnitt(double tag) {
        int a = 0;
        for (int k = 0; k < abschnitte.size(); k++) if (tag >= abschnitte.get(k).tag0() - 1e-9) a = k;
        return a;
    }

    /** Die Zeit im Archiv des Abschnitts zum Modelltag. */
    public Instant zeit(double tag) {
        Abschnitt a = abschnitte.get(abschnitt(tag));
        double t = Math.max(0, Math.min(a.tage(), tag - a.tag0())) * 86_400 / a.faktor();
        return a.von().plusMillis(Math.round(t * 1000));
    }

    /** Eine Kennzahl zum Tag (gerade zwischen zwei Bildern). */
    public double kennzahl(Kennzahl z, double tag) {
        if (bilder.isEmpty()) return Double.NaN;
        int i = vor(tag);
        double a = bilder.get(i).werte()[z.ordinal()];
        if (i + 1 >= bilder.size()) return a;
        double t0 = bilder.get(i).tag(), t1 = bilder.get(i + 1).tag(), f = t1 > t0 ? Math.max(0, Math.min(1, (tag - t0) / (t1 - t0))) : 0;
        return a + f * (bilder.get(i + 1).werte()[z.ordinal()] - a);
    }

    // ------------------------------------------------------------------ Boden

    public File karte() { return new File(ordner, KARTE); }

    public File vorschau() { return new File(ordner, VORSCHAU); }

    /** Die Höhe des Bodens zu Beginn (m NHN), Zeilen von Süd. */
    public synchronized float[] grund() throws IOException {
        if (grund == null) {
            byte[] b = Files.readAllBytes(new File(ordner, GRUND).toPath());
            if (b.length != 4L * zellen()) throw new IOException("Jahr: " + GRUND + " hat " + b.length + " Bytes statt " + 4L * zellen());
            float[] g = new float[zellen()];
            ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(g);
            grund = g;
        }
        return grund;
    }

    /** Zahl der Bilder in {@value #BODEN} (sollte {@code bilder.size()} sein). */
    public synchronized int bilderImBoden() throws IOException {
        index();
        return versatz.length;
    }

    /** Ist Bild k schon im Speicher? */
    public boolean da(int k) {
        synchronized (vorrat) {
            return vorrat.containsKey(k);
        }
    }

    /** Die Änderung des Bodens im Bild k (mm, {@link #LEER} ohne Boden); im Speicher gehalten. */
    public short[] boden(int k) throws IOException {
        synchronized (vorrat) {
            short[] v = vorrat.get(k);
            if (v != null) return v;
        }
        short[] s = liesBoden(k);
        synchronized (vorrat) {
            vorrat.put(k, s);
        }
        return s;
    }

    private short[] liesBoden(int k) throws IOException {
        byte[] gepackt;
        synchronized (this) {
            index();
            if (k < 0 || k >= versatz.length) throw new IOException("Jahr: Bild " + k + " gibt es nicht (" + versatz.length + ")");
            gepackt = new byte[laenge[k]];
            try (RandomAccessFile f = new RandomAccessFile(new File(ordner, BODEN), "r")) {
                f.seek(versatz[k]);
                f.readFully(gepackt);
            }
        }
        byte[] roh = new byte[2 * zellen()];
        Inflater inf = new Inflater();
        try {
            inf.setInput(gepackt);
            int n = 0;
            while (n < roh.length && !inf.finished()) {
                int r = inf.inflate(roh, n, roh.length - n);
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break;
                n += r;
            }
            if (n != roh.length) throw new IOException("Jahr: Bild " + k + " zu kurz (" + n + " von " + roh.length + " Bytes)");
        } catch (DataFormatException e) {
            throw new IOException("Jahr: Bild " + k + ": " + e.getMessage(), e);
        } finally {
            inf.end();
        }
        // Bytes getrennt abgelegt (erst alle niedrigen, dann alle hohen): packt besser
        short[] s = new short[zellen()];
        int n = zellen();
        for (int c = 0; c < n; c++) s[c] = (short) ((roh[c] & 0xff) | (roh[n + c] << 8));
        return s;
    }

    /** Liest einmal die Lage der Bilder in {@value #BODEN}. */
    private void index() throws IOException {
        if (versatz != null) return;
        File d = new File(ordner, BODEN);
        try (RandomAccessFile f = new RandomAccessFile(d, "r")) {
            byte[] m = new byte[MAGIE.length];
            f.readFully(m);
            if (!java.util.Arrays.equals(m, MAGIE)) throw new IOException("Jahr: " + d + " ist keine Mappe des Jahrs");
            int n = f.readInt(), bx = f.readInt(), by = f.readInt();
            if (bx != nx || by != ny) throw new IOException("Jahr: " + d + " hat " + bx + " × " + by + " statt " + nx + " × " + ny);
            List<long[]> l = new ArrayList<>();
            long p = f.getFilePointer(), ende = f.length();
            while (l.size() < n && p + 4 <= ende) {
                f.seek(p);
                int len = f.readInt();
                if (len < 0 || p + 4 + len > ende) break;
                l.add(new long[] {p + 4, len});
                p += 4 + len;
            }
            versatz = new long[l.size()];
            laenge = new int[l.size()];
            for (int k = 0; k < l.size(); k++) {
                versatz[k] = l.get(k)[0];
                laenge[k] = (int) l.get(k)[1];
            }
        }
    }

    /** Die Änderung zum Tag (m; gerade zwischen den Bildern) in ziel; NaN ohne Boden. */
    public void boden(double tag, float[] ziel) throws IOException {
        int i = vor(tag);
        short[] a = boden(i);
        double t0 = tag(i);
        if (i + 1 >= bilder.size() || tag <= t0) {
            for (int c = 0; c < ziel.length; c++) ziel[c] = a[c] == LEER ? Float.NaN : a[c] * 0.001f;
            return;
        }
        short[] b = boden(i + 1);
        double t1 = tag(i + 1);
        float f = (float) Math.max(0, Math.min(1, (tag - t0) / (t1 - t0))), g = 1 - f;
        for (int c = 0; c < ziel.length; c++) ziel[c] = a[c] == LEER || b[c] == LEER ? Float.NaN : (g * a[c] + f * b[c]) * 0.001f;
    }

    /** Höhe des Bodens (m NHN) am Ort aus Grund und Änderung {@code dz} (bilinear zwischen den Mitten); NaN außerhalb. */
    public double hoehe(float[] dz, double ost, double nord) throws IOException {
        float[] g = grund();
        double fx = (ost - x0) / dx - 0.5, fy = (nord - y0) / dy - 0.5;
        int i = (int) Math.floor(fx), j = (int) Math.floor(fy);
        if (i < -1 || j < -1 || i >= nx || j >= ny) return Double.NaN;
        double ax = fx - i, ay = fy - j, s = 0, w = 0;
        for (int b = 0; b < 2; b++)
            for (int a = 0; a < 2; a++) {
                int ii = Math.max(0, Math.min(nx - 1, i + a)), jj = Math.max(0, Math.min(ny - 1, j + b)), c = ii + nx * jj;
                double v = g[c] + (dz == null ? 0 : dz[c]);
                if (Double.isNaN(v)) continue;
                double ww = (a == 0 ? 1 - ax : ax) * (b == 0 ? 1 - ay : ay);
                s += ww * v;
                w += ww;
            }
        return w > 1e-9 ? s / w : Double.NaN;
    }

    // ------------------------------------------------------------------ Kennung

    /**
     * Die Kennung einer Mappe: die Einstellungen der Rechnung (Text) und je Archiv der Pfad mit Zahl und Größe seiner
     * Dateien (zwei Ebenen tief). Ändert sich ein Lauf oder eine Einstellung, passt die Mappe nicht mehr.
     */
    public static String kennung(String einstellungen, List<File> archive) {
        StringBuilder s = new StringBuilder(einstellungen);
        for (File a : archive) {
            long[] n = new long[2];
            groesse(a, 0, n);
            s.append('|').append(a.getAbsolutePath().toLowerCase(Locale.ROOT)).append(':').append(n[0]).append(':').append(n[1]);
        }
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(s.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder x = new StringBuilder();
            for (int i = 0; i < 6; i++) x.append(String.format("%02x", h[i]));
            return x.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(s.toString().hashCode());
        }
    }

    private static void groesse(File d, int tiefe, long[] n) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (tiefe < 1) groesse(f, tiefe + 1, n);
            } else {
                n[0]++;
                n[1] += f.length();
            }
        }
    }

    // ------------------------------------------------------------------ Hilfen

    static double zahl(String s, double vorgabe) {
        if (s == null) return vorgabe;
        try {
            return Double.parseDouble(s.strip().replace(',', '.'));
        } catch (NumberFormatException e) {
            return vorgabe;
        }
    }

    /** Zahlen durch Leerzeichen getrennt; null ohne Text. */
    public static double[] zahlen(String s) {
        if (s == null || s.isBlank()) return null;
        String[] t = s.strip().split("\\s+");
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) r[i] = zahl(t[i], Double.NaN);
        return r;
    }

    static String z(double v) { return Double.isNaN(v) ? "NaN" : String.format(Locale.ROOT, "%.6g", v); }

    // ------------------------------------------------------------------ Schreiben

    /** Schreibt eine Mappe: Grund, dann die Bilder der Reihe nach, zuletzt Kopf und Liste ({@link #schliesse}). */
    public static final class Schreiber implements Closeable {
        final File ordner;
        final int nx, ny;
        private final DataOutputStream boden;
        private final List<Bild> bilder = new ArrayList<>();
        private final Deflater def = new Deflater(6);
        private long bytes;

        public Schreiber(File ordner, int nx, int ny, int bilderZahl) throws IOException {
            this.ordner = ordner;
            this.nx = nx;
            this.ny = ny;
            if (!ordner.isDirectory() && !ordner.mkdirs()) throw new IOException("Jahr: Ordner nicht anlegbar: " + ordner);
            // eine alte Mappe gilt nicht mehr, bis der neue Kopf steht
            Files.deleteIfExists(new File(ordner, DATEI).toPath());
            boden = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(new File(ordner, BODEN)), 1 << 20));
            boden.write(MAGIE);
            boden.writeInt(bilderZahl);
            boden.writeInt(nx);
            boden.writeInt(ny);
            bytes = MAGIE.length + 12;
        }

        public void grund(float[] g) throws IOException {
            ByteBuffer b = ByteBuffer.allocate(4 * g.length).order(ByteOrder.LITTLE_ENDIAN);
            b.asFloatBuffer().put(g);
            Files.write(new File(ordner, GRUND).toPath(), b.array());
        }

        /** Ein Bild: die Änderung dz (m, je Zelle des Gitters) und seine Zeile. */
        public void bild(Bild b, float[] dz) throws IOException {
            int n = nx * ny;
            byte[] roh = new byte[2 * n];
            for (int c = 0; c < n; c++) {
                float v = dz[c];
                int s = Float.isNaN(v) ? LEER : (int) Math.max(-32767, Math.min(32767, Math.round(v * 1000.0)));
                roh[c] = (byte) s;
                roh[n + c] = (byte) (s >> 8);
            }
            def.reset();
            def.setInput(roh);
            def.finish();
            byte[] puffer = new byte[roh.length + 1024];
            java.io.ByteArrayOutputStream aus = new java.io.ByteArrayOutputStream(roh.length / 3);
            while (!def.finished()) {
                int r = def.deflate(puffer);
                aus.write(puffer, 0, r);
            }
            byte[] p = aus.toByteArray();
            boden.writeInt(p.length);
            boden.write(p);
            bytes += 4 + p.length;
            bilder.add(b);
        }

        public int bilder() { return bilder.size(); }

        public long bytes() { return bytes; }

        /** Schreibt die Liste der Bilder und den Kopf; erst damit ist die Mappe fertig. */
        public void schliesse(Map<String, String> kopf) throws IOException {
            boden.close();
            def.end();
            StringBuilder c = new StringBuilder("nr;abschnitt;tag;zeit;auftrag_m3;abtrag_m3;japsand_flaeche_m2;japsand_ost;japsand_nord;japsand_volumen_m3;japsand_ost_v;japsand_nord_v;groesste_m\n");
            for (Bild b : bilder)
                c.append(b.nr()).append(';').append(b.abschnitt()).append(';').append(String.format(Locale.ROOT, "%.5f", b.tag())).append(';').append(b.zeit()).append(';')
                        .append(z(b.auftrag())).append(';').append(z(b.abtrag())).append(';').append(z(b.flaeche())).append(';')
                        .append(String.format(Locale.ROOT, "%.3f;%.3f", b.ost(), b.nord())).append(';').append(z(b.volumen())).append(';')
                        .append(String.format(Locale.ROOT, "%.3f;%.3f", b.ostV(), b.nordV())).append(';').append(z(b.groesste())).append('\n');
            Files.writeString(new File(ordner, BILDER).toPath(), c.toString(), StandardCharsets.UTF_8);
            StringBuilder k = new StringBuilder("# Ein Jahr (Y3, Werkbuch HalligHooge 154) – gerechnet von der Ausgabe (--jahr); nur lesen\n");
            for (Map.Entry<String, String> e : kopf.entrySet()) k.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
            k.append("fertig = ja\n");
            Files.writeString(new File(ordner, DATEI).toPath(), k.toString(), StandardCharsets.UTF_8);
        }

        @Override public void close() throws IOException {
            boden.close();
        }
    }
}
