package com.dan.steuerhaus.chronik;

import com.dan.fbutton.FButton;
import com.dan.steuerhaus.Chronik;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * Die Chronik der Hochwasser (seit DB2, Werkbuch HalligHooge 169): oben das Band der Zeit mit jedem Ereignis als Strich
 * bis zu seiner Höhe gegen die Krone und die Höhe des Landunters, darunter die Ereignisse, das jüngste zuerst. Deckt ein
 * Szenario aus dem Archiv die Zeit des höchsten Wassers ab, führt ein Knopf dorthin (Szenario, Ansicht, Zeit). Liest nur
 * die {@link Chronik}; die Datenbank braucht die App nicht.
 */
public final class Chronikansicht extends JPanel {
    static final Color ROT = new Color(235, 90, 70), ORANGE = new Color(240, 160, 60), BLAU = new Color(90, 160, 230);
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("EE d.M.yyyy, HH:mm", Locale.GERMAN), KURZ = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN),
            MONAT = DateTimeFormatter.ofPattern("MMM yyyy", Locale.GERMAN);

    /** Ein Szenario mit der Zeit, die sein Archiv abdeckt. */
    public record Szene(String schluessel, String name, Instant von, Instant bis) {
        public boolean deckt(Instant t) { return t != null && !t.isBefore(von) && !t.isAfter(bis); }
    }

    /** Was der Knopf eines Ereignisses tut. */
    public interface Sprung { void zu(Szene s, Chronik.Ereignis e); }

    public final Chronik chronik;
    public final List<Szene> szenen;
    public final ZoneId zone;
    public Supplier<Instant> jetzt = Instant::now;
    private final Sprung sprung;
    private final String hinweis;
    private final List<FButton> knoepfe = new ArrayList<>();
    final Band band = new Band();

    public Chronikansicht(Chronik chronik, List<Szene> szenen, ZoneId zone, String hinweis, Sprung sprung) {
        super(new BorderLayout());
        this.chronik = chronik;
        this.szenen = List.copyOf(szenen);
        this.zone = zone;
        this.hinweis = hinweis == null ? "" : hinweis;
        this.sprung = sprung;
        Faerber.rolle(this, "grund");
        JPanel liste = Faerber.rolle(new JPanel(), "grund");
        liste.setLayout(new BoxLayout(liste, BoxLayout.Y_AXIS));
        liste.setBorder(BorderFactory.createEmptyBorder(4, 12, 8, 12));
        List<Chronik.Ereignis> ev = chronik.neuesteZuerst();
        if (ev.isEmpty()) liste.add(new Leer());
        for (Chronik.Ereignis e : ev) liste.add(zeile(e));
        JScrollPane sp = new JScrollPane(liste, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getViewport().setBackground(Thema.aktuell().grund);
        sp.getVerticalScrollBar().setUnitIncrement(24);
        add(band, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);
        if (!this.hinweis.isEmpty()) add(new Fuss(), BorderLayout.SOUTH);
    }

    /** Das erste Szenario (in der Folge der Szenendatei), das die Zeit des höchsten Wassers abdeckt, oder null. */
    public Szene szene(Chronik.Ereignis e) {
        for (Szene s : szenen) if (s.deckt(e.hoechst())) return s;
        return null;
    }

    /** Die Knöpfe, die in ein Szenario führen (für die Prüfung). */
    public List<FButton> knoepfe() { return List.copyOf(knoepfe); }

    JComponent zeile(Chronik.Ereignis e) {
        JPanel z = Faerber.rolle(new JPanel(new BorderLayout(12, 0)), "grund");
        z.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        z.add(new Eintrag(e), BorderLayout.CENTER);
        Szene s = szene(e);
        JPanel r = Faerber.rolle(new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 14)), "grund");
        r.setPreferredSize(new Dimension(330, 74));
        if (s != null && sprung != null) {
            FButton b = new FButton("Ansehen: " + kurz(s.name()));
            b.setToolTipText("Szenario „" + s.name() + "“ zur Zeit des höchsten Wassers (" + TAG.format(e.hoechst().atZone(zone)) + " Uhr)");
            b.setFocusable(false);
            Faerber.knopf(b, Thema.aktuell());
            b.setPreferredSize(new Dimension(318, 36));
            b.addActionListener(x -> sprung.zu(s, e));
            knoepfe.add(b);
            r.add(b);
        }
        z.add(r, BorderLayout.EAST);
        z.setMaximumSize(new Dimension(Integer.MAX_VALUE, 82));
        z.setAlignmentX(Component.LEFT_ALIGNMENT);
        return z;
    }

    static String kurz(String s) { return s.length() > 30 ? s.substring(0, 29) + "…" : s; }

    static Color farbe(Chronik.Art a) { return a == Chronik.Art.LANDUNTER ? ROT : a == Chronik.Art.KRONE ? ORANGE : BLAU; }

    static String zahl(double d) { return Double.isNaN(d) ? "–" : String.format(Locale.GERMAN, "%.2f", d); }

    static void glatt(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    String zeitraum(Chronik.Ereignis e) {
        ZonedDateTime a = e.ab().atZone(zone), b = e.bis().atZone(zone);
        if (e.tiden() <= 1 || e.ab().equals(e.bis())) return TAG.format(a) + " Uhr";
        return TAG.format(a) + " bis " + (a.toLocalDate().equals(b.toLocalDate()) ? DateTimeFormatter.ofPattern("HH:mm").format(b) : TAG.format(b)) + " Uhr";
    }

    static String tiden(int n) { return n == 1 ? "eine Tide" : n == 2 ? "zwei Tiden" : n == 3 ? "drei Tiden" : n + " Tiden"; }

    /** Eine Zeile der Liste, gemalt: Farbe der Art, Zeitraum, Werte, Text. */
    final class Eintrag extends JComponent {
        final Chronik.Ereignis e;

        Eintrag(Chronik.Ereignis e) {
            this.e = e;
            setPreferredSize(new Dimension(760, 74));
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.flaeche);
            g.fill(new java.awt.geom.RoundRectangle2D.Double(0, 0, w - 1, h - 1, 14, 14));
            Color c = farbe(e.art());
            g.setColor(c);
            g.fill(new java.awt.geom.RoundRectangle2D.Double(0, 0, 8, h - 1, 8, 8));
            g.setFont(th.titel(16f));
            FontMetrics f1 = g.getFontMetrics();
            String titel = e.art() == Chronik.Art.STURMFLUT ? "Sturmflut an den Pegeln" : e.art().anzeige + " am " + chronik.ort;
            g.drawString(titel, 22, 8 + f1.getAscent());
            int tx = 22 + f1.stringWidth(titel) + 14;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(th.text);
            g.drawString(zeitraum(e), tx, 8 + f1.getAscent());
            String werte = e.art() == Chronik.Art.STURMFLUT
                    ? "Scheitel " + zahl(e.hoehe()) + " m NHN in " + e.ort() + ", " + zahl(e.stau()) + " m über dem MThw · " + tiden(e.tiden())
                    : "Scheitel " + zahl(e.hoehe()) + " m NHN · Windstau " + zahl(e.stau()) + " m · " + tiden(e.tiden()) + " · "
                            + (e.art() == Chronik.Art.LANDUNTER ? zahl(e.hoehe() - chronik.nass) + " m über " + zahl(chronik.nass) + " m"
                                    : "bis zum Landunter fehlten " + zahl(chronik.nass - e.hoehe()) + " m");
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.drawString(werte, 22, 8 + f1.getHeight() + 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(th.leise);
            g.drawString(e.text(), 22, 8 + f1.getHeight() + 38);
            g.dispose();
        }
    }

    /** Ohne Ereignisse: ein Satz. */
    final class Leer extends JComponent {
        Leer() {
            setPreferredSize(new Dimension(760, 60));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 60));
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            g.setColor(th.leise);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.drawString("Seit " + (chronik.seit == null ? "dem Beginn" : KURZ.format(chronik.seit.atZone(zone))) + " kein Wasser über der Krone (" + zahl(chronik.krone)
                    + " m NHN) und keine Sturmflut.", 8, 30);
            g.dispose();
        }
    }

    /** Das Band der Zeit: jedes Ereignis als Strich bis zu seiner Höhe, Krone und Landunter als Linien. */
    final class Band extends JComponent {
        Band() { setPreferredSize(new Dimension(1100, 230)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight());
            g.dispose();
        }

        void male(Graphics2D g, int w, int h) {
            Thema th = Thema.aktuell();
            glatt(g);
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            Instant t0 = chronik.seit, t1 = jetzt.get();
            for (Chronik.Ereignis e : chronik.ereignisse) {
                if (t0 == null || e.ab().isBefore(t0)) t0 = e.ab();
                if (e.bis().isAfter(t1)) t1 = e.bis();
            }
            if (t0 == null) t0 = t1.minusSeconds(30L * 86400);
            t0 = t0.minusSeconds(2 * 86400);
            t1 = t1.plusSeconds(86400);
            double zo = 4.0;
            for (Chronik.Ereignis e : chronik.ereignisse) if (!Double.isNaN(e.hoehe())) zo = Math.max(zo, Math.ceil((e.hoehe() + 0.3) * 2) / 2);
            double zu = 0;
            int x0 = 62, x1 = w - 20, y0 = 44, y1 = h - 34;
            g.setFont(th.titel(16f));
            g.setColor(th.text);
            g.drawString("Hochwasser über der Krone, Landunter und Sturmfluten" + (chronik.seit == null ? "" : " seit " + KURZ.format(chronik.seit.atZone(zone))), 14, 24);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            g.setColor(th.leise);
            String st = chronik.erstellt == null ? "" : "Stand der Chronik " + TAG.format(chronik.erstellt.atZone(zone)) + " Uhr";
            g.drawString(st, w - 20 - g.getFontMetrics().stringWidth(st), 24);
            g.setColor(th.flaeche);
            g.fill(new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0));
            final Instant a = t0, b = t1;
            final double zuF = zu, zoF = zo;
            java.util.function.ToDoubleFunction<Instant> xt = t -> x0 + (x1 - x0) * (t.toEpochMilli() - a.toEpochMilli()) / (double) Math.max(1, b.toEpochMilli() - a.toEpochMilli());
            java.util.function.DoubleUnaryOperator yz = z -> y1 - (y1 - y0) * (Math.max(zuF, Math.min(zoF, z)) - zuF) / (zoF - zuF);
            // Raster: Meter und Monate
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            for (double z = zu; z <= zo + 1e-9; z += 1) {
                double y = yz.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, 90));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Line2D.Double(x0, y, x1, y));
                g.setColor(th.leise);
                String l = String.format(Locale.GERMAN, "%.0f m", z);
                g.drawString(l, x0 - 8 - fm.stringWidth(l), (int) y + 4);
            }
            LocalDate d = a.atZone(zone).toLocalDate().withDayOfMonth(1).plusMonths(1);
            // der angebrochene erste Monat, wenn bis zum nächsten Platz für den Namen ist
            if (xt.applyAsDouble(d.atStartOfDay(zone).toInstant()) - x0 > fm.stringWidth(MONAT.format(d.minusMonths(1))) + 12) {
                g.setColor(th.leise);
                g.drawString(MONAT.format(d.minusMonths(1)), x0 + 4, y1 + 16);
            }
            while (!d.atStartOfDay(zone).toInstant().isAfter(b)) {
                double x = xt.applyAsDouble(d.atStartOfDay(zone).toInstant());
                g.setColor(Thema.mitAlpha(th.linie, 120));
                g.draw(new Line2D.Double(x, y0, x, y1));
                g.setColor(th.leise);
                g.drawString(MONAT.format(d), (int) x + 4, y1 + 16);
                d = d.plusMonths(1);
            }
            // Krone und Landunter
            float[] strich = {8f, 6f};
            for (Object[] o : new Object[][] {{chronik.krone, ORANGE, "Krone " + zahl(chronik.krone) + " m"}, {chronik.nass, ROT, "Landunter " + zahl(chronik.nass) + " m"}}) {
                double z = (Double) o[0];
                if (Double.isNaN(z)) continue;
                double y = yz.applyAsDouble(z);
                g.setColor((Color) o[1]);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, strich, 0f));
                g.draw(new Line2D.Double(x0, y, x1, y));
                g.drawString((String) o[2], x1 - 4 - fm.stringWidth((String) o[2]), (int) y - 4);
            }
            // jetzt
            double xj = xt.applyAsDouble(jetzt.get());
            g.setColor(th.marke);
            g.setStroke(new BasicStroke(1.2f));
            g.draw(new Line2D.Double(xj, y0, xj, y1));
            g.drawString("jetzt", (int) xj - fm.stringWidth("jetzt") / 2, y0 - 4);
            // die Ereignisse: am Ort Strich bis zur Höhe, Sturmfluten als Band über die Zeit
            for (Chronik.Ereignis e : chronik.ereignisse) {
                Color c = farbe(e.art());
                if (e.art() == Chronik.Art.STURMFLUT) {
                    double xa = xt.applyAsDouble(e.ab()), xb = Math.max(xa + 4, xt.applyAsDouble(e.bis()));
                    g.setColor(Thema.mitAlpha(c, 70));
                    g.fill(new Rectangle2D.Double(xa - 2, y0, xb - xa + 4, y1 - y0));
                    g.setColor(c);
                    g.fill(new Rectangle2D.Double(xa - 2, y0, xb - xa + 4, 5));
                    continue;
                }
                double x = xt.applyAsDouble(e.hoechst()), y = yz.applyAsDouble(e.hoehe());
                g.setColor(c);
                g.setStroke(new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(new Line2D.Double(x, y1, x, y));
                g.fill(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
                String l = zahl(e.hoehe()) + " m";
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                g.drawString(l, (int) (x + 7), (int) y + 4);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            }
            // Legende
            int lx = x0;
            for (Object[] o : new Object[][] {{ROT, "Landunter am " + chronik.ort}, {ORANGE, "über der Krone"}, {BLAU, "Sturmflut an den Pegeln (BSH)"}}) {
                g.setColor((Color) o[0]);
                g.fill(new Rectangle2D.Double(lx, h - 14, 12, 8));
                g.setColor(th.leise);
                g.drawString((String) o[1], lx + 16, h - 6);
                lx += 16 + fm.stringWidth((String) o[1]) + 24;
            }
        }
    }

    /** Der Hinweis unter der Liste. */
    final class Fuss extends JComponent {
        Fuss() { setPreferredSize(new Dimension(800, 40)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            g.setColor(th.grund);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.leise);
            FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent() + 4;
            StringBuilder z = new StringBuilder();
            for (String w : hinweis.split("\\s+")) {
                if (z.length() > 0 && fm.stringWidth(z + " " + w) > getWidth() - 28) {
                    g.drawString(z.toString(), 14, y);
                    y += fm.getHeight();
                    z.setLength(0);
                }
                if (z.length() > 0) z.append(' ');
                z.append(w);
            }
            if (z.length() > 0) g.drawString(z.toString(), 14, y);
            g.dispose();
        }
    }

    /** Das ganze Fenster ohne Fenster als Bild (für die Prüfung). */
    public BufferedImage alsBild(int w, int h) {
        setSize(w, h);
        for (int i = 0; i < 3; i++) layout(this);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        printAll(g);
        g.dispose();
        return im;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }
}
