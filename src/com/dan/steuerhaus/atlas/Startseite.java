package com.dan.steuerhaus.atlas;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.ficons.FIconType;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.tafel.Tafel;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JWindow;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.ZoneId;

/**
 * Die Startseite einer App (seit X1, Werkbuch HalligHooge 145): oben Titel und der Stand der Bühne, in der Mitte der
 * Atlas ({@link Kartenblatt}) mit der Infotafel ({@link Tafel}) rechts, unten die Kacheln ({@link Kachelleiste}).
 * <p>
 * Ein eigenes Fenster, das über dem Inhalt des Hauptfensters liegt und ihm folgt ({@link #anheften}): Die Leinwand der
 * Bühne ist eine Vulkan-Fläche (schwergewichtig) und darf nicht aus dem Fenster genommen werden; so lädt die Bühne
 * dahinter, während man den Atlas schon benutzt. Was ein Klick tut, setzt die App.
 */
public final class Startseite {
    public final JWindow fenster;
    public final Kartenblatt karte;
    public final Tafel tafel = new Tafel();
    public final Kachelleiste kacheln = new Kachelleiste();
    private final Kopf kopf;
    private final FButton zurSzene = new FButton("Zur Szene");
    private Component anker;
    /** „Zur Szene“ (Knopf, Eingabe, Esc). */
    public Runnable beiZurSzene = () -> { };

