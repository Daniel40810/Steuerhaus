package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Das Vergleichsbild (seit W5, Werkbuch HalligHooge 126, F210 A): zwei Standbilder derselben Ansicht in zwei Szenarien
 * (aus der Ausgabe), übereinander; ein Schieber (Maus ziehen) zeigt links das eine, rechts das andere.
 */
final class Vergleichsfenster extends JDialog {
    private final BufferedImage a, b;
    private final String na, nb;
    private double teil = 0.5;

    Vergleichsfenster(JFrame besitzer, File fa, String na, File fb, String nb) throws IOException {
        super(besitzer, "Vergleich: " + na + " | " + nb, false);
        this.a = ImageIO.read(fa);
        this.b = ImageIO.read(fb);
        this.na = na;
        this.nb = nb;
        JComponent bild = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0.create();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                double s = Math.min(w / (double) a.getWidth(), h / (double) a.getHeight());
                int bw = (int) (a.getWidth() * s), bh = (int) (a.getHeight() * s), x0 = (w - bw) / 2, y0 = (h - bh) / 2;
                g.setColor(Thema.aktuell().tief);
                g.fillRect(0, 0, w, h);
                int sx = x0 + (int) (teil * bw);
                g.drawImage(a, x0, y0, bw, bh, null);
                g.setClip(sx, y0, x0 + bw - sx, bh);
                g.drawImage(b, x0, y0, bw, bh, null);
                g.setClip(null);
                g.setColor(Thema.aktuell().marke);
                g.fillRect(sx - 1, y0, 2, bh);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
                g.setColor(Thema.aktuell().text);
                g.drawString(na, x0 + 10, y0 + 22);
                g.drawString(nb, x0 + bw - 10 - g.getFontMetrics().stringWidth(nb), y0 + 22);
                g.dispose();
            }
        };
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { setze(e, bild); }

            @Override public void mouseDragged(MouseEvent e) { setze(e, bild); }
        };
        bild.addMouseListener(m);
        bild.addMouseMotionListener(m);
        bild.setPreferredSize(new Dimension(1280, 740));
        getContentPane().add(bild, BorderLayout.CENTER);
        pack();
        setLocationRelativeTo(besitzer);
    }

    private void setze(MouseEvent e, JComponent c) {
        int w = c.getWidth(), h = c.getHeight();
        double s = Math.min(w / (double) a.getWidth(), h / (double) a.getHeight());
        int bw = (int) (a.getWidth() * s), x0 = (w - bw) / 2;
        teil = Math.max(0, Math.min(1, (e.getX() - x0) / (double) bw));
        c.repaint();
    }
}
