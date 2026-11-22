package com.dan.steuerhaus.vergleich;

import com.dan.steuerhaus.Manifest;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Mappe eines gerechneten Vergleichs (seit Y1, Werkbuch HalligHooge 152): ein Ordner mit {@code vergleich.txt}
 * (Beschreibung, Bezug, je Bild die Zeiten und Pegel beider Seiten, die Sonden mit ihrem Ort im Bild),
 * {@code A_0001.jpg} … und {@code B_0001.jpg} … (dieselbe Kamera), {@code sonde_N.csv} (die Reihen beider Seiten über
 * dem Abstand zum Bezug) und {@code vorschau.jpg} (die Kachel: links A, rechts B). Die Ausgabe schreibt sie mit
 * {@link Schreiber}; {@code fertig = ja} steht zuletzt. Lesen ohne Archiv und ohne Bühne.
 */
public final class Mappe {
    /** Ein Bild der Folge: Abstand zum Bezug (s), Zeiten und Pegel (m NHN; NaN: trocken oder unbekannt) beider Seiten. */
    public record Bild(double rel, Instant a, Instant b, double pegelA, double pegelB) { }

    /** Eine Sonde: Ort, Grund, Ort im Bild (Bildpunkte der Mappe; NaN: nicht zu sehen) und ihre Reihen. */
    public record Sonde(String name, double ost, double nord, double grund, double x, double y, List<Reihe> reihen) {
        public Reihe reihe(String name) {
            for (Reihe r : reihen) if (r.name.equals(name)) return r;
            return null;
        }
    }

    /** Eine Größe an einer Sonde: Name, Einheit, Abstand zum Bezug (s) und die Werte von A und B (NaN: kein Wert). */
    public record Reihe(String name, String einheit, double[] t, double[] a, double[] b) {
        public double bei(double[] w, double ts) {
            if (t.length == 0 || ts < t[0] || ts > t[t.length - 1]) return Double.NaN;
            int i = java.util.Arrays.binarySearch(t, ts);
            if (i >= 0) return w[i];
            int j = -i - 1, k = j - 1;
            double s = (ts - t[k]) / (t[j] - t[k]);
            return w[k] + s * (w[j] - w[k]);
        }
    }

    public final File ordner;
    public final String schluessel, name, text, kennung;
    public final String nameA, nameB, schluesselA, schluesselB, ansicht, ausrichten, pegelName;
    public final Vergleich.Bezug bezug;
    public final int breite, hoehe;
    public final double schritt, rechenzeit;
    /** Die Kamera: Ost, Nord, Höhe, Richtung, Neigung, Bildwinkel (null: unbekannt). */
    public final double[] blick;
    public final boolean fertig;
    public final List<Bild> bilder;
    public final List<Sonde> sonden;
    public final List<String> hinweise;

