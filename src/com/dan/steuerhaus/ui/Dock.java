package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Ein Dock neben der Leinwand (Werkbuch HalligHooge 110, F179 A): links, rechts oder unten, mit einem Griff an der
 * Kante zum Bild. Ein Klick auf den Griff klappt das Dock weg oder auf, Ziehen ändert Breite (Höhe) – übernommen beim
 * Loslassen, weil jede Größe der Leinwand die Vulkan-Fläche neu baut. Seitliche Docks halten {@link Abschnitt}e
 * untereinander und rollen, wenn sie nicht passen; das untere Dock hält ein Teil über die ganze Breite.
 * <p>
 * Docks liegen immer neben der Leinwand, nie über ihr: Die Leinwand ist schwergewichtig (Befund 8).
 */
public final class Dock extends JPanel {
    public enum Seite { LINKS, RECHTS, UNTEN }

    public final Seite seite;
    public final String kennung;
    private final Griff griff;
    private final JPanel inhalt;
    private final JComponent huelle;
    private final List<Abschnitt> abschnitte = new ArrayList<>();
    private int mass;
    private final int minMass, maxMass;
    /** Wird nach jedem Auf- oder Zuklappen und nach jeder neuen Größe gerufen. */
    public Runnable geaendert = () -> { };

    public Dock(Seite seite, String kennung, String hinweis, int mass, int minMass, int maxMass) {
        super(new BorderLayout());
        this.seite = seite;
        this.kennung = kennung;
        this.mass = mass;
        this.minMass = minMass;
        this.maxMass = maxMass;
        Faerber.rolle(this, "grund");
        inhalt = new JPanel();
        Faerber.rolle(inhalt, "flaeche");
        if (seite == Seite.UNTEN) {
            inhalt.setLayout(new BorderLayout());
            huelle = inhalt;
        } else {
            inhalt.setLayout(new BoxLayout(inhalt, BoxLayout.Y_AXIS));
            inhalt.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            JPanel oben = new Spalte();
            Faerber.rolle(oben, "flaeche");
            oben.add(inhalt, BorderLayout.NORTH);
            JScrollPane sp = new JScrollPane(oben, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            sp.setBorder(BorderFactory.createEmptyBorder());
            sp.getVerticalScrollBar().setUnitIncrement(24);
            sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 8));
            sp.getVerticalScrollBar().setUI(new Leiste());
            Faerber.rolle(sp, "flaeche");
            Faerber.rolle(sp.getViewport(), "flaeche");
            huelle = sp;
        }
        griff = new Griff(hinweis);
        add(griff, switch (seite) {
            case LINKS -> BorderLayout.EAST;
            case RECHTS -> BorderLayout.WEST;
            case UNTEN -> BorderLayout.NORTH;
        });
        add(huelle, BorderLayout.CENTER);
        setzeMass(mass);
    }

    /** Hängt einen Abschnitt an (seitliche Docks). */
    public Abschnitt abschnitt(Abschnitt a) {
        if (seite == Seite.UNTEN) throw new IllegalStateException("Dock unten: inhalt(...) benutzen");
        if (!abschnitte.isEmpty()) inhalt.add(Box.createVerticalStrut(10));
        abschnitte.add(a);
        a.setAlignmentX(LEFT_ALIGNMENT);
        inhalt.add(a);
        a.geaendert = () -> geaendert.run();
        return a;
    }

    /** Setzt das Teil des unteren Docks. */
    public void inhalt(JComponent c, String lage) {
        inhalt.add(c, lage);
    }

    public List<Abschnitt> abschnitte() { return Collections.unmodifiableList(abschnitte); }

    public boolean offen() { return huelle.isVisible(); }

    public void zeige(boolean offen) {
        if (huelle.isVisible() == offen) return;
        huelle.setVisible(offen);
        revalidate();
        if (getParent() instanceof JComponent p) p.revalidate();
        griff.repaint();
        geaendert.run();
    }

    public void klappe() { zeige(!offen()); }

    /** Breite (links, rechts) oder Höhe (unten) des Inhalts in Bildpunkten. */
    public int mass() { return mass; }

    public void setzeMass(int m) {
        mass = Math.max(minMass, Math.min(maxMass, m));
        Dimension d = seite == Seite.UNTEN ? new Dimension(10, mass) : new Dimension(mass, 10);
        huelle.setPreferredSize(d);
        revalidate();
    }

    /** Der Inhalt im Rollbereich folgt der Breite des Docks (sonst schnitte das Dock breite Teile rechts ab). */
    static final class Spalte extends JPanel implements Scrollable {
        Spalte() { super(new BorderLayout()); }

        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }

        @Override public int getScrollableUnitIncrement(Rectangle r, int o, int d) { return 24; }

        @Override public int getScrollableBlockIncrement(Rectangle r, int o, int d) { return Math.max(24, r.height - 24); }

        @Override public boolean getScrollableTracksViewportWidth() { return true; }

        @Override public boolean getScrollableTracksViewportHeight() { return false; }
    }

    /** Eine schmale Rollleiste in den Farben des Themas, ohne Pfeile. */
    static final class Leiste extends BasicScrollBarUI {
        @Override protected void configureScrollBarColors() { }

        @Override protected JButton createDecreaseButton(int o) { return knopf(); }

        @Override protected JButton createIncreaseButton(int o) { return knopf(); }

        private static JButton knopf() {
            JButton b = new JButton();
            b.setPreferredSize(new Dimension(0, 0));
            b.setMinimumSize(new Dimension(0, 0));
            b.setMaximumSize(new Dimension(0, 0));
            return b;
        }

        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(Thema.aktuell().flaeche);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override protected void paintThumb(Graphics g0, JComponent c, Rectangle r) {
            if (r.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            g.setColor(isThumbRollover() ? t.leise : t.linie);
            g.fillRoundRect(r.x + 1, r.y + 2, r.width - 2, r.height - 4, r.width - 2, r.width - 2);
            g.dispose();
        }
    }

    /** Der Griff an der Kante zum Bild: Pfeil zeigt, wohin ein Klick das Dock klappt; Ziehen ändert die Größe. */
    final class Griff extends JComponent {
        static final int DICKE = 12;
        private boolean ueber, gezogen;
        private int start, massStart, vorschau = -1;

        Griff(String hinweis) {
            setPreferredSize(new Dimension(DICKE, DICKE));
            setOpaque(true);
            setToolTipText(hinweis);
            setCursor(Cursor.getPredefinedCursor(seite == Seite.UNTEN ? Cursor.N_RESIZE_CURSOR : seite == Seite.LINKS ? Cursor.E_RESIZE_CURSOR : Cursor.W_RESIZE_CURSOR));
            MouseAdapter m = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    start = seite == Seite.UNTEN ? e.getYOnScreen() : e.getXOnScreen();
                    massStart = mass;
                    gezogen = false;
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (!offen()) return;
                    int d = (seite == Seite.UNTEN ? e.getYOnScreen() : e.getXOnScreen()) - start;
                    if (Math.abs(d) > 3) gezogen = true;
                    vorschau = Math.max(minMass, Math.min(maxMass, massStart + (seite == Seite.LINKS ? d : -d)));
                    setToolTipText(vorschau + " px");
                    repaint();
                }

                @Override public void mouseReleased(MouseEvent e) {
                    if (gezogen && vorschau > 0) {
                        setzeMass(vorschau);
                        geaendert.run();
                    } else klappe();
                    vorschau = -1;
                    gezogen = false;
                    repaint();
                }

                @Override public void mouseEntered(MouseEvent e) {
                    ueber = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    ueber = false;
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight();
            g.setColor(ueber || gezogen ? Thema.mische(t.tief, t.linie, 0.45) : t.tief);
            g.fillRect(0, 0, w, h);
            double cx = w / 2.0, cy = h / 2.0, a = 3.5, l = 7;
            boolean auf = offen();
            Path2D.Double p = new Path2D.Double();
            // der Pfeil zeigt vom Bild weg, solange das Dock offen ist (ein Klick klappt es dorthin)
            double s = switch (seite) {
                case RECHTS, UNTEN -> auf ? 1 : -1;
                case LINKS -> auf ? -1 : 1;
            };
            if (seite != Seite.UNTEN) {
                p.moveTo(cx - s * a / 2, cy - l);
                p.lineTo(cx + s * a / 2, cy);
                p.lineTo(cx - s * a / 2, cy + l);
            } else {
                p.moveTo(cx - l, cy - s * a / 2);
                p.lineTo(cx, cy + s * a / 2);
                p.lineTo(cx + l, cy - s * a / 2);
            }
            g.setColor(ueber || gezogen ? t.kurve : t.leise);
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
            if (gezogen) {
                g.setColor(t.marke);
                if (seite == Seite.UNTEN) g.fillRect(0, h - 2, w, 2);
                else g.fillRect(seite == Seite.LINKS ? w - 2 : 0, 0, 2, h);
            }
            g.dispose();
        }
    }
}
