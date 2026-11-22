package com.dan.steuerhaus.vergleich;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.fcombobox.FComboBox;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Der Inhalt des Vergleichsfensters (seit Y1, Werkbuch HalligHooge 152): oben das Bild beider Seiten mit Schieber (oder
 * nebeneinander, oder im Wechsel), darunter die Zeitleiste über dem Abstand zum Bezug und die Kurven derselben Sonden
 * in A (durchgezogen) und B (gestrichelt), mit den Werten am Zeiger. Abspielen, Bild für Bild, Bild sichern. Tasten:
 * Leertaste, ← →, Pos1, Ende, 1 2 3 (Darstellung), Esc (schließt das Fenster, siehe {@link Spieler}).
 */
public final class Vergleichsansicht extends JPanel {
    /** Die Darstellung der beiden Seiten. */
    public enum Darstellung {
        SCHIEBER("Schieber"), NEBENEINANDER("Nebeneinander"), WECHSEL("Im Wechsel");
        public final String name;

        Darstellung(String n) { name = n; }

        @Override public String toString() { return name; }
    }

    /** Farben der Sonden, wie im Abschnitt Sonden. */
    static final Color[] FARBE = {new Color(255, 170, 60), new Color(80, 210, 230), new Color(230, 110, 210), new Color(150, 220, 90)};
    static final String[] GROESSEN = {"Wasserstand", "Wassertiefe", "Strömung", "Wind"};
    static final int[] TEMPO = {6, 12, 24};
    /** Takt des Wechsels (ms) [G]. */
    static final int WECHSEL_MS = 700;
    /** Ränder der Zeitleiste und der Kurven: gleiche x-Achse. */
    static final int LINKS = 64, RECHTS = 24;

