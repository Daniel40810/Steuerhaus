package com.dan.steuerhaus.regler;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.fslider.FSlider;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Das Pult der Regler im Live-Lauf (seit Y2, Werkbuch HalligHooge 153): Wind (Faktor, Drehung), Aufschlag und
 * Meeresspiegel als FSlider, darüber eine Windrose (gestrichelt der Wind aus ERA5, durchgezogen der wirksame) und was
 * im Lauf gerade wirkt – die Physik fährt die Ziele in der Modellzeit an. Ohne Live-Lauf ein Hinweis und „Live starten“.
 */
public final class Reglerpult extends JPanel {
    /** Bei jeder Änderung (auch beim Ziehen). */
    public Consumer<Reglerwerte> aenderung = w -> { };
    /** Wenn ein Regler losgelassen ist (für das Protokoll und die Marken in der Zeitleiste). */
    public Consumer<Reglerwerte> eingriff = w -> { };
    /** „Live starten“. */
    public Runnable live = () -> { };

    private final FSlider faktor = new FSlider(0, 30, 10), drehung = new FSlider(-12, 12, 0), aufschlag = new FSlider(-10, 30, 0), meer = new FSlider(0, 15, 0);
    private final JLabel wf = wert(), wd = wert(), wa = wert(), wm = wert();
    private final JLabel ist = leise(" "), hinweis = leise(" ");
    private final FButton zurueck = new FButton("Zurück auf ERA5"), liveKnopf = new FButton("Live starten");
    private final Rose rose = new Rose();
    private boolean still;
    private Reglerwerte werte = Reglerwerte.NEUTRAL;

    public Reglerpult() {
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        Faerber.rolle(this, "flaeche");
        for (FSlider s : new FSlider[] {faktor, drehung, aufschlag, meer}) {
            s.setFocusable(false);
            s.addChangeListener(e -> {
                beschriften();
                if (still) return;
                werte = ausReglern();
                aenderung.accept(werte);
                if (!s.getValueIsAdjusting()) eingriff.accept(werte);
            });
        }
        zurueck.setVariant(FButtonVariant.GHOST);
        zurueck.addActionListener(e -> {
            setze(Reglerwerte.NEUTRAL);
            aenderung.accept(werte);
            eingriff.accept(werte);
        });
        liveKnopf.addActionListener(e -> live.run());
        JPanel oben = new JPanel(new BorderLayout(10, 0));
        oben.setOpaque(false);
        oben.add(rose, BorderLayout.WEST);
        JPanel txt = new JPanel(new GridLayout(0, 1, 0, 2));
        txt.setOpaque(false);
        txt.add(ist);
        txt.add(hinweis);
        oben.add(txt, BorderLayout.CENTER);
        add(links(oben));
        add(Box.createVerticalStrut(6));
        zeile("Wind (Stärke)", wf, faktor);
        zeile("Wind (Drehung)", wd, drehung);
        zeile("Aufschlag am Rand", wa, aufschlag);
        zeile("Meeresspiegel", wm, meer);
        JPanel kn = new JPanel(new GridLayout(1, 2, 6, 0));
        kn.setOpaque(false);
        kn.add(zurueck);
        kn.add(liveKnopf);
        add(links(kn));
        add(Box.createVerticalStrut(6));
        JLabel erkl = leise("<html>Der Wind folgt mit höchstens × 2 und 180° je Stunde Modellzeit, Aufschlag und Meeresspiegel zusammen mit 1 m je Stunde – "
                + "als Wasserstand am Rand des Gitters G2, rund 40 km um Hooge. Bis Hooge braucht das Wasser dann noch etwa eine Stunde.</html>");
        add(links(erkl));
        beschriften();
        zeige(null, false, Double.NaN, Double.NaN);
    }

