package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.thema.Thema;

import javax.swing.JComponent;
import javax.swing.JWindow;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * Eine kurze Meldung über dem Bild, unten rechts (Werkbuch HalligHooge 110, 5.1). Ein eigenes, schweres Fenster: FToast
 * legt sich in die {@code JLayeredPane} des Fensters und läge hinter der Vulkan-Fläche. Verschwindet nach einigen
 * Sekunden oder auf Klick; eine neue Meldung ersetzt die alte.
 */
public final class Hinweis {
    public enum Art { INFO, OK, FEHLER }

    private static JWindow fenster;
    private static Timer uhr;

    private Hinweis() { }

    /** Zeigt die Meldung über dem Besitzer (Oberfläche). */
    public static void zeige(Window besitzer, String text, Art art) {
        if (besitzer == null || !besitzer.isShowing()) return;
        if (fenster != null) fenster.dispose();
        if (uhr != null) uhr.stop();
        JWindow w = new JWindow(besitzer);
        Karte k = new Karte(text, art);
        w.setContentPane(k);
        Dimension d = k.getPreferredSize();
        w.setSize(d);
        w.setShape(new RoundRectangle2D.Double(0, 0, d.width, d.height, 14, 14));
        Rectangle r = besitzer.getBounds();
        w.setLocation(r.x + r.width - d.width - 24, r.y + r.height - d.height - 64);
        w.setFocusableWindowState(false);
        k.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { w.dispose(); }
        });
        w.setVisible(true);
        fenster = w;
        uhr = new Timer(art == Art.FEHLER ? 9000 : 5000, e -> w.dispose());
        uhr.setRepeats(false);
        uhr.start();
    }

    static final class Karte extends JComponent {
        private final String text;
        private final Art art;

        Karte(String text, Art art) {
            this.text = text;
            this.art = art;
            Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
            setFont(f);
            FontMetrics fm = getFontMetrics(f);
            setPreferredSize(new Dimension(Math.min(620, fm.stringWidth(text) + 52), 44));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight();
            Color farbe = switch (art) {
                case INFO -> t.kurve;
                case OK -> t.primaer;
                case FEHLER -> new Color(0xD0, 0x4A, 0x3A);
            };
            g.setColor(t.flaeche);
            g.fillRoundRect(0, 0, w, h, 14, 14);
            g.setColor(farbe);
            g.fillRoundRect(0, 0, 6, h, 6, 6);
            g.setColor(Thema.mitAlpha(farbe, 140));
            g.drawRoundRect(0, 0, w - 1, h - 1, 14, 14);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            g.setColor(t.text);
            String s = text;
            while (fm.stringWidth(s) > w - 40 && s.length() > 4) s = s.substring(0, s.length() - 2);
            if (!s.equals(text)) s = s + " …";
            g.drawString(s, 20, (h + fm.getAscent() - fm.getDescent()) / 2);
            g.dispose();
        }
    }
}
