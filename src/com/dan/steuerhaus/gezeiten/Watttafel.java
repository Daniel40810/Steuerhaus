package com.dan.steuerhaus.gezeiten;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
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
 * Der Weg ins Watt für das Watt-Fenster (seit Z3, Werkbuch HalligHooge 158), als Textdatei im Projekt; einmal von der
 * Ausgabe gerechnet ({@code --watt}), danach nur gelesen. Dazu im Ordner {@code watt} daneben die Höhen der Karte
 * ({@code hoehe.png}, 16 Bit, Millimeter über −10 m NHN; 0: keine Höhe) und das Land ({@code land.jpg}).
 * <pre>
 * start    = Westspitze; 468290.0; 6047690.0
 * ziel     = Japsand; 465698.0; 6046728.0; 1.66        # Name; Ost; Nord; Höhe
 * sattel   = 468073.0; 6046518.0; 0.089                # die tiefste Stelle des höchsten Wegs (m NHN)
 * lage     = 8.5312; 54.5701                           # Länge, Breite (für die Sonne)
 * schwelle = -0.50; 0.24                               # Wasserstand am Anleger: trocken ab (fallend), nass ab (steigend)
 * karte    = 463000 6044000 470000 6050000; 5; 1400; 1200
 * punkt    = 0.0; 468293.0; 6047693.0; 2.70            # der Weg: Strecke (m); Ost; Nord; Höhe – je Punkt eine Zeile
 * ereignis = morpho; AB; 2026-10-14T07:38:00Z; -0.46; -0.35   # im Modell: der Priel am Sattel kreuzt dessen Höhe; Anleger im Modell, Gezeit des Kalenders
 * pruef.N, wert.*, quelle.*                            # Prüfwerte der Rechnung
 * </pre>
 */
public final class Watttafel {
    public record Punkt(double strecke, double ost, double nord, double hoehe) { }

    /**
     * Ein Ereignis im Modell: Lauf, fallend (AB, ab dann trocken) oder steigend (BIS), Zeit, Wasserstand am Anleger im
     * Modell und die Gezeit des Kalenders dort zur selben Zeit.
     */
    public record Ereignis(String lauf, boolean ab, Instant zeit, double anleger, double gezeit) { }

    public final File datei;
    public final String start, ziel;
    public final double startOst, startNord, zielOst, zielNord, zielHoehe, sattelOst, sattelNord, sattel, laenge, breite, schwelleAb, schwelleBis;
    /** Rechteck der Karte (Ost, Nord, Ost, Nord), Zelle (m), Bildpunkte. */
    public final double[] karte;
    public final double zelle;
    public final int nx, ny;
    public final List<Punkt> weg;
    public final List<Ereignis> ereignisse;
    public final Map<String, String> kopf;
    private float[] hoehen;
    private BufferedImage land;

    private Watttafel(File datei, String start, double so, double sn, String ziel, double zo, double zn, double zh, double sa, double sb, double sz, double laenge,
                      double breite, double ab, double bis, double[] karte, double zelle, int nx, int ny, List<Punkt> weg, List<Ereignis> er, Map<String, String> kopf) {
        this.datei = datei;
        this.start = start;
        this.startOst = so;
        this.startNord = sn;
        this.ziel = ziel;
        this.zielOst = zo;
        this.zielNord = zn;
        this.zielHoehe = zh;
        this.sattelOst = sa;
        this.sattelNord = sb;
        this.sattel = sz;
        this.laenge = laenge;
        this.breite = breite;
        this.schwelleAb = ab;
        this.schwelleBis = bis;
        this.karte = karte;
        this.zelle = zelle;
        this.nx = nx;
        this.ny = ny;
        this.weg = Collections.unmodifiableList(weg);
        this.ereignisse = Collections.unmodifiableList(er);
        this.kopf = Collections.unmodifiableMap(kopf);
    }

    /** Länge des Wegs (m). */
    public double wegLaenge() { return weg.isEmpty() ? 0 : weg.get(weg.size() - 1).strecke(); }