    public final Mappe m;
    final ZoneId zone;
    final File fotos;
    final Flaeche flaeche = new Flaeche();
    final Zeitband band = new Zeitband();
    final Kurven kurven = new Kurven();
    final FButton spielen = new FButton("Abspielen"), zurueck = new FButton("« Zurück"), vor = new FButton("Vor »"), sichern = new FButton("Bild sichern");
    final FComboBox tempo = new FComboBox(new Object[] {"6 Bilder/s", "12 Bilder/s", "24 Bilder/s"});
    final FComboBox darstellung = new FComboBox(Darstellung.values());
    final FComboBox groesse;
    final JLabel stand = new JLabel(" ");
    private final Timer takt, wechsel;
    // seit Y2: zwei Fäden lesen voraus und bringen die Bilder auf die Größe der Fläche (Y1 spielte nur 2,5 Bilder/s)
    private final ExecutorService vorleser = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "Vergleich: Bilder lesen");
        t.setDaemon(true);
        return t;
    });
    private long wartetSeit;
    private final AtomicInteger vorlesenBis = new AtomicInteger(-1);
    private int k;
    private double teil = 0.5;
    private boolean zeigeB;
    private final DateTimeFormatter uhr = DateTimeFormatter.ofPattern("EE dd.MM.yyyy, HH:mm 'Uhr'", Locale.GERMAN);
    /** Nach „Bild sichern“: die Datei (für die App ein Hinweis). */
    public Consumer<File> gesichert = f -> { };

    public Vergleichsansicht(Mappe m, ZoneId zone, File fotos) {
        super(new BorderLayout());
        this.m = m;
        this.zone = zone;
        this.fotos = fotos;
        List<String> gr = new ArrayList<>();
        for (String g : GROESSEN) for (Mappe.Sonde s : m.sonden) if (s.reihe(g) != null && !gr.contains(g)) gr.add(g);
        if (gr.isEmpty()) gr.add("Wasserstand");
        groesse = new FComboBox(gr.toArray());
        m.platz(2 * 16);
        k = m.beimBezug();
        tempo.setSelectedIndex(1);
        takt = new Timer(1000 / TEMPO[1], e -> schritt());
        wechsel = new Timer(WECHSEL_MS, e -> {
            zeigeB = !zeigeB;
            flaeche.repaint();
        });
        for (JComponent c : new JComponent[] {spielen, zurueck, vor, sichern, tempo, darstellung, groesse}) c.setFocusable(false);
        tempo.setLightWeightPopupEnabled(false);
        darstellung.setLightWeightPopupEnabled(false);
        groesse.setLightWeightPopupEnabled(false);
        for (FButton b : new FButton[] {zurueck, vor, sichern}) b.setVariant(FButtonVariant.GHOST);
        spielen.addActionListener(e -> spiele(!takt.isRunning()));
        // seit Y3: Beschriftung mit « » statt ◀ ▶, die die Schrift der Knöpfe nicht hat (leere Kästchen)
        zurueck.setToolTipText("Ein Bild zurück (Pfeil links)");
        vor.setToolTipText("Ein Bild vor (Pfeil rechts)");
        zurueck.addActionListener(e -> zeige(k - 1));
        vor.addActionListener(e -> zeige(k + 1));
        sichern.addActionListener(e -> sichern());
        tempo.addActionListener(e -> takt.setDelay(1000 / TEMPO[Math.max(0, tempo.getSelectedIndex())]));
        darstellung.addActionListener(e -> darstellung((Darstellung) darstellung.getSelectedItem()));
        groesse.addActionListener(e -> kurven.repaint());

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.setOpaque(false);
        knoepfe.add(spielen);
        knoepfe.add(zurueck);
        knoepfe.add(vor);
        knoepfe.add(tempo);
        knoepfe.add(darstellung);
        knoepfe.add(groesse);
        knoepfe.add(sichern);
        Faerber.rolle(stand, "leise");
        stand.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JPanel leiste = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        leiste.add(knoepfe, BorderLayout.WEST);
        JPanel rechts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        rechts.setOpaque(false);
        rechts.add(stand);
        leiste.add(rechts, BorderLayout.CENTER);
        JPanel unten = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        unten.add(band, BorderLayout.NORTH);
        unten.add(kurven, BorderLayout.CENTER);
        unten.add(leiste, BorderLayout.SOUTH);
        Faerber.rolle(this, "grund");
        add(flaeche, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        Faerber.faerbe(this);
        tasten();
        standText();
    }

    private void tasten() {
        Object[][] t = {
                {"SPACE", (Runnable) () -> spiele(!takt.isRunning())},
                {"LEFT", (Runnable) () -> zeige(k - 1)}, {"RIGHT", (Runnable) () -> zeige(k + 1)},
                {"HOME", (Runnable) () -> zeige(0)}, {"END", (Runnable) () -> zeige(m.bilder.size() - 1)},
                {"1", (Runnable) () -> darstellung(Darstellung.SCHIEBER)}, {"2", (Runnable) () -> darstellung(Darstellung.NEBENEINANDER)},
                {"3", (Runnable) () -> darstellung(Darstellung.WECHSEL)}};
        for (Object[] x : t) {
            String n = "vergleich." + x[0];
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke((String) x[0]), n);
            Runnable r = (Runnable) x[1];
            getActionMap().put(n, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }
    }

    // ------------------------------------------------------------------ Steuern

    /** Das Bild k (begrenzt) zeigen. */
    public void zeige(int n) {
        int z = Math.max(0, Math.min(m.bilder.size() - 1, n));
        if (z == k && n == z) {
            repaint();
            return;
        }
        k = z;
        vorlesen();
        standText();
        flaeche.repaint();
        band.repaint();
        kurven.repaint();
    }

    public int bild() { return k; }

    public boolean spielt() { return takt.isRunning(); }

    /** Abspielen an oder aus; am Ende beginnt es von vorn. */
    public void spiele(boolean an) {
        if (an) {
            if (k >= m.bilder.size() - 1) zeige(0);
            takt.start();
        } else takt.stop();
        spielen.setText(an ? "Halt" : "Abspielen");
        vorlesen();
    }

    private void schritt() {
        if (k >= m.bilder.size() - 1) {
            spiele(false);
            return;
        }
        // seit Y2: auf das nächste Bild warten, statt es hier zu lesen (höchstens 0,4 s, dann liest die Fläche selbst)
        int w = flaeche.zielW, h = flaeche.zielH;
        boolean da = w > 0 && m.skaliertDa(k + 1, false, w, h) && m.skaliertDa(k + 1, true, w, h);
        long jetzt = System.nanoTime();
        if (!da) {
            vorlesen();
            if (wartetSeit == 0) wartetSeit = jetzt;
            if (jetzt - wartetSeit < 400_000_000L) return;
        }
        wartetSeit = 0;
        zeige(k + 1);
    }

    public void darstellung(Darstellung d) {
        if (darstellung.getSelectedItem() != d) darstellung.setSelectedItem(d);
        if (d == Darstellung.WECHSEL) {
            if (!wechsel.isRunning()) wechsel.start();
        } else {
            wechsel.stop();
            zeigeB = false;
        }
        vorlesen();
        flaeche.repaint();
    }

    public Darstellung darstellung() { return (Darstellung) darstellung.getSelectedItem(); }

    /** Der Schieber: Anteil der Breite, den A zeigt (0 … 1). */
    public void teil(double t) {
        teil = Math.max(0, Math.min(1, t));
        flaeche.repaint();
    }

    public void groesse(String g) { groesse.setSelectedItem(g); }

    /** Hält Abspielen und Wechsel an und gibt den Leser frei. */
    public void ende() {
        takt.stop();
        wechsel.stop();
        vorleser.shutdownNow();
    }

    /** Liest die nächsten Bilder in der Richtung des Abspielens im Hintergrund, je Seite ein Faden, in der Größe der Fläche. */
    private void vorlesen() {
        int bis = Math.min(m.bilder.size() - 1, k + (takt.isRunning() ? 10 : 1));
        int w = flaeche.zielW, h = flaeche.zielH;
        if (w <= 0 || h <= 0) return;
        if (vorlesenBis.get() >= bis) return;
        vorlesenBis.set(bis);
        int von = k;
        try {
            for (int seite = 0; seite < 2; seite++) {
                boolean b = seite == 1;
                vorleser.submit(() -> {
                    for (int i = von; i <= bis; i++) {
                        if (i < k - 1) continue;
                        m.skaliert(i, b, w, h);
                    }
                    if (b) vorlesenBis.compareAndSet(bis, -1);
                });
            }
        } catch (java.util.concurrent.RejectedExecutionException x) {
            // nach dem Ende
        }
    }

    private void standText() {
        if (m.bilder.isEmpty()) {
            stand.setText("Die Mappe hat keine Bilder.");
            return;
        }
        Mappe.Bild b = m.bilder.get(k);
        stand.setText(String.format(Locale.GERMAN, "Bild %d von %d · %s %s · Schritt %.0f min", k + 1, m.bilder.size(), Vergleich.abstand(b.rel()), nach(), m.schritt / 60));
    }

    /** „nach Hochwasser“ oder, bei gleicher Uhrzeit, „ab 12:00 Uhr“. */
    String nach() {
        Vergleich.Bezug z = m.bezug;
        if (z == null) return "";
        if (z.art().equals("Hochwasser") || z.art().equals("Niedrigwasser") || z.art().equals("Scheitel")) return z.nach();
        return "ab " + DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN).format(z.a().atZone(zone)) + " Uhr";
    }

    /** Das Bild jetzt in der Größe der Mappe, wie es die Fläche zeigt, als PNG in den Ordner der Fotos. */
    public File sichern() {
        BufferedImage im = new BufferedImage(m.breite, m.hoehe, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        flaeche.male(g, m.breite, m.hoehe, true, Math.max(1, m.hoehe / 1080.0));
        g.dispose();
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File f = new File(fotos, String.format(Locale.ROOT, "Vergleich_%s_%03d_%s.png", m.schluessel, k + 1, stempel));
        try {
            f.getParentFile().mkdirs();
            ImageIO.write(im, "png", f);
            stand.setText("Gesichert: " + f.getName());
            gesichert.accept(f);
            return f;
        } catch (IOException x) {
            stand.setText("Nicht gesichert: " + x.getMessage());
            return null;
        }
    }

    /** Der ganze Inhalt als Bild (ohne Fenster; für Prüfungen und die Ablage im Lauf). */
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

    // ------------------------------------------------------------------ x-Achse von Zeitleiste und Kurven

    double relMin() { return m.bilder.isEmpty() ? -1 : m.bilder.get(0).rel(); }

    double relMax() {
        double a = relMin(), b = m.bilder.isEmpty() ? 1 : m.bilder.get(m.bilder.size() - 1).rel();
        return b > a ? b : a + 1;
    }

    double x(double rel, int w) { return LINKS + (rel - relMin()) / (relMax() - relMin()) * (w - LINKS - RECHTS); }

    double rel(int px, int w) { return relMin() + (px - LINKS) / (double) Math.max(1, w - LINKS - RECHTS) * (relMax() - relMin()); }

    static String zahl(double v, int stellen) { return Double.isNaN(v) ? "–" : String.format(Locale.GERMAN, "%+." + stellen + "f", v).replace('-', '−'); }

    // ------------------------------------------------------------------ Fläche

    final class Flaeche extends JComponent {
        Flaeche() {
            setPreferredSize(new Dimension(1280, 620));
            setFocusable(true);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    schiebe(e);
                }

                @Override public void mouseDragged(MouseEvent e) { schiebe(e); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private void schiebe(MouseEvent e) {
            if (darstellung() != Darstellung.SCHIEBER) return;
            Rectangle r = rahmen(getWidth(), getHeight());
            teil((e.getX() - r.x) / (double) Math.max(1, r.width));
        }

        /** Der Rahmen eines Bilds in der Fläche (Seitenverhältnis der Mappe). */
        Rectangle rahmen(int w, int h) {
            double s = Math.min(w / (double) m.breite, h / (double) m.hoehe);
            int bw = (int) Math.round(m.breite * s), bh = (int) Math.round(m.hoehe * s);
            return new Rectangle((w - bw) / 2, (h - bh) / 2, bw, bh);
        }

        /** Größe eines Bilds auf der Fläche beim letzten Malen (seit Y2: so liest der Vorleser). */
        volatile int zielW, zielH;

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight(), false, 1);
            g.dispose();
        }

        /** Malt die Fläche w × h; {@code voll}: aus den vollen Bildern (gesichertes Bild); {@code mass} vergrößert Schrift und Marken. */
        void male(Graphics2D g, int w, int h, boolean voll, double mass) {
            Thema th = Thema.aktuell();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(th.tief);
            g.fillRect(0, 0, w, h);
            if (m.bilder.isEmpty()) {
                g.setColor(th.leise);
                g.drawString("Die Mappe hat keine Bilder.", 20, 30);
                return;
            }
            Mappe.Bild b = m.bilder.get(k);
            Darstellung d = darstellung();
            if (d == Darstellung.NEBENEINANDER) {
                int lw = (w - 6) / 2;
                Rectangle ra = rahmenIn(0, 0, lw, h), rb = rahmenIn(w - lw, 0, lw, h);
                if (!voll) ziel(ra);
                bildIn(g, k, false, voll, ra);
                bildIn(g, k, true, voll, rb);
                marken(g, ra, mass);
                marken(g, rb, mass);
                schild(g, ra, true, m.nameA, b.a(), b.pegelA(), false, mass);
                schild(g, rb, true, m.nameB, b.b(), b.pegelB(), true, mass);
                return;
            }
            Rectangle r = rahmen(w, h);
            if (!voll) ziel(r);
            if (d == Darstellung.WECHSEL) {
                bildIn(g, k, zeigeB, voll, r);
                marken(g, r, mass);
                if (zeigeB) schild(g, r, false, m.nameB, b.b(), b.pegelB(), true, mass);
                else schild(g, r, false, m.nameA, b.a(), b.pegelA(), false, mass);
                kennbuchstabe(g, r, zeigeB ? "B" : "A", mass);
                return;
            }
            int sx = r.x + (int) Math.round(teil * r.width);
            bildIn(g, k, false, voll, r);
            Shape alt = g.getClip();
            g.clipRect(sx, r.y, r.x + r.width - sx, r.height);
            bildIn(g, k, true, voll, r);
            g.setClip(alt);
            marken(g, r, mass);
            // der Schieber: Linie und Griff
            g.setColor(Thema.mitAlpha(Color.BLACK, 90));
            g.fillRect(sx - (int) Math.round(2 * mass), r.y, (int) Math.round(4 * mass), r.height);
            g.setColor(th.marke);
            g.fillRect(sx - (int) Math.round(mass), r.y, (int) Math.max(2, Math.round(2 * mass)), r.height);
            int gr = (int) Math.round(15 * mass), gy = r.y + r.height / 2;
            g.fillOval(sx - gr, gy - gr, 2 * gr, 2 * gr);
            g.setColor(th.tief);
            g.setStroke(new BasicStroke((float) (2 * mass), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            int a = (int) Math.round(5 * mass);
            g.drawPolyline(new int[] {sx - a / 2, sx - a - a / 2, sx - a / 2}, new int[] {gy - a, gy, gy + a}, 3);
            g.drawPolyline(new int[] {sx + a / 2, sx + a + a / 2, sx + a / 2}, new int[] {gy - a, gy, gy + a}, 3);
            schild(g, r, false, m.nameA, b.a(), b.pegelA(), false, mass);
            schild(g, r, false, m.nameB, b.b(), b.pegelB(), true, mass);
        }

        private Rectangle rahmenIn(int x0, int y0, int w, int h) {
            Rectangle r = rahmen(w, h);
            r.translate(x0, y0);
            return r;
        }

        private void ziel(Rectangle r) {
            boolean neu = r.width != zielW || r.height != zielH;
            zielW = r.width;
            zielH = r.height;
            if (neu) {
                vorlesenBis.set(-1);
                SwingUtilities.invokeLater(Vergleichsansicht.this::vorlesen);
            }
        }

        private void bildIn(Graphics2D g, int n, boolean seiteB, boolean voll, Rectangle r) {
            BufferedImage im = voll ? m.bild(n, seiteB, 1) : m.skaliert(n, seiteB, r.width, r.height);
            if (im == null) {
                g.setColor(Thema.aktuell().flaeche);
                g.fillRect(r.x, r.y, r.width, r.height);
                g.setColor(Thema.aktuell().leise);
                g.drawString("Bild fehlt: " + m.datei(n, seiteB).getName(), r.x + 16, r.y + r.height / 2);
                return;
            }
            if (voll) g.drawImage(im, r.x, r.y, r.width, r.height, null);
            else g.drawImage(im, r.x, r.y, null);
        }

        /** Die Sonden an ihrem Ort im Bild: Kreis in ihrer Farbe mit der Nummer. */
        private void marken(Graphics2D g, Rectangle r, double mass) {
            double s = r.width / (double) m.breite;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(11 * mass)));
            FontMetrics fm = g.getFontMetrics();
            for (int i = 0; i < m.sonden.size(); i++) {
                Mappe.Sonde so = m.sonden.get(i);
                if (Double.isNaN(so.x()) || Double.isNaN(so.y())) continue;
                double x = r.x + so.x() * s, y = r.y + so.y() * s;
                int rad = (int) Math.round(9 * mass);
                g.setColor(Thema.mitAlpha(Color.BLACK, 140));
                g.fillOval((int) x - rad - 1, (int) y - rad - 1, 2 * rad + 2, 2 * rad + 2);
                g.setColor(FARBE[i % FARBE.length]);
                g.fillOval((int) x - rad, (int) y - rad, 2 * rad, 2 * rad);
                g.setColor(Color.BLACK);
                String t = String.valueOf(i + 1);
                g.drawString(t, (int) Math.round(x - fm.stringWidth(t) / 2.0), (int) Math.round(y + fm.getAscent() / 2.0 - 1));
            }
        }

        /** Das Schild einer Seite: Name, Uhrzeit, Pegel; links oben (A) oder rechts oben (B). */
        private void schild(Graphics2D g, Rectangle r, boolean eng, String name, Instant t, double pegel, boolean rechts, double mass) {
            Thema th = Thema.aktuell();
            Font f1 = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(14 * mass)), f2 = new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(12 * mass));
            String z1 = (rechts ? "B  " : "A  ") + name, z2 = uhr.format(t.atZone(zone)), z3 = Double.isNaN(pegel) ? null : m.pegelName + " " + zahl(pegel, 2) + " m NHN";
            g.setFont(f1);
            FontMetrics m1 = g.getFontMetrics();
            g.setFont(f2);
            FontMetrics m2 = g.getFontMetrics();
            int bw = Math.max(m1.stringWidth(z1), Math.max(m2.stringWidth(z2), z3 == null ? 0 : m2.stringWidth(z3))) + (int) Math.round(20 * mass);
            int bh = m1.getHeight() + m2.getHeight() * (z3 == null ? 1 : 2) + (int) Math.round(12 * mass);
            int rand = (int) Math.round((eng ? 8 : 14) * mass);
            int x = rechts ? r.x + r.width - rand - bw : r.x + rand, y = r.y + rand;
            g.setColor(Thema.mitAlpha(th.tief, 190));
            g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 10 * mass, 10 * mass));
            g.setColor(rechts ? Thema.mische(th.primaer, Color.WHITE, 0.25) : th.marke);
            g.fillRect(x, y + (int) Math.round(6 * mass), (int) Math.max(2, Math.round(3 * mass)), bh - (int) Math.round(12 * mass));
            int tx = x + (int) Math.round(12 * mass), ty = y + (int) Math.round(6 * mass) + m1.getAscent();
            g.setFont(f1);
            g.setColor(th.text);
            g.drawString(z1, tx, ty);
            g.setFont(f2);
            g.setColor(th.leise);
            ty += m2.getHeight();
            g.drawString(z2, tx, ty);
            if (z3 != null) {
                ty += m2.getHeight();
                g.setColor(th.text);
                g.drawString(z3, tx, ty);
            }
        }

        private void kennbuchstabe(Graphics2D g, Rectangle r, String s, double mass) {
            Thema th = Thema.aktuell();
            g.setFont(th.titel((float) (40 * mass)));
            FontMetrics fm = g.getFontMetrics();
            int x = r.x + r.width - fm.stringWidth(s) - (int) Math.round(24 * mass), y = r.y + r.height - (int) Math.round(22 * mass);
            g.setColor(Thema.mitAlpha(Color.BLACK, 120));
            g.drawString(s, x + 2, y + 2);
            g.setColor(s.equals("A") ? th.marke : Thema.mische(th.primaer, Color.WHITE, 0.25));
            g.drawString(s, x, y);
        }
    }

    // ------------------------------------------------------------------ Zeitleiste

    final class Zeitband extends JComponent {
        Zeitband() {
            setPreferredSize(new Dimension(800, 52));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { zeige(m.naechstes(rel(e.getX(), getWidth()))); }

                @Override public void mouseDragged(MouseEvent e) { zeige(m.naechstes(rel(e.getX(), getWidth()))); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            if (m.bilder.isEmpty()) {
                g.dispose();
                return;
            }
            Mappe.Bild b = m.bilder.get(k);
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            String t = Vergleich.abstand(b.rel()) + " " + nach();
            g.drawString(t, LINKS, 19);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            String uh = "A " + DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN).format(b.a().atZone(zone)) + "   ·   B "
                    + DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN).format(b.b().atZone(zone));
            g.setColor(th.leise);
            g.drawString(uh, w - RECHTS - fm.stringWidth(uh), 19);
            int y = 34;
            int x0 = (int) x(relMin(), w), x1 = (int) x(relMax(), w);
            g.setColor(th.linie);
            g.fillRoundRect(x0, y - 3, x1 - x0, 6, 6, 6);
            // volle Stunden
            for (long s = (long) Math.ceil(relMin() / 3600); s <= Math.floor(relMax() / 3600); s++) {
                int x = (int) x(s * 3600.0, w);
                g.setColor(s == 0 ? th.marke : th.leise);
                g.fillRect(x - (s == 0 ? 1 : 0), y - (s == 0 ? 9 : 6), s == 0 ? 3 : 1, s == 0 ? 18 : 12);
                String l = s == 0 ? "0" : (s > 0 ? "+" : "−") + Math.abs(s) + " h";
                g.drawString(l, x - fm.stringWidth(l) / 2, y + 17);
            }
            int x = (int) x(b.rel(), w);
            g.setColor(Thema.mische(th.primaer, Color.WHITE, 0.2));
            g.fillRoundRect(x0, y - 3, Math.max(0, x - x0), 6, 6, 6);
            g.setColor(th.text);
            g.fillOval(x - 7, y - 7, 14, 14);
            g.setColor(th.primaer);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ Kurven

    final class Kurven extends JComponent {
        private static final int OBEN = 26, UNTEN = 20;

        Kurven() {
            setPreferredSize(new Dimension(800, 210));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { zeige(m.naechstes(rel(e.getX(), getWidth()))); }

                @Override public void mouseDragged(MouseEvent e) { zeige(m.naechstes(rel(e.getX(), getWidth()))); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            String gr = (String) groesse.getSelectedItem();
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            int px0 = LINKS, px1 = w - RECHTS, py0 = OBEN, py1 = h - UNTEN;
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            String einheit = "";
            for (Mappe.Sonde s : m.sonden) {
                Mappe.Reihe r = s.reihe(gr);
                if (r == null) continue;
                einheit = r.einheit();
                for (double[] v : new double[][] {r.a(), r.b()})
                    for (int i = 0; i < v.length; i++) {
                        if (r.t()[i] < relMin() || r.t()[i] > relMax() || Double.isNaN(v[i])) continue;
                        lo = Math.min(lo, v[i]);
                        hi = Math.max(hi, v[i]);
                    }
            }
            // Kopf: Größe, Legende
            g.setColor(th.text);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            String kopf = gr + (einheit.isEmpty() ? "" : " (" + einheit + ")");
            g.drawString(kopf, px0, 17);
            int kx = px0 + g.getFontMetrics().stringWidth(kopf) + 16;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            for (int i = 0; i < m.sonden.size(); i++) {
                String n = (i + 1) + " " + m.sonden.get(i).name();
                g.setColor(FARBE[i % FARBE.length]);
                g.fillOval(kx, 9, 9, 9);
                g.drawString(n, kx + 13, 17);
                kx += fm.stringWidth(n) + 28;
            }
            String leg = "—— " + m.nameA + "     - - - " + m.nameB;
            g.setColor(th.leise);
            g.drawString(leg, px1 - fm.stringWidth(leg), 17);
            if (!(hi >= lo)) {
                g.setColor(th.leise);
                g.drawString(m.sonden.isEmpty() ? "Keine Sonden in dieser Mappe." : "Keine Werte für " + gr + ".", px0 + 10, py0 + 20);
                g.dispose();
                return;
            }
            if (hi - lo < 1e-3) hi = lo + 1e-3;
            double pad = 0.08 * (hi - lo);
            lo -= pad;
            hi += pad;
            double ylo = lo, yhi = hi;
            java.util.function.DoubleUnaryOperator yv = v -> py1 - (v - ylo) / (yhi - ylo) * (py1 - py0);
            // Gitter: fünf Linien
            double stufe = rund((hi - lo) / 4);
            g.setColor(th.leise);
            for (double v = Math.ceil(lo / stufe) * stufe; v <= hi; v += stufe) {
                int y = (int) yv.applyAsDouble(v);
                g.setColor(Thema.mitAlpha(th.linie, 160));
                g.drawLine(px0 + 1, y, px1 - 1, y);
                g.setColor(th.leise);
                String l = String.format(Locale.GERMAN, stufe < 0.1 ? "%.2f" : "%.1f", v).replace('-', '−');
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            // Bezug und Stunden
            for (long s = (long) Math.ceil(relMin() / 3600); s <= Math.floor(relMax() / 3600); s++) {
                int x = (int) x(s * 3600.0, w);
                g.setColor(s == 0 ? Thema.mitAlpha(th.marke, 200) : Thema.mitAlpha(th.linie, 140));
                g.drawLine(x, py0 + 1, x, py1 - 1);
                if (s != 0) continue;
                g.setColor(th.marke);
                String l = m.bezug != null ? m.bezug.art() : "Bezug";
                g.drawString(l, x + 4, py1 - 5);
            }
            Stroke voll = new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND),
                    strich = new BasicStroke(1.7f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {7f, 5f}, 0f);
            Shape alt = g.getClip();
            g.clipRect(px0 + 1, py0 + 1, px1 - px0 - 1, py1 - py0 - 1);
            for (int i = 0; i < m.sonden.size(); i++) {
                Mappe.Reihe r = m.sonden.get(i).reihe(gr);
                if (r == null) continue;
                for (int s = 1; s >= 0; s--) {
                    double[] v = s == 0 ? r.a() : r.b();
                    Path2D.Double p = new Path2D.Double();
                    boolean frei = true;
                    for (int j = 0; j < v.length; j++) {
                        if (Double.isNaN(v[j])) {
                            frei = true;
                            continue;
                        }
                        double x = x(r.t()[j], w), y = yv.applyAsDouble(v[j]);
                        if (frei) p.moveTo(x, y);
                        else p.lineTo(x, y);
                        frei = false;
                    }
                    Color c = FARBE[i % FARBE.length];
                    g.setColor(s == 0 ? c : Thema.mische(c, th.flaeche, 0.15));
                    g.setStroke(s == 0 ? voll : strich);
                    g.draw(p);
                }
            }
            g.setClip(alt);
            g.setStroke(new BasicStroke(1f));
            if (!m.bilder.isEmpty()) {
                double rel = m.bilder.get(k).rel();
                int x = (int) x(rel, w);
                g.setColor(th.text);
                g.drawLine(x, py0, x, py1);
                // Werte am Zeiger: je Sonde A, B, Unterschied
                List<String> z = new ArrayList<>();
                List<Color> zc = new ArrayList<>();
                for (int i = 0; i < m.sonden.size(); i++) {
                    Mappe.Reihe r = m.sonden.get(i).reihe(gr);
                    if (r == null) continue;
                    double a = r.bei(r.a(), rel), b = r.bei(r.b(), rel);
                    int st = einheit.equals("m/s") ? 2 : 2;
                    z.add(String.format(Locale.GERMAN, "%d  A %s   B %s   B − A %s", i + 1, betrag(a, st, gr), betrag(b, st, gr),
                            Double.isNaN(a) || Double.isNaN(b) ? "–" : zahl(b - a, st)));
                    zc.add(FARBE[i % FARBE.length]);
                }
                if (!z.isEmpty()) {
                    int bw = 0;
                    for (String s : z) bw = Math.max(bw, fm.stringWidth(s));
                    bw += 16;
                    int bh = z.size() * (fm.getHeight() + 1) + 8;
                    int bx = x + 12 + bw < px1 - 4 ? x + 12 : x - 12 - bw, by = py0 + 6;
                    g.setColor(Thema.mitAlpha(th.tief, 215));
                    g.fillRoundRect(bx, by, bw, bh, 8, 8);
                    for (int i = 0; i < z.size(); i++) {
                        g.setColor(zc.get(i));
                        g.drawString(z.get(i), bx + 8, by + 4 + fm.getAscent() + i * (fm.getHeight() + 1));
                    }
                }
            }
            // Stunden unten
            g.setColor(th.leise);
            for (long s = (long) Math.ceil(relMin() / 3600); s <= Math.floor(relMax() / 3600); s++) {
                int x = (int) x(s * 3600.0, w);
                String l = s == 0 ? "0" : (s > 0 ? "+" : "−") + Math.abs(s) + " h";
                g.drawString(l, x - fm.stringWidth(l) / 2, h - 5);
            }
            g.dispose();
        }

        /** Wasserstand mit Vorzeichen, die anderen ohne. */
        private String betrag(double v, int st, String gr) {
            if (Double.isNaN(v)) return gr.equals("Wasserstand") ? "trocken" : "–";
            return gr.equals("Wasserstand") ? zahl(v, st) : String.format(Locale.GERMAN, "%." + st + "f", v);
        }

        private double rund(double x) {
            double e = Math.pow(10, Math.floor(Math.log10(x)));
            double f = x / e;
            return (f < 1.5 ? 1 : f < 3 ? 2 : f < 7 ? 5 : 10) * e;
        }
    }

    /** Für die App: das Bild sofort zeigen, wenn die Fläche groß ist (vorher gelesen). */
    public void vorladen() { SwingUtilities.invokeLater(this::vorlesen); }
}
