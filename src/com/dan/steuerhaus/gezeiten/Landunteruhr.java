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
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Inhalt der Landunter-Uhr (seit Z2, Werkbuch HalligHooge 156): links die Uhr – oben Hochwasser, unten
 * Niedrigwasser, der Zeiger läuft einmal je Tide, im Zifferblatt steht das Wasser so hoch wie jetzt, rot der Bogen, in dem
 * es über der Krone des Sommerdeichs steht; daneben die Pegellatte mit Krone, Fennen und Kuppen der Warften; rechts die
 * Warften beim nächsten Scheitel und die Messung am Bezugspegel (PEGELONLINE) gegen die Gezeit; unten je Hochwasser der
 * nächsten 14 Tage der Windstau, ab dem Landunter ist. Der Windstau: gemessen (Bezugspegel), ohne Wind, gewählt oder eigener
 * Wert. Nicht zur Navigation.
 */
public final class Landunteruhr extends JPanel {
    static final Color ROT = new Color(235, 90, 70), ORANGE = new Color(240, 160, 60), GRUEN = new Color(120, 200, 120), WASSER = new Color(80, 150, 210);
    static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN), TAG = DateTimeFormatter.ofPattern("EE d.M., HH:mm", Locale.GERMAN);
    /** Bereich des eigenen Windstaus (m) in Schritten von 5 cm [G]. */
    static final int STAU_MIN = -20, STAU_MAX = 70;
    /** Tage im Ausblick. */
    static final int TAGE = 14;

    public final Gezeitentafel tafel;
    public final Landunter lu;
    final ZoneId zone;
    final File fotos;
    final String kroneName, nassName, hinweis;
    /** Fehler des Scheitels mit gemessenem Windstau je Vorlauf (aus der Tafel; null ohne). */
    final double[] fehlerVorlauf, fehlerRms;
    final List<String> wahlNamen = new ArrayList<>();
    final List<Double> wahlWerte = new ArrayList<>();
    /** Die Uhr (für Prüfungen einstellbar). */
    public Supplier<Instant> jetzt = Instant::now;
    /** Seit Z2b: die gemessenen Pegel für den Windstau (aus der Tafel); der Windstau ist das Mittel ihrer Windstaus. */
    public final List<Gezeitentafel.Bezug> bezuege;
    /** Woher die Messungen kommen, je Bezug eine (für Prüfungen ersetzbar); leer ohne Bezug. */
    public List<Callable<List<Pegelonline.Messwert>>> messung = new ArrayList<>();
    public Consumer<File> gesichert = f -> { };
    /** Seit DB2: eine Zeile aus der Chronik des Messarchivs unter der Uhr („Zuletzt Landunter …“), null: keine. */
    public String chronikZeile;

    final Uhr uhr = new Uhr();
    final Latte latte = new Latte();
    final Warftenliste liste = new Warftenliste();
    final Messbild messbild = new Messbild();
    final Ausblick ausblick = new Ausblick();
    final FComboBox quelle;
    final FSlider eigen = new FSlider(STAU_MIN, STAU_MAX, 0);
    final JLabel eigenText = new JLabel(" "), stand = new JLabel(" ");
    final FButton neu = new FButton("Messung neu laden"), sichern = new FButton("Bild sichern");
    private final Timer takt;
    /** Je Bezug der Windstau der letzten Stunde (null: nicht erreichbar) und der Fehler beim Laden. */
    private volatile List<Pegelonline.Stau> staus = List.of();
    private volatile List<String> fehler = List.of();
    private volatile boolean laedt;
    private boolean still;
    private long geladen;

    /**
     * @param wahl Windstau zur Auswahl: je Eintrag {Name, Wert in m}, etwa „Sturmflut 25.10.2026 im Modell“
     */
    public Landunteruhr(Gezeitentafel tafel, double krone, String kroneName, double nass, String nassName, List<Landunter.Warft> warften, List<Object[]> wahl, String hinweis,
                        ZoneId zone, File fotos) {
        super(new BorderLayout());
        this.tafel = tafel;
        this.lu = new Landunter(tafel.vorhersage(), krone, nass, warften);
        this.kroneName = kroneName == null || kroneName.isBlank() ? "Krone" : kroneName;
        this.nassName = nassName == null || nassName.isBlank() ? "Landunter" : nassName;
        this.fehlerVorlauf = zahlen(tafel.kopf.get("wert.stau.vorlauf"));
        this.fehlerRms = zahlen(tafel.kopf.get("wert.stau.fehler"));
        this.hinweis = hinweis == null ? "" : hinweis;
        this.zone = zone;
        this.fotos = fotos;
        this.bezuege = tafel.bezuege;
        if (!bezuege.isEmpty()) {
            wahlNamen.add("Gemessen (" + kurzNamen() + ")");
            wahlWerte.add(Double.NaN);
            for (Gezeitentafel.Bezug b : bezuege) messung.add(() -> Pegelonline.lies(b.nummer(), "P1D", b.pegelnull()));
        }
        wahlNamen.add("Ohne Wind (nur Gezeit)");
        wahlWerte.add(0.0);
        for (Object[] w : wahl) {
            wahlNamen.add((String) w[0] + " (" + zahl((Double) w[1], 2, true) + " m)");
            wahlWerte.add((Double) w[1]);
        }
        wahlNamen.add("Eigener Wert");
        wahlWerte.add(Double.NaN);
        quelle = new FComboBox(wahlNamen.toArray());
        quelle.setPreferredSize(new Dimension(360, quelle.getPreferredSize().height));
        quelle.setLightWeightPopupEnabled(false);
        quelle.setFocusable(false);
        quelle.setToolTipText("Woher der Windstau kommt, der zur Gezeit dazukommt");
        quelle.addActionListener(e -> aktualisieren());
        eigen.setFocusable(false);
        eigen.setPreferredSize(new Dimension(200, eigen.getPreferredSize().height));
        eigen.setToolTipText("Eigener Windstau in Schritten von 5 cm (wählt „Eigener Wert“)");
        // der Regler zeigt immer den Windstau, der gilt; wer ihn zieht, wählt „Eigener Wert“
        eigen.addChangeListener(e -> {
            if (still) return;
            if (!eigenGewaehlt()) {
                still = true;
                quelle.setSelectedIndex(wahlNamen.size() - 1);
                still = false;
            }
            aktualisieren();
        });
        for (FButton b : new FButton[] {neu, sichern}) {
            b.setFocusable(false);
            b.setVariant(FButtonVariant.GHOST);
        }
        neu.addActionListener(e -> laden(true));
        neu.setEnabled(!bezuege.isEmpty());
        sichern.addActionListener(e -> sichern());
        Faerber.rolle(eigenText, "text");
        eigenText.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        eigenText.setPreferredSize(new Dimension(150, 24));
        Faerber.rolle(stand, "leise");
        stand.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        stand.setPreferredSize(new Dimension(250, 24));

        // seit Z2b feste Breiten: alles in einer Zeile ab 1 300 Bildpunkten (Befund im Lauf Z2: abgeschnitten)
        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.setOpaque(false);
        for (JComponent c : new JComponent[] {quelle, eigen, eigenText, neu, sichern, stand}) knoepfe.add(c);
        JPanel leiste = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        leiste.add(knoepfe, BorderLayout.CENTER);

        JPanel links = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        links.add(uhr, BorderLayout.CENTER);
        links.add(latte, BorderLayout.EAST);
        JPanel rechtsOben = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        rechtsOben.add(liste, BorderLayout.CENTER);
        rechtsOben.add(messbild, BorderLayout.SOUTH);
        JPanel oben = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        oben.add(links, BorderLayout.CENTER);
        oben.add(rechtsOben, BorderLayout.EAST);
        JPanel fuss = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        fuss.add(leiste, BorderLayout.NORTH);
        if (!this.hinweis.isEmpty()) fuss.add(new Hinweiszeile(this.hinweis), BorderLayout.CENTER);
        JPanel unten = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        unten.add(ausblick, BorderLayout.CENTER);
        unten.add(fuss, BorderLayout.SOUTH);
        Faerber.rolle(this, "grund");
        add(oben, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        takt = new Timer(20_000, e -> {
            if (System.currentTimeMillis() - geladen > 10 * 60_000L) laden(false);
            aktualisieren();
        });
        aktualisieren();
    }

    /** „Pellworm, Wittdün“. */
    String kurzNamen() {
        StringBuilder b = new StringBuilder();
        for (Gezeitentafel.Bezug x : bezuege) b.append(b.length() > 0 ? ", " : "").append(x.kurz());
        return b.toString();
    }

    /** Beginnt die Uhr (Takt alle 20 s, Messung alle 10 min) und lädt die Messung. */
    public void starte() {
        takt.start();
        laden(false);
    }

    public void ende() { takt.stop(); }

    boolean eigenGewaehlt() { return quelle.getSelectedIndex() == wahlNamen.size() - 1; }

    boolean gemessenGewaehlt() { return !bezuege.isEmpty() && quelle.getSelectedIndex() == 0; }

    /** Wählt die Quelle des Windstaus nach ihrer Nummer in der Liste. */
    public void quelle(int i) { quelle.setSelectedIndex(Math.max(0, Math.min(wahlNamen.size() - 1, i))); }

    public List<String> quellen() { return List.copyOf(wahlNamen); }

    /** Eigener Windstau (m); wählt „Eigener Wert“. */
    public void eigenerStau(double m) {
        quelle(wahlNamen.size() - 1);
        eigen.setValue((int) Math.round(m / 0.05));
        aktualisieren();
    }

    /** Der Windstau, der jetzt gilt (m). */
    public double stau() {
        int i = quelle.getSelectedIndex();
        if (eigenGewaehlt()) return eigen.getValue() * 0.05;
        if (gemessenGewaehlt()) {
            Pegelonline.Stau s = gemessen();
            return s == null ? 0 : s.meter();
        }
        Double w = wahlWerte.get(Math.max(0, i));
        return w == null || w.isNaN() ? 0 : w;
    }

    /**
     * Der gemessene Windstau: das Mittel der Windstaus der erreichten Pegel (Stand: der ältere ihrer jüngsten Werte,
     * Werte: zusammen); null, wenn keiner erreicht ist.
     */
    public Pegelonline.Stau gemessen() {
        List<Pegelonline.Stau> l = staus;
        double sum = 0;
        int n = 0, werte = 0;
        Instant stand = null;
        List<Pegelonline.Messwert> erste = null;
        for (Pegelonline.Stau x : l) {
            if (x == null) continue;
            sum += x.meter();
            n++;
            werte += x.werte();
            if (stand == null || x.stand().isBefore(stand)) stand = x.stand();
            if (erste == null) erste = x.messung();
        }
        return n == 0 ? null : new Pegelonline.Stau(sum / n, stand, werte, erste);
    }

    /** Je Bezug der Windstau der letzten Stunde (null: nicht erreicht). */
    public List<Pegelonline.Stau> gemessenJe() { return staus; }

    /** Was beim Laden fehlschlug („Wittdün: …“), null, wenn alle erreicht sind. */
    public String messFehler() {
        List<String> f = fehler;
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < f.size(); i++)
            if (f.get(i) != null) b.append(b.length() > 0 ? "; " : "").append(i < bezuege.size() ? bezuege.get(i).kurz() + ": " : "").append(f.get(i));
        return b.length() == 0 ? null : b.toString();
    }

    /** Lädt die Messung im Hintergrund (nicht öfter als einmal je Minute, außer erzwungen). */
    public void laden(boolean erzwingen) {
        if (bezuege.isEmpty() || laedt || !erzwingen && System.currentTimeMillis() - geladen < 60_000) return;
        laedt = true;
        Thread t = new Thread(() -> {
            try {
                holen();
            } finally {
                geladen = System.currentTimeMillis();
                laedt = false;
                SwingUtilities.invokeLater(this::aktualisieren);
            }
        }, "PEGELONLINE");
        t.setDaemon(true);
        t.start();
    }

    /** Lädt die Messung sofort in diesem Faden (für Prüfungen). */
    public void ladenJetzt() {
        if (bezuege.isEmpty()) return;
        holen();
        geladen = System.currentTimeMillis();
        aktualisieren();
    }

    /** Je Bezug: Messung holen, Windstau der letzten Stunde gegen seine Gezeit. */
    private void holen() {
        List<Pegelonline.Stau> neu = new ArrayList<>();
        List<String> f = new ArrayList<>();
        for (int i = 0; i < bezuege.size(); i++) {
            Callable<List<Pegelonline.Messwert>> c = i < messung.size() ? messung.get(i) : null;
            try {
                Pegelonline.Stau s = c == null ? null : Pegelonline.stau(c.call(), bezuege.get(i).vorhersage());
                neu.add(s);
                f.add(s == null ? "keine Werte" : null);
            } catch (Exception x) {
                neu.add(null);
                f.add(x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage());
            }
        }
        staus = neu;
        fehler = f;
    }

    void aktualisieren() {
        double s = stau();
        if (!eigenGewaehlt() && !still) {
            still = true;
            eigen.setValue((int) Math.round(Math.max(STAU_MIN, Math.min(STAU_MAX, s / 0.05))));
            still = false;
        }
        eigenText.setText("Windstau " + zahl(s, 2, true) + " m");
        if (gemessenGewaehlt()) {
            Pegelonline.Stau g = gemessen();
            List<Pegelonline.Stau> je = staus;
            StringBuilder nur = new StringBuilder(), tip = new StringBuilder("<html>PEGELONLINE (WSV), Windstau der letzten Stunde:");
            int erreicht = 0;
            for (int i = 0; i < bezuege.size(); i++) {
                Pegelonline.Stau x = i < je.size() ? je.get(i) : null;
                tip.append("<br>").append(bezuege.get(i).anzeige()).append(": ").append(x == null ? "nicht erreichbar" : zahl(x.meter(), 2, true) + " m, " + x.werte() + " Werte bis "
                        + UHR.format(x.stand().atZone(zone)) + " Uhr");
                if (x != null) {
                    erreicht++;
                    nur.append(nur.length() > 0 ? ", " : "").append(bezuege.get(i).kurz());
                }
            }
            stand.setText(laedt ? "PEGELONLINE: lädt …" : g == null ? "PEGELONLINE nicht erreichbar – Windstau 0"
                    : "Messung bis " + UHR.format(g.stand().atZone(zone)) + " Uhr" + (erreicht < bezuege.size() ? ", nur " + nur : ""));
            stand.setToolTipText(tip + "</html>");
        } else stand.setText(" ");
        repaint();
    }

    static double[] zahlen(String s) {
        if (s == null || s.isBlank()) return null;
        String[] t = s.strip().split("\\s+");
        double[] r = new double[t.length];
        try {
            for (int i = 0; i < t.length; i++) r[i] = Double.parseDouble(t[i]);
        } catch (NumberFormatException x) {
            return null;
        }
        return r;
    }

    /** Der Fehler des Scheitels (± m) beim gemessenen Windstau für ein Hochwasser in so vielen Stunden; NaN sonst. */
    public double band(double stunden) {
        if (!gemessenGewaehlt() || gemessen() == null || fehlerRms == null || fehlerVorlauf == null || fehlerRms.length != fehlerVorlauf.length) return Double.NaN;
        return Landunter.fehler(fehlerVorlauf, fehlerRms, stunden);
    }

    static String zahl(double v, int stellen, boolean vorzeichen) {
        if (Double.isNaN(v)) return "–";
        return String.format(Locale.GERMAN, "%" + (vorzeichen ? "+" : "") + "." + stellen + "f", v).replace('-', '−');
    }

    static String dauer(Duration d) {
        long m = Math.max(0, d.toMinutes());
        return m >= 60 ? m / 60 + " h " + m % 60 + " min" : m + " min";
    }

    // ------------------------------------------------------------------ Sichern

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
        File f = new File(fotos, "Landunter_Uhr_" + stempel + ".png");
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
        setSize(w, h);
        for (int i = 0; i < 3; i++) layout(this);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        printAll(g);
        g.dispose();
        return im;
    }

    /** Nur die Uhr w × h (Kachel der Startseite). */
    public BufferedImage uhrbild(int w, int h) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        uhr.male(g, w, h, true);
        g.dispose();
        return im;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }

    /** Schreibt den Text auf die Breite umbrochen ab y (Grundlinie der ersten Zeile); gibt die Grundlinie der letzten. */
    static int umbrechen(Graphics2D g, String text, int x, int y, int breite) {
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
        if (z.length() > 0) g.drawString(z.toString(), x, y);
        return y;
    }

    static void glatt(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    // ------------------------------------------------------------------ Uhr

    final class Uhr extends JComponent {
        Uhr() { setPreferredSize(new Dimension(640, 640)); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight(), false);
            g.dispose();
        }

        /** Punkt auf dem Kreis zum Winkel der Uhr (0 oben, im Uhrzeigersinn). */
        double[] punkt(double cx, double cy, double r, double winkel) {
            double a = Math.toRadians(winkel);
            return new double[] {cx + r * Math.sin(a), cy - r * Math.cos(a)};
        }

        void bogen(Graphics2D g, double cx, double cy, double r, Instant von, Instant bis, Color c, float breite) {
            double a0 = lu.winkel(von), a1 = lu.winkel(bis);
            if (a1 < a0) a1 += 360;
            g.setStroke(new BasicStroke(breite, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
            g.setColor(Thema.mitAlpha(c, 225));
            g.draw(new Arc2D.Double(cx - r, cy - r, 2 * r, 2 * r, 90 - a0, -(a1 - a0), Arc2D.OPEN));
        }

        void male(Graphics2D g, int w, int h, boolean kachel) {
            Thema th = Thema.aktuell();
            glatt(g);
            g.setColor(kachel ? th.tief : th.grund);
            g.fillRect(0, 0, w, h);
            Instant t = jetzt.get();
            double s = stau(), jetztH = lu.wert(t, s);
            Landunter.Hochwasser hw = lu.naechstes(t, s);
            int textH = kachel ? 0 : 132;
            double r = Math.max(40, Math.min(w, h - textH) / 2.0 - (kachel ? 26 : 32)), cx = w / 2.0, cy = (kachel ? h / 2.0 : (h - textH) / 2.0 + 6);
            // Zifferblatt mit Wasser
            Ellipse2D.Double kreis = new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r);
            g.setColor(th.flaeche);
            g.fill(kreis);
            // seit Z2b bis +4 m: Platz zwischen „HW“ und den Linien von Krone und Landunter, auch bei kleiner Uhr
            double zu = -2.5, zo = 4.0;
            java.util.function.DoubleUnaryOperator yv = z -> cy + r - (Math.max(zu, Math.min(zo, z)) - zu) / (zo - zu) * 2 * r;
            Shape alt = g.getClip();
            g.clip(kreis);
            double yw = yv.applyAsDouble(jetztH);
            g.setPaint(new GradientPaint(0, (float) yw, new Color(70, 140, 200, 200), 0, (float) (cy + r), new Color(20, 60, 110, 220)));
            g.fill(new Rectangle2D.Double(cx - r, yw, 2 * r, cy + r - yw));
            // Landunter (rot) und Krone (orange)
            double yk = yv.applyAsDouble(lu.krone), yn = yv.applyAsDouble(lu.nass);
            g.setStroke(new BasicStroke(kachel ? 1.5f : 2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {8f, 6f}, 0f));
            g.setColor(ORANGE);
            g.draw(new Line2D.Double(cx - r, yk, cx + r, yk));
            if (yn < yk - 2) {
                g.setColor(ROT);
                g.draw(new Line2D.Double(cx - r, yn, cx + r, yn));
            }
            g.setClip(alt);
            if (!kachel) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                if (yn < yk - 2) {
                    g.setColor(ROT);
                    String nl = "Landunter " + zahl(lu.nass, 2, true) + " m";
                    g.drawString(nl, (int) (cx - g.getFontMetrics().stringWidth(nl) / 2.0), (int) yn - 6);
                }
                g.setColor(ORANGE);
                String kl = kroneName + " " + zahl(lu.krone, 2, true) + " m";
                g.drawString(kl, (int) (cx - g.getFontMetrics().stringWidth(kl) / 2.0), (int) yk + 16);
            }
            // Bögen: über der Krone (orange), Landunter (rot)
            if (hw != null && hw.ueberlauf()) bogen(g, cx, cy, r, hw.ueberAb(), hw.ueberBis(), ORANGE, kachel ? 8f : 14f);
            if (hw != null && hw.landunter()) bogen(g, cx, cy, r, hw.nassAb(), hw.nassBis(), ROT, kachel ? 8f : 14f);
            g.setStroke(new BasicStroke(2f));
            g.setColor(th.linie);
            g.draw(kreis);
            // Stunden: vom letzten Hochwasser bis zum nächsten
            List<Vorhersage.Extrem> ex = lu.v.extreme(t.minusSeconds(14 * 3600), t.plusSeconds(14 * 3600));
            Vorhersage.Extrem vorHw = null, nachHw = null;
            for (Vorhersage.Extrem e : ex) {
                if (e.art() != Vorhersage.Art.HOCHWASSER) continue;
                if (!e.zeit().isAfter(t)) vorHw = e;
                else if (nachHw == null) nachHw = e;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, kachel ? 9 : 11));
            FontMetrics fm = g.getFontMetrics();
            if (vorHw != null && nachHw != null) {
                for (long st = vorHw.zeit().getEpochSecond() / 3600 + 1; st * 3600 < nachHw.zeit().getEpochSecond(); st++) {
                    Instant ti = Instant.ofEpochSecond(st * 3600);
                    double a = lu.winkel(ti);
                    double[] p1 = punkt(cx, cy, r - 2, a), p2 = punkt(cx, cy, r - 12, a);
                    g.setColor(th.leise);
                    g.setStroke(new BasicStroke(1.5f));
                    g.draw(new Line2D.Double(p1[0], p1[1], p2[0], p2[1]));
                    if (!kachel) {
                        double[] p3 = punkt(cx, cy, r + 16, a);
                        String l = UHR.format(ti.atZone(zone)).substring(0, 2);
                        g.drawString(l, (int) (p3[0] - fm.stringWidth(l) / 2.0), (int) (p3[1] + fm.getAscent() / 2.0 - 1));
                    }
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, kachel ? 11 : 14));
            fm = g.getFontMetrics();
            g.setColor(th.text);
            g.drawString("HW", (int) (cx - fm.stringWidth("HW") / 2.0), (int) (cy - r + (kachel ? 14 : 20) + fm.getAscent()));
            g.drawString("NW", (int) (cx - fm.stringWidth("NW") / 2.0), (int) (cy + r - (kachel ? 8 : 14)));
            // Zeiger
            double a = lu.winkel(t);
            double[] p = punkt(cx, cy, r * 0.86, a);
            g.setStroke(new BasicStroke(kachel ? 3f : 5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Thema.mitAlpha(Color.BLACK, 120));
            g.draw(new Line2D.Double(cx + 2, cy + 2, p[0] + 2, p[1] + 2));
            g.setColor(th.marke);
            g.draw(new Line2D.Double(cx, cy, p[0], p[1]));
            g.fill(new Ellipse2D.Double(cx - 8, cy - 8, 16, 16));
            g.fill(new Ellipse2D.Double(p[0] - 6, p[1] - 6, 12, 12));
            // Mitte: Wasserstand jetzt
            String z1 = zahl(jetztH, 2, true) + " m", z2 = "jetzt, " + UHR.format(t.atZone(zone)) + " Uhr";
            // seit Z2b mit der Uhr skaliert (Befund im Lauf Z2: bei kleinem Fenster über „NW“)
            float fs1 = kachel ? 18f : (float) Math.max(18, Math.min(30, r / 5.5));
            int fs2 = kachel ? 10 : r < 170 ? 11 : 13;
            g.setFont(th.titel(fs1));
            FontMetrics f1 = g.getFontMetrics();
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fs2));
            FontMetrics f2 = g.getFontMetrics();
            int bw = Math.max(f1.stringWidth(z1), f2.stringWidth(z2)) + 24, bh = f1.getHeight() + f2.getHeight() + 10;
            double by = cy + r * 0.18;
            g.setColor(Thema.mitAlpha(th.tief, 205));
            g.fill(new RoundRectangle2D.Double(cx - bw / 2.0, by, bw, bh, 12, 12));
            g.setFont(th.titel(fs1));
            g.setColor(jetztH > lu.nass ? ROT : jetztH > lu.krone ? ORANGE : th.text);
            g.drawString(z1, (int) (cx - f1.stringWidth(z1) / 2.0), (int) (by + 4 + f1.getAscent()));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fs2));
            g.setColor(th.leise);
            g.drawString(z2, (int) (cx - f2.stringWidth(z2) / 2.0), (int) (by + 4 + f1.getHeight() + f2.getAscent()));
            if (kachel) {
                g.setFont(th.titel(14f));
                g.setColor(th.text);
                g.drawString("Landunter-Uhr", 10, 20);
                return;
            }
            // Text unter der Uhr: vier kurze Zeilen
            if (hw == null) return;
            int ty = (int) (cy + r + 42);
            double vorlauf = Duration.between(t, hw.zeit()).toSeconds() / 3600.0, bd = band(vorlauf);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            g.setColor(th.text);
            g.drawString("Nächstes Hochwasser " + TAG.format(hw.zeit().atZone(zone)) + " Uhr, in " + dauer(Duration.between(t, hw.zeit())), 20, ty);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            g.drawString("Gezeit " + zahl(hw.gezeit(), 2, true) + " m  " + (s < 0 ? "−" : "+") + "  Windstau " + zahl(Math.abs(s), 2, false) + " m  =  Scheitel " + zahl(hw.scheitel(), 2, true)
                    + " m" + (Double.isNaN(bd) ? "" : "  (± " + zahl(bd, 2, false) + " m)"), 20, ty + 22);
            String l3;
            if (hw.landunter()) {
                l3 = "Landunter " + UHR.format(hw.nassAb().atZone(zone)) + "–" + UHR.format(hw.nassBis().atZone(zone)) + " Uhr, Scheitel " + zahl(-hw.fehltNass(), 2, false) + " m darüber";
                g.setColor(ROT);
            } else if (hw.ueberlauf()) {
                l3 = "Über der Krone " + UHR.format(hw.ueberAb().atZone(zone)) + "–" + UHR.format(hw.ueberBis().atZone(zone)) + " Uhr, zum Landunter fehlen " + zahl(hw.fehltNass(), 2, false) + " m";
                g.setColor(ORANGE);
            } else {
                l3 = "Kein Landunter: bis zur Krone fehlen " + zahl(hw.fehltKrone(), 2, false) + " m";
                g.setColor(GRUEN);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            g.drawString(l3, 20, ty + 47);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            g.setColor(th.leise);
            String l4 = "Landunter ab " + zahl(hw.noetigNass(), 2, true) + " m Windstau, über die Krone ab " + zahl(hw.noetigKrone(), 2, true) + " m";
            if (!Double.isNaN(bd) && !hw.landunter() && hw.scheitel() + bd > lu.nass) l4 = "Landunter möglich (Scheitel + Fehler über " + zahl(lu.nass, 2, true) + " m); " + l4.substring(0, 1).toLowerCase(Locale.GERMAN) + l4.substring(1);
            g.drawString(l4, 20, ty + 68);
            if (chronikZeile != null && !chronikZeile.isBlank()) {
                // passt die Zeile nicht: kleiner, dann nur der erste Teil (bis „;“) und der Schluss („· Messarchiv“)
                String cz = chronikZeile;
                g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 13));
                if (g.getFontMetrics().stringWidth(cz) > w - 40) g.setFont(new Font(Font.SANS_SERIF, Font.ITALIC, 12));
                if (g.getFontMetrics().stringWidth(cz) > w - 40 && cz.contains("; ")) {
                    int pm = cz.lastIndexOf(" · ");
                    cz = cz.substring(0, cz.indexOf("; ")) + (pm > 0 ? cz.substring(pm) : "");
                }
                g.drawString(cz, 20, ty + 90);
            }
        }
    }

    // ------------------------------------------------------------------ Pegellatte

    final class Latte extends JComponent {
        static final double ZU = -2.5, ZO = 7.0;

        Latte() { setPreferredSize(new Dimension(272, 640)); }

        /** Eine Beschriftung rechts der Latte: gewünschte Höhe (Pixel), Zeilen, Farbe der ersten Zeile. */
        record Schild(double y, String[] zeilen, Color farbe) { }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            Instant t = jetzt.get();
            double s = stau(), jetztH = lu.wert(t, s);
            Landunter.Hochwasser hw = lu.naechstes(t, s);
            int py0 = 40, py1 = h - 120, x0 = 40, x1 = x0 + 34, xr = x1 + 8;
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (z - ZU) / (ZO - ZU) * (py1 - py0);
            g.setFont(th.titel(14f));
            g.setColor(th.text);
            g.drawString("Pegellatte (m NHN)", 10, 22);
            g.setColor(th.flaeche);
            g.fillRect(x0, py0, x1 - x0, py1 - py0);
            // Wasser jetzt
            double yw = yv.applyAsDouble(Math.max(ZU, Math.min(ZO, jetztH)));
            g.setPaint(new GradientPaint(0, (float) yw, new Color(80, 150, 210), 0, py1, new Color(20, 60, 110)));
            g.fill(new Rectangle2D.Double(x0, yw, x1 - x0, py1 - yw));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            for (int z = (int) Math.ceil(ZU); z <= ZO; z++) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(th.leise);
                g.drawLine(x0 - 5, y, x0, y);
                String l = z == 0 ? "0" : zahl(z, 0, true);
                g.drawString(l, x0 - 8 - fm.stringWidth(l), y + 4);
            }
            g.setColor(th.linie);
            g.drawRect(x0, py0, x1 - x0, py1 - py0);
            List<Schild> schilder = new ArrayList<>();
            // Fennen und Kuppen der Warften: Spannen neben der Latte
            double fmin = Double.MAX_VALUE, fmax = -Double.MAX_VALUE, kmin = Double.MAX_VALUE, kmax = -Double.MAX_VALUE;
            for (Landunter.Warft wa : lu.warften) {
                fmin = Math.min(fmin, wa.fuss());
                fmax = Math.max(fmax, wa.fuss());
                kmin = Math.min(kmin, wa.kuppe());
                kmax = Math.max(kmax, wa.kuppe());
            }
            if (fmax >= fmin) {
                double ya = yv.applyAsDouble(fmax), yb = yv.applyAsDouble(fmin);
                g.setColor(new Color(130, 170, 90, 190));
                g.fill(new Rectangle2D.Double(xr, ya, 7, Math.max(3, yb - ya)));
                schilder.add(new Schild((ya + yb) / 2, new String[] {"Fennen am Fuß", zahl(fmin, 1, false) + "–" + zahl(fmax, 1, false) + " m"}, th.text));
            }
            if (kmax >= kmin) {
                g.setColor(new Color(200, 170, 115));
                for (Landunter.Warft wa : lu.warften) g.fill(new Rectangle2D.Double(xr, yv.applyAsDouble(wa.kuppe()) - 1, 7, 3));
                double ya = yv.applyAsDouble(kmax), yb = yv.applyAsDouble(kmin);
                schilder.add(new Schild((ya + yb) / 2, new String[] {"Kuppen der Warften", zahl(kmin, 1, false) + "–" + zahl(kmax, 1, false) + " m"}, th.text));
            }
            // Krone
            int yk = (int) yv.applyAsDouble(lu.krone);
            g.setStroke(new BasicStroke(2.4f));
            g.setColor(ORANGE);
            g.drawLine(x0 - 4, yk, x1 + 4, yk);
            schilder.add(new Schild(yk, new String[] {kroneName, zahl(lu.krone, 2, true) + " m, niedrigste Stelle"}, ORANGE));
            if (lu.nass > lu.krone + 0.005) {
                int yn = (int) yv.applyAsDouble(lu.nass);
                g.setColor(ROT);
                g.drawLine(x0 - 4, yn, x1 + 4, yn);
                schilder.add(new Schild(yn, new String[] {nassName, zahl(lu.nass, 2, true) + " m außen (Modell)"}, ROT));
            }
            // Scheitel
            if (hw != null) {
                int ys = (int) yv.applyAsDouble(Math.max(ZU, Math.min(ZO, hw.scheitel())));
                g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
                g.setColor(Color.WHITE);
                g.drawLine(x0 - 4, ys, x1 + 4, ys);
                schilder.add(new Schild(ys, new String[] {"nächster Scheitel", zahl(hw.scheitel(), 2, true) + " m"}, hw.landunter() ? ROT : hw.ueberlauf() ? ORANGE : th.text));
            }
            // Schilder von oben nach unten, ohne Überlappung
            schilder.sort(java.util.Comparator.comparingDouble(Schild::y));
            g.setStroke(new BasicStroke(1f));
            Font f1 = new Font(Font.SANS_SERIF, Font.BOLD, 11), f2 = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
            int zh = 14, unten = Integer.MIN_VALUE, xs = xr + 16;
            List<double[]> lagen = new ArrayList<>();
            for (Schild sc : schilder) {
                double hoehe = sc.zeilen().length * zh;
                double y = Math.max(sc.y() - hoehe / 2, unten + 4);
                lagen.add(new double[] {y, hoehe});
                unten = (int) (y + hoehe);
            }
            // läuft es unten hinaus, von unten her zurückschieben
            double grenze = py1 + 30;
            for (int i = lagen.size() - 1; i >= 0; i--) {
                double[] l = lagen.get(i);
                if (l[0] + l[1] > grenze) l[0] = grenze - l[1];
                grenze = l[0] - 4;
            }
            for (int i = 0; i < schilder.size(); i++) {
                Schild sc = schilder.get(i);
                double y = lagen.get(i)[0];
                g.setColor(Thema.mitAlpha(th.leise, 150));
                g.drawLine(xr + 8, (int) sc.y(), xs - 3, (int) (y + zh / 2.0));
                for (int k = 0; k < sc.zeilen().length; k++) {
                    g.setFont(k == 0 ? f1 : f2);
                    g.setColor(k == 0 ? sc.farbe() : th.leise);
                    g.drawString(sc.zeilen()[k], xs, (int) (y + (k + 1) * zh - 3));
                }
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.leise);
            int ly = py1 + 50;
            for (String z : new String[] {"blau: Wasser jetzt", "weiß gestrichelt: nächster Scheitel", "orange: Wasser läuft über", "rot: Landunter, Fennen nass"}) {
                g.drawString(z, 10, ly);
                ly += 15;
            }
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ Warften

    final class Warftenliste extends JComponent {
        Warftenliste() { setPreferredSize(new Dimension(560, 330)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            Instant t = jetzt.get();
            double s = stau();
            Landunter.Hochwasser hw = lu.naechstes(t, s);
            if (hw == null) return;
            g.setFont(th.titel(16f));
            g.setColor(th.text);
            g.drawString("Warften beim nächsten Scheitel (" + zahl(hw.scheitel(), 2, true) + " m, " + UHR.format(hw.zeit().atZone(zone)) + " Uhr)", 10, 24);
            int[] sx = {14, 160, 220, 276, 334, 450};
            String[] kopf = {"Warft", "Kuppe", "Fuß", "Häuser", "über Wasser", "am Fuß"};
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(th.leise);
            for (int i = 0; i < kopf.length; i++) g.drawString(kopf[i], sx[i], 48);
            // seit Z2b: die Zeilen passen sich der Höhe an (15–22 Bildpunkte), der Hinweis steht in einer Zeile darunter
            int y = 52, i = 0, n = Math.max(1, lu.warften.size());
            int zh = (int) Math.max(15, Math.min(22, (h - 52 - 22) / (double) n)), fz = zh < 19 ? 11 : 12;
            if (lu.warften.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("Keine Warften (der Atlas ist nicht gerechnet).", sx[0], 76);
            }
            for (Landunter.Lage l : lu.lagen(hw.scheitel())) {
                if (y + zh > h - 20) break;
                if (i++ % 2 == 1) {
                    g.setColor(Thema.mitAlpha(th.flaeche, 160));
                    g.fillRect(8, y + 2, w - 16, zh);
                }
                int by = y + zh - (zh < 19 ? 3 : 4);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fz));
                g.setColor(th.text);
                g.drawString(l.warft().name(), sx[0], by);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, fz));
                g.drawString(zahl(l.warft().kuppe(), 2, false), sx[1], by);
                g.drawString(zahl(l.warft().fuss(), 2, false), sx[2], by);
                g.drawString(String.valueOf(l.warft().haeuser()), sx[3], by);
                g.setColor(l.kuppeUeber() < 1 ? ROT : th.text);
                g.drawString(l.kuppeUeber() >= 0 ? zahl(l.kuppeUeber(), 2, false) + " m" : zahl(-l.kuppeUeber(), 2, false) + " m unter", sx[4], by);
                if (l.amFuss() > 0) {
                    g.setColor(WASSER);
                    g.drawString(zahl(l.amFuss(), 2, false) + " m Wasser", sx[5], by);
                } else {
                    g.setColor(GRUEN);
                    g.drawString("trocken", sx[5], by);
                }
                y += zh;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.setColor(th.leise);
            umbrechen(g, "Höhen: Atlas (DGM1). Bei Landunter innen so hoch wie außen (Modell, ± 6 cm); danach bleibt das Wasser, bis die Siele es ablassen.", 14,
                    Math.min(h - 6, y + 16), w - 28);
            g.dispose();
        }
    }

    // ------------------------------------------------------------------ Messung am Bezug

    final class Messbild extends JComponent {
        final Color[] farben = {Color.WHITE, WASSER, GRUEN, ORANGE};

        Messbild() { setPreferredSize(new Dimension(560, 175)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            g.setFont(th.titel(14f));
            g.setColor(th.text);
            g.drawString("Windstau gemessen (Messung − Gezeit), 24 h", 10, 20);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            List<Pegelonline.Stau> je = staus;
            Pegelonline.Stau mit = gemessen();
            if (bezuege.isEmpty() || mit == null) {
                g.setColor(th.leise);
                g.drawString(bezuege.isEmpty() ? "Keine Konstanten der Pegel (einmal --gezeiten neu rechnen)." : laedt ? "lädt …" : "PEGELONLINE nicht erreichbar"
                        + (messFehler() != null ? ": " + messFehler() : ""), 14, 46);
                g.dispose();
                return;
            }
            // je Pegel der Windstau, gemittelt über 10 min; dazu das Mittel der Pegel
            Instant t1 = null;
            for (Pegelonline.Stau x : je) if (x != null && (t1 == null || x.stand().isAfter(t1))) t1 = x.stand();
            Instant t0 = t1.minusSeconds(24 * 3600);
            int nb = 144;
            double[][] r = new double[bezuege.size()][nb];
            double lo = -0.5, hi = 0.5;
            for (int i = 0; i < bezuege.size(); i++) {
                java.util.Arrays.fill(r[i], Double.NaN);
                Pegelonline.Stau x = i < je.size() ? je.get(i) : null;
                if (x == null) continue;
                Vorhersage v = bezuege.get(i).vorhersage();
                double[] sum = new double[nb];
                int[] cnt = new int[nb];
                for (Pegelonline.Messwert m : x.messung()) {
                    int k = (int) ((m.zeit().getEpochSecond() - t0.getEpochSecond()) / 600);
                    if (k < 0 || k >= nb) continue;
                    sum[k] += m.hoehe() - v.wert(m.zeit());
                    cnt[k]++;
                }
                for (int k = 0; k < nb; k++)
                    if (cnt[k] > 0) {
                        r[i][k] = sum[k] / cnt[k];
                        lo = Math.min(lo, r[i][k]);
                        hi = Math.max(hi, r[i][k]);
                    }
            }
            int px0 = 50, px1 = w - 14, py0 = 32, py1 = h - 40;
            double ylo = Math.floor(lo * 2) / 2, yhi = Math.ceil(hi * 2) / 2;
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (z - ylo) / (yhi - ylo) * (py1 - py0);
            java.util.function.IntToDoubleFunction xv = k -> px0 + (k + 0.5) / nb * (px1 - px0);
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            FontMetrics fm = g.getFontMetrics();
            double schritt = yhi - ylo > 2.5 ? 1 : 0.5;
            for (double z = Math.ceil(ylo / schritt) * schritt; z <= yhi + 1e-9; z += schritt) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Math.abs(z) < 1e-9 ? th.linie : Thema.mitAlpha(th.linie, 120));
                g.drawLine(px0, y, px1, y);
                g.setColor(th.leise);
                String l = Math.abs(z) < 1e-9 ? "0" : zahl(z, 1, true);
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            for (int k = 0; k <= nb; k += 36) {
                int x = (int) (px0 + (double) k / nb * (px1 - px0));
                g.setColor(th.leise);
                String l = UHR.format(t0.plusSeconds(600L * k).atZone(zone));
                g.drawString(l, Math.min(px1 - fm.stringWidth(l), Math.max(px0, x - fm.stringWidth(l) / 2)), py1 + 13);
            }
            // die Pegel dünn, das Mittel dick
            for (int i = 0; i < r.length; i++) {
                Path2D.Double pl = linie(r[i], xv, yv);
                g.setStroke(new BasicStroke(1.3f));
                g.setColor(Thema.mitAlpha(farben[i % farben.length], 200));
                g.draw(pl);
            }
            double[] mm = new double[nb];
            for (int k = 0; k < nb; k++) {
                double s0 = 0;
                int c = 0;
                for (double[] ri : r) if (!Double.isNaN(ri[k])) {
                    s0 += ri[k];
                    c++;
                }
                mm[k] = c == 0 ? Double.NaN : s0 / c;
            }
            if (r.length > 1) {
                g.setStroke(new BasicStroke(2.6f));
                g.setColor(th.marke);
                g.draw(linie(mm, xv, yv));
            }
            g.setColor(th.linie);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            // Legende: je Pegel der Windstau der letzten Stunde
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            int lx = px0;
            for (int i = 0; i < bezuege.size(); i++) {
                Pegelonline.Stau x = i < je.size() ? je.get(i) : null;
                String l = bezuege.get(i).kurz() + " " + (x == null ? "–" : zahl(x.meter(), 2, true) + " m");
                g.setColor(farben[i % farben.length]);
                g.fillRect(lx, h - 14, 14, 3);
                g.setColor(th.leise);
                g.drawString(l, lx + 18, h - 9);
                lx += 18 + g.getFontMetrics().stringWidth(l) + 16;
            }
            if (bezuege.size() > 1) {
                String l = "Mittel " + zahl(mit.meter(), 2, true) + " m (bis " + UHR.format(mit.stand().atZone(zone)) + ")";
                g.setColor(th.marke);
                g.fillRect(lx, h - 15, 14, 4);
                g.setColor(th.text);
                g.drawString(l, lx + 18, h - 9);
            }
            g.dispose();
        }

        Path2D.Double linie(double[] werte, java.util.function.IntToDoubleFunction xv, java.util.function.DoubleUnaryOperator yv) {
            Path2D.Double p = new Path2D.Double();
            boolean an = false;
            for (int k = 0; k < werte.length; k++) {
                if (Double.isNaN(werte[k])) {
                    an = false;
                    continue;
                }
                double x = xv.applyAsDouble(k), y = yv.applyAsDouble(werte[k]);
                if (an) p.lineTo(x, y);
                else p.moveTo(x, y);
                an = true;
            }
            return p;
        }
    }

    // ------------------------------------------------------------------ Ausblick

    final class Ausblick extends JComponent {
        static final int LINKS = 64, RECHTS = 24, OBEN = 40, UNTEN = 26;

        Ausblick() { setPreferredSize(new Dimension(800, 180)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            Instant t = jetzt.get();
            LocalDate d0 = t.atZone(zone).toLocalDate();
            Instant a = d0.atStartOfDay(zone).toInstant(), b = d0.plusDays(TAGE).atStartOfDay(zone).toInstant();
            double s = stau();
            List<Landunter.Hochwasser> l = lu.hochwasser(a, b, s);
            int n = 0, u = 0, k = 0;
            for (Landunter.Hochwasser x : l) {
                if (!x.zeit().isAfter(t)) continue;
                k++;
                if (x.landunter()) n++;
                else if (x.ueberlauf()) u++;
            }
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            g.drawString("Die nächsten " + TAGE + " Tage: Windstau, ab dem jedes Hochwasser Landunter bringt (Strich: über die Krone)", LINKS, 20);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            String sum = "Mit " + zahl(s, 2, true) + " m Windstau: Landunter bei " + n + " von " + k + " Hochwassern" + (u > 0 ? ", über die Krone bei " + u + " weiteren" : "");
            FontMetrics fb = g.getFontMetrics();
            g.setColor(n > 0 ? ROT : u > 0 ? ORANGE : GRUEN);
            g.drawString(sum, w - RECHTS - fb.stringWidth(sum), 20);
            int px0 = LINKS, px1 = w - RECHTS, py0 = OBEN, py1 = h - UNTEN;
            double ymax = 1.5;
            for (Landunter.Hochwasser x : l) ymax = Math.max(ymax, Math.ceil(x.noetigNass() * 2) / 2);
            ymax = Math.max(ymax, Math.ceil((s + 0.3) * 2) / 2);
            double ymin = Math.min(0, Math.floor(Math.min(s, 0) * 2) / 2);
            double ylo = ymin, yhi = ymax;
            java.util.function.DoubleUnaryOperator yv = z -> py1 - (z - ylo) / (yhi - ylo) * (py1 - py0);
            double dauer = b.getEpochSecond() - a.getEpochSecond();
            java.util.function.DoubleUnaryOperator xv = sec -> px0 + sec / dauer * (px1 - px0);
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            for (double z = Math.ceil(ylo * 2) / 2; z <= yhi + 1e-9; z += 0.5) {
                int y = (int) yv.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, 130));
                g.drawLine(px0, y, px1, y);
                g.setColor(th.leise);
                String lb = zahl(z, 1, true);
                g.drawString(lb, px0 - 8 - fm.stringWidth(lb), y + 4);
            }
            for (int d = 0; d <= TAGE; d++) {
                Instant ti = d0.plusDays(d).atStartOfDay(zone).toInstant();
                int x = (int) xv.applyAsDouble(ti.getEpochSecond() - a.getEpochSecond());
                g.setColor(Thema.mitAlpha(th.linie, 120));
                g.drawLine(x, py0, x, py1);
                if (d < TAGE) {
                    g.setColor(th.leise);
                    g.drawString(DateTimeFormatter.ofPattern("EE d.M.", Locale.GERMAN).format(d0.plusDays(d)), x + 4, py1 + 16);
                }
            }
            double bw = Math.max(3, (px1 - px0) / (TAGE * 2.2) * 0.6), y0 = yv.applyAsDouble(0);
            for (Landunter.Hochwasser x : l) {
                double xc = xv.applyAsDouble(x.zeit().getEpochSecond() - a.getEpochSecond()), y = yv.applyAsDouble(x.noetigNass()), yk = yv.applyAsDouble(x.noetigKrone());
                boolean vorbei = !x.zeit().isAfter(t);
                Color c = x.landunter() ? ROT : x.ueberlauf() ? ORANGE : WASSER;
                g.setColor(vorbei ? Thema.mitAlpha(c, 70) : c);
                g.fill(new Rectangle2D.Double(xc - bw / 2, Math.min(y, y0), bw, Math.abs(y0 - y)));
                g.setColor(vorbei ? Thema.mitAlpha(th.text, 90) : th.text);
                g.fill(new Rectangle2D.Double(xc - bw / 2 - 2, yk - 1, bw + 4, 2.5));
            }
            // Windstau jetzt
            int ys = (int) yv.applyAsDouble(s);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {7f, 5f}, 0f));
            g.setColor(th.marke);
            g.drawLine(px0, ys, px1, ys);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            String wl = "Windstau " + zahl(s, 2, true) + " m";
            int wy = Math.max(py0 + 14, ys - 6), ww = g.getFontMetrics().stringWidth(wl);
            g.setColor(Thema.mitAlpha(th.grund, 210));
            g.fillRect(px1 - ww - 14, wy - 12, ww + 8, 16);
            g.setColor(th.marke);
            g.drawString(wl, px1 - ww - 10, wy);
            // jetzt
            int xj = (int) xv.applyAsDouble(t.getEpochSecond() - a.getEpochSecond());
            g.setStroke(new BasicStroke(1.4f));
            g.setColor(th.text);
            g.drawLine(xj, py0, xj, py1);
            g.setColor(th.linie);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            g.setColor(th.leise);
            g.drawString("m", 8, py0 + 10);
            g.dispose();
        }
    }

    /** Der Hinweis unter den Knöpfen, umbrochen auf die Breite. */
    static final class Hinweiszeile extends JComponent {
        final String text;

        Hinweiszeile(String text) {
            this.text = text;
            setPreferredSize(new Dimension(800, 44));
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            g.setColor(th.grund);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.setColor(th.leise);
            FontMetrics fm = g.getFontMetrics();
            int y = fm.getAscent() + 2;
            StringBuilder z = new StringBuilder();
            for (String w : text.split("\\s+")) {
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
}
