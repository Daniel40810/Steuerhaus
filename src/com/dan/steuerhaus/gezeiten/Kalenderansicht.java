package com.dan.steuerhaus.gezeiten;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Inhalt des Tidenkalenders (seit Z1, Werkbuch HalligHooge 155): links der gewählte Tag (Kurve von 0 bis 24 Uhr
 * Ortszeit mit Hoch- und Niedrigwasser, jetzt, Mondphase, Spring- oder Nipptide, Marken des Modells, wo es einen Lauf
 * gibt), rechts vier Wochen als Liste, darunter der Ausblick über acht Wochen (Hoch- und Niedrigwasser jeder Tide, Mond,
 * Spring und Nipp). Tasten: ← → (Tag), ↑ ↓ (Woche), Pos1 (heute), Esc (schließt, siehe {@link Kalenderfenster}).
 * <p>
 * Seit DB5 (Werkbuch HalligHooge 176, F282 A): unter dem Hinweis eine Zeile, aus wie vielen Tagen Messung die Gezeit stammt
 * und wie gut sie zuletzt war ({@code herkunft} in der Tafel), und der Knopf „Herkunft“: statt Tag und Liste der Verlauf der
 * angenommenen Stände und der Zeitplan der Tiden, die die Messung mit der Zeit trennt (H).
 */