    public File ordner() { return new File(datei.getAbsoluteFile().getParentFile(), "watt"); }

    /** Die Höhen der Karte (m NHN; NaN: keine), j von Süd; einmal gelesen. */
    public synchronized float[] hoehen() throws IOException {
        if (hoehen != null) return hoehen;
        BufferedImage b = ImageIO.read(new File(ordner(), "hoehe.png"));
        if (b == null || b.getWidth() != nx || b.getHeight() != ny) throw new IOException("Watt: " + new File(ordner(), "hoehe.png") + " fehlt oder passt nicht");
        WritableRaster r = b.getRaster();
        float[] h = new float[nx * ny];
        int[] z = new int[nx];
        for (int jb = 0; jb < ny; jb++) {
            r.getSamples(0, jb, nx, 1, 0, z);
            int j = ny - 1 - jb;
            for (int i = 0; i < nx; i++) h[i + nx * j] = z[i] == 0 ? Float.NaN : z[i] / 1000f - 10f;
        }
        hoehen = h;
        return h;
    }

    /** Das Land ohne Wasser (Zeilen von Nord). */
    public synchronized BufferedImage land() throws IOException {
        if (land == null) land = ImageIO.read(new File(ordner(), "land.jpg"));
        if (land == null) throw new IOException("Watt: " + new File(ordner(), "land.jpg") + " fehlt");
        return land;
    }

    public static Watttafel lies(File datei) throws IOException {
        if (!datei.isFile()) throw new IOException("Watt: " + datei + " fehlt (einmal mit der Ausgabe --watt rechnen)");
        String start = "Start", ziel = "Ziel";
        double so = Double.NaN, sn = Double.NaN, zo = Double.NaN, zn = Double.NaN, zh = Double.NaN, sa = Double.NaN, sb = Double.NaN, sz = Double.NaN, la = 8.53, br = 54.57,
                ab = Double.NaN, bis = Double.NaN, zelle = 5;
        double[] karte = null;
        int nx = 0, ny = 0;
        List<Punkt> weg = new ArrayList<>();
        List<Ereignis> er = new ArrayList<>();
        Map<String, String> kopf = new LinkedHashMap<>();
        int nr = 0;
        for (String z : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) {
            nr++;
            String s = z.strip();
            if (s.isEmpty() || s.startsWith("#")) continue;
            int p = s.indexOf('=');
            if (p <= 0) continue;
            String k = s.substring(0, p).strip(), v = s.substring(p + 1).strip();
            String[] t = Gezeitenauftrag.teile(v);
            try {
                switch (k) {
                    case "start" -> {
                        start = t[0];
                        so = zahl(t[1]);
                        sn = zahl(t[2]);
                    }
                    case "ziel" -> {
                        ziel = t[0];
                        zo = zahl(t[1]);
                        zn = zahl(t[2]);
                        zh = zahl(t[3]);
                    }
                    case "sattel" -> {
                        sa = zahl(t[0]);
                        sb = zahl(t[1]);
                        sz = zahl(t[2]);
                    }
                    case "lage" -> {
                        la = zahl(t[0]);
                        br = zahl(t[1]);
                    }
                    case "schwelle" -> {
                        ab = zahl(t[0]);
                        bis = zahl(t[1]);
                    }
                    case "karte" -> {
                        karte = Gezeitenauftrag.zahlen(t[0]);
                        zelle = zahl(t[1]);
                        nx = Integer.parseInt(t[2]);
                        ny = Integer.parseInt(t[3]);
                    }
                    case "punkt" -> weg.add(new Punkt(zahl(t[0]), zahl(t[1]), zahl(t[2]), zahl(t[3])));
                    case "ereignis" -> er.add(new Ereignis(t[0], t[1].equals("AB"), Instant.parse(t[2]), zahl(t[3]), zahl(t[4])));
                    default -> kopf.put(k, v);
                }
            } catch (RuntimeException e) {
                throw new IOException("Watt: " + datei.getName() + ", Zeile " + nr + ": " + e.getMessage(), e);
            }
        }
        if (weg.size() < 2 || Double.isNaN(sz) || Double.isNaN(ab) || Double.isNaN(bis) || karte == null || karte.length != 4)
            throw new IOException("Watt: " + datei.getName() + " unvollständig (Weg, Sattel, Schwelle, Karte)");
        return new Watttafel(datei, start, so, sn, ziel, zo, zn, zh, sa, sb, sz, la, br, ab, bis, karte, zelle, nx, ny, weg, er, kopf);
    }

