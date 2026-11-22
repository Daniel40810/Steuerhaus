package com.dan.steuerhaus.buehne;

import com.dan.fframe.FFrame;
import com.dan.steuerhaus.thema.Thema;
import com.dan.lauf.Schnitt;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Das Fenster des Querschnitts (Werkbuch HalligHooge Teil A, P11c3): oben das Profil zur Zeit des Bilds – Grund,
 * Wasser bis zum Wasserstand, darunter die Geschwindigkeit quer zum Schnitt (nach links positiv, von A nach B gesehen)
 * und, wo es sie gibt, die Trübung; unten der Durchfluss Q(t) über das ganze Archiv mit der Zeit des Bilds und die
 * Bilanz über eine Tide (12 h 25 min um die Zeit des Bilds): wie viel Wasser nach links und nach rechts floss.
 */
public final class SchnittFenster {
    static final Color GRUND = new Color(122, 100, 76), WASSER = new Color(60, 125, 190, 170), LINKS = new Color(70, 200, 150), RECHTS = new Color(240, 150, 60),
            TRUEB = new Color(200, 170, 110);
    /** Länge einer Tide (s) für die Bilanz [G]: M2. */
    public static final double TIDE = 44_712;
    static final DateTimeFormatter UHR = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN);

    private final FFrame fenster = new FFrame("Schnitt");
    private final Flaeche flaeche = new Flaeche();

    public SchnittFenster() {
        // seit Y1: FFrame schließt sonst die ganze App (EXIT_ON_CLOSE); der Knopf in der Leiste schickt WINDOW_CLOSING
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
        com.dan.steuerhaus.thema.Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(flaeche, BorderLayout.CENTER);
        fenster.setPreferredFrameSize(new Dimension(1100, 680));
        fenster.setSize(1100, 680);
        fenster.setLocationRelativeTo(null);
    }

    public void oeffne() {
        fenster.setVisible(true);
        fenster.toFront();
    }

    public boolean offen() { return fenster.isVisible(); }

    public void schliesse() { fenster.dispose(); }

    /** Neuer Schnitt: Titel, Länge (m); Reihe und Profil folgen. */
    public void schnitt(String titel, Schnitt s) {
        if (s == null) {
            flaeche.d = null;
            flaeche.repaint();
            return;
        }
        Daten d = new Daten();
        d.titel = titel;
        d.laenge = s.laenge;
        flaeche.d = d;
        flaeche.repaint();
    }

    /** Die Reihe Q(t) ({zeiten, Q}) oder null, solange sie gelesen wird. */
    public void reihe(double[][] zq) {
        Daten d = flaeche.d;
        if (d == null) return;
        d.zeiten = zq == null ? null : zq[0];
        d.q = zq == null ? null : zq[1];
        flaeche.repaint();
    }

    /** Das Profil zur Zeit t (s seit start), dazu Zone, Start und ein Hinweis (darf null sein). */
    public void profil(Schnitt.Profil p, double t, Instant start, ZoneId zone, String hinweis) {
        Daten d = flaeche.d;
        if (d == null) return;
        d.p = p;
        d.t = t;
        d.start = start;
        d.zone = zone;
        d.hinweis = hinweis;
        flaeche.repaint();
    }

    /** Das Fenster als Bild (für die Ablage). */
    public BufferedImage bild(int w, int h) {
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        flaeche.male(g, w, h);
        g.dispose();
        return b;
    }

    /** Die Bilanz über die Tide um t (null ohne Reihe). */
    public Schnitt.Bilanz bilanz() {
        Daten d = flaeche.d;
        if (d == null || d.q == null) return null;
        return Schnitt.bilanz(d.zeiten, d.q, d.t - TIDE / 2, d.t + TIDE / 2);
    }

    static final class Daten {
        String titel, hinweis;
        double laenge, t = Double.NaN;
        double[] zeiten, q;
        Schnitt.Profil p;
        Instant start;
        ZoneId zone = ZoneId.of("Europe/Berlin");
    }

    static final class Flaeche extends JComponent {
        volatile Daten d;

        Flaeche() { setOpaque(true); }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            male(g, getWidth(), getHeight());
            g.dispose();
        }

        void male(Graphics2D g, int w, int h) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Thema.aktuell().grund);
            g.fillRect(0, 0, w, h);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
            Daten d = this.d;
            if (d == null) {
                text(g, "Kein Schnitt. Im Bedienfeld „Schnitt ziehen“, dann zwei Punkte im Bild klicken.", 16, 24, Thema.aktuell().text);
                return;
            }
            int l = 64, r = w - 20;
            text(g, d.titel + String.format(Locale.GERMAN, " · %.0f m", d.laenge) + (d.start != null && !Double.isNaN(d.t) ? " · " + UHR.format(d.start.plusMillis(Math.round(d.t * 1000)).atZone(d.zone)) : ""), 16, 20, Thema.aktuell().text);
            if (d.hinweis != null) text(g, d.hinweis, 16, 38, RECHTS);
            int y0 = 50, y1 = (int) (h * 0.46), y2 = y1 + 14, y3 = (int) (h * 0.62), y4 = y3 + 30, y5 = h - 44;
            profil(g, d, l, r, y0, y1);
            quer(g, d, l, r, y2, y3);
            reihe(g, d, l, r, y4, y5, h);
        }

        private void profil(Graphics2D g, Daten d, int l, int r, int o, int u) {
            Schnitt.Profil p = d.p;
            rahmen(g, l, r, o, u);
            if (p == null) {
                text(g, "Profil folgt mit dem nächsten Stand …", l + 8, o + 18, Thema.aktuell().leise);
                return;
            }
            double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
            for (int k = 0; k < p.punkte(); k++) {
                if (!Double.isNaN(p.grund()[k])) {
                    lo = Math.min(lo, p.grund()[k]);
                    hi = Math.max(hi, p.grund()[k]);
                }
                if (!Double.isNaN(p.eta()[k])) hi = Math.max(hi, p.eta()[k]);
            }
            if (!(hi > lo)) {
                lo -= 1;
                hi += 1;
            }
            double rand = 0.08 * (hi - lo);
            lo -= rand;
            hi += rand;
            double L = p.s()[p.punkte() - 1];
            // Wasser
            g.setColor(WASSER);
            for (int k = 0; k + 1 < p.punkte(); k++) {
                double e0 = p.eta()[k], e1 = p.eta()[k + 1];
                if (Double.isNaN(e0) || Double.isNaN(e1)) continue;
                Path2D.Double s = new Path2D.Double();
                s.moveTo(x(p.s()[k], L, l, r), y(e0, lo, hi, o, u));
                s.lineTo(x(p.s()[k + 1], L, l, r), y(e1, lo, hi, o, u));
                s.lineTo(x(p.s()[k + 1], L, l, r), y(p.grund()[k + 1], lo, hi, o, u));
                s.lineTo(x(p.s()[k], L, l, r), y(p.grund()[k], lo, hi, o, u));
                s.closePath();
                g.fill(s);
            }
            // Grund
            Path2D.Double b = new Path2D.Double();
            b.moveTo(l, u);
            for (int k = 0; k < p.punkte(); k++) b.lineTo(x(p.s()[k], L, l, r), y(Double.isNaN(p.grund()[k]) ? lo : p.grund()[k], lo, hi, o, u));
            b.lineTo(r, u);
            b.closePath();
            g.setColor(GRUND);
            g.fill(b);
            achse(g, lo, hi, o, u, l, "m NHN");
            text(g, "A", l + 2, u - 4, Thema.aktuell().text);
            text(g, "B", r - 10, u - 4, Thema.aktuell().text);
            // Trübung auf eigener Skala (rechts)
            double tm = 0;
            for (double t : p.truebung()) if (!Double.isNaN(t)) tm = Math.max(tm, t);
            if (tm > 0) {
                g.setColor(TRUEB);
                g.setStroke(new BasicStroke(1.5f));
                Path2D.Double tl = new Path2D.Double();
                boolean an = false;
                for (int k = 0; k < p.punkte(); k++) {
                    double t = p.truebung()[k];
                    if (Double.isNaN(t)) {
                        an = false;
                        continue;
                    }
                    double xx = x(p.s()[k], L, l, r), yy = u - (u - o) * t / (tm * 1.1);
                    if (an) tl.lineTo(xx, yy);
                    else tl.moveTo(xx, yy);
                    an = true;
                }
                g.draw(tl);
                text(g, String.format(Locale.GERMAN, "Trübung bis %.0f g/m³", tm), r - 150, o + 14, TRUEB);
            }
        }

        private void quer(Graphics2D g, Daten d, int l, int r, int o, int u) {
            Schnitt.Profil p = d.p;
            rahmen(g, l, r, o, u);
            if (p == null) return;
            double m = 0.05;
            for (double v : p.quer()) if (!Double.isNaN(v)) m = Math.max(m, Math.abs(v));
            double L = p.s()[p.punkte() - 1], mitte = (o + u) / 2.0;
            for (int k = 0; k < p.punkte(); k++) {
                double v = p.quer()[k];
                if (Double.isNaN(v)) continue;
                double hh = (u - o) / 2.0 * v / m;
                g.setColor(v >= 0 ? LINKS : RECHTS);
                double xx = x(p.s()[k], L, l, r);
                g.fill(new java.awt.geom.Rectangle2D.Double(xx - 1, Math.min(mitte, mitte - hh), 2.2, Math.abs(hh)));
            }
            g.setColor(Thema.aktuell().leise);
            g.drawLine(l, (int) mitte, r, (int) mitte);
            text(g, String.format(Locale.GERMAN, "quer ±%.2f m/s", m), 4, (int) mitte + 4, Thema.aktuell().leise);
            text(g, "nach links (von A nach B)", r - 170, o + 12, LINKS);
            text(g, "nach rechts", r - 170, u - 3, RECHTS);
        }

        private void reihe(Graphics2D g, Daten d, int l, int r, int o, int u, int h) {
            rahmen(g, l, r, o, u);
            if (d.q == null) {
                text(g, "Der Durchfluss wird aus dem Archiv gelesen …", l + 8, o + 18, Thema.aktuell().leise);
                return;
            }
            double[] z = d.zeiten, q = d.q;
            if (z.length < 2) {
                text(g, "Keine Strömung: kein Durchfluss.", l + 8, o + 18, Thema.aktuell().leise);
                return;
            }
            double m = 1;
            for (double v : q) if (!Double.isNaN(v)) m = Math.max(m, Math.abs(v));
            double t0 = z[0], t1 = z[z.length - 1], mitte = (o + u) / 2.0;
            g.setColor(Thema.aktuell().leise);
            g.drawLine(l, (int) mitte, r, (int) mitte);
            // die Tide der Bilanz
            if (!Double.isNaN(d.t)) {
                double a = x(Math.max(t0, d.t - TIDE / 2) - t0, t1 - t0, l, r), b = x(Math.min(t1, d.t + TIDE / 2) - t0, t1 - t0, l, r);
                g.setColor(new Color(255, 255, 255, 18));
                g.fill(new java.awt.geom.Rectangle2D.Double(a, o, b - a, u - o));
            }
            g.setStroke(new BasicStroke(1.6f));
            Path2D.Double s = new Path2D.Double();
            boolean an = false;
            for (int k = 1; k < z.length; k++) {
                if (Double.isNaN(q[k])) {
                    an = false;
                    continue;
                }
                double xx = x(z[k] - t0, t1 - t0, l, r), yy = mitte - (u - o) / 2.0 * q[k] / m;
                if (an) s.lineTo(xx, yy);
                else s.moveTo(xx, yy);
                an = true;
            }
            g.setColor(Thema.aktuell().text);
            g.draw(s);
            text(g, String.format(Locale.GERMAN, "Q ±%.0f m³/s", m), 4, o + 12, Thema.aktuell().leise);
            if (d.start != null) {
                text(g, UHR.format(d.start.plusMillis(Math.round(t0 * 1000)).atZone(d.zone)), l, u + 14, Thema.aktuell().leise);
                text(g, UHR.format(d.start.plusMillis(Math.round(t1 * 1000)).atZone(d.zone)), r - 70, u + 14, Thema.aktuell().leise);
            }
            if (!Double.isNaN(d.t)) {
                double xx = x(d.t - t0, t1 - t0, l, r);
                g.setColor(RECHTS);
                g.drawLine((int) xx, o, (int) xx, u);
                int k = 1;
                while (k + 1 < z.length && z[k] < d.t) k++;
                Schnitt.Bilanz b = Schnitt.bilanz(z, q, d.t - TIDE / 2, d.t + TIDE / 2);
                text(g, String.format(Locale.GERMAN, "Q jetzt %+.0f m³/s · über die Tide um jetzt: nach links %.2f Mio. m³, nach rechts %.2f Mio. m³, netto %+.2f Mio. m³",
                        q[k], b.links() / 1e6, b.rechts() / 1e6, b.netto() / 1e6), l, h - 12, Thema.aktuell().text);
            }
        }

        static double x(double s, double L, int l, int r) { return l + (r - l) * (L > 0 ? s / L : 0); }

        static double y(double v, double lo, double hi, int o, int u) { return u - (u - o) * (v - lo) / (hi - lo); }

        static void rahmen(Graphics2D g, int l, int r, int o, int u) {
            g.setColor(new Color(20, 26, 32));
            g.fillRect(l, o, r - l, u - o);
        }

        static void achse(Graphics2D g, double lo, double hi, int o, int u, int l, String einheit) {
            g.setColor(Thema.aktuell().leise);
            double schritt = Math.pow(10, Math.floor(Math.log10((hi - lo) / 4)));
            if ((hi - lo) / schritt > 8) schritt *= 2;
            for (double v = Math.ceil(lo / schritt) * schritt; v <= hi; v += schritt) {
                int yy = (int) y(v, lo, hi, o, u);
                g.drawLine(l - 4, yy, l, yy);
                // die Einheit steht oben in der Achse (o + 12): Zahlen, die sie überdecken würden, entfallen (P11d)
                if (yy >= o + 18) text(g, String.format(Locale.GERMAN, "%.1f", v), 8, yy + 4, Thema.aktuell().leise);
            }
            text(g, einheit, 8, o + 12, Thema.aktuell().leise);
        }

        static void text(Graphics2D g, String t, int x, int y, Color c) {
            g.setColor(c);
            g.drawString(t, x, y);
        }
    }
}
