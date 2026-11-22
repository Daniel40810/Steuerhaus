package com.dan.steuerhaus.ui;

import com.dan.floadingspinner.FLoadingSpinner;
import com.dan.floadingspinner.FSpinnerVariant;
import com.dan.ficons.FIconType;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import java.awt.BasicStroke;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;

/**
 * Das Startbild (Werkbuch HalligHooge 110, 5.1; F182 B): Logo, Titel und Unterzeile aus dem Manifest, eine Ladeanzeige
 * und eine Zeile, was gerade geschieht. Steht als eigenes Fenster über allem, bis die App das erste Bild zeigt.
 * Seit W2 mit den Schritten der Bühne ({@link #schritt}): Name des Schritts und ein Balken mit dem Anteil.
 */
public final class Startbild {
    private final JWindow fenster;
    private final Flaeche flaeche;
    private final FLoadingSpinner spinner = new FLoadingSpinner(FSpinnerVariant.WAVE);

    public Startbild(Manifest m, String version) {
        fenster = new JWindow();
        flaeche = new Flaeche(m, version);
        JPanel p = new JPanel(null);
        p.setOpaque(false);
        flaeche.setBounds(0, 0, Flaeche.B, Flaeche.H);
        Thema t = Thema.aktuell();
        spinner.setDiameter(34);
        spinner.setPrimaryColor(t.primaer);
        spinner.setAccentColor(t.akzent);
        spinner.setTrackColor(Thema.mitAlpha(t.linie, 160));
        spinner.setGlowColor(t.primaer);
        spinner.setCustomColorsLocked(true);
        spinner.setBounds(Flaeche.B - 70, Flaeche.H - 74, 44, 44);
        p.add(spinner);
        p.add(flaeche);
        fenster.setContentPane(p);
        fenster.setSize(Flaeche.B, Flaeche.H);
        fenster.setShape(new RoundRectangle2D.Double(0, 0, Flaeche.B, Flaeche.H, 22, 22));
        fenster.setAlwaysOnTop(true);
        Rectangle r = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        fenster.setLocation(r.x + (r.width - Flaeche.B) / 2, r.y + (r.height - Flaeche.H) / 2);
    }

    public void zeige() {
        fenster.setVisible(true);
        spinner.start();
    }

    /** Was gerade geschieht (Oberfläche). */
    public void text(String t) {
        flaeche.text = t;
        flaeche.repaint();
    }

    /** Seit W2: ein Schritt der Bühne mit dem Anteil der Arbeit danach (0 … 1; Oberfläche). */
    public void schritt(String name, double anteil) {
        schritte.add(new Schritt(name, anteil));
        flaeche.anteil = Math.max(0, Math.min(1, anteil));
        flaeche.text = anteil >= 1 ? "Die Bühne steht." : "Die Bühne öffnet: " + name + " …";
        flaeche.repaint();
    }

    /** Ein gemeldeter Schritt. */
    public record Schritt(String name, double anteil) { }

    private final java.util.List<Schritt> schritte = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Die Schritte in der Folge ihrer Meldung (Prüfstein 7). */
    public java.util.List<Schritt> schritte() { return java.util.List.copyOf(schritte); }

    /** Prüfstein 7: Schritte der Reihe nach, Anteil nie fallend, der letzte 1. */
    public static boolean folgeGut(java.util.List<Schritt> l) {
        if (l.isEmpty() || l.get(l.size() - 1).anteil() != 1.0) return false;
        for (int i = 1; i < l.size(); i++) if (l.get(i).anteil() < l.get(i - 1).anteil()) return false;
        return true;
    }

    public void schliesse() {
        spinner.stop();
        fenster.dispose();
    }

    public boolean sichtbar() { return fenster.isVisible(); }

    /** Das Startbild als Bild, ohne Fenster (Prüfung, Bilder der Oberfläche). */
    public static BufferedImage bild(Manifest m, String version, String text) { return bild(m, version, text, Double.NaN); }

    /** Wie oben, mit dem Balken bei {@code anteil} (seit W2). */
    public static BufferedImage bild(Manifest m, String version, String text, double anteil) {
        Flaeche f = new Flaeche(m, version);
        f.text = text;
        f.anteil = anteil;
        f.setSize(Flaeche.B, Flaeche.H);
        BufferedImage b = new BufferedImage(Flaeche.B, Flaeche.H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        f.paint(g);
        g.dispose();
        return b;
    }

    static final class Flaeche extends JComponent {
        static final int B = 640, H = 340;
        private final String titel, unterzeile, version;
        private final BufferedImage logo;
        String text = "Die Bühne öffnet …";
        /** Anteil des Balkens (NaN: kein Balken). */
        double anteil = Double.NaN;

        Flaeche(Manifest m, String version) {
            titel = m.titel();
            unterzeile = m.unterzeile();
            this.version = version;
            BufferedImage l = null;
            try {
                l = FIconType.valueOf(m.logo()).toImage(56);
            } catch (RuntimeException e) {
                // kein Logo dieses Namens
            }
            logo = l;
            setPreferredSize(new Dimension(B, H));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            g.setPaint(new GradientPaint(0, 0, t.flaeche, 0, H, t.tief));
            g.fillRect(0, 0, B, H);
            // Wellenlinien unten: drei Tidenlinien im Ton der Kurve
            for (int k = 0; k < 3; k++) {
                Path2D.Double p = new Path2D.Double();
                double y0 = H - 92 + k * 14, a = 7 - k * 2;
                for (int x = 0; x <= B; x += 6) {
                    double y = y0 + a * Math.sin(x / (70.0 + 18 * k) + k * 1.3);
                    if (x == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                g.setColor(Thema.mitAlpha(t.kurve, 70 - k * 18));
                g.setStroke(new BasicStroke(1.4f));
                g.draw(p);
            }
            int x = 44;
            if (logo != null) {
                g.drawImage(logo, x, 52, null);
                x += 74;
            }
            g.setFont(t.titel(38f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(t.text);
            g.drawString(titel, x, 52 + fm.getAscent() - 4);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
            g.setColor(t.leise);
            g.drawString(unterzeile, x, 52 + fm.getHeight() + 10);
            g.setColor(t.primaer);
            g.fillRect(44, 150, 64, 2);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(t.text);
            g.drawString(text, 44, H - 44);
            if (!Double.isNaN(anteil)) {
                int bx = 44, by = H - 38, bw = B - 44 - 90, bh = 4;
                g.setColor(Thema.mitAlpha(t.linie, 200));
                g.fillRoundRect(bx, by, bw, bh, bh, bh);
                g.setPaint(new GradientPaint(bx, 0, t.primaer, bx + bw, 0, t.kurve));
                g.fillRoundRect(bx, by, (int) Math.round(bw * anteil), bh, bh, bh);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(t.leise);
            g.drawString(version, 44, H - 22);
            g.setColor(t.linie);
            g.setStroke(new BasicStroke(1f));
            g.drawRoundRect(0, 0, B - 1, H - 1, 22, 22);
            g.dispose();
        }
    }
}