    private final Map<Long, BufferedImage> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, BufferedImage> e) { return size() > platz; }
    });
    private volatile int platz = 24;

    private Mappe(File ordner, Manifest d, List<Bild> bilder, List<Sonde> sonden) {
        this.ordner = ordner;
        schluessel = d.text("schluessel", ordner.getName());
        name = d.text("name", ordner.getName());
        text = d.text("text", "");
        kennung = d.text("kennung", "");
        nameA = d.text("a.name", "A");
        nameB = d.text("b.name", "B");
        schluesselA = d.text("a.schluessel", "");
        schluesselB = d.text("b.schluessel", "");
        ansicht = d.text("ansicht", "");
        ausrichten = d.text("ausrichten", "");
        pegelName = d.text("pegel.name", "Pegel");
        double[] g = Vergleich.zahlen(d.text("groesse", "1920 1080"));
        breite = (int) g[0];
        hoehe = (int) g[1];
        schritt = zahl(d.text("schritt", "300"));
        rechenzeit = zahl(d.text("rechenzeit_s", "NaN"));
        String bl = d.text("blick", "");
        blick = bl.isEmpty() ? null : Vergleich.zahlen(bl);
        fertig = d.text("fertig", "").equals("ja");
        String[] ba = d.text("bezug.a", "").split(";"), bb = d.text("bezug.b", "").split(";");
        bezug = ba.length >= 1 && !ba[0].isBlank() && bb.length >= 1 && !bb[0].isBlank()
                ? new Vergleich.Bezug(Instant.parse(ba[0].strip()), Instant.parse(bb[0].strip()), ba.length > 1 ? zahl(ba[1]) : Double.NaN,
                bb.length > 1 ? zahl(bb[1]) : Double.NaN, d.text("bezug", "Zeit"))
                : null;
        this.bilder = List.copyOf(bilder);
        this.sonden = List.copyOf(sonden);
        List<String> h = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            String x = d.text("hinweis." + i, "");
            if (!x.isEmpty()) h.add(x);
        }
        hinweise = List.copyOf(h);
    }

    static double zahl(String s) {
        String t = s.strip();
        return t.isEmpty() || t.equals("-") ? Double.NaN : Double.parseDouble(t.replace(',', '.'));
    }

    /** Liest die Mappe (Bilder erst bei Bedarf). */
    public static Mappe lies(File ordner) throws IOException {
        File f = new File(ordner, "vergleich.txt");
        if (!f.isFile()) throw new IOException("keine Mappe (vergleich.txt fehlt): " + ordner);
        Manifest d = Manifest.lies(f);
        List<Bild> bl = new ArrayList<>();
        for (int k = 1; ; k++) {
            String z = d.text("bild." + k, "");
            if (z.isEmpty()) break;
            String[] t = z.split(";");
            bl.add(new Bild(zahl(t[0]), Instant.parse(t[1].strip()), Instant.parse(t[2].strip()), zahl(t[3]), zahl(t[4])));
        }
        List<Sonde> so = new ArrayList<>();
        for (int n = 1; n <= Vergleich.SONDEN; n++) {
            String z = d.text("sonde." + n, "");
            if (z.isEmpty()) continue;
            String[] t = z.split(";");
            double[] xy = t.length > 4 && !t[4].isBlank() && !t[4].strip().equals("-") ? Vergleich.zahlen(t[4]) : new double[] {Double.NaN, Double.NaN};
            File csv = new File(ordner, "sonde_" + n + ".csv");
            so.add(new Sonde(t[0].strip(), zahl(t[1]), zahl(t[2]), zahl(t[3]), xy[0], xy[1], csv.isFile() ? reihen(csv) : List.of()));
        }
        return new Mappe(ordner, d, bl, so);
    }

    /** Die Reihen aus {@code sonde_N.csv}: Spalte 1 der Abstand (s), dann je Größe A und B („Name A (Einheit)“). */
    static List<Reihe> reihen(File csv) throws IOException {
        List<String> z = new ArrayList<>();
        for (String s : Files.readAllLines(csv.toPath(), StandardCharsets.UTF_8)) if (!s.isBlank() && !s.startsWith("#")) z.add(s);
        if (z.isEmpty()) return List.of();
        String[] kopf = z.get(0).split(";");
        int n = z.size() - 1;
        double[][] w = new double[kopf.length][n];
        for (int i = 0; i < n; i++) {
            String[] t = z.get(i + 1).split(";", -1);
            for (int c = 0; c < kopf.length; c++) w[c][i] = c < t.length ? zahl(t[c]) : Double.NaN;
        }
        List<Reihe> r = new ArrayList<>();
        for (int c = 1; c + 1 < kopf.length; c += 2) {
            String k = kopf[c].strip();
            int p = k.indexOf(" A");
            String name = p > 0 ? k.substring(0, p) : k;
            int e0 = k.indexOf('('), e1 = k.lastIndexOf(')');
            String einheit = e0 >= 0 && e1 > e0 ? k.substring(e0 + 1, e1) : "";
            r.add(new Reihe(name, einheit, w[0], w[c], w[c + 1]));
        }
        return r;
    }

    /** Die Mappen unter einem Ordner (je Unterordner mit vergleich.txt), die neuesten zuerst; unlesbare fallen weg. */
    public static List<Mappe> alle(File wurzel) {
        List<Mappe> l = new ArrayList<>();
        File[] fs = wurzel.listFiles(File::isDirectory);
        if (fs == null) return l;
        for (File f : fs)
            try {
                if (new File(f, "vergleich.txt").isFile()) l.add(lies(f));
            } catch (IOException | RuntimeException x) {
                // keine oder eine kaputte Mappe
            }
        l.sort((x, y) -> Long.compare(new File(y.ordner, "vergleich.txt").lastModified(), new File(x.ordner, "vergleich.txt").lastModified()));
        return l;
    }

    public File datei(int k, boolean b) { return new File(ordner, dateiName(k, b)); }

    /** Name der Datei von Bild k (ab 0) der Seite A oder B. */
    public static String dateiName(int k, boolean b) { return String.format(Locale.ROOT, "%s_%04d.jpg", b ? "B" : "A", k + 1); }

    public File vorschau() { return new File(ordner, "vorschau.jpg"); }

    /** Wie viele Bilder der Folge höchstens im Speicher bleiben (je Seite die Hälfte). */
    public void platz(int n) { platz = Math.max(4, n); }

    public boolean geladen(int k, boolean b, int teiler) { return cache.containsKey(schluessel(k, b, teiler)); }

    /**
     * Bild k der Seite (b: B) in voller Größe oder nur jeden {@code teiler}-ten Bildpunkt (1, 2, 4; schneller zu lesen).
     * Aus dem Speicher, sonst von der Platte; null, wenn die Datei fehlt.
     */
    public BufferedImage bild(int k, boolean b, int teiler) {
        long s = schluessel(k, b, teiler);
        BufferedImage im = cache.get(s);
        if (im != null) return im;
        File f = datei(k, b);
        if (!f.isFile()) return null;
        try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
            var it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(in);
                ImageReadParam p = r.getDefaultReadParam();
                if (teiler > 1) p.setSourceSubsampling(teiler, teiler, 0, 0);
                im = r.read(0, p);
            } finally {
                r.dispose();
            }
        } catch (IOException x) {
            return null;
        }
        cache.put(s, im);
        return im;
    }

    private static long schluessel(int k, boolean b, int teiler) { return ((long) k << 8) | (b ? 1 : 0) | ((long) teiler << 1); }

    // ------------------------------------------------------------------ seit Y2: auf die Fläche gebracht (schnelles Abspielen)

    private final Map<String, BufferedImage> skaliert = Collections.synchronizedMap(new LinkedHashMap<>(64, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> e) { return size() > platz + 8; }
    });

    /**
     * Seit Y2: Bild k der Seite in genau w × h Bildpunkten (TYPE_INT_RGB, ohne Skalieren zu zeichnen): gelesen mit dem
     * größten Teiler, der noch mindestens w breit ist, dann geglättet verkleinert. Aus dem Speicher, sonst neu; null,
     * wenn die Datei fehlt. Lesen und Verkleinern dürfen in einem anderen Faden laufen.
     */
    public BufferedImage skaliert(int k, boolean b, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        String s = k + (b ? "B" : "A") + w + "x" + h;
        BufferedImage im = skaliert.get(s);
        if (im != null) return im;
        int teiler = 1;
        while (teiler < 4 && breite / (teiler * 2) >= w) teiler *= 2;
        BufferedImage q = teiler > 1 ? bildOhneSpeicher(k, b, teiler) : bild(k, b, 1);
        if (q == null) return null;
        im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.drawImage(q, 0, 0, w, h, null);
        g.dispose();
        skaliert.put(s, im);
        return im;
    }

    public boolean skaliertDa(int k, boolean b, int w, int h) { return skaliert.containsKey(k + (b ? "B" : "A") + w + "x" + h); }

    /** Gelesen mit Teiler, ohne den Speicher der vollen Bilder zu füllen. */
    private BufferedImage bildOhneSpeicher(int k, boolean b, int teiler) {
        File f = datei(k, b);
        if (!f.isFile()) return null;
        try (ImageInputStream in = ImageIO.createImageInputStream(f)) {
            var it = ImageIO.getImageReaders(in);
            if (!it.hasNext()) return null;
            ImageReader r = it.next();
            try {
                r.setInput(in);
                ImageReadParam p = r.getDefaultReadParam();
                p.setSourceSubsampling(teiler, teiler, 0, 0);
                return r.read(0, p);
            } finally {
                r.dispose();
            }
        } catch (IOException x) {
            return null;
        }
    }

    /** Der Index des Bilds, dessen Abstand zum Bezug rel am nächsten liegt. */
    public int naechstes(double rel) {
        int best = 0;
        for (int i = 1; i < bilder.size(); i++) if (Math.abs(bilder.get(i).rel - rel) < Math.abs(bilder.get(best).rel - rel)) best = i;
        return best;
    }

    /** Das Bild beim Bezug (Abstand 0) oder das nächste. */
    public int beimBezug() { return bilder.isEmpty() ? 0 : naechstes(0); }

    // ------------------------------------------------------------------ Schreiben

    /** Schreibt eine Mappe: Bilder, Sonden, Vorschau, zuletzt den Index. Alte Bilder, Sonden und Index im Ordner fallen vorher weg. */
    public static final class Schreiber {
        public final File ordner;
        private final StringBuilder index = new StringBuilder();
        private final float guete;

        public Schreiber(File ordner, float guete) throws IOException {
            this.ordner = ordner;
            this.guete = guete;
            Files.createDirectories(ordner.toPath());
            File[] alt = ordner.listFiles((d, n) -> n.matches("[AB]_\\d{4}\\.jpg") || n.matches("sonde_\\d+\\.csv") || n.equals("vorschau.jpg") || n.equals("vergleich.txt"));
            if (alt != null) for (File f : alt) Files.deleteIfExists(f.toPath());
            index.append("# Vergleich (Werkbuch HalligHooge 152): geschrieben von der Ausgabe, nicht von Hand ändern\n");
        }

        /** Eine Zeile des Index (ohne „#“ und Zeilenumbruch im Wert). */
        public Schreiber setze(String schluessel, Object wert) {
            String w = String.valueOf(wert).replace('\n', ' ').replace('\r', ' ').replace(" #", " ");
            index.append(schluessel).append(" = ").append(w).append('\n');
            return this;
        }

        /** Bild k (ab 0) der Seite A oder B. */
        public void bild(int k, boolean b, BufferedImage im) throws IOException { jpeg(im, new File(ordner, dateiName(k, b)), guete); }

        public void sonde(int n, String csv) throws IOException { Files.writeString(new File(ordner, "sonde_" + n + ".csv").toPath(), csv, StandardCharsets.UTF_8); }

        /** Die Kachel (640 × 360): links A, rechts B, dazwischen der Schieber. */
        public void vorschau(BufferedImage a, BufferedImage b, Color marke) throws IOException {
            int w = 640, h = 360;
            BufferedImage v = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = v.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(a, 0, 0, w, h, null);
            g.setClip(w / 2, 0, w - w / 2, h);
            g.drawImage(b, 0, 0, w, h, null);
            g.setClip(null);
            g.setColor(marke);
            g.setStroke(new BasicStroke(3f));
            g.drawLine(w / 2, 0, w / 2, h);
            g.fillOval(w / 2 - 9, h / 2 - 9, 18, 18);
            g.dispose();
            jpeg(v, vorschau(), 0.9f);
        }

        private File vorschau() { return new File(ordner, "vorschau.jpg"); }

        /** Der Index; {@code fertig = ja} zuletzt. */
        public void schliesse(boolean fertig) throws IOException {
            if (fertig) index.append("fertig = ja\n");
            Files.writeString(new File(ordner, "vergleich.txt").toPath(), index.toString(), StandardCharsets.UTF_8);
        }
    }

    /** JPEG mit Güte q (0 … 1). */
    public static void jpeg(BufferedImage im, File ziel, float q) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(q);
        BufferedImage rgb = im;
        if (im.getType() != BufferedImage.TYPE_INT_RGB && im.getType() != BufferedImage.TYPE_3BYTE_BGR) {
            rgb = new BufferedImage(im.getWidth(), im.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgb.createGraphics();
            g.drawImage(im, 0, 0, null);
            g.dispose();
        }
        Files.deleteIfExists(ziel.toPath());
        try (ImageOutputStream out = ImageIO.createImageOutputStream(ziel)) {
            w.setOutput(out);
            w.write(null, new IIOImage(rgb, null, null), p);
        } finally {
            w.dispose();
        }
    }

    /** Die Spalten einer Sonde als CSV (Abstand in s, je Größe A und B) aus Reihen auf gemeinsamen Zeiten. */
    public static String csv(String kopf, List<Reihe> reihen) {
        StringBuilder sb = new StringBuilder("# ").append(kopf.replace('\n', ' ')).append('\n').append("abstand_s");
        for (Reihe r : reihen) sb.append(';').append(r.name).append(" A (").append(r.einheit).append(");").append(r.name).append(" B (").append(r.einheit).append(')');
        sb.append('\n');
        if (reihen.isEmpty()) return sb.toString();
        double[] t = reihen.get(0).t;
        for (int i = 0; i < t.length; i++) {
            sb.append(String.format(Locale.ROOT, "%.0f", t[i]));
            for (Reihe r : reihen) sb.append(';').append(f4(r.a[i])).append(';').append(f4(r.b[i]));
            sb.append('\n');
        }
        return sb.toString();
    }

    private static String f4(double x) { return Double.isNaN(x) ? "" : String.format(Locale.ROOT, "%.4f", x); }

    @Override public String toString() { return name; }
}
