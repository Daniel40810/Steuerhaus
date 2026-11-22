package com.dan.steuerhaus.atlas;

import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Die Kacheln der Startseite (seit X1, Werkbuch HalligHooge 145): oben die Gruppen zum Umschalten, darunter eine Reihe
 * Kacheln mit Bild und Namen, waagrecht zu rollen (Rad, Pfeile). Ein Klick auf eine Kachel führt sie aus.
 */
public final class Kachelleiste extends JComponent {
    /** Eine Kachel: Titel, Unterzeile, Bild (16 : 9; null oder fehlend: Platzhalter), was ein Klick tut. */
    public record Kachel(String titel, String unterzeile, File bild, Runnable tun) { }

    static final int KB = 232, KH = 131, ABSTAND = 14, GRUPPEN = 40, RAND = 16;
    private final Map<String, List<Kachel>> gruppen = new LinkedHashMap<>();
    private String gruppe;
    private double versatz;
    private int unter = -1;
    private final Map<File, BufferedImage> bilder = new HashMap<>();
    private final List<Object[]> flaechen = new ArrayList<>();

    public Kachelleiste() {
        setPreferredSize(new Dimension(800, GRUPPEN + KH + 52));
        MouseAdapter m = new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                for (Object[] f : flaechen)
                    if (((Shape) f[0]).contains(e.getPoint())) {
                        ((Runnable) f[1]).run();
                        return;
                    }
            }

            @Override public void mouseMoved(MouseEvent e) {
                int u = -1;
                boolean hand = false;
                for (Object[] f : flaechen)
                    if (((Shape) f[0]).contains(e.getPoint())) {
                        hand = true;
                        if (f.length > 2) u = (Integer) f[2];
                    }
                setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (u != unter) {
                    unter = u;
                    repaint();
                }
            }

