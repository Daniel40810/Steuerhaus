package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.thema.Thema;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Der Abschnitt „Leistung“ (seit W5, Werkbuch HalligHooge 126): oben die Dauer jedes Bilds der letzten 60 s (Linie bei
 * 16,7 und 33,3 ms), darunter Bilder/s, Median und 95 %, die Qualitätsstufe, der Speicher auf der Karte, das letzte
 * lange Bild und die zehn teuersten Abschnitte der Stoppuhr auf der Karte (gleitendes Mittel). Gemalt im Takt der
 * Oberfläche; die Bildschleife merkt sich nur die Dauer je Bild.
 */
final class LeistungPaneel extends JComponent {
    private float[] ms = new float[0];
    private List<Map.Entry<String, Double>> ab = List.of();
    private long[] speicher = {0, 0};
    private String lang, stufe = "";
    private double summeKarte;

    LeistungPaneel() {
        setPreferredSize(new Dimension(300, 360));
        setOpaque(true);
    }

    void zeige(float[] ms, Map<String, Double> abschnitte, long[] speicher, String lang, String stufe) {
        this.ms = ms;
        this.ab = abschnitte.entrySet().stream().limit(10).toList();
        double s = 0;
        for (double v : abschnitte.values()) s += v;
        summeKarte = s;
        this.speicher = speicher;
        this.lang = lang;
        this.stufe = stufe;
        repaint();
    }

    /** Median und 95 % der Dauern (ms). */
    static double[] kennwerte(float[] ms) {
        if (ms.length == 0) return new double[] {Double.NaN, Double.NaN, Double.NaN};
        float[] s = ms.clone();
        Arrays.sort(s);
        double summe = 0;
        for (float v : s) summe += v;
        return new double[] {s[s.length / 2], s[Math.min(s.length - 1, (int) (0.95 * s.length))], 1000 * s.length / summe};
    }

    @Override protected void paintComponent(Graphics g0) {
        Thema t = Thema.aktuell();
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(t.tief);
        g.fillRect(0, 0, getWidth(), getHeight());
        int w = getWidth() - 16, x0 = 8, y0 = 8, h = 90;
        g.setColor(t.flaeche);
        g.fillRect(x0, y0, w, h);
        double skala = 50;                                                  // ms über die Höhe
        for (double linie : new double[] {1000 / 60.0, 1000 / 30.0}) {
            int y = (int) (y0 + h - linie / skala * h);
            g.setColor(Thema.mitAlpha(t.linie, 200));
            g.drawLine(x0, y, x0 + w, y);
        }
        int n = ms.length;
        for (int i = 0; i < n; i++) {
            int x = x0 + (int) ((long) i * w / Math.max(1, n));
            double v = Math.min(skala, ms[i]);
            g.setColor(ms[i] > 1000 / 30.0 ? t.marke : t.kurve);
            g.drawLine(x, y0 + h, x, (int) (y0 + h - v / skala * h));
        }
        g.setColor(t.linie);
        g.drawRect(x0, y0, w, h);
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics();
        int y = y0 + h + 4 + fm.getAscent();
        double[] k = kennwerte(ms);
        g.setColor(t.text);
        g.drawString(String.format(Locale.GERMAN, "%.1f Bilder/s · Median %.1f ms · 95 %% %.1f ms (60 s)", k[2], k[0], k[1]), x0, y);
        y += fm.getHeight();
        g.setColor(t.leise);
        g.drawString(String.format(Locale.GERMAN, "Qualität: %s · Karte %.0f MB (Spitze %.0f MB)", stufe, speicher[0] / 1048576.0, speicher[1] / 1048576.0), x0, y);
        y += fm.getHeight();
        if (lang != null) {
            String s = "Langes Bild: " + lang;
            while (fm.stringWidth(s) > w && s.length() > 10) s = s.substring(0, s.length() - 2);
            g.drawString(s, x0, y);
            y += fm.getHeight();
        }
        y += 4;
        g.setColor(t.text);
        g.drawString(String.format(Locale.GERMAN, "Karte je Bild: %.2f ms (Summe der Abschnitte)", summeKarte), x0, y);
        y += fm.getHeight() + 2;
        double max = ab.isEmpty() ? 1 : Math.max(0.01, ab.get(0).getValue());
        for (Map.Entry<String, Double> e : ab) {
            int bw = (int) (e.getValue() / max * (w * 0.35));
            g.setColor(Thema.mitAlpha(t.kurve, 140));
            g.fillRect(x0, y - fm.getAscent() + 2, bw, fm.getAscent() - 2);
            g.setColor(t.text);
            String name = e.getKey();
            String wert = String.format(Locale.GERMAN, "%.2f", e.getValue());
            g.drawString(wert, x0 + (int) (w * 0.35) + 6, y);
            int nx = x0 + (int) (w * 0.35) + 6 + fm.stringWidth("00,00") + 6;
            while (fm.stringWidth(name) > x0 + w - nx && name.length() > 6) name = name.substring(0, name.length() - 2);
            g.setColor(t.leise);
            g.drawString(name, nx, y);
            y += fm.getHeight();
        }
        g.dispose();
    }
}
