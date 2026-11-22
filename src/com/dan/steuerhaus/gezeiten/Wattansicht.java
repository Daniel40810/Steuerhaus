package com.dan.steuerhaus.gezeiten;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.fcombobox.FComboBox;
import com.dan.fslider.FSlider;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
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
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Inhalt des Watt-Fensters (seit Z3, Werkbuch HalligHooge 158): links die Karte (Land, Wasser zur gewählten Zeit, der
 * Weg als Band – sandfarben, wo er trocken ist, blau, wo nicht – mit Sattel, Start und Ziel), rechts der Tag (Gezeit am
 * Anleger, Schwellen, trockene Zeit als grünes Band, Nacht dunkel) und die Fenster der nächsten sieben Tage, unten das
 * Profil des Wegs mit dem Wasserstand. Zeit über Tag und Uhrzeit; Windstau ohne, gemessen oder eigen. Modell, nicht zur
 * Navigation.
 */
public final class Wattansicht extends JPanel {
    static final Color SAND = new Color(225, 200, 140), NASS = new Color(70, 140, 210), GRUEN = new Color(110, 200, 120), ROT = new Color(235, 90, 70);
    static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN), TAG = DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN),
            LANG = DateTimeFormatter.ofPattern("EEEE, d. MMMM", Locale.GERMAN);
    static final int TAGE = 7, STAU_MIN = -10, STAU_MAX = 20;

    public final Watttafel tafel;
    public final Watt watt;
    public final Windstaumessung messung;
    final ZoneId zone;
    final File fotos;
    final String hinweis;
    final double gehen;
    public Supplier<Instant> jetzt = Instant::now;
    public Consumer<File> gesichert = f -> { };
    /** Die gewählte Zeit. */
    private Instant zeit;
    private final float[] hoehen;
    private final BufferedImage land;
    /** Ausschnitt der Karte (Ost, Nord, Ost, Nord). */
    final double[] ausschnitt;

    final Karte karte = new Karte();
    final Tageslinie tag = new Tageslinie();
    final Liste liste = new Liste();
    final Profil profil = new Profil();
    final FComboBox quelle;
    final FSlider eigen = new FSlider(STAU_MIN, STAU_MAX, 0), uhrzeit = new FSlider(0, 143, 0);
    final JLabel uhrText = new JLabel(" "), stand = new JLabel(" ");
    final FButton zurueck = new FButton("« Tag"), heute = new FButton("Jetzt"), vor = new FButton("Tag »"), neu = new FButton("Messung laden"), sichern = new FButton("Bild sichern");
    private boolean still;

    public Wattansicht(Watttafel tafel, Vorhersage v, Gezeitentafel gt, double gehen, String hinweis, ZoneId zone, File fotos) throws IOException {
        super(new BorderLayout());
        this.tafel = tafel;
        this.watt = new Watt(v, tafel.schwelleAb, tafel.schwelleBis, tafel.sattel, tafel.laenge, tafel.breite);
        this.messung = new Windstaumessung(gt);
        this.gehen = gehen > 0 ? gehen : 3;
        this.hinweis = hinweis == null ? "" : hinweis;
        this.zone = zone;
        this.fotos = fotos;
        this.hoehen = tafel.hoehen();
        this.land = tafel.land();
        double minO = Double.MAX_VALUE, maxO = -Double.MAX_VALUE, minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
        for (Watttafel.Punkt p : tafel.weg) {
            minO = Math.min(minO, p.ost());
            maxO = Math.max(maxO, p.ost());
            minN = Math.min(minN, p.nord());
            maxN = Math.max(maxN, p.nord());
        }
        double rand = 600;
        ausschnitt = new double[] {Math.max(tafel.karte[0], minO - rand), Math.max(tafel.karte[1], minN - rand), Math.min(tafel.karte[2], maxO + rand),
                Math.min(tafel.karte[3], maxN + rand)};
        zeit = Instant.now();

        List<String> q = new ArrayList<>(List.of("Ohne Wind (nur Gezeit)"));
        if (!messung.bezuege.isEmpty()) q.add("Gemessen (" + messung.namen() + ")");
        q.add("Eigener Wert");
        quelle = new FComboBox(q.toArray());
        quelle.setPreferredSize(new Dimension(300, quelle.getPreferredSize().height));
        quelle.setLightWeightPopupEnabled(false);
        quelle.setFocusable(false);
        quelle.setToolTipText("Windstau, der zur Gezeit dazukommt (gemessen gilt nur für die nächsten Stunden)");
        quelle.addActionListener(e -> {
            if (gemessenGewaehlt() && messung.gemessen() == null) laden();
            aktualisieren();
        });
        eigen.setFocusable(false);
        eigen.setPreferredSize(new Dimension(150, eigen.getPreferredSize().height));
        eigen.setToolTipText("Eigener Windstau (−0,50 … +1,00 m in 5 cm)");
        eigen.addChangeListener(e -> {
            if (still) return;
            if (!eigenGewaehlt()) {
                still = true;
                quelle.setSelectedIndex(quelle.getItemCount() - 1);
                still = false;
            }
            aktualisieren();
        });
        uhrzeit.setFocusable(false);
        uhrzeit.setPreferredSize(new Dimension(260, uhrzeit.getPreferredSize().height));
        uhrzeit.setToolTipText("Uhrzeit am gewählten Tag (10 min)");
        uhrzeit.addChangeListener(e -> {
            if (still) return;
            LocalDate d = zeit.atZone(zone).toLocalDate();
            zeit = d.atStartOfDay(zone).plusMinutes(10L * uhrzeit.getValue()).toInstant();
            aktualisieren();
        });
        for (FButton b : new FButton[] {zurueck, heute, vor, neu, sichern}) {
            b.setFocusable(false);
            b.setVariant(FButtonVariant.GHOST);
        }
        zurueck.addActionListener(e -> zeige(zeit.minusSeconds(86400)));
        vor.addActionListener(e -> zeige(zeit.plusSeconds(86400)));
        heute.addActionListener(e -> zeige(jetzt.get()));
        neu.addActionListener(e -> laden());
        neu.setEnabled(!messung.bezuege.isEmpty());
        sichern.addActionListener(e -> sichern());
        Faerber.rolle(uhrText, "text");
        uhrText.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        uhrText.setPreferredSize(new Dimension(150, 24));
        Faerber.rolle(stand, "leise");
        stand.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        stand.setPreferredSize(new Dimension(230, 24));

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.setOpaque(false);
        for (JComponent c : new JComponent[] {zurueck, heute, vor, uhrzeit, uhrText, quelle, eigen, neu, sichern, stand}) knoepfe.add(c);
        JPanel leiste = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        leiste.add(knoepfe, BorderLayout.CENTER);
        JPanel rechts = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        rechts.add(tag, BorderLayout.CENTER);
        rechts.add(liste, BorderLayout.SOUTH);
        // links: die Karte so hoch, wie ihr Ausschnitt bei der Breite braucht; das Profil darunter (mindestens 170 px)
        JPanel links = Faerber.rolle(new JPanel(null) {
            @Override public void doLayout() {
                int w = getWidth(), h = getHeight();
                int kh = Math.min(h - 170, karte.wunschHoehe(w));
                karte.setBounds(0, 0, w, Math.max(0, kh));
                profil.setBounds(0, Math.max(0, kh), w, h - Math.max(0, kh));
            }

            @Override public Dimension getPreferredSize() { return new Dimension(760, 480 + 170); }
        }, "grund");
        links.add(karte);
        links.add(profil);
        JPanel oben = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        oben.add(links, BorderLayout.CENTER);
        oben.add(rechts, BorderLayout.EAST);
        JPanel fuss = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        fuss.add(leiste, BorderLayout.NORTH);
        if (!this.hinweis.isEmpty()) fuss.add(new Landunteruhr.Hinweiszeile(this.hinweis), BorderLayout.CENTER);
        Faerber.rolle(this, "grund");
        add(oben, BorderLayout.CENTER);
        add(fuss, BorderLayout.SOUTH);
        zeige(jetzt.get());
    }

    /** Die Quellen des Windstaus in der Auswahl. */
    public List<String> quellen() {
        List<String> l = new ArrayList<>();
        for (int i = 0; i < quelle.getItemCount(); i++) l.add(String.valueOf(quelle.getItemAt(i)));
        return l;
    }

    boolean gemessenGewaehlt() { return !messung.bezuege.isEmpty() && quelle.getSelectedIndex() == 1; }

    boolean eigenGewaehlt() { return quelle.getSelectedIndex() == quelle.getItemCount() - 1; }

    /** Wählt die Quelle des Windstaus: 0 ohne Wind, 1 gemessen (wenn Pegel da sind), zuletzt eigener Wert. */
    public void quelle(int i) { quelle.setSelectedIndex(Math.max(0, Math.min(quelle.getItemCount() - 1, i))); }

    public void eigenerStau(double m) {
        quelle(quelle.getItemCount() - 1);
        eigen.setValue((int) Math.round(m / 0.05));
        aktualisieren();
    }

    /** Der Windstau, der gilt (m). */
    public double stau() {
        if (eigenGewaehlt()) return eigen.getValue() * 0.05;
        if (gemessenGewaehlt()) {
            Pegelonline.Stau s = messung.gemessen();
            return s == null ? 0 : s.meter();
        }
        return 0;
    }

    public Instant zeit() { return zeit; }

    /** Zeigt diese Zeit (auf 10 min gerundet). */
    public void zeige(Instant t) {
        ZonedDateTime z = t.atZone(zone);
        int k = (int) Math.min(143, Math.round((z.getHour() * 60 + z.getMinute()) / 10.0));
        zeit = z.toLocalDate().atStartOfDay(zone).plusMinutes(10L * k).toInstant();
        still = true;
        uhrzeit.setValue(k);
        still = false;
        aktualisieren();
    }

    /** Lädt die Messung im Hintergrund. */
    public void laden() {
        stand.setText("PEGELONLINE: lädt …");
        Thread th = new Thread(() -> {
            messung.holen();
            SwingUtilities.invokeLater(this::aktualisieren);
        }, "PEGELONLINE");
        th.setDaemon(true);
        th.start();
    }

    /** Lädt die Messung in diesem Faden (für Prüfungen). */
    public void ladenJetzt() {
        messung.holen();
        aktualisieren();
    }

    void aktualisieren() {
        double s = stau();
        if (!eigenGewaehlt() && !still) {
            still = true;
            eigen.setValue((int) Math.round(Math.max(STAU_MIN, Math.min(STAU_MAX, s / 0.05))));
            still = false;
        }
        uhrText.setText(TAG.format(zeit.atZone(zone)) + " " + UHR.format(zeit.atZone(zone)));
        if (gemessenGewaehlt()) {
            Pegelonline.Stau g = messung.gemessen();
            stand.setText(g == null ? "PEGELONLINE nicht erreicht – Windstau 0" : "Windstau " + Landunteruhr.zahl(g.meter(), 2, true) + " m, Messung bis " + UHR.format(g.stand().atZone(zone)));
            stand.setToolTipText(messung.fehler());
        } else stand.setText(eigenGewaehlt() ? "Windstau " + Landunteruhr.zahl(s, 2, true) + " m" : " ");
        repaint();
    }

    /** Die Fenster der sieben Tage ab dem gewählten Tag. */
    public List<Watt.Fenster> fensterWoche() {
        LocalDate d = zeit.atZone(zone).toLocalDate();
        return watt.fenster(d.atStartOfDay(zone).toInstant(), d.plusDays(TAGE).atStartOfDay(zone).toInstant(), stau());
    }

    /** Zu Fuß hin oder zurück (min). */
    public long gehMinuten() { return Math.round(tafel.wegLaenge() / 1000 / gehen * 60); }

    static String dauer(Duration d) {
        long m = Math.max(0, d.toMinutes());
        return m / 60 + " h " + String.format("%02d", m % 60) + " min";
    }

    // ------------------------------------------------------------------ Sichern

    public File sichern() {
        BufferedImage im;
        if (getWidth() > 200 && getHeight() > 200) {
            im = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            printAll(g);
            g.dispose();
        } else im = alsBild(1600, 1000);
        File f = new File(fotos, "Watt_Fenster_" + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".png");
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

    public BufferedImage alsBild(int w, int h) {
        // ohne Fenster läuft die Animation der Schieber nicht: ihre Kugel gleich an den Wert setzen
        schieberStellen(uhrzeit);
        schieberStellen(eigen);
        setSize(w, h);
        for (int i = 0; i < 3; i++) layout(this);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        printAll(g);
        g.dispose();
        return im;
    }

    /** Nur der Tag w × h (Kachel der Startseite). */
    public BufferedImage tagesbild(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        tag.male(g, w, h, true);
        g.dispose();
        return im;
    }

    /** Setzt die gezeigte Stellung eines FSliders auf seinen Wert (sonst gleitet sie erst im Fenster dahin). */
    static void schieberStellen(FSlider s) {
        try {
            java.lang.reflect.Field f = FSlider.class.getDeclaredField("displayFrac");
            f.setAccessible(true);
            f.setDouble(s, (s.getValue() - s.getMinimum()) / (double) Math.max(1, s.getMaximum() - s.getMinimum()));
        } catch (ReflectiveOperationException | RuntimeException x) {
            // nur Schönheit des Bildes
        }
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }

    static void glatt(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    // ------------------------------------------------------------------ Karte

    final class Karte extends JComponent {
        Karte() { setPreferredSize(new Dimension(760, 480)); }

        /** Die Höhe, in der der Ausschnitt die Breite w ganz nutzt (px). */
        int wunschHoehe(int w) {
            double bo = ausschnitt[2] - ausschnitt[0], bn = ausschnitt[3] - ausschnitt[1];
            return (int) Math.ceil(34 + 10 + 24 + (w - 20) * bn / bo) + 2;
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            double s = stau(), stand0 = watt.kartenStand(zeit, s);
            boolean trocken = watt.trocken(zeit, s);
            // Maßstab: der Ausschnitt passt hinein
            double bo = ausschnitt[2] - ausschnitt[0], bn = ausschnitt[3] - ausschnitt[1];
            int oben = 34, rand = 10;
            double mpp = Math.max(bo / (w - 2 * rand), bn / (h - oben - rand - 24));
            int bw = (int) (bo / mpp), bh = (int) (bn / mpp), x0 = (w - bw) / 2, y0 = oben;
            // Land und Wasser in Zellen des Rasters, dann skaliert
            int i0 = (int) Math.floor((ausschnitt[0] - tafel.karte[0]) / tafel.zelle), i1 = (int) Math.ceil((ausschnitt[2] - tafel.karte[0]) / tafel.zelle);
            int j0 = (int) Math.floor((ausschnitt[1] - tafel.karte[1]) / tafel.zelle), j1 = (int) Math.ceil((ausschnitt[3] - tafel.karte[1]) / tafel.zelle);
            i0 = Math.max(0, i0);
            j0 = Math.max(0, j0);
            i1 = Math.min(tafel.nx, i1);
            j1 = Math.min(tafel.ny, j1);
            int cw = i1 - i0, ch = j1 - j0;
            BufferedImage b = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            for (int jj = 0; jj < ch; jj++) {
                int j = j1 - 1 - jj, jl = tafel.ny - 1 - j;
                for (int ii = 0; ii < cw; ii++) {
                    int i = i0 + ii;
                    int rgb = land.getRGB(Math.min(land.getWidth() - 1, (int) ((i + 0.5) * land.getWidth() / tafel.nx)),
                            Math.min(land.getHeight() - 1, (int) ((jl + 0.5) * land.getHeight() / tafel.ny)));
                    float hh = hoehen[i + tafel.nx * j];
                    double d = Float.isNaN(hh) ? 5 : stand0 - hh;
                    if (d > 0.02) {
                        double a = 0.35 + 0.6 * (1 - Math.exp(-d / 0.5)), t2 = 1 - Math.exp(-d / 2.5);
                        int wr = (int) (80 - 50 * t2), wg = (int) (150 - 80 * t2), wb = (int) (200 - 60 * t2);
                        int r = (int) ((rgb >> 16 & 255) * (1 - a) + wr * a), gg = (int) ((rgb >> 8 & 255) * (1 - a) + wg * a), bb = (int) ((rgb & 255) * (1 - a) + wb * a);
                        rgb = r << 16 | gg << 8 | bb;
                    }
                    b.setRGB(ii, jj, rgb);
                }
            }
            g.drawImage(b, x0, y0, bw, bh, null);
            g.setColor(th.linie);
            g.drawRect(x0, y0, bw, bh);
            java.util.function.DoubleUnaryOperator px = o -> x0 + (o - ausschnitt[0]) / mpp, py = n -> y0 + (ausschnitt[3] - n) / mpp;
            // der Weg als Band: erst der Schatten im Ganzen, dann je Lauf gleicher Lage (trocken, nass) ein Zug
            List<Watttafel.Punkt> l = tafel.weg;
            Path2D.Double ganz = new Path2D.Double();
            for (int i = 0; i < l.size(); i++) {
                double x = px.applyAsDouble(l.get(i).ost()), y = py.applyAsDouble(l.get(i).nord());
                if (i == 0) ganz.moveTo(x, y);
                else ganz.lineTo(x, y);
            }
            g.setStroke(new BasicStroke(10f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Thema.mitAlpha(Color.BLACK, 120));
            g.draw(ganz);
            g.setStroke(new BasicStroke(6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            // Abschnitt i verbindet Punkt i − 1 und i; nass, wenn seine tiefere Ecke unter dem Wasser liegt
            int a0 = 1;
            while (a0 < l.size()) {
                boolean nass = Math.min(l.get(a0 - 1).hoehe(), l.get(a0).hoehe()) < stand0;
                int b0 = a0;
                while (b0 + 1 < l.size() && (Math.min(l.get(b0).hoehe(), l.get(b0 + 1).hoehe()) < stand0) == nass) b0++;
                Path2D.Double lauf = new Path2D.Double();
                for (int k2 = a0 - 1; k2 <= b0; k2++) {
                    double x = px.applyAsDouble(l.get(k2).ost()), y = py.applyAsDouble(l.get(k2).nord());
                    if (k2 == a0 - 1) lauf.moveTo(x, y);
                    else lauf.lineTo(x, y);
                }
                g.setColor(nass ? NASS : SAND);
                g.draw(lauf);
                a0 = b0 + 1;
            }
            g.setStroke(new BasicStroke(1.5f));
            marke(g, px.applyAsDouble(tafel.sattelOst), py.applyAsDouble(tafel.sattelNord), "Sattel " + Landunteruhr.zahl(tafel.sattel, 2, true) + " m", trocken ? GRUEN : ROT, th);
            marke(g, px.applyAsDouble(tafel.startOst), py.applyAsDouble(tafel.startNord), tafel.start, Color.WHITE, th);
            marke(g, px.applyAsDouble(tafel.zielOst), py.applyAsDouble(tafel.zielNord), tafel.ziel, Color.WHITE, th);
            // Überschrift und Lage
            g.setFont(th.titel(16f));
            g.setColor(th.text);
            g.drawString("Weg " + tafel.start + " – " + tafel.ziel + ", " + String.format(Locale.GERMAN, "%.1f km", tafel.wegLaenge() / 1000) + " (zu Fuß rund " + gehMinuten() + " min)", 10, 22);
            String z = trocken ? "trocken" : "nass";
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            FontMetrics fm = g.getFontMetrics();
            String zl = LANG.format(zeit.atZone(zone)) + ", " + UHR.format(zeit.atZone(zone)) + " Uhr: Weg " + z;
            g.setColor(Thema.mitAlpha(th.tief, 215));
            g.fillRoundRect(x0 + 8, y0 + 8, fm.stringWidth(zl) + 20, fm.getHeight() + 8, 10, 10);
            g.setColor(trocken ? GRUEN : NASS);
            g.drawString(zl, x0 + 18, y0 + 12 + fm.getAscent());
            // Maßstab 1 km
            int ml = (int) (1000 / mpp);
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(2f));
            g.drawLine(x0 + 12, y0 + bh - 14, x0 + 12 + ml, y0 + bh - 14);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("1 km", x0 + 12 + ml / 2 - 12, y0 + bh - 20);
            g.dispose();
        }

        void marke(Graphics2D g, double x, double y, String text, Color c, Thema th) {
            g.setColor(Thema.mitAlpha(Color.BLACK, 150));
            g.fill(new Ellipse2D.Double(x - 7, y - 7, 14, 14));
            g.setColor(c);
            g.setStroke(new BasicStroke(2.5f));
            g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(Thema.mitAlpha(th.tief, 200));
            g.fillRoundRect((int) x + 9, (int) y - 9, fm.stringWidth(text) + 10, 18, 8, 8);
            g.setColor(c);
            g.drawString(text, (int) x + 14, (int) y + 4);
        }
    }

    // ------------------------------------------------------------------ Tag

    final class Tageslinie extends JComponent {
        Tageslinie() { setPreferredSize(new Dimension(640, 290)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight(), false);
            g.dispose();
        }

        void male(Graphics2D g, int w, int h, boolean kachel) {
            Thema th = Thema.aktuell();
            glatt(g);
            g.setColor(kachel ? th.tief : th.grund);
            g.fillRect(0, 0, w, h);
            double s = stau();
            LocalDate d = zeit.atZone(zone).toLocalDate();
            Instant a = d.atStartOfDay(zone).toInstant(), b = d.plusDays(1).atStartOfDay(zone).toInstant();
            int px0 = kachel ? 30 : 46, px1 = w - 12, py0 = kachel ? 28 : 40, py1 = h - (kachel ? 22 : 30);
            double ylo = -2.5, yhi = 2.5, dauer = b.getEpochSecond() - a.getEpochSecond();
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (Math.max(ylo, Math.min(yhi, z)) - ylo) / (yhi - ylo) * (py1 - py0);
            java.util.function.DoubleUnaryOperator xv = sec -> px0 + sec / dauer * (px1 - px0);
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            // Nacht
            for (int k = 0; k < 144; k++) {
                Instant ti = a.plusSeconds(600L * k + 300);
                if (watt.sonne(ti) > Watt.TAG) continue;
                g.setColor(Thema.mitAlpha(Color.BLACK, 70));
                g.fill(new Rectangle2D.Double(xv.applyAsDouble(600.0 * k), py0, (px1 - px0) / 144.0 + 0.5, py1 - py0));
            }
            // trockene Zeit
            for (Watt.Fenster f : watt.fenster(a, b, s)) {
                double xa = xv.applyAsDouble(Math.max(0, f.ab().getEpochSecond() - a.getEpochSecond())), xb = xv.applyAsDouble(Math.min(dauer, f.bis().getEpochSecond() - a.getEpochSecond()));
                g.setColor(Thema.mitAlpha(GRUEN, 70));
                g.fill(new Rectangle2D.Double(xa, py0, xb - xa, py1 - py0));
                g.setColor(GRUEN);
                g.fill(new Rectangle2D.Double(xa, py1 - 6, xb - xa, 6));
                if (!kachel) {
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                    String l = UHR.format(f.ab().atZone(zone)) + "–" + UHR.format(f.bis().atZone(zone));
                    FontMetrics fm = g.getFontMetrics();
                    if (xb - xa > fm.stringWidth(l) + 4) g.drawString(l, (int) ((xa + xb - fm.stringWidth(l)) / 2), py1 - 10);
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, kachel ? 9 : 11));
            FontMetrics fm = g.getFontMetrics();
            for (int z = -2; z <= 2; z++) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, 110));
                g.drawLine(px0, y, px1, y);
                g.setColor(th.leise);
                String l = z == 0 ? "0" : Landunteruhr.zahl(z, 0, true);
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            for (int st = 0; st <= 24; st += kachel ? 6 : 3) {
                int x = (int) xv.applyAsDouble(st * 3600.0);
                g.setColor(Thema.mitAlpha(th.linie, 90));
                g.drawLine(x, py0, x, py1);
                g.setColor(th.leise);
                String l = String.format("%02d", st % 24);
                g.drawString(l, Math.max(px0, Math.min(px1 - fm.stringWidth(l), x - fm.stringWidth(l) / 2)), py1 + (kachel ? 12 : 15));
            }
            // Schwellen
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
            g.setColor(Thema.mitAlpha(GRUEN, 200));
            g.draw(new Line2D.Double(px0, yv.applyAsDouble(watt.ab), px1, yv.applyAsDouble(watt.ab)));
            g.setColor(Thema.mitAlpha(NASS, 220));
            g.draw(new Line2D.Double(px0, yv.applyAsDouble(watt.bis), px1, yv.applyAsDouble(watt.bis)));
            // Gezeit
            Path2D.Double p = new Path2D.Double();
            for (int k = 0; k <= 288; k++) {
                Instant ti = a.plusSeconds(300L * k);
                double x = xv.applyAsDouble(300.0 * k), y = yv.applyAsDouble(watt.wert(ti, s));
                if (k == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            g.setStroke(new BasicStroke(kachel ? 1.8f : 2.2f));
            g.setColor(th.kurve);
            g.draw(p);
            // gewählte Zeit, jetzt
            Instant jt = jetzt.get();
            if (!jt.isBefore(a) && jt.isBefore(b)) {
                int xj = (int) xv.applyAsDouble(jt.getEpochSecond() - a.getEpochSecond());
                g.setStroke(new BasicStroke(1f));
                g.setColor(th.leise);
                g.drawLine(xj, py0, xj, py1);
            }
            if (!kachel) {
                int xz = (int) xv.applyAsDouble(zeit.getEpochSecond() - a.getEpochSecond());
                g.setStroke(new BasicStroke(2f));
                g.setColor(th.marke);
                g.drawLine(xz, py0, xz, py1);
            }
            g.setStroke(new BasicStroke(1f));
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(kachel ? th.titel(13f) : th.titel(15f));
            g.setColor(th.text);
            g.drawString(kachel ? "Watt-Fenster " + TAG.format(d) : LANG.format(d) + ": Gezeit am Anleger" + (Math.abs(s) > 0.004 ? " mit " + Landunteruhr.zahl(s, 2, true) + " m Windstau" : ""),
                    kachel ? 8 : 10, kachel ? 18 : 22);
            if (!kachel) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
                g.setColor(th.leise);
                String l = "grün: Weg trocken · gestrichelt: trocken ab " + Landunteruhr.zahl(watt.ab, 2, true) + " m, nass ab " + Landunteruhr.zahl(watt.bis, 2, true) + " m · dunkel: Nacht";
                g.drawString(l, px0, h - 4);
            }
        }
    }

    // ------------------------------------------------------------------ Liste

    final class Liste extends JComponent {
        Liste() { setPreferredSize(new Dimension(640, 350)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            List<Watt.Fenster> l = fensterWoche();
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            g.drawString("Trocken in den nächsten " + TAGE + " Tagen" + (Math.abs(stau()) > 0.004 ? " (Windstau " + Landunteruhr.zahl(stau(), 2, true) + " m)" : " (ohne Wind)"), 10, 20);
            long hinZurueck = 2 * gehMinuten();
            int[] sx = {14, 110, 230, 340, 470};
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(th.leise);
            String[] kopf = {"Tag", "trocken", "Dauer", "Licht", "hin und zurück (" + hinZurueck / 60 + " h " + hinZurueck % 60 + " min)"};
            for (int i = 0; i < kopf.length; i++) g.drawString(kopf[i], sx[i], 42);
            int n = Math.max(1, l.size()), zh = (int) Math.max(14, Math.min(20, (h - 50) / (double) n)), y = 46;
            Font f1 = new Font(Font.SANS_SERIF, Font.PLAIN, zh < 17 ? 11 : 12), fb = f1.deriveFont(Font.BOLD);
            int i = 0;
            if (l.isEmpty()) {
                g.setFont(f1);
                g.drawString("Kein trockenes Fenster – die Gezeit fällt nicht unter " + Landunteruhr.zahl(watt.ab - stau(), 2, true) + " m.", sx[0], 70);
            }
            for (Watt.Fenster f : l) {
                if (y + zh > h - 4) break;
                boolean gewaehlt = f.enthaelt(zeit);
                if (gewaehlt || i % 2 == 1) {
                    g.setColor(gewaehlt ? Thema.mitAlpha(th.marke, 60) : Thema.mitAlpha(th.flaeche, 160));
                    g.fillRect(8, y + 2, w - 16, zh);
                }
                i++;
                int by = y + zh - 4;
                g.setFont(fb);
                g.setColor(th.text);
                g.drawString(TAG.format(f.ab().atZone(zone)), sx[0], by);
                g.setFont(f1);
                g.drawString(UHR.format(f.ab().atZone(zone)) + " – " + UHR.format(f.bis().atZone(zone)), sx[1], by);
                g.drawString(dauer(f.dauer()), sx[2], by);
                String licht = f.ganzBeiTag() ? "bei Tag" : f.ganzBeiNacht() ? "bei Nacht" : "teils (" + f.tagMinuten() / 60 + " h " + f.tagMinuten() % 60 + " min Tag)";
                g.setColor(f.ganzBeiNacht() ? th.leise : th.text);
                g.drawString(licht, sx[3], by);
                long rest = f.dauer().toMinutes() - hinZurueck;
                g.setColor(rest >= 30 ? GRUEN : rest >= 0 ? new Color(240, 170, 60) : ROT);
                g.drawString(rest >= 0 ? rest / 60 + " h " + rest % 60 + " min Zeit dort" : "zu kurz", sx[4], by);
                y += zh;
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ Profil

    final class Profil extends JComponent {
        Profil() { setPreferredSize(new Dimension(760, 170)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            double s = stau(), stand0 = watt.kartenStand(zeit, s), L = tafel.wegLaenge();
            int px0 = 56, px1 = w - 16, py0 = 30, py1 = h - 22;
            double ylo = -1.0, yhi = 3.0;
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (Math.max(ylo, Math.min(yhi, z)) - ylo) / (yhi - ylo) * (py1 - py0);
            java.util.function.DoubleUnaryOperator xv = m -> px0 + m / L * (px1 - px0);
            g.setFont(th.titel(14f));
            g.setColor(th.text);
            g.drawString("Profil des Wegs (m NHN) und Wasser um " + UHR.format(zeit.atZone(zone)) + " Uhr: " + Landunteruhr.zahl(stand0, 2, true) + " m", 10, 20);
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            // Wasser
            double yw = yv.applyAsDouble(stand0);
            g.setColor(Thema.mitAlpha(NASS, 150));
            g.fill(new Rectangle2D.Double(px0, yw, px1 - px0, py1 - yw));
            // Boden
            Path2D.Double p = new Path2D.Double();
            p.moveTo(px0, py1);
            for (Watttafel.Punkt q : tafel.weg) p.lineTo(xv.applyAsDouble(q.strecke()), yv.applyAsDouble(q.hoehe()));
            p.lineTo(px1, py1);
            p.closePath();
            g.setColor(SAND.darker());
            g.fill(p);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g.getFontMetrics();
            for (int z = -1; z <= 3; z++) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, 110));
                g.drawLine(px0, y, px1, y);
                g.setColor(th.leise);
                String l = z == 0 ? "0" : Landunteruhr.zahl(z, 0, true);
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            for (int km = 0; km <= L / 1000; km++) {
                int x = (int) xv.applyAsDouble(km * 1000.0);
                g.setColor(th.leise);
                g.drawString(km + " km", x - 8, py1 + 14);
            }
            // Sattel
            double xs = 0;
            for (Watttafel.Punkt q : tafel.weg) if (Math.abs(q.hoehe() - tafel.sattel) < 1e-6) xs = q.strecke();
            int x = (int) xv.applyAsDouble(xs), ys = (int) yv.applyAsDouble(tafel.sattel);
            g.setColor(Color.WHITE);
            g.fill(new Ellipse2D.Double(x - 4, ys - 4, 8, 8));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.drawString("Sattel " + Landunteruhr.zahl(tafel.sattel, 2, true) + " m", x + 6, ys - 6);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.text);
            g.drawString(tafel.start, px0 + 4, py0 + 14);
            g.drawString(tafel.ziel, px1 - 4 - g.getFontMetrics().stringWidth(tafel.ziel), py0 + 14);
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.dispose();
        }
    }
}
