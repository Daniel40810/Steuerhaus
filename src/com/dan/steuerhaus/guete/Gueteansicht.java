package com.dan.steuerhaus.guete;

import com.dan.fbutton.FButton;
import com.dan.fcombobox.FComboBox;
import com.dan.steuerhaus.Guete;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
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
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Modell gegen Messung (seit DB3, Werkbuch HalligHooge 171): oben Lauf und Pegel zur Wahl und der Sprung ins Szenario,
 * in der Mitte die Kurven des Modells und der Messung mit ihren Hoch- und Niedrigwassern, den Paaren der Hochwasser und
 * darunter dem Unterschied Modell − Messung; bei Xaver der Kennwert HThw an seinem Tag. Unten die Kennzahlen aller Pegel
 * des Laufs (ein Klick wählt den Pegel). Liest nur die {@link Guete}; die Datenbank braucht die App nicht.
 */
public final class Gueteansicht extends JPanel {
    static final Color MODELL = new Color(240, 150, 50), MESSUNG = new Color(80, 160, 235), ROT = new Color(225, 80, 70);
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("EE d.M.yyyy", Locale.GERMAN), UHR = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN),
            KURZ = DateTimeFormatter.ofPattern("d.M. HH:mm", Locale.GERMAN), DATUM = DateTimeFormatter.ofPattern("d.M.yyyy", Locale.GERMAN);

    /** Was der Knopf tut: das Szenario (Schlüssel) zur Zeit öffnen. */
    public interface Sprung { void zu(String szenario, Instant zeit); }

    public final Guete guete;
    public final ZoneId zone;
    private final Map<String, String> szenarioNamen;
    private final String hinweis;
    private final Sprung sprung;
    final FComboBox laufWahl, pegelWahl;
    final FButton ansehen = new FButton("Ansehen");
    final JLabel kopf = new JLabel(" ");
    final Kurven kurven = new Kurven();
    final Tabelle tabelle = new Tabelle();
    private Guete.Lauf lauf;
    private Guete.Eintrag eintrag;
    private List<Guete.Eintrag> pegel = List.of();
    private boolean still;

    public Gueteansicht(Guete guete, Map<String, String> szenarioNamen, ZoneId zone, String hinweis, Sprung sprung) {
        super(new BorderLayout());
        this.guete = guete;
        this.zone = zone;
        this.szenarioNamen = Map.copyOf(szenarioNamen);
        this.hinweis = hinweis == null ? "" : hinweis;
        this.sprung = sprung;
        Thema th = Thema.aktuell();
        Faerber.rolle(this, "grund");
        List<String> ln = new ArrayList<>();
        for (Guete.Lauf l : guete.laeufe) ln.add(laufName(l));
        laufWahl = new FComboBox(ln.isEmpty() ? new Object[] {"keine Läufe"} : ln.toArray());
        pegelWahl = new FComboBox(new Object[] {"–"});
        for (FComboBox k : new FComboBox[] {laufWahl, pegelWahl}) {
            k.setFocusable(false);
            k.setLightWeightPopupEnabled(false);
            Faerber.liste(k, th);
        }
        laufWahl.setPreferredSize(new Dimension(330, 34));
        pegelWahl.setPreferredSize(new Dimension(230, 34));
        ansehen.setFocusable(false);
        Faerber.knopf(ansehen, th);
        ansehen.setPreferredSize(new Dimension(300, 34));
        ansehen.addActionListener(e -> springe());
        JPanel oben = Faerber.rolle(new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 8)), "grund");
        oben.add(beschriftung("Lauf"));
        oben.add(laufWahl);
        oben.add(beschriftung("Pegel"));
        oben.add(pegelWahl);
        oben.add(ansehen);
        kopf.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        kopf.setForeground(th.leise);
        kopf.setBorder(BorderFactory.createEmptyBorder(0, 14, 4, 14));
        JPanel nord = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        nord.add(oben, BorderLayout.NORTH);
        nord.add(kopf, BorderLayout.SOUTH);
        JPanel sued = Faerber.rolle(new JPanel(new BorderLayout()), "grund");
        sued.add(tabelle, BorderLayout.CENTER);
        if (!this.hinweis.isEmpty()) sued.add(new Fuss(), BorderLayout.SOUTH);
        add(nord, BorderLayout.NORTH);
        add(kurven, BorderLayout.CENTER);
        add(sued, BorderLayout.SOUTH);
        laufWahl.addActionListener(e -> { if (!still) waehleLauf(laufWahl.getSelectedIndex(), null); });
        pegelWahl.addActionListener(e -> { if (!still) waehlePegel(pegelWahl.getSelectedIndex()); });
        if (!guete.laeufe.isEmpty()) waehleLauf(0, null);
        else ansehen.setEnabled(false);
    }

    JLabel beschriftung(String t) {
        JLabel l = new JLabel(t);
        l.setFont(Thema.aktuell().titel(14f));
        l.setForeground(Thema.aktuell().text);
        return l;
    }

    /** Der Name eines Laufs: das Szenario mit seinem Namen, weitere Szenarien desselben Archivs in Klammern. */
    public String laufName(Guete.Lauf l) {
        String n = szenarioNamen.getOrDefault(l.schluessel(), l.schluessel());
        return l.szenarien().size() > 1 ? n + " (auch " + String.join(", ", l.szenarien().subList(1, l.szenarien().size())) + ")" : n;
    }

    /** Wählt einen Lauf (Index in der Datei) und darin einen Pegel (Schlüssel; null: den ersten oder den bisherigen). */
    public void waehleLauf(int i, String pegelSchluessel) {
        if (i < 0 || i >= guete.laeufe.size()) return;
        String bisher = pegelSchluessel != null ? pegelSchluessel : eintrag == null ? null : eintrag.pegel();
        lauf = guete.laeufe.get(i);
        pegel = guete.eintraege(lauf.schluessel());
        still = true;
        try {
            if (laufWahl.getSelectedIndex() != i) laufWahl.setSelectedIndex(i);
            pegelWahl.removeAllItems();
            for (Guete.Eintrag e : pegel) pegelWahl.addItem(e.name());
            int k = 0;
            for (int j = 0; j < pegel.size(); j++) if (pegel.get(j).pegel().equals(bisher)) k = j;
            if (!pegel.isEmpty()) pegelWahl.setSelectedIndex(k);
            eintrag = pegel.isEmpty() ? null : pegel.get(k);
        } finally {
            still = false;
        }
        aktualisiere();
    }

    public void waehlePegel(int i) {
        if (i < 0 || i >= pegel.size()) return;
        eintrag = pegel.get(i);
        if (pegelWahl.getSelectedIndex() != i) {
            still = true;
            pegelWahl.setSelectedIndex(i);
            still = false;
        }
        aktualisiere();
    }

    /** Wählt Lauf und Pegel nach Schlüssel (für die App und die Prüfung); false, wenn es sie nicht gibt. */
    public boolean waehle(String laufSchluessel, String pegelSchluessel) {
        for (int i = 0; i < guete.laeufe.size(); i++)
            if (guete.laeufe.get(i).schluessel().equals(laufSchluessel)) {
                waehleLauf(i, pegelSchluessel);
                return eintrag != null && (pegelSchluessel == null || eintrag.pegel().equals(pegelSchluessel));
            }
        return false;
    }

    public Guete.Lauf lauf() { return lauf; }

    public Guete.Eintrag eintrag() { return eintrag; }

    /** Der Knopf in das Szenario (für die Prüfung). */
    public FButton knopf() { return ansehen; }

    /** Die Zeit, zu der der Knopf springt: eine Stunde vor dem höchsten Wasser des Modells am Pegel, nicht vor dem Beginn der Wertung. */
    public Instant sprungzeit() {
        if (lauf == null) return null;
        Instant t = eintrag != null && eintrag.kennzahlen().maxZeit() != null ? eintrag.kennzahlen().maxZeit().minusSeconds(3600) : lauf.von();
        return t.isBefore(lauf.von()) ? lauf.von() : t;
    }

    void springe() {
        Instant t = sprungzeit();
        if (lauf != null && sprung != null && t != null) sprung.zu(lauf.schluessel(), t);
    }

    void aktualisiere() {
        String sz = lauf == null ? "" : szenarioNamen.getOrDefault(lauf.schluessel(), lauf.schluessel());
        ansehen.setText("Ansehen: " + (sz.length() > 26 ? sz.substring(0, 25) + "…" : sz));
        ansehen.setToolTipText(lauf == null ? null : "Szenario „" + sz + "“ eine Stunde vor dem höchsten Wasser des Modells am Pegel");
        ansehen.setEnabled(lauf != null && sprung != null);
        if (eintrag != null) {
            Guete.Eintrag e = eintrag;
            kopf.setText(String.format(Locale.GERMAN, "%s · Modell: Zelle %d von %s, %.0f m vom Pegel, Grund %s m NHN · gewertet %s bis %s Uhr (%d Stände je %d min) · Archiv %s",
                    e.name(), e.zelle(), e.gitter(), e.abstand(), zahl(e.grund(), 1), KURZ.format(lauf.von().atZone(zone)), KURZ.format(lauf.bis().atZone(zone)), e.modell().n(),
                    lauf.schritt() / 60, lauf.archiv()));
        } else kopf.setText(lauf == null ? "Noch keine Läufe" : "Keine Pegel im Lauf " + lauf.schluessel());
        kurven.repaint();
        tabelle.repaint();
    }

    // ------------------------------------------------------------------ Zahlen

    static String zahl(double d, int stellen) { return Double.isNaN(d) ? "–" : String.format(Locale.GERMAN, "%." + stellen + "f", d); }

    static String vz(double d, int stellen) { return Double.isNaN(d) ? "–" : String.format(Locale.GERMAN, "%+." + stellen + "f", d); }

    static String m(double d) { return Double.isNaN(d) ? "–" : String.format(Locale.GERMAN, "%.2f m", d); }

    static String cm(double m) { return Double.isNaN(m) ? "–" : String.format(Locale.GERMAN, "%+d cm", Math.round(m * 100)); }

    static void glatt(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    // ------------------------------------------------------------------ Kurven

    /** Die Kurven: oben Modell und Messung (m NHN), unten der Unterschied Modell − Messung (cm). */
    final class Kurven extends JComponent {
        Kurven() { setPreferredSize(new Dimension(1200, 470)); }

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
            if (lauf == null || eintrag == null) {
                g.setColor(th.leise);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 15));
                g.drawString(guete.laeufe.isEmpty() ? "Noch keine Läufe: Ausgabe --guete, dann Sammler --guete." : "Keine Reihe.", 30, 60);
                return;
            }
            Guete.Eintrag e = eintrag;
            Guete.Kennzahlen k = e.kennzahlen();
            Instant t0 = lauf.von(), t1 = lauf.bis();
            // Höhe: beide Reihen, Marken und der Kennwert
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (Guete.Reihe r : new Guete.Reihe[] {e.modell(), e.messung()})
                for (float v : r.werte()) if (!Float.isNaN(v)) { lo = Math.min(lo, v); hi = Math.max(hi, v); }
            if (k.mitKennwert()) hi = Math.max(hi, k.kennwert());
            if (lo > hi) { lo = 0; hi = 1; }
            double zu = Math.floor((lo - 0.2) * 2) / 2, zo = Math.ceil((hi + 0.25) * 2) / 2;
            int x0 = 70, x1 = w - 24, y0 = 40, yd = h - 92, y1 = yd - 26, yd1 = h - 30;
            final double a = t0.getEpochSecond(), b = Math.max(a + 1, t1.getEpochSecond()), zuF = zu, zoF = zo;
            java.util.function.DoubleUnaryOperator xt = s -> x0 + (x1 - x0) * (s - a) / (b - a);
            java.util.function.DoubleUnaryOperator yz = z -> y1 - (y1 - y0) * (z - zuF) / (zoF - zuF);
            // Kopfzeile und Legende
            g.setFont(th.titel(16f));
            g.setColor(th.text);
            g.drawString("Wasserstand am Pegel " + e.name() + " (m NHN, Zeiten " + (zone.getId().equals("Europe/Berlin") ? "MEZ/MESZ" : zone.getId()) + ")", 14, 24);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            int lx = w - 24;
            Object[][] leg = e.messung().n() > 0
                    ? new Object[][] {{MESSUNG, "Messung (PEGELONLINE, 10 min)"}, {MODELL, "Modell (App)"}}
                    : new Object[][] {{ROT, "HThw (Kennwert)"}, {MODELL, "Modell (App)"}};
            for (Object[] o : leg) {
                String t = (String) o[1];
                lx -= fm.stringWidth(t);
                g.setColor(th.leise);
                g.drawString(t, lx, 24);
                lx -= 24;
                g.setColor((Color) o[0]);
                g.setStroke(new BasicStroke(3f));
                g.draw(new Line2D.Double(lx, 20, lx + 18, 20));
                lx -= 18;
            }
            // Flächen
            g.setColor(th.flaeche);
            g.fill(new Rectangle2D.Double(x0, y0, x1 - x0, y1 - y0));
            g.fill(new Rectangle2D.Double(x0, yd, x1 - x0, yd1 - yd));
            // Raster: Meter
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            fm = g.getFontMetrics();
            double schritt = zo - zu > 4 ? 1 : 0.5;
            for (double z = Math.ceil(zu / schritt) * schritt; z <= zo + 1e-9; z += schritt) {
                double y = yz.applyAsDouble(z);
                g.setColor(Thema.mitAlpha(th.linie, Math.abs(z) < 1e-9 ? 160 : 80));
                g.setStroke(new BasicStroke(1f));
                g.draw(new Line2D.Double(x0, y, x1, y));
                g.setColor(th.leise);
                String l = String.format(Locale.GERMAN, schritt < 1 ? "%.1f m" : "%.0f m", z);
                g.drawString(l, x0 - 8 - fm.stringWidth(l), (int) y + 4);
            }
            // Raster: alle 3 Stunden, Tage mit Datum
            ZonedDateTime z0 = t0.atZone(zone).withMinute(0).withSecond(0).withNano(0);
            int stunden = (b - a) > 3 * 86400 ? 12 : (b - a) > 30 * 3600 ? 6 : 3;
            for (ZonedDateTime z = z0.withHour(z0.getHour() / stunden * stunden); !z.toInstant().isAfter(t1); z = z.plusHours(stunden)) {
                if (z.toInstant().isBefore(t0)) continue;
                double x = xt.applyAsDouble(z.toEpochSecond());
                boolean tag = z.getHour() == 0;
                g.setColor(Thema.mitAlpha(th.linie, tag ? 150 : 70));
                g.draw(new Line2D.Double(x, y0, x, y1));
                g.draw(new Line2D.Double(x, yd, x, yd1));
                g.setColor(th.leise);
                String l = tag ? TAG.format(z) : UHR.format(z);
                g.drawString(l, (int) x - fm.stringWidth(l) / 2, yd1 + 16);
            }
            // Kennwert HThw an seinem Tag
            if (k.mitKennwert()) {
                ZonedDateTime ta = k.kennwertTag().atStartOfDay(zone);
                double xa = Math.max(x0, xt.applyAsDouble(ta.toEpochSecond())), xb = Math.min(x1, xt.applyAsDouble(ta.plusDays(1).toEpochSecond()));
                double y = yz.applyAsDouble(k.kennwert());
                g.setColor(ROT);
                g.setStroke(new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {9f, 6f}, 0f));
                g.draw(new Line2D.Double(xa, y, xb, y));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
                String l = "HThw " + zahl(k.kennwert(), 2) + " m am " + DATUM.format(k.kennwertTag()) + (Double.isNaN(k.amTag()) ? "" : "; Modell an dem Tag " + zahl(k.amTag(), 2) + " m ("
                        + cm(k.amTag() - k.kennwert()) + ")");
                g.drawString(l, (int) Math.max(x0 + 6, Math.min(xa + 6, x1 - g.getFontMetrics().stringWidth(l) - 6)), (int) y - 7);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            }
            // Kurven
            kurve(g, e.messung(), MESSUNG, 2.2f, xt, yz);
            kurve(g, e.modell(), MODELL, 2.2f, xt, yz);
            // Paare der Hochwasser
            for (Guete.Paar p : e.paare()) {
                double xm = xt.applyAsDouble(p.modell().getEpochSecond()), ym = yz.applyAsDouble(p.hModell());
                double xg = xt.applyAsDouble(p.messung().getEpochSecond()), yg = yz.applyAsDouble(p.hMessung());
                g.setColor(Thema.mitAlpha(th.text, 150));
                g.setStroke(new BasicStroke(1.2f));
                g.draw(new Line2D.Double(xm, ym, xg, yg));
                String l = cm(p.versatzM()) + ", " + String.format(Locale.GERMAN, "%+d min", Math.round(p.versatzMin()));
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
                g.setColor(th.text);
                double lxx = Math.max(x0 + 2, Math.min(x1 - g.getFontMetrics().stringWidth(l) - 2, (xm + xg) / 2 - g.getFontMetrics().stringWidth(l) / 2.0));
                g.drawString(l, (float) lxx, (float) Math.min(ym, yg) - 10);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            }
            // Marken: Messung als Ring, Modell als Punkt
            g.setStroke(new BasicStroke(1.8f));
            for (Guete.Marke m : e.gemessen()) {
                double x = xt.applyAsDouble(m.zeit().getEpochSecond()), y = yz.applyAsDouble(m.hoehe());
                g.setColor(MESSUNG);
                g.draw(new Ellipse2D.Double(x - 5, y - 5, 10, 10));
            }
            for (Guete.Marke m : e.marken()) {
                double x = xt.applyAsDouble(m.zeit().getEpochSecond()), y = yz.applyAsDouble(m.hoehe());
                g.setColor(MODELL);
                g.fill(new Ellipse2D.Double(x - 4, y - 4, 8, 8));
            }
            // Unterschied Modell − Messung (cm)
            List<double[]> d = new ArrayList<>();
            double dmax = 0.1;
            for (int i = 0; i < e.modell().n(); i++) {
                double m = e.modell().wert(i), o = e.messung().bei(e.modell().zeit(i));
                if (Double.isNaN(m) || Double.isNaN(o)) continue;
                d.add(new double[] {e.modell().zeit(i).getEpochSecond(), m - o});
                dmax = Math.max(dmax, Math.abs(m - o));
            }
            dmax = Math.ceil(dmax * 10) / 10;
            double ym0 = (yd + yd1) / 2.0, sk = (yd1 - yd) / 2.0 / dmax;
            g.setColor(Thema.mitAlpha(th.linie, 160));
            g.setStroke(new BasicStroke(1f));
            g.draw(new Line2D.Double(x0, ym0, x1, ym0));
            g.setColor(th.leise);
            String dl = "±" + Math.round(dmax * 100) + " cm";
            g.drawString(dl, x0 - 8 - fm.stringWidth(dl), yd + 10);
            g.drawString("0", x0 - 8 - fm.stringWidth("0"), (int) ym0 + 4);
            if (d.isEmpty()) {
                g.drawString(e.messung().n() == 0 ? "Keine Messung in diesem Lauf (nur der Kennwert)" : "Keine gemeinsamen Zeiten", x0 + 10, (int) ym0 - 6);
            } else {
                g.drawString("Modell − Messung", x0 + 8, yd + 13);
                double bw = Math.max(1.5, (x1 - x0) * 600.0 / (b - a) - 1);
                for (double[] p : d) {
                    double x = xt.applyAsDouble(p[0]), y = ym0 - p[1] * sk;
                    g.setColor(Thema.mitAlpha(p[1] >= 0 ? MODELL : MESSUNG, 200));
                    g.fill(new Rectangle2D.Double(x - bw / 2, Math.min(y, ym0), bw, Math.abs(y - ym0)));
                }
            }
            // Kennzahlen kurz unter der Kopfzeile
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(th.text);
            String kz = k.werte() > 0
                    ? String.format(Locale.GERMAN, "%d Werte · RMSE %s · Bias %s · Skill %s · Hochwasser: %d %s, %s, %s min%s", k.werte(), m(k.rmse()), cm(k.bias()),
                            zahl(k.skill(), 3), k.hw(), k.hw() == 1 ? "Paar" : "Paare", cm(k.hwHoehe()), vz(k.hwZeit(), 0),
                            Double.isNaN(k.stauAnteil()) ? "" : " · Windstau im Modell " + Math.round(100 * k.stauAnteil()) + " % des gemessenen")
                    : "Höchstes Wasser im Modell " + zahl(k.max(), 2) + " m" + (k.maxZeit() == null ? "" : " am " + TAG.format(k.maxZeit().atZone(zone)) + " " + UHR.format(k.maxZeit().atZone(zone)) + " Uhr");
            g.drawString(kz, x0 + 8, y0 + 16);
        }

        void kurve(Graphics2D g, Guete.Reihe r, Color c, float breite, java.util.function.DoubleUnaryOperator xt, java.util.function.DoubleUnaryOperator yz) {
            Path2D.Double p = new Path2D.Double();
            boolean offen = false;
            for (int i = 0; i < r.n(); i++) {
                double v = r.wert(i);
                if (Double.isNaN(v)) { offen = false; continue; }
                double x = xt.applyAsDouble(r.zeit(i).getEpochSecond()), y = yz.applyAsDouble(v);
                if (offen) p.lineTo(x, y);
                else p.moveTo(x, y);
                offen = true;
            }
            g.setColor(c);
            g.setStroke(new BasicStroke(breite, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(p);
        }
    }

    // ------------------------------------------------------------------ Tabelle

    /** Die Kennzahlen aller Pegel des Laufs; ein Klick wählt den Pegel. */
    final class Tabelle extends JComponent {
        static final int ZEILE = 26, KOPF = 34;

        Tabelle() {
            setPreferredSize(new Dimension(1200, KOPF + 8 * ZEILE + 10));
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent ev) {
                    int i = (ev.getY() - KOPF) / ZEILE;
                    if (ev.getY() >= KOPF && i >= 0 && i < pegel.size()) waehlePegel(i);
                }
            });
        }

        boolean mitKennwert() {
            for (Guete.Eintrag e : pegel) if (!Double.isNaN(e.kennzahlen().kennwert())) return true;
            return false;
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            glatt(g);
            int w = getWidth();
            g.setColor(th.grund);
            g.fillRect(0, 0, w, getHeight());
            boolean kw = mitKennwert();
            String[] kopf = kw ? new String[] {"Pegel", "Abstand der Zelle", "Höchstes im Modell", "HThw (Kennwert)", "Modell am Tag", "Modell − HThw", "Zeit des Modells"}
                    : new String[] {"Pegel", "Abstand der Zelle", "Werte", "RMSE", "Bias", "Skill", "Hochwasser", "Versatz Höhe", "Versatz Zeit", "Windstau im Modell"};
            double[] anteil = kw ? new double[] {0.2, 0.12, 0.16, 0.14, 0.12, 0.12, 0.14} : new double[] {0.17, 0.1, 0.07, 0.08, 0.08, 0.07, 0.09, 0.1, 0.1, 0.14};
            int x0 = 14, breite = w - 28;
            int[] xs = new int[kopf.length + 1];
            xs[0] = x0;
            for (int i = 0; i < kopf.length; i++) xs[i + 1] = xs[i] + (int) Math.round(anteil[i] * breite);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            g.setColor(th.leise);
            for (int i = 0; i < kopf.length; i++) g.drawString(kopf[i], xs[i] + 6, KOPF - 10);
            g.setColor(Thema.mitAlpha(th.linie, 140));
            g.draw(new Line2D.Double(x0, KOPF - 3, x0 + breite, KOPF - 3));
            for (int r = 0; r < pegel.size(); r++) {
                Guete.Eintrag e = pegel.get(r);
                Guete.Kennzahlen k = e.kennzahlen();
                int y = KOPF + r * ZEILE;
                if (e == eintrag) {
                    g.setColor(Thema.mitAlpha(th.primaer, 70));
                    g.fill(new java.awt.geom.RoundRectangle2D.Double(x0, y + 1, breite, ZEILE - 2, 10, 10));
                } else if (r % 2 == 1) {
                    g.setColor(Thema.mitAlpha(th.flaeche, 160));
                    g.fill(new Rectangle2D.Double(x0, y + 1, breite, ZEILE - 2));
                }
                String[] z = kw
                        ? new String[] {e.name(), String.format(Locale.GERMAN, "%.0f m", e.abstand()), m(k.max()), m(k.kennwert())
                                + (k.kennwertTag() == null ? "" : " (" + DATUM.format(k.kennwertTag()) + ")"), m(k.amTag()), cm(k.amTag() - k.kennwert()),
                                k.amTagZeit() == null ? "–" : KURZ.format(k.amTagZeit().atZone(zone)) + " Uhr"}
                        : new String[] {e.name(), String.format(Locale.GERMAN, "%.0f m", e.abstand()), String.valueOf(k.werte()), m(k.rmse()), cm(k.bias()),
                                zahl(k.skill(), 3), String.valueOf(k.hw()), cm(k.hwHoehe()), Double.isNaN(k.hwZeit()) ? "–" : vz(k.hwZeit(), 0) + " min",
                                Double.isNaN(k.stauAnteil()) ? "–" : Math.round(100 * k.stauAnteil()) + " % (" + k.stauPaare() + ")"};
                for (int i = 0; i < z.length; i++) {
                    g.setFont(new Font(Font.SANS_SERIF, i == 0 || e == eintrag ? Font.BOLD : Font.PLAIN, 12));
                    g.setColor(th.text);
                    g.drawString(z[i], xs[i] + 6, y + ZEILE - 8);
                }
            }
            g.dispose();
        }
    }

    /** Der Hinweis unter der Tabelle. */
    final class Fuss extends JComponent {
        Fuss() { setPreferredSize(new Dimension(800, 58)); }

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

    /** Für die Kachel der Startseite: „10 Läufe an 7 Pegeln · Skill im Mittel 0,95“. */
    public static String kurz(Guete g) {
        if (g == null || g.laeufe.isEmpty()) return null;
        java.util.Set<String> p = new java.util.HashSet<>();
        double s = 0;
        int n = 0;
        for (Guete.Eintrag e : g.eintraege) {
            p.add(e.pegel());
            if (!Double.isNaN(e.kennzahlen().skill())) { s += e.kennzahlen().skill(); n++; }
        }
        return g.laeufe.size() + (g.laeufe.size() == 1 ? " Lauf" : " Läufe") + " an " + p.size() + " Pegeln" + (n > 0 ? String.format(Locale.GERMAN, " · Skill im Mittel %.2f", s / n) : "");
    }
}