    /**
     * Schreibt die Datei und, wenn gegeben, die Bilder (Höhen j von Süd, NaN: keine; Land mit Zeilen von Nord) in den
     * Ordner {@code watt} daneben.
     */
    public static void schreibe(File datei, String start, double so, double sn, String ziel, double zo, double zn, double zh, double sa, double sb, double sz, double laenge,
                                double breite, double ab, double bis, double[] karte, double zelle, int nx, int ny, List<Punkt> weg, List<Ereignis> er, Map<String, String> kopf,
                                float[] hoehen, BufferedImage land) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("# Weg ins Watt für das Watt-Fenster (Z3, Werkbuch HalligHooge 158) – gerechnet von der Ausgabe (--watt); nicht von Hand ändern.\n");
        s.append("# Modell, nicht zur Navigation.\n");
        s.append(String.format(Locale.ROOT, "start    = %s; %.1f; %.1f%n", start, so, sn));
        s.append(String.format(Locale.ROOT, "ziel     = %s; %.1f; %.1f; %.3f%n", ziel, zo, zn, zh));
        s.append(String.format(Locale.ROOT, "sattel   = %.1f; %.1f; %.3f%n", sa, sb, sz));
        s.append(String.format(Locale.ROOT, "lage     = %.5f; %.5f%n", laenge, breite));
        s.append(String.format(Locale.ROOT, "schwelle = %.3f; %.3f%n", ab, bis));
        s.append(String.format(Locale.ROOT, "karte    = %.0f %.0f %.0f %.0f; %.2f; %d; %d%n", karte[0], karte[1], karte[2], karte[3], zelle, nx, ny));
        for (Map.Entry<String, String> e : kopf.entrySet()) s.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        for (Punkt p : weg) s.append(String.format(Locale.ROOT, "punkt    = %.1f; %.1f; %.1f; %.3f%n", p.strecke(), p.ost(), p.nord(), p.hoehe()));
        for (Ereignis e : er) s.append(String.format(Locale.ROOT, "ereignis = %s; %s; %s; %.3f; %.3f%n", e.lauf(), e.ab() ? "AB" : "BIS", e.zeit(), e.anleger(), e.gezeit()));
        datei.getAbsoluteFile().getParentFile().mkdirs();
        Files.writeString(datei.toPath(), s.toString(), StandardCharsets.UTF_8);
        File o = new File(datei.getAbsoluteFile().getParentFile(), "watt");
        o.mkdirs();
        if (hoehen != null) {
            BufferedImage b = new BufferedImage(nx, ny, BufferedImage.TYPE_USHORT_GRAY);
            WritableRaster r = b.getRaster();
            int[] z = new int[nx];
            for (int jb = 0; jb < ny; jb++) {
                int j = ny - 1 - jb;
                for (int i = 0; i < nx; i++) {
                    float h = hoehen[i + nx * j];
                    z[i] = Float.isNaN(h) ? 0 : (int) Math.max(1, Math.min(65535, Math.round((h + 10) * 1000)));
                }
                r.setSamples(0, jb, nx, 1, 0, z);
            }
            ImageIO.write(b, "png", new File(o, "hoehe.png"));
        }
        if (land != null) {
            BufferedImage rgb = new BufferedImage(land.getWidth(), land.getHeight(), BufferedImage.TYPE_INT_RGB);
            rgb.getGraphics().drawImage(land, 0, 0, null);
            ImageIO.write(rgb, "jpg", new File(o, "land.jpg"));
        }
    }

    static double zahl(String s) { return Double.parseDouble(s.strip().replace(',', '.')); }
}
