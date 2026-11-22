package com.dan.steuerhaus.jahr;

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
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Der Inhalt des Fensters „Ein Jahr in einer Minute“ (seit Y3, Werkbuch HalligHooge 154): oben die Karte (Differenz
 * oder Höhe des Bodens, der Kasten des Sands mit der Linie der Schwelle zu Beginn und jetzt, die Linie des Profils mit
 * zwei Griffen), rechts die Zahlen des Sands und die Legende, darunter das Profil (Höhe zu Beginn und jetzt, darunter die
 * Änderung), das Band des Jahrs (Abschnitte nach ihrer Spielzeit) und eine Kurve über das Jahr. Tasten: Leertaste,
 * ← → (ein Bild), Pos1, Ende, 1 2 (Darstellung), + − (Skala), Esc (schließt, siehe {@link Jahresfenster}).
 */
public final class Jahresansicht extends JPanel {
    /** Was die Karte zeigt. */
    public enum Darstellung {
        DIFFERENZ("Differenz"), HOEHE("Höhe");
        public final String name;

        Darstellung(String n) { name = n; }

        @Override public String toString() { return name; }
    }

    /** Was die Kurve über das Jahr zeigt. */
    public enum Groesse {
        OST("Sand: Schwerpunkt nach Osten", "Sand: nach Osten", "m"), FLAECHE("Sand: Fläche über der Schwelle", "Sand: Fläche", "km²"),
        VOLUMEN("Sand: Volumen über der Schwelle seit Beginn", "Sand: Volumen", "m³"), AUFTRAG("Auftrag im Fenster seit Beginn", "Auftrag", "Mio. m³"),
        ABTRAG("Abtrag im Fenster seit Beginn", "Abtrag", "Mio. m³"), GROESSTE("Größte Änderung im Fenster", "Größte Änderung", "m");
        /** Titel der Kurve, Eintrag der Liste („Sand“ wird der Name des Sands), Einheit. */
        public final String name, kurz, einheit;

        Groesse(String n, String k, String e) {
            name = n;
            kurz = k;
            einheit = e;
        }

        @Override public String toString() { return name; }
    }

    /** Spielzeiten des Jahrs (s) [G]; Vorgabe eine Minute. */
    public static final double[] DAUER = {30, 60, 120};
    static final String[] DAUER_TEXT = {"30 s", "1 min", "2 min"};
    /** Ränder von Band und Kurve: gleiche x-Achse. */
    static final int LINKS = 64, RECHTS = 24;
    /** Abstand der Proben im Profil (m) [G]. */
    static final double PROFIL_SCHRITT = 10;

