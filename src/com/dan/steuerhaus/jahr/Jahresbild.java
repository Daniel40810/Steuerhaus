package com.dan.steuerhaus.jahr;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Die Farben und Bilder des Jahrs (seit Y3): Differenzkarte (Abtrag blau, Auftrag ocker bis rotbraun, unter 1 cm
 * durchsichtig), Höhenkarte mit Schummerung, die Linie der Schwelle (Marching Squares) und der abgedunkelte Grund.
 * Ohne Fenster; Ansicht und Ausgabe (Vorschau) malen damit gleich.
 */
public final class Jahresbild {
    /** Unter dieser Änderung (m) bleibt die Differenzkarte durchsichtig [G]. */
    public static final double KLEIN = 0.01;
    /** Die Skalen der Differenzkarte (± m) [G]; Vorgabe ± 0,5 m. */
    public static final double[] SKALEN = {0.1, 0.25, 0.5, 1, 2};
    public static final int SKALA_VORGABE = 2;
    static final float[][] ABTRAG = {{196, 220, 242}, {28, 72, 165}}, AUFTRAG = {{246, 216, 150}, {168, 58, 18}};
    /** Stufen der Höhenkarte: m NHN und Farbe. */
    static final double[] HOEHE_Z = {-10, -5, -2, -0.5, 0, 0.5, 1.0, 1.5, 2.0, 4.0};
    static final int[][] HOEHE_F = {{12, 32, 68}, {24, 66, 120}, {52, 110, 165}, {110, 160, 192}, {150, 186, 192}, {192, 182, 142}, {226, 206, 150}, {238, 222, 172},
            {150, 176, 104}, {94, 130, 70}};

    private Jahresbild() { }

    /** Die Farbe einer Änderung dz (m) bei der Skala ± skala, mit Deckung (ARGB); 0 ohne Farbe. */
    public static int differenz(double dz, double skala) {
        if (Double.isNaN(dz)) return 0;
        double a = Math.abs(dz);
        if (a < KLEIN) return 0;
        double t = Math.pow(Math.min(1, a / skala), 0.8);
        float[][] f = dz < 0 ? ABTRAG : AUFTRAG;
        int r = (int) Math.round(f[0][0] + t * (f[1][0] - f[0][0])), g = (int) Math.round(f[0][1] + t * (f[1][1] - f[0][1])),
                b = (int) Math.round(f[0][2] + t * (f[1][2] - f[0][2]));
        double voll = Math.max(0.03, 0.2 * skala), s = Math.max(0, Math.min(1, (a - KLEIN) / (voll - KLEIN)));
        int al = (int) Math.round(235 * s * s * (3 - 2 * s));
        return (al << 24) | (r << 16) | (g << 8) | b;
    }

    /** Farbe der Höhe z (m NHN), RGB. */
    public static int hoehe(double z) {
        if (Double.isNaN(z)) return 0x202428;
        int n = HOEHE_Z.length;
        if (z <= HOEHE_Z[0]) return rgb(HOEHE_F[0]);
        if (z >= HOEHE_Z[n - 1]) return rgb(HOEHE_F[n - 1]);
        int k = 0;
        while (k + 1 < n && HOEHE_Z[k + 1] < z) k++;
        double t = (z - HOEHE_Z[k]) / (HOEHE_Z[k + 1] - HOEHE_Z[k]);
        int[] a = HOEHE_F[k], b = HOEHE_F[k + 1];
        return ((int) Math.round(a[0] + t * (b[0] - a[0])) << 16) | ((int) Math.round(a[1] + t * (b[1] - a[1])) << 8) | (int) Math.round(a[2] + t * (b[2] - a[2]));
    }

    private static int rgb(int[] f) { return (f[0] << 16) | (f[1] << 8) | f[2]; }

    /** Die Differenzkarte im Gitter (ARGB, Zeilen von Nord) in {@code bild} (nx × ny). */
    public static void differenz(float[] dz, double skala, int nx, int ny, int[] bild) {
        for (int j = 0; j < ny; j++) {
            int z = (ny - 1 - j) * nx;
            for (int i = 0; i < nx; i++) bild[z + i] = differenz(dz[i + nx * j], skala);
        }
    }

