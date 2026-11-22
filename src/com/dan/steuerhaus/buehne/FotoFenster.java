package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.thema.Thema;
import com.dan.fframe.FFrame;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * Das Fenster des Fotos (Werkbuch HalligHooge Teil A, P11b; F109 A, F113 A): zeigt das Foto, wie es klar wird – das
 * Zwischenbild nach jeder Runde, auf die Größe des Fensters gebracht (das Foto rechnet im halben Fenster), darunter der
 * Stand. Das Bild der Hallig daneben bleibt stehen, solange das Foto rechnet.
 */
public final class FotoFenster {

    private final FFrame fenster = new FFrame("Foto (Referenz)");
    private final Flaeche flaeche = new Flaeche();
    private final JLabel zeile = new JLabel(" ");

    public FotoFenster() {
        // seit Y1: FFrame schließt sonst die ganze App (EXIT_ON_CLOSE); der Knopf in der Leiste schickt WINDOW_CLOSING
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
        com.dan.steuerhaus.thema.Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(flaeche, BorderLayout.CENTER);
        zeile.setForeground(Thema.aktuell().text);
        zeile.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        zeile.setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        root.add(zeile, BorderLayout.SOUTH);
        fenster.setPreferredFrameSize(new Dimension(1000, 600));
        fenster.setSize(1000, 600);
        fenster.setLocationRelativeTo(null);
    }

    /** Zeigt das Fenster (nach vorn). */
    public void oeffne() {
        fenster.setVisible(true);
        fenster.toFront();
    }

    public boolean offen() { return fenster.isVisible(); }

    /** Neues Zwischenbild (darf null sein) und Stand. */
    public void zeige(BufferedImage bild, String text) {
        if (bild != null) flaeche.bild = bild;
        zeile.setText(text);
        flaeche.repaint();
    }

    public void schliesse() { fenster.dispose(); }

    /** Malt das Bild mit seinem Seitenverhältnis in die Fläche. */
    static final class Flaeche extends JComponent {
        volatile BufferedImage bild;

        Flaeche() {
            setOpaque(true);
            setBackground(Color.BLACK);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, getWidth(), getHeight());
            BufferedImage b = bild;
            if (b != null) {
                double s = Math.min(getWidth() / (double) b.getWidth(), getHeight() / (double) b.getHeight());
                int w = (int) Math.round(b.getWidth() * s), h = (int) Math.round(b.getHeight() * s);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(b, (getWidth() - w) / 2, (getHeight() - h) / 2, w, h, null);
            } else {
                g.setColor(Thema.aktuell().text);
                g.drawString("Das Foto wird vorbereitet …", 20, 30);
            }
            g.dispose();
        }
    }
}