public final class Kalenderansicht extends JPanel {
    static final int LISTE_WOCHEN = 4, AUSBLICK_WOCHEN = 8;
    static final Color HOCH = new Color(255, 196, 80), NIEDRIG = new Color(110, 175, 230);
    static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN);

    public final Gezeitentafel tafel;
    final Vorhersage v;
    final ZoneId zone;
    final File fotos;
    final String hinweis, markeName;
    final double marke;
    /** Die Uhr (für Prüfungen einstellbar). */
    public Supplier<Instant> jetzt = Instant::now;
    public Consumer<File> gesichert = f -> { };

    final Tagesbild tagesbild = new Tagesbild();
    final Liste liste = new Liste();
    final Ausblick ausblick = new Ausblick();
    final Herkunftsbild herkunftsbild = new Herkunftsbild();
    final FButton herkunft = new FButton("Herkunft");
    final java.awt.CardLayout karten = new java.awt.CardLayout();
    private boolean zeigtHerkunft;
    final FButton wocheZurueck = new FButton("« Woche"), tagZurueck = new FButton("« Tag"), heute = new FButton("Heute"), tagVor = new FButton("Tag »"),
            wocheVor = new FButton("Woche »"), csv = new FButton("Als Liste sichern"), sichern = new FButton("Bild sichern");
    final JLabel stand = new JLabel(" ");

    private LocalDate tag, bereich;
    private List<Vorhersage.Tag> tage = List.of();
    private List<Vorhersage.Gang> gaenge = List.of();
    private List<Mond.Ereignis> phasen = List.of();
    private double ymin = -2.5, ymax = 2.5;

    public Kalenderansicht(Gezeitentafel tafel, double marke, String markeName, String hinweis, ZoneId zone, File fotos) {
        super(new BorderLayout());
        this.tafel = tafel;
        this.v = tafel.vorhersage();
        this.marke = marke;
        this.markeName = markeName == null ? "" : markeName;
        this.hinweis = hinweis == null ? "" : hinweis;
        this.zone = zone;
        this.fotos = fotos;
        for (FButton b : new FButton[] {wocheZurueck, tagZurueck, heute, tagVor, wocheVor, csv, sichern, herkunft}) b.setFocusable(false);
        for (FButton b : new FButton[] {wocheZurueck, tagZurueck, tagVor, wocheVor, csv, sichern, herkunft}) b.setVariant(FButtonVariant.GHOST);
        herkunft.addActionListener(e -> zeigeHerkunft(!zeigtHerkunft));
        herkunft.setToolTipText("Woher die Gezeit stammt: Tage der Messung, Prüfung, Verlauf und welche Tiden die Messung mit der Zeit trennt (H)");
        wocheZurueck.addActionListener(e -> zeige(tag.minusWeeks(1)));
        tagZurueck.addActionListener(e -> zeige(tag.minusDays(1)));
        heute.addActionListener(e -> zeige(heute()));
        tagVor.addActionListener(e -> zeige(tag.plusDays(1)));
        wocheVor.addActionListener(e -> zeige(tag.plusWeeks(1)));
        csv.addActionListener(e -> alsListe());
        sichern.addActionListener(e -> sichern());
        csv.setToolTipText("Hoch- und Niedrigwasser der acht Wochen als CSV in den Ordner der Fotos");

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.setOpaque(false);
        for (JComponent c : new JComponent[] {wocheZurueck, tagZurueck, heute, tagVor, wocheVor, csv, sichern, herkunft}) knoepfe.add(c);
        Faerber.rolle(stand, "leise");
        stand.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JPanel leiste = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        leiste.add(knoepfe, BorderLayout.WEST);
        JPanel rechts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        rechts.setOpaque(false);
        rechts.add(stand);
        leiste.add(rechts, BorderLayout.CENTER);

        JPanel kalender = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        kalender.add(tagesbild, BorderLayout.CENTER);
        kalender.add(liste, BorderLayout.EAST);
        JPanel oben = Faerber.rolle(new JPanel(karten), "grund");
        oben.add(kalender, "kalender");
        oben.add(herkunftsbild, "herkunft");
        JPanel unten = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        unten.add(ausblick, BorderLayout.CENTER);
        JPanel fuss = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        fuss.add(leiste, BorderLayout.NORTH);
        if (!this.hinweis.isEmpty() || tafel.kopf.containsKey("herkunft")) fuss.add(new Fusszeile(), BorderLayout.CENTER);
        unten.add(fuss, BorderLayout.SOUTH);
        Faerber.rolle(this, "grund");
        add(oben, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        tasten();
        zeige(heute());
    }

    private void tasten() {
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('h'), "herkunft");
        getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke('H'), "herkunft");
        getActionMap().put("herkunft", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { zeigeHerkunft(!zeigtHerkunft); }
        });
        Object[][] t = {{"LEFT", (Runnable) () -> zeige(tag.minusDays(1))}, {"RIGHT", (Runnable) () -> zeige(tag.plusDays(1))},
                {"UP", (Runnable) () -> zeige(tag.minusWeeks(1))}, {"DOWN", (Runnable) () -> zeige(tag.plusWeeks(1))}, {"HOME", (Runnable) () -> zeige(heute())}};
        for (Object[] x : t) {
            String n = "kalender." + x[0];
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke((String) x[0]), n);
            Runnable r = (Runnable) x[1];
            getActionMap().put(n, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }
    }

    // ------------------------------------------------------------------ Steuern

    public LocalDate heute() { return jetzt.get().atZone(zone).toLocalDate(); }

    /** Seit DB5: statt Tag und Liste die Herkunft der Gezeit (oder zurück). */
    public void zeigeHerkunft(boolean an) {
        zeigtHerkunft = an;
        Container oben = herkunftsbild.getParent();
        if (oben != null) karten.show(oben, an ? "herkunft" : "kalender");
        herkunft.setText(an ? "Kalender" : "Herkunft");
        herkunftsbild.repaint();
    }

    public boolean zeigtHerkunft() { return zeigtHerkunft; }

    public LocalDate tag() { return tag; }

    /** Den Tag zeigen; die Liste beginnt am Montag seiner Woche, der Ausblick ebenso. */
    public void zeige(LocalDate d) {
        tag = d;
        LocalDate b = d.with(DayOfWeek.MONDAY);
        if (!b.equals(bereich)) {
            bereich = b;
            rechne();
        }
        standText();
        repaint();
    }

    /** Rechnet die acht Wochen ab dem Montag des Bereichs: Tage, Spring und Nipp, Mondphasen, Höhenbereich. */
    private void rechne() {
        tage = v.tage(bereich, 7 * AUSBLICK_WOCHEN, zone);
        Instant a = bereich.atStartOfDay(zone).toInstant(), b = bereich.plusWeeks(AUSBLICK_WOCHEN).atStartOfDay(zone).toInstant();
        gaenge = v.gaenge(a, b);
        phasen = Mond.phasen(a, b);
        double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
        for (Vorhersage.Tag t : tage) for (Vorhersage.Extrem e : t.extreme()) {
            lo = Math.min(lo, e.hoehe());
            hi = Math.max(hi, e.hoehe());
        }
        if (!Double.isNaN(marke)) hi = Math.max(hi, marke);
        if (hi > lo) {
            double pad = 0.12 * (hi - lo);
            ymin = Math.floor((lo - pad) * 2) / 2;
            ymax = Math.ceil((hi + pad) * 2) / 2;
        }
    }

    public List<Vorhersage.Tag> tage() { return tage; }

    public List<Vorhersage.Gang> gaenge() { return gaenge; }

    public List<Mond.Ereignis> phasen() { return phasen; }

    Vorhersage.Tag tagDaten(LocalDate d) {
        for (Vorhersage.Tag t : tage) if (t.datum().equals(d)) return t;
        return null;
    }

    private void standText() {
        Vorhersage.Tag t = tagDaten(tag);
        if (t == null) {
            stand.setText(" ");
            return;
        }
        stand.setText(String.format(Locale.GERMAN, "%s · %d Hoch- und Niedrigwasser · Tidenhub %s m", lang(tag), t.extreme().size(), zahl(t.hub(), 2, false)));
    }

    static String lang(LocalDate d) { return DateTimeFormatter.ofPattern("EEEE, d. MMMM yyyy", Locale.GERMAN).format(d); }

    static String zahl(double v, int stellen, boolean vorzeichen) {
        if (Double.isNaN(v)) return "–";
        return String.format(Locale.GERMAN, "%" + (vorzeichen ? "+" : "") + "." + stellen + "f", v).replace('-', '−');
    }

    String uhr(Instant t) { return UHR.format(t.atZone(zone)); }

    /** Die Zeitzone als Kürzel (MEZ oder MESZ, sonst die Abkürzung der Zone). */
    String zoneKurz(Instant t) {
        boolean sommer = zone.getRules().isDaylightSavings(t);
        if (zone.getId().equals("Europe/Berlin")) return sommer ? "MESZ" : "MEZ";
        return DateTimeFormatter.ofPattern("zzz", Locale.GERMAN).format(t.atZone(zone));
    }

    /** Spring- oder Nipptide an diesem Tag (Ortszeit) oder null. */
    Vorhersage.Gang gang(LocalDate d) {
        for (Vorhersage.Gang g : gaenge) if (g.zeit().atZone(zone).toLocalDate().equals(d)) return g;
        return null;
    }

    Mond.Ereignis phase(LocalDate d) {
        for (Mond.Ereignis p : phasen) if (p.zeit().atZone(zone).toLocalDate().equals(d)) return p;
        return null;
    }

    // ------------------------------------------------------------------ Sichern

    /** Hoch- und Niedrigwasser der acht Wochen als CSV (Semikolon, Ortszeit und UTC). */
    public File alsListe() {
        StringBuilder s = new StringBuilder("# " + tafel.ort + " – astronomische Gezeit ohne Wind; nicht zur Navigation\n");
        s.append("Datum;Uhrzeit;Zone;Art;Hoehe_m_NHN;UTC\n");
        for (Vorhersage.Tag t : tage)
            for (Vorhersage.Extrem e : t.extreme()) {
                ZonedDateTime z = e.zeit().atZone(zone);
                s.append(DateTimeFormatter.ISO_LOCAL_DATE.format(z)).append(';').append(UHR.format(z)).append(';').append(zoneKurz(e.zeit())).append(';')
                        .append(e.art() == Vorhersage.Art.HOCHWASSER ? "Hochwasser" : "Niedrigwasser").append(';')
                        .append(String.format(Locale.GERMAN, "%.2f", e.hoehe())).append(';').append(e.zeit()).append('\n');
            }
        File f = new File(fotos, "Tidenkalender_" + bereich + "_" + AUSBLICK_WOCHEN + "_Wochen.csv");
        try {
            f.getParentFile().mkdirs();
            Files.writeString(f.toPath(), s.toString(), StandardCharsets.UTF_8);
            stand.setText("Gesichert: " + f.getName());
            gesichert.accept(f);
            return f;
        } catch (IOException x) {
            stand.setText("Nicht gesichert: " + x.getMessage());
            return null;
        }
    }

    /** Das Fenster jetzt als PNG in den Ordner der Fotos. */
    public File sichern() {
        int w = getWidth() > 200 ? getWidth() : 1600, h = getHeight() > 200 ? getHeight() : 1000;
        BufferedImage im = getWidth() > 200 ? null : alsBild(w, h);
        if (im == null) {
            im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = im.createGraphics();
            printAll(g);
            g.dispose();
        }
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File f = new File(fotos, "Tidenkalender_" + tag + "_" + stempel + ".png");
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

    /** Der ganze Inhalt als Bild (ohne Fenster; für Prüfungen und die Kachel der Startseite). */
    public BufferedImage alsBild(int w, int h) {
        setSize(w, h);
        for (int i = 0; i < 3; i++) layout(this);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        printAll(g);
        g.dispose();
        return im;
    }

    /** Nur das Tagesbild w × h (Kachel der Startseite). */
    public BufferedImage tagesbild(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        tagesbild.male(g, w, h, true);
        g.dispose();
        return im;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }

    /** Der Hinweis unter den Knöpfen, umbrochen auf die Breite; seit DB5 davor die Herkunft der Gezeit. */
    final class Fusszeile extends JComponent {
        Fusszeile() { setPreferredSize(new Dimension(800, tafel.kopf.containsKey("herkunft") ? 52 : 36)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(th.grund);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.leise);
            FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent() + 2;
            String hk = tafel.kopf.get("herkunft");
            if (hk != null) {
                g.setColor(th.text);
                String z0 = "Gezeit " + hk;
                while (z0.length() > 20 && fm.stringWidth(z0) > getWidth() - 28) z0 = z0.substring(0, z0.length() - 2);
                g.drawString(z0, 14, y);
                y += fm.getHeight() + 2;
                g.setColor(th.leise);
            }
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

    // ------------------------------------------------------------------ Mond

    /** Malt den Mond in der Phase der Elongation e (Grad) als Scheibe mit Durchmesser d. */
    static void mond(Graphics2D g, double x, double y, double d, double e) {
        double r = d / 2;
        g.setColor(new Color(40, 46, 56));
        g.fill(new Ellipse2D.Double(x - r, y - r, d, d));
        double c = Math.cos(Math.toRadians(e));
        boolean zunehmend = e < 180;
        Area hell = new Area(new Rectangle2D.Double(zunehmend ? x : x - r, y - r, r, d));
        hell.intersect(new Area(new Ellipse2D.Double(x - r, y - r, d, d)));
        Area ell = new Area(new Ellipse2D.Double(x - r * Math.abs(c), y - r, 2 * r * Math.abs(c), d));
        if (c > 0) hell.subtract(ell);
        else hell.add(ell);
        g.setColor(new Color(236, 232, 214));
        g.fill(hell);
        g.setColor(new Color(150, 156, 168));
        g.setStroke(new BasicStroke(0.8f));
        g.draw(new Ellipse2D.Double(x - r, y - r, d, d));
    }

    static double winkel(Mond.Phase p) { return p.winkel; }

    // ------------------------------------------------------------------ Tag

    final class Tagesbild extends JComponent {
        static final int LINKS = 58, RECHTS = 24, OBEN = 92, UNTEN = 34;

        Tagesbild() {
            setPreferredSize(new Dimension(900, 620));
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight(), false);
            g.dispose();
        }

        void male(Graphics2D g, int w, int h, boolean kachel) {
            Thema th = Thema.aktuell();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(kachel ? th.tief : th.grund);
            g.fillRect(0, 0, w, h);
            if (tag == null) return;
            int oben = kachel ? 40 : OBEN, links = kachel ? 30 : LINKS, rechts = kachel ? 12 : RECHTS, unten = kachel ? 22 : UNTEN;
            ZonedDateTime a = tag.atStartOfDay(zone);
            Instant t0 = a.toInstant(), t1 = tag.plusDays(1).atStartOfDay(zone).toInstant();
            double dauer = t1.getEpochSecond() - t0.getEpochSecond();
            int px0 = links, px1 = w - rechts, py0 = oben, py1 = h - unten;
            java.util.function.DoubleUnaryOperator xv = s -> px0 + s / dauer * (px1 - px0);
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (z - ymin) / (ymax - ymin) * (py1 - py0);
            // Kopf
            Vorhersage.Tag td = tagDaten(tag);
            if (!kachel) {
                g.setFont(th.titel(22f));
                g.setColor(th.text);
                g.drawString(lang(tag), px0, 34);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
                g.setColor(th.leise);
                String unter = tafel.ort + " · astronomische Gezeit in " + zoneKurz(t0) + " · Tidenhub " + (td == null ? "–" : zahl(td.hub(), 2, false) + " m");
                g.drawString(unter, px0, 56);
                // Mond, Spring/Nipp
                int bx = px0;
                Mond.Ereignis ph = phase(tag);
                Vorhersage.Gang gg = gang(tag);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                FontMetrics fm = g.getFontMetrics();
                if (gg != null) {
                    String s = (gg.spring() ? "Springtide" : "Nipptide") + " um " + uhr(gg.zeit());
                    int bw = fm.stringWidth(s) + 18;
                    g.setColor(gg.spring() ? Thema.mitAlpha(HOCH, 70) : Thema.mitAlpha(NIEDRIG, 70));
                    g.fill(new RoundRectangle2D.Double(bx, 64, bw, 20, 10, 10));
                    g.setColor(th.text);
                    g.drawString(s, bx + 9, 78);
                    bx += bw + 10;
                }
                if (ph != null) {
                    mond(g, bx + 10, 74, 16, winkel(ph.phase()));
                    g.setColor(th.text);
                    g.drawString(ph.phase().name + " um " + uhr(ph.zeit()), bx + 24, 78);
                } else {
                    mond(g, bx + 10, 74, 16, Mond.elongation(a.plusHours(12).toInstant()));
                    g.setColor(th.leise);
                    g.drawString(String.format(Locale.GERMAN, "Mond zu %.0f %% beleuchtet", 100 * Mond.beleuchtet(a.plusHours(12).toInstant())), bx + 24, 78);
                }
            } else {
                g.setFont(th.titel(15f));
                g.setColor(th.text);
                g.drawString("Tidenkalender " + tafel.ort, 10, 24);
            }
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            // Gitter: Höhen und Stunden
            for (double z = Math.ceil(ymin); z <= ymax; z += 1) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, z == 0 ? 255 : 140));
                g.drawLine(px0 + 1, y, px1 - 1, y);
                if (!kachel) {
                    g.setColor(th.leise);
                    String l = zahl(z, 0, true).replace("+0", "0").replace("−0", "0");
                    g.drawString(l, px0 - 8 - fm.stringWidth(l), y + 4);
                }
            }
            for (int st = 0; st <= 24; st += 3) {
                ZonedDateTime zt = a.plusHours(st);
                if (st == 24) zt = tag.plusDays(1).atStartOfDay(zone);
                int x = (int) xv.applyAsDouble(zt.toEpochSecond() - t0.getEpochSecond());
                g.setColor(Thema.mitAlpha(th.linie, 110));
                g.drawLine(x, py0 + 1, x, py1 - 1);
                if (!kachel) {
                    g.setColor(th.leise);
                    String l = String.format("%02d:00", st % 24 == 0 && st > 0 ? 24 : st);
                    g.drawString(l, x - fm.stringWidth(l) / 2, py1 + 16);
                }
            }
            if (!kachel) {
                g.setColor(th.leise);
                g.drawString("m NHN", 6, py0 - 8);
            }
            // Kurve
            Path2D.Double kurve = new Path2D.Double(), flaeche = new Path2D.Double();
            int n = 289;
            for (int i = 0; i < n; i++) {
                double s = i * dauer / (n - 1);
                double z = v.wert(t0.plusSeconds(Math.round(s)));
                double x = xv.applyAsDouble(s), y = yv.applyAsDouble(z);
                if (i == 0) {
                    kurve.moveTo(x, y);
                    flaeche.moveTo(x, py1);
                    flaeche.lineTo(x, y);
                } else {
                    kurve.lineTo(x, y);
                    flaeche.lineTo(x, y);
                }
            }
            flaeche.lineTo(px1, py1);
            flaeche.closePath();
            Shape alt = g.getClip();
            g.clipRect(px0 + 1, py0 + 1, px1 - px0 - 1, py1 - py0 - 1);
            g.setPaint(new GradientPaint(0, py0, new Color(70, 140, 190, 150), 0, py1, new Color(20, 50, 80, 120)));
            g.fill(flaeche);
            // Marke (Landunter)
            if (!Double.isNaN(marke)) {
                int y = (int) yv.applyAsDouble(marke);
                g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {6f, 4f}, 0f));
                g.setColor(th.marke);
                g.drawLine(px0 + 1, y, px1 - 1, y);
                if (!kachel) g.drawString(markeName + " " + zahl(marke, 2, true) + " m", px0 + 8, y - 5);
            }
            g.setStroke(new BasicStroke(kachel ? 2f : 2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(150, 210, 245));
            g.draw(kurve);
            g.setClip(alt);
            // jetzt
            Instant j = jetzt.get();
            if (!j.isBefore(t0) && j.isBefore(t1)) {
                int x = (int) xv.applyAsDouble(j.getEpochSecond() - t0.getEpochSecond());
                double z = v.wert(j);
                g.setStroke(new BasicStroke(1.4f));
                g.setColor(th.text);
                g.drawLine(x, py0, x, py1);
                g.fill(new Ellipse2D.Double(x - 5, yv.applyAsDouble(z) - 5, 10, 10));
                if (!kachel) {
                    String s = "jetzt " + uhr(j) + " · " + zahl(z, 2, true) + " m";
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                    int sw = g.getFontMetrics().stringWidth(s);
                    int bx = x + 8 + sw < px1 ? x + 8 : x - 8 - sw;
                    g.drawString(s, bx, py0 + 16);
                }
            }
            // Hoch- und Niedrigwasser
            if (td != null) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, kachel ? 11 : 14));
                FontMetrics fb = g.getFontMetrics();
                for (Vorhersage.Extrem e : td.extreme()) {
                    double x = xv.applyAsDouble(e.zeit().getEpochSecond() - t0.getEpochSecond()), y = yv.applyAsDouble(e.hoehe());
                    boolean hoch = e.art() == Vorhersage.Art.HOCHWASSER;
                    g.setColor(hoch ? HOCH : NIEDRIG);
                    g.fill(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
                    g.setColor(th.tief);
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new Ellipse2D.Double(x - 6, y - 6, 12, 12));
                    String s1 = uhr(e.zeit()), s2 = zahl(e.hoehe(), 2, true) + " m";
                    int lx = (int) Math.max(px0 + 4, Math.min(px1 - 4 - fb.stringWidth(s2), x - fb.stringWidth(s1) / 2.0));
                    int ly = hoch ? (int) y - 14 - (kachel ? 0 : fb.getHeight()) : (int) y + 22;
                    g.setColor(th.text);
                    g.drawString(s1, lx, ly);
                    if (!kachel) {
                        g.setColor(hoch ? HOCH : NIEDRIG);
                        g.drawString(s2, lx, ly + fb.getHeight());
                    }
                }
            }
            // Marken des Modells
            if (!kachel) {
                int z = 0;
                for (Gezeitentafel.Marke mk : tafel.marken) {
                    if (mk.zeit().isBefore(t0) || !mk.zeit().isBefore(t1)) continue;
                    double x = xv.applyAsDouble(mk.zeit().getEpochSecond() - t0.getEpochSecond()), y = yv.applyAsDouble(mk.hoehe());
                    Path2D.Double raute = new Path2D.Double();
                    raute.moveTo(x, y - 7);
                    raute.lineTo(x + 7, y);
                    raute.lineTo(x, y + 7);
                    raute.lineTo(x - 7, y);
                    raute.closePath();
                    g.setColor(Color.WHITE);
                    g.setStroke(new BasicStroke(1.6f));
                    g.draw(raute);
                    z++;
                }
                if (z > 0) {
                    String mn = "";
                    for (Gezeitentafel.Marke mk : tafel.marken)
                        if (!mk.zeit().isBefore(t0) && mk.zeit().isBefore(t1)) {
                            mn = tafel.modelle.getOrDefault(mk.szenario(), mk.szenario());
                            break;
                        }
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                    g.setColor(th.text);
                    g.drawString("Hoch- und Niedrigwasser im Modell (Lauf mit Wind): " + mn, px0 + 26, py1 - 10);
                    Path2D.Double raute = new Path2D.Double();
                    double x = px0 + 14, y = py1 - 14;
                    raute.moveTo(x, y - 5);
                    raute.lineTo(x + 5, y);
                    raute.lineTo(x, y + 5);
                    raute.lineTo(x - 5, y);
                    raute.closePath();
                    g.setColor(Color.WHITE);
                    g.draw(raute);
                }
            }
        }
    }

    // ------------------------------------------------------------------ Liste

    final class Liste extends JComponent {
        static final int ZEILE = 20, KOPF = 46;

        Liste() {
            setPreferredSize(new Dimension(548, 620));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    int i = (e.getY() - KOPF) / ZEILE;
                    if (i >= 0 && i < 7 * LISTE_WOCHEN) zeige(bereich.plusDays(i));
                }
            });
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            if (bereich == null) return;
            int x0 = 8, xd = x0 + 8, xe = xd + 82, sp = 88, xh = xe + 4 * sp + 4, xs = xh + 36, xm = xs + 26;
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            g.drawString("Vier Wochen ab " + DateTimeFormatter.ofPattern("d. MMMM", Locale.GERMAN).format(bereich), x0 + 8, 20);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(th.leise);
            g.drawString("Tag", xd, KOPF - 6);
            g.drawString("Hoch- und Niedrigwasser (Uhrzeit, m NHN)", xe, KOPF - 6);
            g.drawString("Hub", xh, KOPF - 6);
            LocalDate h0 = heute();
            for (int i = 0; i < 7 * LISTE_WOCHEN; i++) {
                LocalDate d = bereich.plusDays(i);
                int y = KOPF + i * ZEILE;
                if (y + ZEILE > h) break;
                if (d.equals(tag)) {
                    g.setColor(Thema.mitAlpha(th.primaer, 90));
                    g.fill(new RoundRectangle2D.Double(x0, y + 1, w - x0 - 6, ZEILE - 2, 8, 8));
                } else if (i % 2 == 1) {
                    g.setColor(Thema.mitAlpha(th.flaeche, 150));
                    g.fillRect(x0, y + 1, w - x0 - 6, ZEILE - 2);
                }
                if (d.getDayOfWeek() == DayOfWeek.MONDAY && i > 0) {
                    g.setColor(th.linie);
                    g.drawLine(x0, y, w - 6, y);
                }
                int by = y + ZEILE - 6;
                boolean wochenende = d.getDayOfWeek() == DayOfWeek.SATURDAY || d.getDayOfWeek() == DayOfWeek.SUNDAY;
                g.setFont(new Font(Font.SANS_SERIF, d.equals(h0) ? Font.BOLD : Font.PLAIN, 12));
                g.setColor(d.equals(h0) ? th.marke : wochenende ? th.text : th.leise);
                g.drawString(DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN).format(d), xd, by);
                Vorhersage.Tag t = tagDaten(d);
                if (t != null) {
                    int k = 0;
                    for (Vorhersage.Extrem e : t.extreme()) {
                        boolean hoch = e.art() == Vorhersage.Art.HOCHWASSER;
                        g.setFont(new Font(Font.SANS_SERIF, hoch ? Font.BOLD : Font.PLAIN, 12));
                        g.setColor(hoch ? HOCH : NIEDRIG);
                        g.drawString(uhr(e.zeit()) + " " + zahl(e.hoehe(), 1, true), xe + k * sp, by);
                        k++;
                    }
                    g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                    g.setColor(th.leise);
                    g.drawString(zahl(t.hub(), 1, false), xh, by);
                }
                Mond.Ereignis ph = phase(d);
                if (ph != null) mond(g, xm, y + ZEILE / 2.0, 12, winkel(ph.phase()));
                Vorhersage.Gang gg = gang(d);
                if (gg != null) {
                    g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
                    g.setColor(gg.spring() ? HOCH : NIEDRIG);
                    String s = gg.spring() ? "S" : "N";
                    g.drawString(s, xs, by);
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.leise);
            int ly = KOPF + 7 * LISTE_WOCHEN * ZEILE + 18;
            if (ly < h) g.drawString("fett: Hochwasser · S Springtide · N Nipptide · Mond: Hauptphasen", xd, ly);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ seit DB5: Herkunft

    /** Ein Stand im Verlauf der Tafel (Kopfzeile verlauf.N). */
    record Stand(String stand, String von, String bis, String tage, String tiden, String faktor, String n, String hoehe, String zeit, String hoeheAlt, String zeitAlt,
                 String woher) {
        static Stand aus(String z) {
            String[] t = z.split(";", -1);
            String[] u = new String[12];
            for (int i = 0; i < 12; i++) u[i] = i < t.length ? t[i].strip() : "–";
            return new Stand(u[0], u[1], u[2], u[3], u[4], u[5], u[6], u[7], u[8], u[9], u[10], u[11]);
        }
    }

    /** Die Stände der Tafel, der älteste zuerst. */
    public List<Stand> verlauf() {
        List<Stand> l = new ArrayList<>();
        for (int i = 1; tafel.kopf.containsKey("verlauf." + i); i++) l.add(Stand.aus(tafel.kopf.get("verlauf." + i)));
        return l;
    }

    static String datumKurz(String iso, ZoneId z) {
        try {
            return DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN).format(Instant.parse(iso).atZone(z));
        } catch (RuntimeException e) {
            return iso;
        }
    }

    static String zahlText(String s, int stellen) {
        try {
            return zahl(Double.parseDouble(s), stellen, false);
        } catch (RuntimeException e) {
            return s;
        }
    }

    /** Herkunft der Gezeit: jetzt, Verlauf, Zeitplan der Tiden. */
    final class Herkunftsbild extends JComponent {
        Herkunftsbild() { setPreferredSize(new Dimension(1448, 620)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight());
            g.dispose();
        }

        void male(Graphics2D g, int w, int h) {
            Thema th = Thema.aktuell();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            int x = 58, y = 34;
            g.setFont(th.titel(22f));
            g.setColor(th.text);
            g.drawString("Herkunft der Gezeit", x, y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(th.leise);
            String faktor = tafel.kopf.get("herkunft.faktor");
            g.drawString(tafel.ort + " · gemessen am Pegel " + (tafel.bezugAnzeige.isEmpty() ? "–" : tafel.bezugAnzeige) + " · mit dem Modell übertragen"
                    + (faktor == null ? "" : " · " + faktor + " Faktor auf die Kennwerte 2011–2020"), x, y + 22);
            // ---- jetzt
            y += 58;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(th.text);
            g.drawString("Jetzt", x, y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            String hk = tafel.kopf.get("herkunft");
            y = umbrochen(g, hk != null ? "Die Gezeit stammt " + hk + "." : "Die Gezeit stammt aus der Grundrechnung (Z1): " + tafel.kopf.getOrDefault("bezug", "–") + ".", x,
                    y + 20, w - 2 * x, th.text);
            List<Vorhersage.Konstante> bt = new ArrayList<>(tafel.bezugTiden.isEmpty() ? tafel.tiden : tafel.bezugTiden);
            bt.sort((a, b) -> Double.compare(b.amplitude(), a.amplitude()));
            StringBuilder sb = new StringBuilder();
            for (Vorhersage.Konstante k : bt) sb.append(sb.length() > 0 ? " · " : "").append(k.tide().name).append(' ').append(zahl(k.amplitude() * 100, 1, false)).append(" cm");
            String ab = tafel.kopf.getOrDefault("bezug.abgeleitet", "");
            y = umbrochen(g, bt.size() + " Tiden am Pegel (Amplitude): " + sb + (ab.isBlank() ? "" : ". Abgeleitet aus den Nachbarn: " + ab.replaceAll(" [0-9.]+ m [0-9.]+° aus ", " aus ")
                    + "."), x, y + 4, w - 2 * x, th.leise);
            // ---- Verlauf
            y += 22;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(th.text);
            g.drawString("Verlauf (angenommene Stände; geprüft an den Hoch- und Niedrigwassern ohne Windstau, mittlere Abweichung)", x, y);
            String[] kopf = {"Stand", "Messung", "Tage", "Tiden", "Faktor", "geprüft an", "Höhe", "Zeit", "vorher Höhe", "vorher Zeit", "Woher"};
            int[] sx = {0, 110, 300, 360, 420, 490, 590, 670, 750, 850, 940};
            y += 22;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(th.leise);
            for (int i = 0; i < kopf.length; i++) g.drawString(kopf[i], x + sx[i], y);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            List<Stand> vl = verlauf();
            if (vl.isEmpty()) {
                g.setColor(th.leise);
                g.drawString("noch kein Stand aus dem Messarchiv – der Sammler prüft am 1. jedes Monats (Sammler --gezeiten)", x, y + 20);
                y += 20;
            }
            for (int r = 0; r < vl.size(); r++) {
                Stand st = vl.get(r);
                y += 20;
                if (r == vl.size() - 1) {
                    g.setColor(Thema.mitAlpha(th.primaer, 70));
                    g.fill(new RoundRectangle2D.Double(x - 6, y - 14, sx[10] + 300, 19, 8, 8));
                }
                g.setColor(r == vl.size() - 1 ? th.text : th.leise);
                String[] z = {datumKurz(st.stand(), zone), datumKurz(st.von(), zone) + "–" + datumKurz(st.bis(), zone), zahlText(st.tage(), 1), st.tiden(), st.faktor(),
                        st.n().equals("–") ? "–" : st.n() + " Extreme", st.hoehe().equals("–") ? "–" : "± " + zahlText(st.hoehe(), 2) + " m",
                        st.zeit().equals("–") ? "–" : "± " + zahlText(st.zeit(), 0) + " min", st.hoeheAlt().equals("–") ? "–" : "± " + zahlText(st.hoeheAlt(), 2) + " m",
                        st.zeitAlt().equals("–") ? "–" : "± " + zahlText(st.zeitAlt(), 0) + " min", st.woher()};
                for (int i = 0; i < z.length; i++) g.drawString(z[i], x + sx[i], y);
            }
            // ---- Zeitplan
            y += 40;
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            g.setColor(th.text);
            g.drawString("Was die Messung mit der Zeit trennt (Rayleigh: zwei Tiden brauchen eine Welle Unterschied)", x, y);
            Instant beginn = null;
            try {
                if (tafel.kopf.containsKey("herkunft.beginn")) beginn = Instant.parse(tafel.kopf.get("herkunft.beginn").strip());
            } catch (RuntimeException e) {
                beginn = null;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            if (beginn == null) {
                g.setColor(th.leise);
                g.drawString("Beginn der Messung unbekannt – erst mit dem ersten Stand aus dem Messarchiv", x, y + 22);
            } else {
                Instant j = jetzt.get();
                for (Gezeitenanalyse.Schwelle sw : Gezeitenanalyse.zeitplan(beginn)) {
                    y += 22;
                    boolean da = !sw.ab().isAfter(j);
                    long rest = Math.max(0, (sw.ab().getEpochSecond() - j.getEpochSecond()) / 86_400);
                    g.setColor(da ? HOCH : th.leise);
                    g.fill(new Ellipse2D.Double(x, y - 10, 10, 10));
                    g.setColor(da ? th.text : th.leise);
                    g.drawString(DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN).format(sw.ab().atZone(zone)), x + 20, y);
                    g.drawString(String.format(Locale.GERMAN, "%.0f Tage", sw.tage()), x + 120, y);
                    g.drawString(sw.text(), x + 200, y);
                    g.drawString(da ? "erreicht" : "in " + rest + " Tagen", x + 860, y);
                }
                y += 26;
                g.setColor(th.leise);
                g.drawString("Bis dahin folgen K2, P1, NU2 und 2N2 aus ihren Nachbarn (Verhältnis der Gleichgewichtstide). Die Prüfung vergleicht jeden Monat neu; ersetzt wird "
                        + "nur, was besser ist.", x, y);
            }
        }

        /** Text in Zeilen auf die Breite; gibt das y unter der letzten Zeile. */
        int umbrochen(Graphics2D g, String text, int x, int y, int breite, Color farbe) {
            g.setColor(farbe);
            FontMetrics fm = g.getFontMetrics();
            StringBuilder z = new StringBuilder();
            for (String w : text.split("\\s+")) {
                if (z.length() > 0 && fm.stringWidth(z + " " + w) > breite) {
                    g.drawString(z.toString(), x, y);
                    y += fm.getHeight();
                    z.setLength(0);
                }
                if (z.length() > 0) z.append(' ');
                z.append(w);
            }
            if (z.length() > 0) {
                g.drawString(z.toString(), x, y);
                y += fm.getHeight();
            }
            return y;
        }
    }

    // ------------------------------------------------------------------ Ausblick

    final class Ausblick extends JComponent {
        static final int LINKS = 58, RECHTS = 24, OBEN = 44, UNTEN = 26;

        Ausblick() {
            setPreferredSize(new Dimension(800, 230));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { waehle(e.getX()); }

                @Override public void mouseDragged(MouseEvent e) { waehle(e.getX()); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private void waehle(int px) {
            int w = getWidth();
            double f = (px - LINKS) / (double) Math.max(1, w - LINKS - RECHTS);
            int d = (int) Math.floor(f * 7 * AUSBLICK_WOCHEN);
            if (d >= 0 && d < 7 * AUSBLICK_WOCHEN) zeige(bereich.plusDays(d));
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            if (bereich == null) return;
            Instant t0 = bereich.atStartOfDay(zone).toInstant(), t1 = bereich.plusWeeks(AUSBLICK_WOCHEN).atStartOfDay(zone).toInstant();
            double dauer = t1.getEpochSecond() - t0.getEpochSecond();
            int px0 = LINKS, px1 = w - RECHTS, py0 = OBEN, py1 = h - UNTEN;
            java.util.function.DoubleUnaryOperator xv = s -> px0 + s / dauer * (px1 - px0);
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (z - ymin) / (ymax - ymin) * (py1 - py0);
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            g.drawString("Ausblick: acht Wochen – Hoch- und Niedrigwasser jeder Tide", px0, 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            String leg = "Mond oben · S Springtide · N Nipptide (Alter der Gezeit " + zahl(v.alterStunden() / 24, 1, false) + " Tage)";
            g.setColor(th.leise);
            g.drawString(leg, px1 - fm.stringWidth(leg), 18);
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            // gewählter Tag
            double sa = tag.atStartOfDay(zone).toInstant().getEpochSecond() - t0.getEpochSecond(), sb = sa + 86400;
            g.setColor(Thema.mitAlpha(th.primaer, 70));
            g.fill(new Rectangle2D.Double(xv.applyAsDouble(sa), py0, xv.applyAsDouble(sb) - xv.applyAsDouble(sa), py1 - py0));
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            for (double z = Math.ceil(ymin); z <= ymax; z += 1) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, z == 0 ? 255 : 120));
                g.drawLine(px0 + 1, y, px1 - 1, y);
                g.setColor(th.leise);
                String l = z == 0 ? "0" : zahl(z, 0, true);
                g.drawString(l, px0 - 8 - fm.stringWidth(l), y + 4);
            }
            // Wochen
            for (int wo = 0; wo <= AUSBLICK_WOCHEN; wo++) {
                LocalDate d = bereich.plusWeeks(wo);
                int x = (int) xv.applyAsDouble(d.atStartOfDay(zone).toInstant().getEpochSecond() - t0.getEpochSecond());
                g.setColor(Thema.mitAlpha(th.linie, 150));
                g.drawLine(x, py0 + 1, x, py1 - 1);
                if (wo < AUSBLICK_WOCHEN) {
                    g.setColor(th.leise);
                    g.drawString("KW " + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + " · " + DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN).format(d), x + 4, py1 + 16);
                }
            }
            if (!Double.isNaN(marke)) {
                int y = (int) yv.applyAsDouble(marke);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
                g.setColor(th.marke);
                g.drawLine(px0 + 1, y, px1 - 1, y);
            }
            // Hüllen und Punkte
            Path2D.Double hoch = new Path2D.Double(), tief = new Path2D.Double();
            boolean ersterH = true, ersterT = true;
            for (Vorhersage.Tag t : tage)
                for (Vorhersage.Extrem e : t.extreme()) {
                    double x = xv.applyAsDouble(e.zeit().getEpochSecond() - t0.getEpochSecond()), y = yv.applyAsDouble(e.hoehe());
                    if (e.art() == Vorhersage.Art.HOCHWASSER) {
                        if (ersterH) hoch.moveTo(x, y);
                        else hoch.lineTo(x, y);
                        ersterH = false;
                    } else {
                        if (ersterT) tief.moveTo(x, y);
                        else tief.lineTo(x, y);
                        ersterT = false;
                    }
                }
            g.setStroke(new BasicStroke(1.2f));
            g.setColor(Thema.mitAlpha(HOCH, 150));
            g.draw(hoch);
            g.setColor(Thema.mitAlpha(NIEDRIG, 150));
            g.draw(tief);
            for (Vorhersage.Tag t : tage)
                for (Vorhersage.Extrem e : t.extreme()) {
                    double x = xv.applyAsDouble(e.zeit().getEpochSecond() - t0.getEpochSecond()), y = yv.applyAsDouble(e.hoehe());
                    g.setColor(e.art() == Vorhersage.Art.HOCHWASSER ? HOCH : NIEDRIG);
                    g.fill(new Ellipse2D.Double(x - 2.2, y - 2.2, 4.4, 4.4));
                }
            // Spring und Nipp
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            for (Vorhersage.Gang gg : gaenge) {
                int x = (int) xv.applyAsDouble(gg.zeit().getEpochSecond() - t0.getEpochSecond());
                g.setColor(Thema.mitAlpha(gg.spring() ? HOCH : NIEDRIG, 120));
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {2f, 3f}, 0f));
                g.drawLine(x, py0 + 14, x, py1 - 1);
                g.setColor(gg.spring() ? HOCH : NIEDRIG);
                g.drawString(gg.spring() ? "S" : "N", x - 3, py0 + 12);
            }
            // Mond
            for (Mond.Ereignis p : phasen) {
                double x = xv.applyAsDouble(p.zeit().getEpochSecond() - t0.getEpochSecond());
                mond(g, x, py0 - 12, 13, winkel(p.phase()));
            }
            // heute
            Instant j = jetzt.get();
            if (!j.isBefore(t0) && j.isBefore(t1)) {
                int x = (int) xv.applyAsDouble(j.getEpochSecond() - t0.getEpochSecond());
                g.setStroke(new BasicStroke(1.6f));
                g.setColor(th.text);
                g.drawLine(x, py0, x, py1);
                g.drawString("heute", x + 4, py1 - 4);
            }
            g.dispose();
        }
    }
}