    /**
     * Die Höhenkarte im Gitter (RGB, Zeilen von Nord): Farbe der Höhe grund + dz, Schummerung aus Nordwest (Höhe fünffach
     * überhöht) [G].
     */
    public static void hoehe(float[] grund, float[] dz, int nx, int ny, double zelle, int[] bild) {
        double lx = -0.57, ly = 0.57, lz = 0.59;
        for (int j = 0; j < ny; j++) {
            int zei = (ny - 1 - j) * nx;
            for (int i = 0; i < nx; i++) {
                double z = h(grund, dz, nx, ny, i, j);
                int f = hoehe(z);
                double hw = h(grund, dz, nx, ny, i - 1, j), ho = h(grund, dz, nx, ny, i + 1, j), hs = h(grund, dz, nx, ny, i, j - 1), hn = h(grund, dz, nx, ny, i, j + 1);
                double gx = (ho - hw) / (2 * zelle) * 5, gy = (hn - hs) / (2 * zelle) * 5;
                if (Double.isNaN(gx)) gx = 0;
                if (Double.isNaN(gy)) gy = 0;
                double n = Math.sqrt(gx * gx + gy * gy + 1), c = Math.max(0, (-gx * lx - gy * ly + lz) / n) / lz;
                double s = 0.62 + 0.38 * Math.min(1.25, c);
                int r = (int) Math.min(255, ((f >> 16) & 255) * s), g = (int) Math.min(255, ((f >> 8) & 255) * s), b = (int) Math.min(255, (f & 255) * s);
                bild[zei + i] = 0xff000000 | (r << 16) | (g << 8) | b;
            }
        }
    }

    private static double h(float[] g, float[] dz, int nx, int ny, int i, int j) {
        i = Math.max(0, Math.min(nx - 1, i));
        j = Math.max(0, Math.min(ny - 1, j));
        int c = i + nx * j;
        float d = dz == null ? 0 : dz[c];
        return Float.isNaN(d) ? g[c] : g[c] + d;
    }