    public final Jahresmappe m;
    final double[] anteil;
    final String hinweis, sandName;
    final ZoneId zone;
    final File fotos;
    final Karte karte = new Karte();
    final Profil profilBild = new Profil();
    final Band band = new Band();
    final Kurven kurven = new Kurven();
    final Zahlen zahlen = new Zahlen();
    final FButton spielen = new FButton("Abspielen"), zurueck = new FButton("« Zurück"), vor = new FButton("Vor »"), imBildKnopf = new FButton("Im Bild zeigen"),
            sichern = new FButton("Bild sichern");
    final FComboBox dauer = new FComboBox(DAUER_TEXT), darstellung = new FComboBox(Darstellung.values()), skala, groesse;
    final JLabel stand = new JLabel(" ");
    private final Timer takt;
    private final ExecutorService vorleser = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Jahr: Bilder lesen");
        t.setDaemon(true);
        return t;
    });
    /** Ruft die App: Szenario und Zeit des Abschnitts zum Tag jetzt (null: Knopf aus). */
    public BiConsumer<String, Instant> imBild;
    /** Nach „Bild sichern“: die Datei. */
    public Consumer<File> gesichert = f -> { };
    /** Gemalte Bilder der Karte (für die Probe). */
    public volatile long gemalt;

    private Zeitplan plan;
    private double tag, sek;
    private long letzte;
    private final float[] dz;
    private float[] grund;
    private String fehler;
    private BufferedImage karteGrau;
    private final BufferedImage ueber;
    private final int[] px;
    private boolean ueberAlt = true;
    private double[] profil;
    private List<double[]> linie0 = List.of(), linieJetzt = List.of();
    private final int[] kastenZellen;
    /** Abstand im Profil unter der Maus (m; NaN: keiner). */
    private double zeigerD = Double.NaN;

    public Jahresansicht(Jahresmappe m, double[] anteil, String hinweis, String sandName, ZoneId zone, File fotos) {
        super(new BorderLayout());
        this.m = m;
        this.anteil = anteil;
        this.hinweis = hinweis == null ? "" : hinweis;
        this.sandName = sandName == null || sandName.isBlank() ? "Sand" : sandName;
        this.zone = zone;
        this.fotos = fotos;
        dz = new float[m.zellen()];
        px = new int[m.zellen()];
        ueber = new BufferedImage(m.nx, m.ny, BufferedImage.TYPE_INT_ARGB);
        kastenZellen = Jahresbild.bereich(m);
        profil = m.profil != null && m.profil.length >= 4 ? m.profil.clone() : new double[] {m.x0 + 0.1 * (m.ost1() - m.x0), (m.y0 + m.nord1()) / 2, m.ost1() - 0.1 * (m.ost1() - m.x0), (m.y0 + m.nord1()) / 2};
        String[] sk = new String[Jahresbild.SKALEN.length];
        for (int i = 0; i < sk.length; i++) sk[i] = "± " + kurz(Jahresbild.SKALEN[i]) + " m";
        skala = new FComboBox(sk);
        String[] gk = new String[Groesse.values().length];
        for (Groesse x : Groesse.values()) gk[x.ordinal()] = x.kurz.replace("Sand", this.sandName);
        groesse = new FComboBox(gk);
        skala.setSelectedIndex(Jahresbild.SKALA_VORGABE);
        dauer.setSelectedIndex(1);
        plan = new Zeitplan(m.abschnitte, anteil, DAUER[1]);
        try {
            grund = m.grund();
            linie0 = Jahresbild.linie(m, grund, null, m.schwelle, kastenZellen);
            if (m.karte().isFile()) karteGrau = Jahresbild.grau(ImageIO.read(m.karte()));
        } catch (IOException x) {
            fehler = x.getMessage();
        }
        takt = new Timer(16, e -> schritt());
        for (JComponent c : new JComponent[] {spielen, zurueck, vor, imBildKnopf, sichern, dauer, darstellung, skala, groesse}) c.setFocusable(false);
        for (FComboBox c : new FComboBox[] {dauer, darstellung, skala, groesse}) c.setLightWeightPopupEnabled(false);
        dauer.setToolTipText("Spielzeit des ganzen Jahrs");
        skala.setToolTipText("Skala der Differenzkarte und der Balken im Profil (+ −)");
        groesse.setToolTipText("Was die Kurve über das Jahr zeigt");
        for (FButton b : new FButton[] {zurueck, vor, imBildKnopf, sichern}) b.setVariant(FButtonVariant.GHOST);
        spielen.addActionListener(e -> spiele(!takt.isRunning()));
        zurueck.setToolTipText("Ein Bild zurück (Pfeil links)");
        vor.setToolTipText("Ein Bild vor (Pfeil rechts)");
        imBildKnopf.setToolTipText("Das Szenario dieses Abschnitts im Hauptfenster, zur Zeit im Lauf, die zu diesem Tag gehört");
        zurueck.addActionListener(e -> schrittBild(-1));
        vor.addActionListener(e -> schrittBild(1));
        imBildKnopf.addActionListener(e -> imBildZeigen());
        sichern.addActionListener(e -> sichern());
        dauer.addActionListener(e -> dauer(DAUER[Math.max(0, dauer.getSelectedIndex())]));
        darstellung.addActionListener(e -> {
            ueberAlt = true;
            karte.repaint();
        });
        skala.addActionListener(e -> {
            ueberAlt = true;
            repaint();
        });
        groesse.addActionListener(e -> kurven.repaint());

        JPanel knoepfe = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        knoepfe.setOpaque(false);
        for (JComponent c : new JComponent[] {spielen, zurueck, vor, dauer, darstellung, skala, groesse, imBildKnopf, sichern}) knoepfe.add(c);
        Faerber.rolle(stand, "leise");
        stand.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        JPanel leiste = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        leiste.add(knoepfe, BorderLayout.WEST);
        JPanel rechts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 12));
        rechts.setOpaque(false);
        rechts.add(stand);
        leiste.add(rechts, BorderLayout.CENTER);

        JPanel oben = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        oben.add(karte, BorderLayout.CENTER);
        oben.add(zahlen, BorderLayout.EAST);
        JPanel unten = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        JPanel zeit = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        zeit.add(band, BorderLayout.NORTH);
        zeit.add(kurven, BorderLayout.CENTER);
        unten.add(profilBild, BorderLayout.NORTH);
        unten.add(zeit, BorderLayout.CENTER);
        unten.add(leiste, BorderLayout.SOUTH);
        Faerber.rolle(this, "grund");
        add(oben, BorderLayout.CENTER);
        add(unten, BorderLayout.SOUTH);
        tasten();
        zeige(0);
    }

    private void tasten() {
        Object[][] t = {
                {"SPACE", (Runnable) () -> spiele(!takt.isRunning())},
                {"LEFT", (Runnable) () -> schrittBild(-1)}, {"RIGHT", (Runnable) () -> schrittBild(1)},
                {"HOME", (Runnable) () -> zeige(0)}, {"END", (Runnable) () -> zeige(m.tage())},
                {"1", (Runnable) () -> darstellung(Darstellung.DIFFERENZ)}, {"2", (Runnable) () -> darstellung(Darstellung.HOEHE)},
                {"typed +", (Runnable) () -> skala(skala.getSelectedIndex() + 1)}, {"typed -", (Runnable) () -> skala(skala.getSelectedIndex() - 1)}};
        for (Object[] x : t) {
            String n = "jahr." + x[0];
            KeyStroke ks = KeyStroke.getKeyStroke((String) x[0]);
            if (ks == null) continue;
            getInputMap(WHEN_IN_FOCUSED_WINDOW).put(ks, n);
            Runnable r = (Runnable) x[1];
            getActionMap().put(n, new AbstractAction() {
                @Override public void actionPerformed(ActionEvent e) { r.run(); }
            });
        }
    }

    // ------------------------------------------------------------------ Steuern

    /** Den Modelltag zeigen (begrenzt); die Spielzeit folgt. */
    public void zeige(double t) {
        tag = Math.max(0, Math.min(m.tage(), t));
        sek = plan.sekunde(tag);
        laden();
    }

    private void laden() {
        if (fehler == null && !m.bilder.isEmpty()) {
            try {
                m.boden(tag, dz);
                linieJetzt = Jahresbild.linie(m, grund, dz, m.schwelle, kastenZellen);
            } catch (IOException x) {
                fehler = x.getMessage();
            }
        }
        ueberAlt = true;
        vorlesen();
        standText();
        repaint();
    }

    public double tag() { return tag; }

    public double spielzeit() { return sek; }

    public Zeitplan plan() { return plan; }

    public boolean spielt() { return takt.isRunning(); }

    /** Abspielen an oder aus; am Ende beginnt es von vorn. */
    public void spiele(boolean an) {
        if (an) {
            if (sek >= plan.gesamt - 1e-6) zeige(0);
            letzte = System.nanoTime();
            takt.start();
        } else takt.stop();
        spielen.setText(an ? "Halt" : "Abspielen");
        vorlesen();
    }

    private void schritt() {
        long jetzt = System.nanoTime();
        double d = (jetzt - letzte) / 1e9;
        letzte = jetzt;
        sek = Math.min(plan.gesamt, sek + Math.min(0.25, d));
        tag = plan.tag(sek);
        laden();
        if (sek >= plan.gesamt - 1e-9) spiele(false);
    }

    /** Ein Bild der Mappe vor (+1) oder zurück (−1). */
    void schrittBild(int r) {
        int k = m.vor(tag);
        double tk = m.tag(k);
        int ziel = r > 0 ? k + 1 : (tag > tk + 1e-6 ? k : k - 1);
        zeige(m.tag(Math.max(0, Math.min(m.bilder.size() - 1, ziel))));
    }

    /** Die Spielzeit des Jahrs (s). */
    public void dauer(double s) {
        double t = tag;
        plan = new Zeitplan(m.abschnitte, anteil, s);
        sek = plan.sekunde(t);
        for (int i = 0; i < DAUER.length; i++) if (Math.abs(DAUER[i] - s) < 1e-9 && dauer.getSelectedIndex() != i) dauer.setSelectedIndex(i);
        band.repaint();
        kurven.repaint();
        standText();
    }

    public void darstellung(Darstellung d) {
        if (darstellung.getSelectedItem() != d) darstellung.setSelectedItem(d);
        ueberAlt = true;
        karte.repaint();
    }

    public Darstellung darstellung() { return (Darstellung) darstellung.getSelectedItem(); }

    public void skala(int i) {
        int k = Math.max(0, Math.min(Jahresbild.SKALEN.length - 1, i));
        if (skala.getSelectedIndex() != k) skala.setSelectedIndex(k);
        ueberAlt = true;
        repaint();
    }

    public double skala() { return Jahresbild.SKALEN[Math.max(0, skala.getSelectedIndex())]; }

    public void groesse(Groesse g) { groesse.setSelectedIndex(g.ordinal()); }

    public Groesse groesse() { return Groesse.values()[Math.max(0, groesse.getSelectedIndex())]; }

    /** Die Linie des Profils (Ost, Nord, Ost, Nord). */
    public void profil(double[] p) {
        profil = p.clone();
        repaint();
    }

    public double[] profil() { return profil.clone(); }

    /** Hält das Abspielen an und gibt den Leser frei. */
    public void ende() {
        takt.stop();
        vorleser.shutdownNow();
    }

    /** Liest die nächsten Bilder im Hintergrund (beim Abspielen vier, sonst eins). */
    private void vorlesen() {
        if (m.bilder.isEmpty()) return;
        int k = m.vor(tag), bis = Math.min(m.bilder.size() - 1, k + (takt.isRunning() ? 4 : 1));
        for (int i = k; i <= bis; i++) {
            if (m.da(i)) continue;
            int n = i;
            try {
                vorleser.submit(() -> {
                    try {
                        m.boden(n);
                    } catch (IOException x) {
                        // die Karte meldet es beim Lesen
                    }
                });
            } catch (java.util.concurrent.RejectedExecutionException x) {
                return;
            }
        }
    }

    private void imBildZeigen() {
        if (imBild == null || m.abschnitte.isEmpty()) return;
        Jahresmappe.Abschnitt a = m.abschnitte.get(m.abschnitt(tag));
        spiele(false);
        imBild.accept(a.szenario(), m.zeit(tag));
    }

    private void standText() {
        if (fehler != null) {
            stand.setText("Jahr: " + fehler);
            return;
        }
        if (m.bilder.isEmpty()) {
            stand.setText("Die Mappe hat keine Bilder.");
            return;
        }
        // Bild und Spielzeit stehen rechts im Band; die Zeile unten bleibt für Meldungen (Bild gesichert)
        if (!stand.getText().startsWith("Gesichert") || takt.isRunning()) stand.setText(" ");
        imBildKnopf.setEnabled(imBild != null);
    }

    static String uhr(double s) {
        int g = (int) Math.floor(s + 1e-6);
        return g / 60 + ":" + String.format(Locale.ROOT, "%02d", g % 60);
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
        File f = new File(fotos, String.format(Locale.ROOT, "Jahr_Tag%03d_%s_%s.png", (int) Math.round(tag), darstellung() == Darstellung.HOEHE ? "Hoehe" : "Differenz", stempel));
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

    /** Die Änderung jetzt in der Zelle (m) und die Höhe (m NHN); für Prüfungen. */
    public float dz(int zelle) { return dz[zelle]; }

    /** Farbe der Karte (ARGB des Gitters, Zeilen von Nord) jetzt; für Prüfungen. */
    public int[] gitterBild() {
        aktualisieren();
        return px.clone();
    }

    /** Die Proben des Profils jetzt: je Probe {Abstand m, Höhe zu Beginn, Höhe jetzt}. */
    public double[][] profilProben() {
        double lx = profil[2] - profil[0], ly = profil[3] - profil[1], len = Math.hypot(lx, ly);
        int n = Math.max(2, (int) Math.round(len / PROFIL_SCHRITT) + 1);
        double[][] r = new double[n][];
        for (int i = 0; i < n; i++) {
            double f = i / (double) (n - 1), x = profil[0] + f * lx, y = profil[1] + f * ly;
            try {
                r[i] = new double[] {f * len, m.hoehe(null, x, y), m.hoehe(dz, x, y)};
            } catch (IOException e) {
                r[i] = new double[] {f * len, Double.NaN, Double.NaN};
            }
        }
        return r;
    }

    private void aktualisieren() {
        if (!ueberAlt || grund == null) return;
        if (darstellung() == Darstellung.HOEHE) Jahresbild.hoehe(grund, dz, m.nx, m.ny, m.dx, px);
        else Jahresbild.differenz(dz, skala(), m.nx, m.ny, px);
        ueber.setRGB(0, 0, m.nx, m.ny, px, 0, m.nx);
        ueberAlt = false;
    }

    // ------------------------------------------------------------------ Texte

    static String zahl(double v, int stellen, boolean vorzeichen) {
        if (Double.isNaN(v)) return "–";
        return String.format(Locale.GERMAN, "%" + (vorzeichen ? "+" : "") + "." + stellen + "f", v).replace('-', '−');
    }

    /** Zahl ohne Nullen am Ende („0,25“, „1“). */
    static String kurz(double v) {
        return new java.math.BigDecimal(Double.toString(v)).stripTrailingZeros().toPlainString().replace('.', ',').replace('-', '−');
    }

    /** Ganze Zahl mit Tausenderpunkt und Vorzeichen. */
    static String ganz(double v, boolean vorzeichen) {
        if (Double.isNaN(v)) return "–";
        return String.format(Locale.GERMAN, "%" + (vorzeichen ? "+" : "") + ",.0f", v).replace('-', '−');
    }

    /** „14.–16.10.2026“ für die Zeit [von, bis) im Archiv (Tage in UTC wie die Läufe). */
    String zeitraum(Instant von, Instant bis) {
        ZonedDateTime a = von.atZone(java.time.ZoneOffset.UTC), b = bis.minusSeconds(1).atZone(java.time.ZoneOffset.UTC);
        if (a.toLocalDate().equals(b.toLocalDate())) return DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN).format(a);
        if (a.getMonth() == b.getMonth()) return a.getDayOfMonth() + ".–" + DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN).format(b);
        return DateTimeFormatter.ofPattern("d.M.", Locale.GERMAN).format(a) + "–" + DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN).format(b);
    }

    String abschnittText(Jahresmappe.Abschnitt a) {
        return a.name() + " · Wetter " + zeitraum(a.von(), a.bis()) + ", Boden × " + zahl(a.faktor(), 0, false);
    }

    // ------------------------------------------------------------------ Karte

    final class Karte extends JComponent {
        private int griff = -1;
        private double[] ziehVon;
        private int mausX = -1, mausY = -1;

        Karte() {
            setPreferredSize(new Dimension(960, 540));
            setFocusable(true);
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    Rectangle2D r = rahmen(getWidth(), getHeight());
                    double[] a = bp(r, profil[0], profil[1]), b = bp(r, profil[2], profil[3]);
                    if (Math.hypot(e.getX() - a[0], e.getY() - a[1]) < 12) griff = 0;
                    else if (Math.hypot(e.getX() - b[0], e.getY() - b[1]) < 12) griff = 1;
                    else if (Line2D.ptSegDist(a[0], a[1], b[0], b[1], e.getX(), e.getY()) < 7) {
                        griff = 2;
                        ziehVon = new double[] {e.getX(), e.getY(), profil[0], profil[1], profil[2], profil[3]};
                    } else griff = -1;
                    if (e.getClickCount() == 2 && griff < 0 && m.profil != null && m.profil.length >= 4) profil(m.profil);
                }

                @Override public void mouseDragged(MouseEvent e) {
                    if (griff < 0) return;
                    Rectangle2D r = rahmen(getWidth(), getHeight());
                    double[] w = welt(r, e.getX(), e.getY());
                    w[0] = Math.max(m.x0, Math.min(m.ost1(), w[0]));
                    w[1] = Math.max(m.y0, Math.min(m.nord1(), w[1]));
                    if (griff < 2) {
                        profil[2 * griff] = w[0];
                        profil[2 * griff + 1] = w[1];
                    } else {
                        double sx = (m.ost1() - m.x0) / r.getWidth(), sy = (m.nord1() - m.y0) / r.getHeight();
                        double ddx = (e.getX() - ziehVon[0]) * sx, ddy = -(e.getY() - ziehVon[1]) * sy;
                        for (int k = 0; k < 2; k++) {
                            profil[2 * k] = ziehVon[2 + 2 * k] + ddx;
                            profil[2 * k + 1] = ziehVon[3 + 2 * k] + ddy;
                        }
                    }
                    mausX = e.getX();
                    mausY = e.getY();
                    Jahresansicht.this.repaint();
                }

                @Override public void mouseReleased(MouseEvent e) { griff = -1; }

                @Override public void mouseMoved(MouseEvent e) {
                    mausX = e.getX();
                    mausY = e.getY();
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    mausX = -1;
                    repaint();
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        /** Das Rechteck der Mappe in der Fläche (Seitenverhältnis des Gitters). */
        Rectangle2D rahmen(int w, int h) {
            double ar = (m.ost1() - m.x0) / (m.nord1() - m.y0), s = Math.min((w - 16) / ar, h - 16.0);
            double rw = s * ar, rh = s;
            return new Rectangle2D.Double(Math.round((w - rw) / 2), Math.round((h - rh) / 2), Math.round(rw), Math.round(rh));
        }

        double[] bp(Rectangle2D r, double ost, double nord) {
            return new double[] {r.getX() + (ost - m.x0) / (m.ost1() - m.x0) * r.getWidth(), r.getY() + (m.nord1() - nord) / (m.nord1() - m.y0) * r.getHeight()};
        }

        double[] welt(Rectangle2D r, double x, double y) {
            return new double[] {m.x0 + (x - r.getX()) / r.getWidth() * (m.ost1() - m.x0), m.nord1() - (y - r.getY()) / r.getHeight() * (m.nord1() - m.y0)};
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight());
            g.dispose();
            gemalt++;
        }

        void male(Graphics2D g, int w, int h) {
            Thema th = Thema.aktuell();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(th.tief);
            g.fillRect(0, 0, w, h);
            if (fehler != null || grund == null) {
                g.setColor(th.leise);
                g.drawString("Jahr: " + (fehler != null ? fehler : "kein Grund"), 20, 30);
                return;
            }
            Rectangle2D r = rahmen(w, h);
            int rx = (int) r.getX(), ry = (int) r.getY(), rw = (int) r.getWidth(), rh = (int) r.getHeight();
            aktualisieren();
            if (darstellung() == Darstellung.DIFFERENZ) {
                if (karteGrau != null) g.drawImage(karteGrau, rx, ry, rw, rh, null);
                else {
                    g.setColor(th.flaeche);
                    g.fillRect(rx, ry, rw, rh);
                }
            }
            g.drawImage(ueber, rx, ry, rw, rh, null);
            g.setColor(th.linie);
            g.drawRect(rx, ry, rw - 1, rh - 1);
            Shape alt = g.getClip();
            g.clipRect(rx, ry, rw, rh);
            // Kasten des Sands
            if (m.kasten != null && m.kasten.length >= 4) {
                double[] a = bp(r, m.kasten[0], m.kasten[3]), b = bp(r, m.kasten[2], m.kasten[1]);
                g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
                g.setColor(Thema.mitAlpha(Color.WHITE, 110));
                g.draw(new Rectangle2D.Double(a[0], a[1], b[0] - a[0], b[1] - a[1]));
            }
            // Linie der Schwelle: zu Beginn gestrichelt, jetzt durchgezogen
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 3f}, 0f));
            g.setColor(Thema.mitAlpha(Color.WHITE, 215));
            g.draw(Jahresbild.pfad(m, linie0, r.getX(), r.getY(), r.getWidth(), r.getHeight()));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 196, 80));
            g.draw(Jahresbild.pfad(m, linieJetzt, r.getX(), r.getY(), r.getWidth(), r.getHeight()));
            // Namen
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            for (Jahresmappe.Ort o : m.orte) {
                double[] p = bp(r, o.ost(), o.nord());
                String t = o.name();
                int x = (int) Math.round(p[0] - fm.stringWidth(t) / 2.0), y = (int) Math.round(p[1] + fm.getAscent() / 2.0);
                g.setColor(Thema.mitAlpha(Color.BLACK, 170));
                g.drawString(t, x + 1, y + 1);
                g.setColor(new Color(240, 240, 232));
                g.drawString(t, x, y);
            }
            // Profil
            double[] a = bp(r, profil[0], profil[1]), b = bp(r, profil[2], profil[3]);
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(Thema.mitAlpha(Color.BLACK, 120));
            g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(th.text);
            g.draw(new Line2D.Double(a[0], a[1], b[0], b[1]));
            for (double[] p : new double[][] {a, b}) {
                g.setColor(Thema.mitAlpha(Color.BLACK, 150));
                g.fillOval((int) p[0] - 7, (int) p[1] - 7, 14, 14);
                g.setColor(th.marke);
                g.fillOval((int) p[0] - 5, (int) p[1] - 5, 10, 10);
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            g.setColor(th.text);
            g.drawString("Profil", (int) a[0] + 9, (int) a[1] - 8);
            if (!Double.isNaN(zeigerD)) {
                double len = Math.hypot(profil[2] - profil[0], profil[3] - profil[1]), f = len > 0 ? zeigerD / len : 0;
                double[] z = bp(r, profil[0] + f * (profil[2] - profil[0]), profil[1] + f * (profil[3] - profil[1]));
                g.setColor(Color.WHITE);
                g.fillOval((int) z[0] - 4, (int) z[1] - 4, 8, 8);
            }
            g.setClip(alt);
            massstab(g, r);
            schild(g, r);
            if (mausX >= 0 && r.contains(mausX, mausY)) zeiger(g, r);
        }

        private void massstab(Graphics2D g, Rectangle2D r) {
            double km = 2000, px = km / (m.ost1() - m.x0) * r.getWidth();
            int x = (int) r.getX() + 14, y = (int) (r.getY() + r.getHeight()) - 16;
            g.setStroke(new BasicStroke(1f));
            g.setColor(Thema.mitAlpha(Color.BLACK, 150));
            g.fillRect(x - 6, y - 20, (int) px + 12, 28);
            g.setColor(Color.WHITE);
            g.fillRect(x, y - 3, (int) (px / 2), 4);
            g.drawRect(x, y - 3, (int) px, 4);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString("0", x - 2, y - 7);
            g.drawString("2 km", x + (int) px - 22, y - 7);
            // Nord
            int nx = (int) (r.getX() + r.getWidth()) - 24, ny = (int) r.getY() + 20;
            g.setColor(Thema.mitAlpha(Color.BLACK, 150));
            g.fillOval(nx - 13, ny - 13, 26, 26);
            g.setColor(Color.WHITE);
            g.fillPolygon(new int[] {nx, nx - 5, nx + 5}, new int[] {ny - 9, ny + 3, ny + 3}, 3);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            g.drawString("N", nx - 3, ny + 11);
        }

        /** Schild links oben: Tag, Abschnitt, Zeit im Lauf. */
        private void schild(Graphics2D g, Rectangle2D r) {
            if (m.abschnitte.isEmpty()) return;
            Thema th = Thema.aktuell();
            Jahresmappe.Abschnitt a = m.abschnitte.get(m.abschnitt(tag));
            Font f1 = th.titel(20f), f2 = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
            String z1 = "Tag " + (int) Math.floor(tag + 1e-6) + " von " + (int) Math.round(m.tage()), z2 = abschnittText(a),
                    z3 = "Im Lauf: " + DateTimeFormatter.ofPattern("EE d.M.yyyy, HH:mm 'Uhr'", Locale.GERMAN).format(m.zeit(tag).atZone(zone));
            g.setFont(f1);
            FontMetrics m1 = g.getFontMetrics();
            g.setFont(f2);
            FontMetrics m2 = g.getFontMetrics();
            int bw = Math.max(m1.stringWidth(z1), Math.max(m2.stringWidth(z2), m2.stringWidth(z3))) + 26, bh = m1.getHeight() + 2 * m2.getHeight() + 14;
            int x = (int) r.getX() + 12, y = (int) r.getY() + 12;
            g.setColor(Thema.mitAlpha(th.tief, 200));
            g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 10, 10));
            g.setColor(th.marke);
            g.fillRect(x, y + 6, 3, bh - 12);
            int tx = x + 13, ty = y + 6 + m1.getAscent();
            g.setFont(f1);
            g.setColor(th.text);
            g.drawString(z1, tx, ty);
            g.setFont(f2);
            ty += m2.getHeight() + 2;
            g.drawString(z2, tx, ty);
            ty += m2.getHeight();
            g.setColor(th.leise);
            g.drawString(z3, tx, ty);
        }

        /** Unter der Maus: Ort, Höhe zu Beginn und jetzt, Änderung. */
        private void zeiger(Graphics2D g, Rectangle2D r) {
            Thema th = Thema.aktuell();
            double[] w = welt(r, mausX, mausY);
            int c = m.zelle(w[0], w[1]);
            if (c < 0) return;
            double z0 = grund[c], d = dz[c];
            List<String> z = List.of(String.format(Locale.GERMAN, "O %.2f km · N %.2f km", w[0] / 1000, w[1] / 1000),
                    "Boden jetzt " + zahl(z0 + (Double.isNaN(d) ? 0 : d), 2, true) + " m NHN (Beginn " + zahl(z0, 2, true) + ")",
                    "Änderung seit Beginn " + zahl(d, 2, true) + " m");
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            int bw = 0;
            for (String s : z) bw = Math.max(bw, fm.stringWidth(s));
            bw += 16;
            int bh = z.size() * fm.getHeight() + 8;
            int x = mausX + 16 + bw < r.getX() + r.getWidth() ? mausX + 16 : mausX - 16 - bw, y = Math.max((int) r.getY() + 4, mausY - bh - 10);
            g.setColor(Thema.mitAlpha(th.tief, 220));
            g.fillRoundRect(x, y, bw, bh, 8, 8);
            g.setColor(th.text);
            for (int i = 0; i < z.size(); i++) g.drawString(z.get(i), x + 8, y + 4 + fm.getAscent() + i * fm.getHeight());
        }
    }

    // ------------------------------------------------------------------ Zahlen und Legende

    final class Zahlen extends JComponent {
        Zahlen() { setPreferredSize(new Dimension(400, 540)); }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, h);
            int x = 14, y = 12, bw = w - 26;
            Font kopf = th.titel(15f), text = new Font(Font.SANS_SERIF, Font.PLAIN, 12), fett = new Font(Font.SANS_SERIF, Font.BOLD, 12);
            Jahresmappe.Bild b0 = m.bilder.isEmpty() ? null : m.bilder.get(0);
            // Karte: der Sand
            List<String[]> sand = new ArrayList<>();
            if (b0 != null) {
                double fl = m.kennzahl(Jahresmappe.Kennzahl.FLAECHE, tag), ost = m.kennzahl(Jahresmappe.Kennzahl.OST, tag) - b0.ost(),
                        nord = m.kennzahl(Jahresmappe.Kennzahl.NORD, tag) - b0.nord(), ostV = m.kennzahl(Jahresmappe.Kennzahl.OST_V, tag) - b0.ostV(),
                        vol = m.kennzahl(Jahresmappe.Kennzahl.VOLUMEN, tag) - b0.volumen();
                sand.add(new String[] {"Fläche", zahl(fl / 1e6, 3, false) + " km² (Beginn " + zahl(b0.flaeche() / 1e6, 3, false) + ")"});
                sand.add(new String[] {"Schwerpunkt", zahl(ost, 1, true) + " m nach Osten, " + zahl(nord, 1, true) + " m nach Norden"});
                sand.add(new String[] {"mit der Höhe", zahl(ostV, 1, true) + " m nach Osten"});
                sand.add(new String[] {"Volumen", ganz(vol, true) + " m³ über der Schwelle"});
            }
            y = karte(g, x, y, bw, sandName + " über " + zahl(m.schwelle, 1, true) + " m NHN im Kasten", sand, kopf, text, fett);
            List<String[]> fenster = new ArrayList<>();
            if (b0 != null) {
                fenster.add(new String[] {"Auftrag", zahl(m.kennzahl(Jahresmappe.Kennzahl.AUFTRAG, tag) / 1e6, 2, false) + " Mio. m³"});
                fenster.add(new String[] {"Abtrag", zahl(m.kennzahl(Jahresmappe.Kennzahl.ABTRAG, tag) / 1e6, 2, false) + " Mio. m³"});
                fenster.add(new String[] {"größte", zahl(m.kennzahl(Jahresmappe.Kennzahl.GROESSTE, tag), 2, false) + " m Änderung in einer Zelle"});
            }
            y = karte(g, x, y + 10, bw, "Im ganzen Fenster seit Beginn", fenster, kopf, text, fett);
            y = legende(g, x, y + 14, bw);
            // Hinweis
            if (!hinweis.isEmpty()) {
                g.setFont(text);
                g.setColor(th.leise);
                FontMetrics fm = g.getFontMetrics();
                y += 14;
                for (String zeile : umbrechen(hinweis, fm, bw)) {
                    if (y + fm.getHeight() > h - 6) break;
                    y += fm.getHeight();
                    g.drawString(zeile, x, y);
                }
            }
            g.dispose();
        }

        private int karte(Graphics2D g, int x, int y, int bw, String titel, List<String[]> zeilen, Font kopf, Font text, Font fett) {
            Thema th = Thema.aktuell();
            g.setFont(fett);
            int spalte = 0;
            for (String[] z : zeilen) spalte = Math.max(spalte, g.getFontMetrics().stringWidth(z[0]));
            g.setFont(kopf);
            FontMetrics km = g.getFontMetrics();
            List<String> tz = umbrechen(titel, km, bw - 22);
            g.setFont(text);
            FontMetrics fm = g.getFontMetrics();
            int kopfH = 8 + tz.size() * km.getHeight();
            int bh = kopfH + zeilen.size() * (fm.getHeight() + 2) + 10;
            g.setColor(th.flaeche);
            g.fill(new RoundRectangle2D.Double(x, y, bw, bh, 10, 10));
            g.setColor(th.marke);
            g.fillRect(x, y + 8, 3, bh - 16);
            g.setFont(kopf);
            g.setColor(th.text);
            int ty = y + 4;
            for (String t : tz) {
                ty += km.getHeight();
                g.drawString(t, x + 13, ty - km.getDescent());
            }
            ty = y + kopfH;
            for (String[] z : zeilen) {
                ty += fm.getHeight() + 2;
                g.setFont(fett);
                g.setColor(th.leise);
                g.drawString(z[0], x + 13, ty);
                g.setFont(text);
                g.setColor(th.text);
                g.drawString(z[1], x + 13 + spalte + 12, ty);
            }
            return y + bh;
        }

        private int legende(Graphics2D g, int x, int y, int bw) {
            Thema th = Thema.aktuell();
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(th.text);
            boolean hoehe = darstellung() == Darstellung.HOEHE;
            g.drawString(hoehe ? "Höhe des Bodens (m NHN)" : "Änderung seit Beginn (m)", x, y + 12);
            int by = y + 20, bh = 14, bx = x, bbw = bw;
            for (int i = 0; i < bbw; i++) {
                double f = i / (double) (bbw - 1);
                Color c;
                if (hoehe) c = new Color(Jahresbild.hoehe(-10 + f * 14));
                else {
                    double s = skala(), v = (2 * f - 1) * s;
                    int argb = Jahresbild.differenz(v, s);
                    int al = (argb >>> 24) & 255;
                    Color grau = new Color(110, 112, 116);
                    c = al == 0 ? grau : Thema.mische(grau, new Color(argb & 0xffffff), al / 255.0);
                }
                g.setColor(c);
                g.fillRect(bx + i, by, 1, bh);
            }
            g.setColor(th.linie);
            g.drawRect(bx, by, bbw - 1, bh);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            String l, mi, r;
            if (hoehe) {
                l = "−10";
                mi = "0";
                r = "+4";
                int x0 = bx + (int) Math.round(10 / 14.0 * (bbw - 1)), x1 = bx + (int) Math.round(11 / 14.0 * (bbw - 1));
                g.setColor(th.text);
                g.drawLine(x0, by - 2, x0, by + bh + 2);
                g.drawLine(x1, by - 2, x1, by + bh + 2);
                g.drawString("+1", x1 - fm.stringWidth("+1") / 2, by + bh + 14);
                g.drawString(mi, x0 - fm.stringWidth(mi) / 2, by + bh + 14);
                g.setColor(th.leise);
                g.drawString(l, bx, by + bh + 14);
                g.drawString(r, bx + bbw - fm.stringWidth(r), by + bh + 14);
            } else {
                double s = skala();
                l = "−" + kurz(s) + "  Abtrag";
                r = "Auftrag  +" + kurz(s);
                mi = "0 (unter 1 cm ohne Farbe)";
                g.setColor(th.leise);
                g.drawString(l, bx, by + bh + 14);
                g.drawString(r, bx + bbw - fm.stringWidth(r), by + bh + 14);
                g.drawString(mi, bx + bbw / 2 - fm.stringWidth(mi) / 2, by + bh + 28);
            }
            // Linien der Karte
            int ly = by + bh + (hoehe ? 32 : 46);
            Stroke st = g.getStroke();
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 3f}, 0f));
            g.setColor(Thema.mitAlpha(Color.WHITE, 215));
            g.drawLine(bx, ly - 4, bx + 26, ly - 4);
            g.setStroke(new BasicStroke(2f));
            g.setColor(new Color(255, 196, 80));
            g.drawLine(bx, ly + 12, bx + 26, ly + 12);
            g.setStroke(st);
            g.setColor(th.text);
            String s = zahl(m.schwelle, 1, true) + " m NHN";
            g.drawString(s + " zu Beginn", bx + 34, ly);
            g.drawString(s + " jetzt", bx + 34, ly + 16);
            return ly + 18;
        }
    }

    static List<String> umbrechen(String t, FontMetrics fm, int breite) {
        List<String> l = new ArrayList<>();
        StringBuilder z = new StringBuilder();
        for (String w : t.split("\\s+")) {
            if (z.length() > 0 && fm.stringWidth(z + " " + w) > breite) {
                l.add(z.toString());
                z.setLength(0);
            }
            if (z.length() > 0) z.append(' ');
            z.append(w);
        }
        if (z.length() > 0) l.add(z.toString());
        return l;
    }

    // ------------------------------------------------------------------ Profil

    final class Profil extends JComponent {
        static final int OBEN = 24, UNTEN = 22, TEIL = 44;

        Profil() {
            setPreferredSize(new Dimension(800, 210));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mouseMoved(MouseEvent e) {
                    double len = Math.hypot(profil[2] - profil[0], profil[3] - profil[1]);
                    int px0 = LINKS, px1 = getWidth() - RECHTS;
                    zeigerD = e.getX() < px0 || e.getX() > px1 ? Double.NaN : (e.getX() - px0) / (double) Math.max(1, px1 - px0) * len;
                    Jahresansicht.this.repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    zeigerD = Double.NaN;
                    Jahresansicht.this.repaint();
                }
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
            double[][] p = profilProben();
            double len = p[p.length - 1][0];
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(th.text);
            String kopf = "Profil: Höhe des Bodens (m NHN), " + zahl(len / 1000, 1, false) + " km";
            g.drawString(kopf, LINKS, 16);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            String leg = "- - - zu Beginn     —— jetzt     unten: Änderung (± " + kurz(skala()) + " m)";
            g.setColor(th.leise);
            g.drawString(leg, w - RECHTS - fm.stringWidth(leg), 16);
            int px0 = LINKS, px1 = w - RECHTS, py0 = OBEN, py1 = h - UNTEN - TEIL, qy0 = py1 + 6, qy1 = h - UNTEN;
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.fillRect(px0, qy0, px1 - px0, qy1 - qy0);
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.drawRect(px0, qy0, px1 - px0, qy1 - qy0);
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (double[] q : p) for (int k = 1; k <= 2; k++) if (!Double.isNaN(q[k])) {
                lo = Math.min(lo, q[k]);
                hi = Math.max(hi, q[k]);
            }
            if (!(hi >= lo)) {
                g.setColor(th.leise);
                g.drawString("Das Profil liegt außerhalb des Gitters.", px0 + 10, py0 + 20);
                g.dispose();
                return;
            }
            lo = Math.min(lo, m.schwelle - 0.5);
            hi = Math.max(hi, m.schwelle + 0.5);
            double pad = 0.08 * (hi - lo);
            double ylo = lo - pad, yhi = hi + pad;
            java.util.function.DoubleUnaryOperator yv = v -> py1 - (v - ylo) / (yhi - ylo) * (py1 - py0);
            java.util.function.DoubleUnaryOperator xv = d -> px0 + (len > 0 ? d / len : 0) * (px1 - px0);
            double stufe = rund((yhi - ylo) / 4);
            for (double v = Math.ceil(ylo / stufe) * stufe; v <= yhi; v += stufe) {
                int y = (int) yv.applyAsDouble(v);
                g.setColor(Thema.mitAlpha(th.linie, 160));
                g.drawLine(px0 + 1, y, px1 - 1, y);
                g.setColor(th.leise);
                String l = zahl(v, stufe < 1 ? 1 : 0, false);
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            // km
            for (int km = 0; km <= (int) Math.floor(len / 1000 + 1e-9); km++) {
                int x = (int) xv.applyAsDouble(km * 1000.0);
                g.setColor(Thema.mitAlpha(th.linie, 120));
                g.drawLine(x, py0 + 1, x, py1 - 1);
                g.setColor(th.leise);
                String l = km + " km";
                g.drawString(l, x - fm.stringWidth(l) / 2, h - 6);
            }
            Shape alt = g.getClip();
            g.clipRect(px0 + 1, py0 + 1, px1 - px0 - 1, py1 - py0 - 1);
            // Fläche unter „jetzt“
            Path2D.Double flaeche = new Path2D.Double(), jetzt = new Path2D.Double(), beginn = new Path2D.Double();
            boolean frei = true;
            for (double[] q : p) {
                if (Double.isNaN(q[2])) continue;
                double x = xv.applyAsDouble(q[0]), y = yv.applyAsDouble(q[2]);
                if (frei) {
                    flaeche.moveTo(x, py1);
                    flaeche.lineTo(x, y);
                    jetzt.moveTo(x, y);
                } else {
                    flaeche.lineTo(x, y);
                    jetzt.lineTo(x, y);
                }
                frei = false;
            }
            flaeche.lineTo(xv.applyAsDouble(p[p.length - 1][0]), py1);
            flaeche.closePath();
            g.setPaint(new GradientPaint(0, py0, new Color(214, 190, 136), 0, py1, new Color(96, 82, 58)));
            g.fill(flaeche);
            // Wasser unter NHN 0 angedeutet
            int y0 = (int) yv.applyAsDouble(0);
            g.setColor(new Color(90, 150, 200, 60));
            g.fillRect(px0 + 1, Math.max(py0, y0), px1 - px0 - 1, Math.max(0, py1 - Math.max(py0, y0)));
            frei = true;
            for (double[] q : p) {
                if (Double.isNaN(q[1])) continue;
                double x = xv.applyAsDouble(q[0]), y = yv.applyAsDouble(q[1]);
                if (frei) beginn.moveTo(x, y);
                else beginn.lineTo(x, y);
                frei = false;
            }
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {6f, 4f}, 0f));
            g.setColor(Thema.mitAlpha(Color.WHITE, 200));
            g.draw(beginn);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(new Color(255, 196, 80));
            g.draw(jetzt);
            // Schwelle und NHN 0
            int ys = (int) yv.applyAsDouble(m.schwelle);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3f, 3f}, 0f));
            g.setColor(th.marke);
            g.drawLine(px0 + 1, ys, px1 - 1, ys);
            g.drawString("Schwelle " + zahl(m.schwelle, 1, true) + " m", px0 + 6, ys - 4);
            g.setColor(Thema.mitAlpha(th.text, 140));
            g.drawLine(px0 + 1, y0, px1 - 1, y0);
            g.setClip(alt);
            // Änderung als Balken
            double s = skala();
            int qm = (qy0 + qy1) / 2;
            g.setStroke(new BasicStroke(1f));
            g.setColor(Thema.mitAlpha(th.linie, 200));
            g.drawLine(px0 + 1, qm, px1 - 1, qm);
            double bw = Math.max(1, (px1 - px0) / (double) p.length);
            for (double[] q : p) {
                if (Double.isNaN(q[1]) || Double.isNaN(q[2])) continue;
                double d = q[2] - q[1];
                int argb = Jahresbild.differenz(d, s);
                if (((argb >>> 24) & 255) == 0) continue;
                double hh = Math.max(-1, Math.min(1, d / s)) * (qy1 - qy0) / 2.0 - 0;
                g.setColor(new Color(argb & 0xffffff));
                double x = xv.applyAsDouble(q[0]);
                g.fill(new Rectangle2D.Double(x - bw / 2, Math.min(qm, qm - hh), bw + 0.5, Math.abs(hh)));
            }
            g.setColor(th.leise);
            String so = "+" + kurz(s), su = "−" + kurz(s);
            g.drawString(so, px0 - 6 - fm.stringWidth(so), qy0 + 9);
            g.drawString(su, px0 - 6 - fm.stringWidth(su), qy1);
            // Zeiger
            if (!Double.isNaN(zeigerD)) {
                int i = (int) Math.round(zeigerD / Math.max(1e-9, len) * (p.length - 1));
                double[] q = p[Math.max(0, Math.min(p.length - 1, i))];
                int x = (int) xv.applyAsDouble(q[0]);
                g.setColor(th.text);
                g.drawLine(x, py0, x, qy1);
                String t = zahl(q[0] / 1000, 2, false) + " km · Beginn " + zahl(q[1], 2, true) + " · jetzt " + zahl(q[2], 2, true) + " m NHN · Änderung " + zahl(q[2] - q[1], 2, true) + " m";
                int tw = fm.stringWidth(t) + 16, bx = x + 12 + tw < px1 ? x + 12 : x - 12 - tw;
                g.setColor(Thema.mitAlpha(th.tief, 220));
                g.fillRoundRect(bx, py0 + 6, tw, fm.getHeight() + 6, 8, 8);
                g.setColor(th.text);
                g.drawString(t, bx + 8, py0 + 9 + fm.getAscent());
            }
            g.dispose();
        }
    }

    static double rund(double s) {
        double e = Math.pow(10, Math.floor(Math.log10(Math.max(1e-9, s)))), f = s / e;
        return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * e;
    }

    // ------------------------------------------------------------------ Band und Kurve (x: Spielzeit)

    double x(double s, int w) { return LINKS + s / Math.max(1e-9, plan.gesamt) * (w - LINKS - RECHTS); }

    double sek(int px, int w) { return Math.max(0, Math.min(plan.gesamt, (px - LINKS) / (double) Math.max(1, w - LINKS - RECHTS) * plan.gesamt)); }

    private void springe(int px, int w) {
        sek = sek(px, w);
        tag = plan.tag(sek);
        laden();
    }

    final class Band extends JComponent {
        Band() {
            setPreferredSize(new Dimension(800, 58));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { springe(e.getX(), getWidth()); }

                @Override public void mouseDragged(MouseEvent e) { springe(e.getX(), getWidth()); }
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
            if (m.abschnitte.isEmpty()) {
                g.dispose();
                return;
            }
            g.setFont(th.titel(15f));
            g.setColor(th.text);
            g.drawString("Ein Jahr in " + (plan.gesamt >= 60 ? zahl(plan.gesamt / 60, 0, false) + (plan.gesamt >= 120 ? " Minuten" : " Minute") : zahl(plan.gesamt, 0, false) + " Sekunden"),
                    LINKS, 18);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            String r = "Tag " + (int) Math.floor(tag + 1e-6) + " · " + m.abschnitte.get(m.abschnitt(tag)).name() + " · Bild " + (m.vor(tag) + 1) + "/" + m.bilder.size() + " · "
                    + uhr(sek) + " von " + uhr(plan.gesamt);
            g.setColor(th.leise);
            g.drawString(r, w - RECHTS - fm.stringWidth(r), 18);
            int y = 34, hh = 14;
            for (int k = 0; k < plan.abschnitte(); k++) {
                Jahresmappe.Abschnitt a = m.abschnitte.get(k);
                int x0 = (int) x(plan.von(k), w) + 1, x1 = (int) x(plan.bis(k), w) - 1;
                g.setColor(k % 2 == 0 ? th.flaeche : Thema.mische(th.flaeche, th.linie, 0.5));
                g.fillRoundRect(x0, y - hh / 2, x1 - x0, hh, 6, 6);
                String t = a.name() + " · " + zahl(a.tage(), 0, false) + " Tage";
                if (fm.stringWidth(t) > x1 - x0 - 8) t = a.name();
                if (fm.stringWidth(t) > x1 - x0 - 8) t = "";
                g.setColor(th.text);
                g.drawString(t, x0 + (x1 - x0 - fm.stringWidth(t)) / 2, y + 4);
                // Teilstriche: 30 Tage, im kurzen Abschnitt jeder Tag
                double schritt = a.tage() > 40 ? 30 : 1;
                for (double d = Math.ceil(a.tag0() / schritt) * schritt; d <= a.tag1() + 1e-6; d += schritt) {
                    if (d <= a.tag0() + 1e-6 && k > 0) continue;
                    int x = (int) x(plan.sekunde(d), w);
                    g.setColor(th.leise);
                    g.fillRect(x, y + hh / 2 + 1, 1, 4);
                    String l = String.valueOf((int) Math.round(d));
                    if (schritt == 1 || ((int) Math.round(d)) % 60 == 0 || d == 0) g.drawString(l, x - fm.stringWidth(l) / 2, y + hh / 2 + 15);
                }
            }
            int x = (int) x(sek, w), x0 = (int) x(0, w);
            g.setColor(Thema.mitAlpha(th.primaer, 110));
            g.fillRoundRect(x0, y - hh / 2, Math.max(0, x - x0), hh, 6, 6);
            g.setColor(th.text);
            g.fillOval(x - 7, y - 7, 14, 14);
            g.setColor(th.primaer);
            g.fillOval(x - 4, y - 4, 8, 8);
            g.dispose();
        }
    }

    final class Kurven extends JComponent {
        static final int OBEN = 22, UNTEN = 8;

        Kurven() {
            setPreferredSize(new Dimension(800, 120));
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { springe(e.getX(), getWidth()); }

                @Override public void mouseDragged(MouseEvent e) { springe(e.getX(), getWidth()); }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        double wert(Groesse gr, double t) {
            Jahresmappe.Bild b0 = m.bilder.get(0);
            return switch (gr) {
                case OST -> m.kennzahl(Jahresmappe.Kennzahl.OST, t) - b0.ost();
                case FLAECHE -> m.kennzahl(Jahresmappe.Kennzahl.FLAECHE, t) / 1e6;
                case VOLUMEN -> m.kennzahl(Jahresmappe.Kennzahl.VOLUMEN, t) - b0.volumen();
                case AUFTRAG -> m.kennzahl(Jahresmappe.Kennzahl.AUFTRAG, t) / 1e6;
                case ABTRAG -> m.kennzahl(Jahresmappe.Kennzahl.ABTRAG, t) / 1e6;
                case GROESSTE -> m.kennzahl(Jahresmappe.Kennzahl.GROESSTE, t);
            };
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
            Groesse gr = groesse();
            int px0 = LINKS, px1 = w - RECHTS, py0 = OBEN, py1 = h - UNTEN;
            g.setColor(th.flaeche);
            g.fillRect(px0, py0, px1 - px0, py1 - py0);
            g.setColor(th.linie);
            g.drawRect(px0, py0, px1 - px0, py1 - py0);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(th.text);
            String gt = gr.name.replace("Sand", sandName);
            g.drawString(gt + " (" + gr.einheit + ")", px0, 15);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (Jahresmappe.Bild b : m.bilder) {
                double v = wert(gr, b.tag());
                if (Double.isNaN(v)) continue;
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            if (!(hi >= lo)) {
                g.dispose();
                return;
            }
            if (hi - lo < 1e-9) hi = lo + 1;
            double pad = 0.1 * (hi - lo), ylo = lo - pad, yhi = hi + pad;
            java.util.function.DoubleUnaryOperator yv = v -> py1 - (v - ylo) / (yhi - ylo) * (py1 - py0);
            double stufe = rund((yhi - ylo) / 3);
            for (double v = Math.ceil(ylo / stufe) * stufe; v <= yhi; v += stufe) {
                int y = (int) yv.applyAsDouble(v);
                g.setColor(Thema.mitAlpha(th.linie, 160));
                g.drawLine(px0 + 1, y, px1 - 1, y);
                g.setColor(th.leise);
                String l = stufe >= 1000 ? ganz(v, false) : zahl(v, stufe < 0.01 ? 3 : stufe < 0.1 ? 2 : stufe < 1 ? 1 : 0, false);
                g.drawString(l, px0 - 6 - fm.stringWidth(l), y + 4);
            }
            for (int k = 1; k < plan.abschnitte(); k++) {
                int x = (int) x(plan.von(k), w);
                g.setColor(Thema.mitAlpha(th.marke, 150));
                g.drawLine(x, py0 + 1, x, py1 - 1);
            }
            Path2D.Double p = new Path2D.Double();
            boolean frei = true;
            for (Jahresmappe.Bild b : m.bilder) {
                double v = wert(gr, b.tag());
                if (Double.isNaN(v)) continue;
                double x = x(plan.sekunde(b.tag()), w), y = yv.applyAsDouble(v);
                if (frei) p.moveTo(x, y);
                else p.lineTo(x, y);
                frei = false;
            }
            Shape alt = g.getClip();
            g.clipRect(px0 + 1, py0 + 1, px1 - px0 - 1, py1 - py0 - 1);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(th.kurve);
            g.draw(p);
            g.setClip(alt);
            int x = (int) x(sek, w);
            g.setStroke(new BasicStroke(1f));
            g.setColor(th.text);
            g.drawLine(x, py0, x, py1);
            double v = wert(gr, tag);
            String t = (gr == Groesse.VOLUMEN ? ganz(v, true) : zahl(v, gr == Groesse.FLAECHE ? 3 : 2, gr == Groesse.OST || gr == Groesse.VOLUMEN)) + " " + gr.einheit;
            int tw = fm.stringWidth(t) + 14, bx = x + 10 + tw < px1 ? x + 10 : x - 10 - tw;
            g.setColor(Thema.mitAlpha(th.tief, 215));
            g.fillRoundRect(bx, py0 + 4, tw, fm.getHeight() + 4, 8, 8);
            g.setColor(th.text);
            g.drawString(t, bx + 7, py0 + 6 + fm.getAscent());
            g.dispose();
        }
    }
}