    public Startseite(Window besitzer, Manifest m, ZoneId zone) {
        fenster = new JWindow(besitzer);
        // X2b: nie das aktive Fenster werden – ein Klick lässt das Hauptfenster aktiv; Eingabe und Esc nimmt das Hauptfenster
        // (Befehl kapitel.weiter, regie.abbrechen). Maus und Rad gehen trotzdem.
        fenster.setFocusableWindowState(false);
        karte = new Kartenblatt(zone);
        kopf = new Kopf(m);
        JPanel inhalt = new JPanel(new BorderLayout());
        Faerber.rolle(inhalt, "grund");
        JPanel knopf = new JPanel(new FlowLayout(FlowLayout.RIGHT, 18, 26));
        knopf.setOpaque(false);
        zurSzene.setVariant(FButtonVariant.PRIMARY);
        zurSzene.setPreferredSize(new Dimension(150, 38));
        zurSzene.addActionListener(e -> beiZurSzene.run());
        knopf.add(zurSzene);
        kopf.add(knopf, BorderLayout.EAST);
        JPanel mitte = new JPanel(new BorderLayout());
        Faerber.rolle(mitte, "grund");
        mitte.add(karte, BorderLayout.CENTER);
        tafel.setPreferredSize(new Dimension(440, 400));
        mitte.add(tafel, BorderLayout.EAST);
        inhalt.add(kopf, BorderLayout.NORTH);
        inhalt.add(mitte, BorderLayout.CENTER);
        inhalt.add(kacheln, BorderLayout.SOUTH);
        fenster.setContentPane(inhalt);
        inhalt.registerKeyboardAction(e -> beiZurSzene.run(), KeyStroke.getKeyStroke("ENTER"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        inhalt.registerKeyboardAction(e -> beiZurSzene.run(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        Thema.beiWechsel(() -> SwingUtilities.invokeLater(this::nachThema));
        nachThema();
        bereit(false);
    }

    private void nachThema() {
        Faerber.faerbe(fenster.getContentPane());
        Faerber.knopf(zurSzene, Thema.aktuell());
        fenster.repaint();
    }

    /** Legt die Startseite über {@code anker} (den Inhalt des Hauptfensters) und folgt ihm, wenn es wandert oder wächst. */
    public void anheften(Window besitzer, Component anker) {
        this.anker = anker;
        ComponentAdapter folgen = new ComponentAdapter() {
            @Override public void componentMoved(ComponentEvent e) { ausrichten(); }

            @Override public void componentResized(ComponentEvent e) { ausrichten(); }

            @Override public void componentShown(ComponentEvent e) { ausrichten(); }
        };
        besitzer.addComponentListener(folgen);
        anker.addComponentListener(folgen);
        if (besitzer instanceof java.awt.Frame f) f.addWindowStateListener(e -> SwingUtilities.invokeLater(this::ausrichten));
        ausrichten();
    }

    public void ausrichten() {
        if (anker == null || !anker.isShowing()) return;
        Point p = anker.getLocationOnScreen();
        fenster.setBounds(p.x, p.y, anker.getWidth(), anker.getHeight());
        fenster.validate();
    }

    public void zeige() {
        ausrichten();
        Window o = fenster.getOwner();
        if (o != null && o.isShowing()) o.toFront();                       // X2b: im Vordergrund des eigenen Programms bleiben
        fenster.setVisible(true);
        fenster.toFront();
        karte.requestFocusInWindow();
    }

    /** Verbirgt die Startseite; das Hauptfenster bleibt vorn. */
    public void verberge() {
        Window o = fenster.getOwner();
        if (o != null && o.isShowing()) o.toFront();
        fenster.setVisible(false);
    }

    public boolean sichtbar() { return fenster.isVisible(); }

    /** Der Stand der Bühne: Text und Anteil (0 … 1; NaN: ohne Balken). */
    public void stand(String text, double anteil) {
        kopf.stand = text;
        kopf.anteil = anteil;
        kopf.repaint();
    }

    /** Ist die Szene bereit? Dann heißt der Knopf „Zur Szene“, sonst „Szene öffnet …“ (ein Klick merkt sich den Wunsch). */
    public void bereit(boolean b) {
        zurSzene.setText(b ? "Zur Szene  ›" : "Szene öffnet …");
        if (b) stand("Die Szene ist bereit.", Double.NaN);
        zurSzene.repaint();
    }

    /** Die Startseite als Bild, ohne Fenster (Prüfung, Bilder der Oberfläche). */
    public BufferedImage bild(int w, int h) {
        Component c = fenster.getContentPane();
        c.setSize(w, h);
        for (int i = 0; i < 3; i++) layout(c);
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        c.printAll(g);
        g.dispose();
        return b;
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) for (Component x : k.getComponents()) layout(x);
    }

    /** Der Kopf: Logo, Titel, Unterzeile, Wellen; rechts darunter der Stand der Bühne. */
    static final class Kopf extends JPanel {
        private final String titel, unterzeile;
        private final BufferedImage logo;
        String stand = "Die Bühne öffnet …";
        double anteil = Double.NaN;

        Kopf(Manifest m) {
            super(new BorderLayout());
            setOpaque(true);
            titel = m.titel();
            unterzeile = m.unterzeile();
            BufferedImage l = null;
            try {
                l = FIconType.valueOf(m.logo()).toImage(44);
            } catch (RuntimeException e) {
                // kein Logo dieses Namens
            }
            logo = l;
            setPreferredSize(new Dimension(800, 96));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight();
            g.setPaint(new GradientPaint(0, 0, t.flaeche, 0, h, t.tief));
            g.fillRect(0, 0, w, h);
            for (int k = 0; k < 3; k++) {
                Path2D.Double p = new Path2D.Double();
                double y0 = h - 22 + k * 7, a = 4 - k;
                for (int x = 0; x <= w; x += 6) {
                    double y = y0 + a * Math.sin(x / (90.0 + 20 * k) + k * 1.3);
                    if (x == 0) p.moveTo(x, y);
                    else p.lineTo(x, y);
                }
                g.setColor(Thema.mitAlpha(t.kurve, 55 - k * 14));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(p);
            }
            int x = 24;
            if (logo != null) {
                g.drawImage(logo, x, 20, null);
                x += 60;
            }
            g.setFont(t.titel(30f));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(t.text);
            g.drawString(titel, x, 20 + fm.getAscent() - 4);
            int tx = x + fm.stringWidth(titel) + 18;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.setColor(t.leise);
            g.drawString(unterzeile, tx, 20 + fm.getAscent() - 6);
            // Stand der Bühne
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(t.leise);
            g.drawString(stand == null ? "" : stand, x, h - 22);
            if (!Double.isNaN(anteil)) {
                int bx = x + g.getFontMetrics().stringWidth(stand == null ? "" : stand) + 14, by = h - 30, bw = 180;
                g.setColor(Thema.mitAlpha(t.linie, 255));
                g.fillRoundRect(bx, by, bw, 6, 6, 6);
                g.setColor(t.primaer);
                g.fillRoundRect(bx, by, (int) Math.round(bw * Math.max(0, Math.min(1, anteil))), 6, 6, 6);
            }
            g.dispose();
        }
    }
}