    /** Der Grund der Differenzkarte: das Gelände von oben, entsättigt und abgedunkelt [G]. */
    public static BufferedImage grau(BufferedImage karte) {
        int w = karte.getWidth(), h = karte.getHeight();
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] z = new int[w];
        for (int y = 0; y < h; y++) {
            karte.getRGB(0, y, w, 1, z, 0, w);
            for (int x = 0; x < w; x++) {
                int p = z[x], r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                double l = 0.2126 * r + 0.7152 * g + 0.0722 * b;
                double rr = 0.25 * r + 0.75 * l, gg = 0.25 * g + 0.75 * l, bb = 0.25 * b + 0.75 * l;
                double s = 0.72;
                z[x] = ((int) (rr * s + 8) << 16) | ((int) (gg * s + 10) << 8) | (int) (bb * s + 14);
            }
            im.setRGB(0, y, w, 1, z, 0, w);
        }
        return im;
    }

    /** Die Zellen (i0, j0, i1, j1; i1, j1 ausschließlich) des Kastens; das ganze Gitter ohne Kasten. */
    public static int[] bereich(Jahresmappe m) {
        if (m.kasten == null || m.kasten.length < 4) return new int[] {0, 0, m.nx, m.ny};
        int i0 = (int) Math.max(0, Math.floor((m.kasten[0] - m.x0) / m.dx)), j0 = (int) Math.max(0, Math.floor((m.kasten[1] - m.y0) / m.dy));
        int i1 = (int) Math.min(m.nx, Math.ceil((m.kasten[2] - m.x0) / m.dx)), j1 = (int) Math.min(m.ny, Math.ceil((m.kasten[3] - m.y0) / m.dy));
        return new int[] {i0, j0, Math.max(i0, i1), Math.max(j0, j1)};
    }

    /**
     * Die Linie der Höhe {@code schwelle} von grund + dz in den Zellen des Bereichs (Marching Squares zwischen den Mitten
     * der Zellen): je Strich {Ost₁, Nord₁, Ost₂, Nord₂} (m, UTM).
     */
    public static List<double[]> linie(Jahresmappe m, float[] grund, float[] dz, double schwelle, int[] b) {
        List<double[]> l = new ArrayList<>();
        int nx = m.nx;
        for (int j = b[1]; j + 1 < b[3]; j++)
            for (int i = b[0]; i + 1 < b[2]; i++) {
                double v00 = z(grund, dz, i + nx * j) - schwelle, v10 = z(grund, dz, i + 1 + nx * j) - schwelle, v01 = z(grund, dz, i + nx * (j + 1)) - schwelle,
                        v11 = z(grund, dz, i + 1 + nx * (j + 1)) - schwelle;
                if (Double.isNaN(v00) || Double.isNaN(v10) || Double.isNaN(v01) || Double.isNaN(v11)) continue;
                int f = (v00 > 0 ? 1 : 0) | (v10 > 0 ? 2 : 0) | (v11 > 0 ? 4 : 0) | (v01 > 0 ? 8 : 0);
                if (f == 0 || f == 15) continue;
                double x = m.ost(i), y = m.nord(j);
                // Schnittpunkte auf den Kanten: unten (00–10), rechts (10–11), oben (01–11), links (00–01)
                double[] u = {x + t(v00, v10) * m.dx, y}, r = {x + m.dx, y + t(v10, v11) * m.dy}, o = {x + t(v01, v11) * m.dx, y + m.dy}, li = {x, y + t(v00, v01) * m.dy};
                switch (f) {
                    case 1, 14 -> l.add(s(u, li));
                    case 2, 13 -> l.add(s(u, r));
                    case 3, 12 -> l.add(s(li, r));
                    case 4, 11 -> l.add(s(r, o));
                    case 6, 9 -> l.add(s(u, o));
                    case 7, 8 -> l.add(s(li, o));
                    case 5 -> {
                        l.add(s(u, r));
                        l.add(s(li, o));
                    }
                    case 10 -> {
                        l.add(s(u, li));
                        l.add(s(r, o));
                    }
                    default -> { }
                }
            }
        return l;
    }

    private static double z(float[] g, float[] dz, int c) {
        float d = dz == null ? 0 : dz[c];
        return Float.isNaN(d) ? g[c] : g[c] + d;
    }

    private static double t(double a, double b) { return a == b ? 0.5 : Math.max(0, Math.min(1, a / (a - b))); }

    private static double[] s(double[] a, double[] b) { return new double[] {a[0], a[1], b[0], b[1]}; }

    /** Die Striche als Pfad in Bildpunkten: Ost, Nord → x, y im Rechteck (rx, ry, rw, rh) der Mappe. */
    public static Path2D.Double pfad(Jahresmappe m, List<double[]> striche, double rx, double ry, double rw, double rh) {
        Path2D.Double p = new Path2D.Double();
        double sx = rw / (m.ost1() - m.x0), sy = rh / (m.nord1() - m.y0);
        for (double[] s : striche) {
            p.moveTo(rx + (s[0] - m.x0) * sx, ry + (m.nord1() - s[1]) * sy);
            p.lineTo(rx + (s[2] - m.x0) * sx, ry + (m.nord1() - s[3]) * sy);
        }
        return p;
    }

    /** Die Kachel: Grund, Differenz am Ende, die Linie der Schwelle zu Beginn und am Ende; w × h. */
    public static BufferedImage vorschau(Jahresmappe m, BufferedImage karte, float[] grund, float[] dzEnde, double skala, int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // das Rechteck der Mappe so, dass es die Kachel füllt (Mitte)
        double ar = (m.ost1() - m.x0) / (m.nord1() - m.y0), s = Math.max(w / ar, h);
        double rw = s * ar, rh = s, rx = (w - rw) / 2, ry = (h - rh) / 2;
        if (karte != null) g.drawImage(grau(karte), (int) rx, (int) ry, (int) Math.round(rw), (int) Math.round(rh), null);
        BufferedImage ov = new BufferedImage(m.nx, m.ny, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[m.nx * m.ny];
        differenz(dzEnde, skala, m.nx, m.ny, px);
        ov.setRGB(0, 0, m.nx, m.ny, px, 0, m.nx);
        g.setComposite(AlphaComposite.SrcOver);
        g.drawImage(ov, (int) rx, (int) ry, (int) Math.round(rw), (int) Math.round(rh), null);
        int[] b = bereich(m);
        g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 3f}, 0f));
        g.setColor(new Color(255, 255, 255, 200));
        g.draw(pfad(m, linie(m, grund, null, m.schwelle, b), rx, ry, rw, rh));
        g.setStroke(new BasicStroke(1.6f));
        g.setColor(new Color(255, 196, 80));
        g.draw(pfad(m, linie(m, grund, dzEnde, m.schwelle, b), rx, ry, rw, rh));
        g.dispose();
        return im;
    }
}
