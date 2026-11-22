package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;

/**
 * Ein Abschnitt in einem seitlichen Dock: Kopf mit Titel in der Titelschrift des Themas und einer feinen Linie,
 * darunter der Inhalt. Ein Klick auf den Kopf klappt den Inhalt zu oder auf. Die Kennung trägt den Stand in die
 * Einstellungen und in die Arbeitsbereiche.
 */
public final class Abschnitt extends JPanel {
    public final String kennung, titel;
    private final JComponent inhalt;
    private final Kopf kopf = new Kopf();
    Runnable geaendert = () -> { };

    public Abschnitt(String kennung, String titel, JComponent inhalt) {
        super(new BorderLayout());
        this.kennung = kennung;
        this.titel = titel;
        this.inhalt = inhalt;
        Faerber.rolle(this, "flaeche");
        inhalt.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        add(kopf, BorderLayout.NORTH);
        add(inhalt, BorderLayout.CENTER);
    }

    public boolean offen() { return inhalt.isVisible(); }

    public void zeige(boolean offen) {
        if (inhalt.isVisible() == offen) return;
        inhalt.setVisible(offen);
        revalidate();
        kopf.repaint();
        geaendert.run();
    }

    @Override public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    final class Kopf extends JComponent {
        private boolean ueber;

        Kopf() {
            setPreferredSize(new Dimension(200, 24));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText("Abschnitt auf- oder zuklappen");
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { zeige(!offen()); }

                @Override public void mouseEntered(MouseEvent e) {
                    ueber = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    ueber = false;
                    repaint();
                }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight();
            g.setColor(t.flaeche);
            g.fillRect(0, 0, w, h);
            // Pfeil: nach unten offen, nach rechts zu
            Path2D.Double p = new Path2D.Double();
            double cx = 6, cy = h / 2.0 - 1;
            if (offen()) {
                p.moveTo(cx - 4, cy - 2);
                p.lineTo(cx, cy + 2);
                p.lineTo(cx + 4, cy - 2);
            } else {
                p.moveTo(cx - 2, cy - 4);
                p.lineTo(cx + 2, cy);
                p.lineTo(cx - 2, cy + 4);
            }
            g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(ueber ? t.primaer : t.leise);
            g.draw(p);
            g.setFont(t.titel(13f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(ueber ? t.primaer : t.text);
            g.drawString(titel, 16, (h + fm.getAscent() - fm.getDescent()) / 2 - 1);
            int x = 16 + fm.stringWidth(titel) + 8;
            g.setColor(t.linie);
            g.fillRect(x, h / 2, Math.max(0, w - x), 1);
            g.dispose();
        }
    }
}