    private void zeile(String name, JLabel w, FSlider s) {
        JPanel k = new JPanel(new BorderLayout());
        k.setOpaque(false);
        JLabel n = new JLabel(name);
        n.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        Faerber.rolle(n, "text");
        k.add(n, BorderLayout.WEST);
        k.add(w, BorderLayout.EAST);
        add(links(k));
        add(links(s));
        add(Box.createVerticalStrut(2));
    }

    private static JComponent links(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c instanceof FSlider ? 44 : c instanceof JLabel ? 400 : c.getPreferredSize().height));
        return c;
    }

    private static JLabel wert() {
        JLabel l = new JLabel(" ");
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        Faerber.rolle(l, "text");
        return l;
    }

    private static JLabel leise(String t) {
        JLabel l = new JLabel(t);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        Faerber.rolle(l, "leise");
        return l;
    }

    private Reglerwerte ausReglern() {
        return new Reglerwerte(faktor.getValue() * Reglerwerte.FAKTOR_SCHRITT, drehung.getValue() * Reglerwerte.DREHUNG_SCHRITT, aufschlag.getValue() * Reglerwerte.AUFSCHLAG_SCHRITT,
                meer.getValue() * Reglerwerte.MEER_SCHRITT);
    }

    private void beschriften() {
        Reglerwerte w = ausReglern();
        wf.setText(String.format(Locale.GERMAN, "× %.1f", w.windFaktor()));
        wd.setText(String.format(Locale.GERMAN, "%+.0f°", w.windDrehung()).replace('-', '−'));
        wa.setText(String.format(Locale.GERMAN, "%+.2f m", w.aufschlag()).replace('-', '−'));
        wm.setText(String.format(Locale.GERMAN, "%+.1f m", w.meeresspiegel()));
    }

    public Reglerwerte werte() { return werte; }

    /** Setzt die Regler, ohne etwas zu melden. */
    public void setze(Reglerwerte w) {
        still = true;
        try {
            faktor.setValue((int) Math.round(w.windFaktor() / Reglerwerte.FAKTOR_SCHRITT));
            drehung.setValue((int) Math.round(w.windDrehung() / Reglerwerte.DREHUNG_SCHRITT));
            aufschlag.setValue((int) Math.round(w.aufschlag() / Reglerwerte.AUFSCHLAG_SCHRITT));
            meer.setValue((int) Math.round(w.meeresspiegel() / Reglerwerte.MEER_SCHRITT));
            werte = ausReglern();
        } finally {
            still = false;
        }
        beschriften();
    }

    /**
     * Je Takt: was im Lauf wirkt ({Faktor, Drehung, Wasserstand am Rand}; null: kein Live-Lauf) und der Wind am Auge
     * (wirksam, m/s nach Osten und Norden; NaN: unbekannt).
     */
    public void zeige(double[] wirkt, boolean live, double windU, double windV) {
        liveKnopf.setVisible(!live);
        if (!live || wirkt == null) {
            ist.setText("<html><b>Kein Live-Lauf.</b></html>");
            hinweis.setText("<html>Die Regler wirken im Live-Lauf; was hier steht, gilt, sobald er läuft.</html>");
            rose.setze(Double.NaN, Double.NaN, 1, 0);
            return;
        }
        double f = wirkt[0], d = wirkt[1], r = wirkt[2];
        boolean folgt = Math.abs(f - werte.windFaktor()) > 1e-6 || Math.abs(d - werte.windDrehung()) > 1e-6 || Math.abs(r - werte.amRand()) > 1e-4;
        ist.setText(String.format(Locale.GERMAN, "<html><b>Wirksam:</b> Wind × %.2f, %+.0f°, am Rand %+.2f m</html>", f, d, r).replace("-", "−"));
        hinweis.setText(folgt ? String.format(Locale.GERMAN, "<html>Die Physik folgt noch: Ziel × %.1f, %+.0f°, am Rand %+.2f m.</html>", werte.windFaktor(), werte.windDrehung(), werte.amRand()).replace("-", "−")
                : "<html>Die Physik hat die Ziele erreicht.</html>");
        rose.setze(windU, windV, f, d);
    }

    /** Für Prüfungen: die Regler auf Werte stellen und melden, als hätte man sie gezogen und losgelassen. */
    public void stelle(Reglerwerte w) {
        setze(w);
        aenderung.accept(werte);
        eingriff.accept(werte);
    }

    /** Die Windrose: woher der Wind kommt, gestrichelt wie gemessen, durchgezogen wie er wirkt. */
    static final class Rose extends JComponent {
        private double u = Double.NaN, v = Double.NaN, f = 1, d;

        Rose() {
            setPreferredSize(new Dimension(96, 96));
            setMinimumSize(new Dimension(96, 96));
        }

        void setze(double u, double v, double f, double d) {
            this.u = u;
            this.v = v;
            this.f = f;
            this.d = d;
            repaint();
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2, rr = Math.min(w, h) / 2 - 12;
            g.setColor(th.tief);
            g.fillOval(cx - rr - 8, cy - rr - 8, 2 * rr + 16, 2 * rr + 16);
            g.setColor(th.linie);
            g.drawOval(cx - rr, cy - rr, 2 * rr, 2 * rr);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
            FontMetrics fm = g.getFontMetrics();
            String[] nesw = {"N", "O", "S", "W"};
            for (int k = 0; k < 4; k++) {
                double a = Math.toRadians(90 * k);
                int x = cx + (int) Math.round((rr + 1) * Math.sin(a)), y = cy - (int) Math.round((rr + 1) * Math.cos(a));
                g.setColor(th.leise);
                g.drawString(nesw[k], x - fm.stringWidth(nesw[k]) / 2, y + fm.getAscent() / 2 - 1);
            }
            if (Double.isNaN(u) || Double.isNaN(v) || Math.hypot(u, v) < 1e-6) {
                g.dispose();
                return;
            }
            // gemessen: der wirksame Wind zurückgedreht und geteilt
            double a = Math.toRadians(-d), c = Math.cos(a), s = Math.sin(a), fi = f > 1e-6 ? 1 / f : 0;
            double ue = fi * (u * c + v * s), ve = fi * (-u * s + v * c);
            double max = Math.max(Math.hypot(u, v), Math.hypot(ue, ve));
            pfeil(g, ue, ve, max, rr, cx, cy, th.leise, new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[] {4f, 3f}, 0f));
            pfeil(g, u, v, max, rr, cx, cy, th.marke, new BasicStroke(2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(th.text);
            String t = String.format(Locale.GERMAN, "%.1f m/s", Math.hypot(u, v));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
            fm = g.getFontMetrics();
            g.drawString(t, cx - fm.stringWidth(t) / 2, h - 1);
            g.dispose();
        }

        /** Ein Pfeil, der vom Rand (woher) zur Mitte und darüber hinaus zeigt (wohin), Länge nach dem Betrag. */
        private static void pfeil(Graphics2D g, double u, double v, double max, int rr, int cx, int cy, Color farbe, Stroke st) {
            double n = Math.hypot(u, v);
            if (n < 1e-6) return;
            double ex = u / n, ey = -v / n, len = rr * Math.max(0.25, n / Math.max(1e-6, max));
            double x0 = cx - ex * len, y0 = cy - ey * len, x1 = cx + ex * len * 0.55, y1 = cy + ey * len * 0.55;
            g.setColor(farbe);
            g.setStroke(st);
            Path2D.Double p = new Path2D.Double();
            p.moveTo(x0, y0);
            p.lineTo(x1, y1);
            g.draw(p);
            double kx = -ey, ky = ex;
            Path2D.Double spitze = new Path2D.Double();
            spitze.moveTo(x1 + ex * 6, y1 + ey * 6);
            spitze.lineTo(x1 - ex * 2 + kx * 5, y1 - ey * 2 + ky * 5);
            spitze.lineTo(x1 - ex * 2 - kx * 5, y1 - ey * 2 - ky * 5);
            spitze.closePath();
            g.fill(spitze);
        }
    }
}
