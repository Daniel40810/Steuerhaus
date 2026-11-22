package com.dan.steuerhaus.ausgabe;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wohin die Bilder der Ausgabe gehen (seit W4, Werkbuch HalligHooge 121, F201 A): an ffmpeg über eine Pipe (H.264,
 * MP4) oder, ohne ffmpeg, als PNG-Folge in einen Ordner. Die Bilder kommen als RGB, 8 Bit, sRGB, zeilenweise von oben.
 */
public interface Kodierer extends AutoCloseable {
    void bild(byte[] rgb) throws IOException;

    /** Wohin (Datei oder Ordner). */
    File ziel();

    /** Kurz, was es ist (für die Ausgabe). */
    String art();

    @Override void close() throws IOException;

    /**
     * ffmpeg suchen: der angegebene Pfad, sonst {@code ffmpeg} auf dem Suchpfad. null: keins da.
     */
    static String ffmpeg(String pfad) {
        List<String> kandidaten = new ArrayList<>();
        if (pfad != null && !pfad.isBlank()) kandidaten.add(pfad);
        kandidaten.add("ffmpeg");
        for (String k : kandidaten)
            try {
                Process p = new ProcessBuilder(k, "-hide_banner", "-version").redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() == 0) return k;
            } catch (IOException | InterruptedException e) {
                // nicht da
            }
        return null;
    }

    /** H.264 über ffmpeg: CRF wie angegeben (Vorgabe der Ausgabe 18, F207 A), yuv420p, Farbraum BT.709, MP4 mit dem Index vorn. */
    final class Ffmpeg implements Kodierer {
        private final Process p;
        private final OutputStream aus;
        private final File ziel;
        private final StringBuilder meldung = new StringBuilder();
        private final Thread leser;

        public Ffmpeg(String ffmpeg, File ziel, int w, int h, double bilderJeS, int crf) throws IOException {
            this.ziel = ziel;
            if (ziel.getParentFile() != null) ziel.getParentFile().mkdirs();
            List<String> c = List.of(ffmpeg, "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", w + "x" + h, "-r", String.format(Locale.ROOT, "%.6f", bilderJeS), "-i", "-",
                    "-vf", "scale=out_color_matrix=bt709:out_range=tv,format=yuv420p",
                    "-c:v", "libx264", "-preset", "slow", "-crf", String.valueOf(crf), "-tune", "film",
                    "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709", "-color_range", "tv",
                    "-movflags", "+faststart", ziel.getAbsolutePath());
            p = new ProcessBuilder(c).redirectErrorStream(true).start();
            aus = p.getOutputStream();
            InputStream ein = p.getInputStream();
            leser = new Thread(() -> {
                try {
                    byte[] b = ein.readAllBytes();
                    synchronized (meldung) {
                        meldung.append(new String(b));
                    }
                } catch (IOException e) {
                    // Ende
                }
            }, "ffmpeg");
            leser.setDaemon(true);
            leser.start();
        }

        @Override public void bild(byte[] rgb) throws IOException {
            try {
                aus.write(rgb);
            } catch (IOException e) {
                throw new IOException("ffmpeg nimmt keine Bilder mehr: " + meldung().strip(), e);
            }
        }

        private String meldung() {
            synchronized (meldung) {
                return meldung.toString();
            }
        }

        @Override public File ziel() { return ziel; }

        @Override public String art() { return "MP4 (H.264, ffmpeg)"; }

        @Override public void close() throws IOException {
            aus.close();
            try {
                int e = p.waitFor();
                leser.join(5000);
                if (e != 0) throw new IOException("ffmpeg endete mit " + e + ": " + meldung().strip());
            } catch (InterruptedException x) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Eine PNG-Folge {@code bild_000001.png} … in einen Ordner. */
    final class PngFolge implements Kodierer {
        private final File ordner;
        private final int w, h;
        private int n;

        public PngFolge(File ordner, int w, int h) {
            this.ordner = ordner;
            this.w = w;
            this.h = h;
            ordner.mkdirs();
        }

        @Override public void bild(byte[] rgb) throws IOException {
            BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            int[] p = new int[w * h];
            for (int i = 0; i < p.length; i++) p[i] = (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | rgb[3 * i + 2] & 0xFF;
            im.setRGB(0, 0, w, h, p, 0, w);
            ImageIO.write(im, "png", new File(ordner, String.format(Locale.ROOT, "bild_%06d.png", ++n)));
        }

        @Override public File ziel() { return ordner; }

        @Override public String art() { return "PNG-Folge"; }

        @Override public void close() { }
    }
}