            @Override public void mouseExited(MouseEvent e) {
                unter = -1;
                repaint();
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) { rolle(e.getPreciseWheelRotation() * (KB + ABSTAND) * 0.6); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    /** Setzt eine Gruppe (Reihenfolge wie gesetzt); die erste ist zuerst gewählt. */
    public void gruppe(String name, List<Kachel> kacheln) {
        if (kacheln == null || kacheln.isEmpty()) return;
        gruppen.put(name, List.copyOf(kacheln));
        if (gruppe == null) gruppe = name;
        repaint();
    }

    public void waehle(String name) {
        if (!gruppen.containsKey(name)) return;
        gruppe = name;
        versatz = 0;
        repaint();
    }

    public String gewaehlt() { return gruppe; }

    public List<String> gruppen() { return List.copyOf(gruppen.keySet()); }

    public List<Kachel> kacheln() { return gruppe == null ? List.of() : gruppen.get(gruppe); }

    void rolle(double d) {
        int n = kacheln().size();
        double max = Math.max(0, RAND * 2 + n * (KB + ABSTAND) - ABSTAND - getWidth());
        versatz = Math.max(0, Math.min(max, versatz + d));
        repaint();
    }

    private BufferedImage bild(File f) {
        if (f == null) return null;
        if (bilder.containsKey(f)) return bilder.get(f);
        BufferedImage b = null;
        try {
            if (f.isFile()) b = ImageIO.read(f);
        } catch (Exception e) {
            // Platzhalter
        }
        bilder.put(f, b);
        return b;
    }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Thema t = Thema.aktuell();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setPaint(new GradientPaint(0, 0, t.flaeche, 0, getHeight(), t.tief));
        g.fillRect(0, 0, getWidth(), getHeight());
        g.setColor(t.linie);
        g.drawLine(0, 0, getWidth(), 0);
        flaechen.clear();
        // Gruppen
        int x = RAND;
        g.setFont(t.titel(14f));
        FontMetrics fm = g.getFontMetrics();
        for (String n : gruppen.keySet()) {
            int w = fm.stringWidth(n);
            boolean an = n.equals(gruppe);
            g.setColor(an ? t.text : t.leise);
            g.drawString(n, x, 26);
            if (an) {
                g.setColor(t.primaer);
                g.fillRoundRect(x, 31, w, 3, 3, 3);
            }
            String nn = n;
            flaechen.add(new Object[] {new java.awt.Rectangle(x - 6, 6, w + 12, 32), (Runnable) () -> waehle(nn)});
            x += w + 28;
        }
        // Kacheln
        List<Kachel> l = kacheln();
        int y = GRUPPEN + 8;
        java.awt.Shape alt = g.getClip();
        g.clipRect(0, GRUPPEN, getWidth(), getHeight() - GRUPPEN);
        for (int i = 0; i < l.size(); i++) {
            Kachel k = l.get(i);
            double kx = RAND + i * (KB + ABSTAND) - versatz;
            if (kx + KB < 0 || kx > getWidth()) continue;
            RoundRectangle2D.Double r = new RoundRectangle2D.Double(kx, y, KB, KH, 12, 12);
            BufferedImage b = bild(k.bild());
            java.awt.Shape c = g.getClip();
            g.clip(r);
            if (b != null) g.drawImage(b, (int) kx, y, KB, KH, null);
            else {
                g.setPaint(new GradientPaint((float) kx, y, Thema.mische(t.tag, t.primaer, 0.25), (float) kx, y + KH, t.nacht));
                g.fill(r);
                wellen(g, t, kx, y + KH * 0.62);
            }
            g.setClip(c);
            boolean hover = i == unter;
            g.setColor(hover ? t.primaer : Thema.mitAlpha(t.linie, 255));
            g.setStroke(new BasicStroke(hover ? 2.4f : 1f));
            g.draw(r);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(hover ? t.text : Thema.mische(t.text, t.leise, 0.2));
            g.drawString(kuerzen(g, k.titel(), KB), (int) kx, y + KH + 18);
            if (k.unterzeile() != null && !k.unterzeile().isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(t.leise);
                g.drawString(kuerzen(g, k.unterzeile(), KB), (int) kx, y + KH + 34);
            }
            flaechen.add(new Object[] {new java.awt.Rectangle((int) kx, y, KB, KH + 36), k.tun(), i});
        }
        g.setClip(alt);
        // Pfeile, wenn es mehr gibt
        double max = Math.max(0, RAND * 2 + l.size() * (KB + ABSTAND) - ABSTAND - getWidth());
        if (versatz > 0) pfeil(g, t, 6, y + KH / 2, true);
        if (versatz < max - 1) pfeil(g, t, getWidth() - 30, y + KH / 2, false);
        g.dispose();
    }

    private void pfeil(Graphics2D g, Thema t, int x, int y, boolean links) {
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(x, y - 20, 24, 40, 10, 10);
        g.setColor(Thema.mitAlpha(t.tief, 220));
        g.fill(r);
        g.setColor(t.primaer);
        Path2D.Double p = new Path2D.Double();
        if (links) {
            p.moveTo(x + 15, y - 8);
            p.lineTo(x + 8, y);
            p.lineTo(x + 15, y + 8);
        } else {
            p.moveTo(x + 9, y - 8);
            p.lineTo(x + 16, y);
            p.lineTo(x + 9, y + 8);
        }
        g.setStroke(new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(p);
        flaechen.add(0, new Object[] {r, (Runnable) () -> rolle((links ? -1 : 1) * (KB + ABSTAND) * 2.0)});
    }

    private static void wellen(Graphics2D g, Thema t, double x0, double y0) {
        for (int k = 0; k < 3; k++) {
            Path2D.Double p = new Path2D.Double();
            for (int x = 0; x <= KB; x += 4) {
                double y = y0 + k * 10 + (5 - k) * Math.sin(x / (26.0 + 7 * k) + k);
                if (x == 0) p.moveTo(x0 + x, y);
                else p.lineTo(x0 + x, y);
            }
            g.setColor(Thema.mitAlpha(t.kurve, 110 - 25 * k));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(p);
        }
    }

    private static String kuerzen(Graphics2D g, String s, int w) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(s) <= w) return s;
        while (s.length() > 1 && fm.stringWidth(s + "…") > w) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /** Färbt sich nach dem Thema neu (Aufruf nach einem Wechsel). */
    public void neu() { repaint(); }
}
