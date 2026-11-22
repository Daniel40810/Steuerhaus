package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.thema.Thema;
import com.dan.lauf.Pegelreihe;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * Die Zeitleiste mit der Pegelkurve (Werkbuch HalligHooge Teil B, B.4): über den ganzen Lauf der Pegel, Tag und Nacht
 * im Hintergrund, Marken für Hoch- und Niedrigwasser, die Zeit über einer Höhe als Band (Landunter) und ein Zeiger auf
 * der Szenenzeit. Ein Klick oder Ziehen meldet die gewünschte Zeit an {@link #sprung}.
 * <p>
 * Seit Steuerhaus W1 (Werkbuch HalligHooge 110) im Steuerhaus; die Farben kommen aus dem Thema.
 * <p>
 * Die Leiste rechnet nichts: Reihe, Marken und Bänder kommen aus {@link Pegelreihe}.
 */
public final class Zeitleiste extends JComponent {
    private static final int LINKS = 54, RECHTS = 16, OBEN = 20, UNTEN = 20;

    private double[] t = new double[0], pegel = new double[0], sonne = new double[0];
    private List<Pegelreihe.Marke> marken = List.of();
    private List<double[]> baender = List.of();
    private Instant start = Instant.EPOCH;
    private double t0, t1 = 1, jetzt = Double.NaN, hoehe = Double.NaN, pMin = -2, pMax = 3;
    private String hoeheName = "", pegelName = "";
    private boolean springen;
    private int staende = -1;
    private Pegelreihe reihe;
    private final ZoneId zone;
    /** Wird mit der gewünschten Szenenzeit (s seit Start) gerufen. */
    public DoubleConsumer sprung = x -> { };
    /** Seit W3: ein Klick auf ein Lesezeichen (Nummer in der Liste von {@link #lesezeichen}). */
    public java.util.function.IntConsumer lesezeichenKlick = i -> { };
    /** Seit W3: Lesezeichen des Szenarios als {Szenenzeit, Nummer} mit Namen. */
    private List<Object[]> lesezeichen = List.of();
    /** Seit W3: Fortschritt von Regiebuch oder Fahrt {Uhr, Länge} (null: nichts läuft) und ihr Name. */
    private double[] fortschritt;
    private String spurName = "";
    /** Seit Y2: Eingriffe der Regler im Live-Lauf als {Szenenzeit (s), Text}. */
    private List<Object[]> eingriffe = List.of();

    public Zeitleiste(ZoneId zone) {
        this.zone = zone;
        setPreferredSize(new Dimension(800, 150));
        setOpaque(true);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                int i = lesezeichenBei(e.getX(), e.getY());
                if (i >= 0) lesezeichenKlick.accept(i);
                else melde(e.getX());
            }

            @Override public void mouseDragged(MouseEvent e) { melde(e.getX()); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
    }

    /** Seit W3: Lesezeichen ({Szenenzeit (s), Nummer, Name}) und der Fortschritt von Regiebuch oder Fahrt. */
    public void spuren(List<Object[]> lesezeichen, double[] fortschritt, String name) {
        this.lesezeichen = lesezeichen == null ? List.of() : List.copyOf(lesezeichen);
        this.fortschritt = fortschritt;
        this.spurName = name == null ? "" : name;
    }

    /** Seit Y2: die Eingriffe der Regler ({Szenenzeit (s), Text}); leer: keine. */
    public void eingriffe(List<Object[]> e) {
        eingriffe = e == null ? List.of() : List.copyOf(e);
        repaint();
    }

    /** Die Nummer des Lesezeichens unter (x, y) im Band über der Zeitachse, sonst −1. */
    private int lesezeichenBei(int x, int y) {
        int y1 = getHeight() - UNTEN;
        if (y < y1 - 16 || y > y1 + 2) return -1;
        for (Object[] l : lesezeichen) {
            double ts = (Double) l[0];
            if (ts >= t0 && ts <= t1 && Math.abs(x(ts) - x) <= 6) return (Integer) l[1];
        }
        return -1;
    }

    private void melde(int x) {
        if (!springen || t1 <= t0) return;
        double w = Math.max(1, getWidth() - LINKS - RECHTS);
        sprung.accept(t0 + Math.max(0, Math.min(1, (x - LINKS) / w)) * (t1 - t0));
    }

    /**
     * Je Takt der Oberfläche: Reihe (null: keine), Bereich und Szenenzeit. Die Reihe wird nur neu gelesen, wenn sie
     * gewechselt hat oder gewachsen ist.
     *
     * @param hoehe    m NHN, über der das Band gilt (NaN: keins)
     * @param springen true, wenn die Quelle springen kann (Archiv)
     */
    public void zeige(Pegelreihe r, String pegelName, double hoehe, String hoeheName, Instant start, double t0, double t1, double jetzt, boolean springen) {
        int n = r == null ? 0 : r.laenge();
        if (r != reihe || n != staende) {
            reihe = r;
            staende = n;
            if (r == null) {
                t = pegel = sonne = new double[0];
                marken = List.of();
                baender = List.of();
            } else {
                t = r.zeiten();
                pegel = r.pegel();
                sonne = r.sonne();
                marken = r.marken();
                baender = Double.isNaN(hoehe) ? List.of() : r.ueber(hoehe);
            }
            double lo = Double.isNaN(hoehe) ? 0 : hoehe, hi = lo;
            for (double p : pegel) if (!Double.isNaN(p)) {
                lo = Math.min(lo, p);
                hi = Math.max(hi, p);
            }
            pMin = Math.floor(lo - 0.3);
            pMax = Math.ceil(hi + 0.3);
        }
        this.pegelName = pegelName == null ? "" : pegelName;
        this.hoehe = hoehe;
        this.hoeheName = hoeheName == null ? "" : hoeheName;
        this.start = start == null ? Instant.EPOCH : start;
        this.t0 = t0;
        this.t1 = Math.max(t1, t0 + 1);
        this.jetzt = jetzt;
        this.springen = springen;
        repaint();
    }

    private double x(double ts) { return LINKS + (ts - t0) / (t1 - t0) * Math.max(1, getWidth() - LINKS - RECHTS); }

    private double y(double p) { return OBEN + (pMax - p) / (pMax - pMin) * Math.max(1, getHeight() - OBEN - UNTEN); }

    private ZonedDateTime zeit(double ts) { return start.plusMillis(Math.round(ts * 1000)).atZone(zone); }

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        // seit Steuerhaus W1: Farben aus dem Thema (Labor: genau die Farben bis Teil A)
        Thema th = Thema.aktuell();
        Color GRUND = th.tief, NACHT = th.nacht, TAG = th.tag, LINIE = th.linie, TINTE = th.text, LEISE = th.leise, PRIEL = th.kurve, MARKE = th.marke;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        int w = getWidth(), h = getHeight(), x0 = LINKS, x1 = w - RECHTS, y0 = OBEN, y1 = h - UNTEN;
        g.setColor(GRUND);
        g.fillRect(0, 0, w, h);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        FontMetrics fm = g.getFontMetrics();
        g.setClip(x0, y0, x1 - x0, y1 - y0);
        // Tag und Nacht aus der Höhe der Sonne je Stand: Nacht unter −6°, Tag über 0°, dazwischen Dämmerung
        g.setColor(NACHT);
        g.fillRect(x0, y0, x1 - x0, y1 - y0);
        for (int i = 0; i + 1 < t.length; i++) {
            double s = Math.max(0, Math.min(1, (sonne[i] + 6) / 6));
            if (s <= 0) continue;
            g.setColor(new Color((int) (NACHT.getRed() + s * (TAG.getRed() - NACHT.getRed())), (int) (NACHT.getGreen() + s * (TAG.getGreen() - NACHT.getGreen())),
                    (int) (NACHT.getBlue() + s * (TAG.getBlue() - NACHT.getBlue()))));
            int a = (int) Math.floor(x(t[i])), b = (int) Math.ceil(x(t[i + 1]));
            g.fillRect(a, y0, Math.max(1, b - a), y1 - y0);
        }
        // Zeit über der Höhe als Band
        g.setColor(new Color(MARKE.getRed(), MARKE.getGreen(), MARKE.getBlue(), 46));
        for (double[] b : baender) g.fillRect((int) x(b[0]), y0, Math.max(2, (int) (x(b[1]) - x(b[0]))), y1 - y0);
        g.setClip(null);
        // Achsen: Pegel in ganzen Metern, Zeit in vollen Stunden
        g.setStroke(new BasicStroke(1f));
        for (double p = pMin; p <= pMax + 1e-9; p += 1) {
            int yy = (int) Math.round(y(p));
            g.setColor(p == 0 ? LEISE : LINIE);
            g.drawLine(x0, yy, x1, yy);
            g.setColor(LEISE);
            String s = String.format(Locale.GERMAN, "%+.0f m", p);
            g.drawString(s, x0 - 6 - fm.stringWidth(s), yy + 4);
        }
        double stunden = (t1 - t0) / 3600, schritt = stunden <= 14 ? 1 : stunden <= 30 ? 2 : stunden <= 80 ? 6 : 24;
        ZonedDateTime z = zeit(t0).truncatedTo(ChronoUnit.HOURS);
        for (int k = 0; k < 400; k++, z = z.plusHours(1)) {
            double ts = start.until(z.toInstant(), ChronoUnit.MILLIS) / 1000.0;
            if (ts > t1) break;
            if (ts < t0 || z.getHour() % (int) schritt != 0) continue;
            int xx = (int) Math.round(x(ts));
            g.setColor(LINIE);
            g.drawLine(xx, y1, xx, y1 + 4);
            g.setColor(LEISE);
            String s = z.getHour() == 0 ? String.format("%02d.%02d.", z.getDayOfMonth(), z.getMonthValue()) : String.format("%02d:00", z.getHour());
            g.drawString(s, xx - fm.stringWidth(s) / 2, y1 + 16);
        }
        if (!Double.isNaN(hoehe)) {
            int yy = (int) Math.round(y(hoehe));
            g.setColor(MARKE);
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5, 4}, 0));
            g.drawLine(x0, yy, x1, yy);
            g.drawString(hoeheName + String.format(Locale.GERMAN, " ab %+.2f m", hoehe), x0 + 6, yy - 4);
        }
        // Pegelkurve; trockene Stände unterbrechen sie
        Path2D.Double kurve = new Path2D.Double();
        boolean offen = false;
        for (int i = 0; i < t.length; i++) {
            if (Double.isNaN(pegel[i])) {
                offen = false;
                continue;
            }
            if (offen) kurve.lineTo(x(t[i]), y(pegel[i]));
            else kurve.moveTo(x(t[i]), y(pegel[i]));
            offen = true;
        }
        g.setColor(PRIEL);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(kurve);
        // Marken: Hochwasser über, Niedrigwasser unter der Kurve
        for (Pegelreihe.Marke m : marken) {
            int xx = (int) Math.round(x(m.t())), yy = (int) Math.round(y(m.pegel()));
            boolean hoch = m.art() == Pegelreihe.Art.HOCHWASSER;
            g.setColor(TINTE);
            g.fillOval(xx - 3, yy - 3, 6, 6);
            ZonedDateTime zt = zeit(m.t());
            String s = String.format(Locale.GERMAN, "%s %02d:%02d  %+.2f m", hoch ? "HW" : "NW", zt.getHour(), zt.getMinute(), m.pegel());
            int tx = Math.max(x0 + 2, Math.min(x1 - fm.stringWidth(s) - 2, xx - fm.stringWidth(s) / 2));
            g.drawString(s, tx, hoch ? Math.max(y0 - 6, yy - 8) : Math.min(y1 - 3, yy + 16));
        }
        // Zeiger auf der Szenenzeit
        if (!Double.isNaN(jetzt) && jetzt >= t0 && jetzt <= t1) {
            int xx = (int) Math.round(x(jetzt));
            g.setColor(MARKE);
            g.setStroke(new BasicStroke(1.6f));
            g.drawLine(xx, y0 - 4, xx, y1);
            g.fillPolygon(new int[] {xx - 5, xx + 5, xx}, new int[] {y0 - 10, y0 - 10, y0 - 3}, 3);
            ZonedDateTime zt = zeit(jetzt);
            double p = reihe == null ? Double.NaN : reihe.bei(jetzt);
            String s = String.format(Locale.GERMAN, "%02d:%02d", zt.getHour(), zt.getMinute()) + (Double.isNaN(p) ? "" : String.format(Locale.GERMAN, "  %+.2f m", p));
            int tx = xx + 8 + fm.stringWidth(s) > x1 ? xx - 8 - fm.stringWidth(s) : xx + 8;
            g.setColor(new Color(GRUND.getRed(), GRUND.getGreen(), GRUND.getBlue(), 200));
            g.fillRect(tx - 3, y0 + 2, fm.stringWidth(s) + 6, fm.getHeight());
            g.setColor(MARKE);
            g.drawString(s, tx, y0 + 2 + fm.getAscent());
        }
        // seit W3: Lesezeichen als Fähnchen über der Zeitachse
        for (Object[] l : lesezeichen) {
            double ts = (Double) l[0];
            if (ts < t0 || ts > t1) continue;
            int xx = (int) Math.round(x(ts));
            g.setColor(TINTE);
            g.setStroke(new BasicStroke(1.2f));
            g.drawLine(xx, y1 - 14, xx, y1);
            g.setColor(PRIEL);
            g.fillPolygon(new int[] {xx, xx + 8, xx}, new int[] {y1 - 14, y1 - 11, y1 - 8}, 3);
            String n = (String) l[2];
            if (n.length() > 22) n = n.substring(0, 21) + "…";
            g.setColor(LEISE);
            g.drawString(n, xx + 10, y1 - 6);
        }
        // seit Y2: Eingriffe der Regler als Linie mit Text oben
        Color ein = Thema.mische(th.primaer, Color.WHITE, 0.3);
        int letztesEnde = Integer.MIN_VALUE;
        for (Object[] e : eingriffe) {
            double ts = (Double) e[0];
            if (ts < t0 || ts > t1) continue;
            int xx = (int) Math.round(x(ts));
            g.setColor(ein);
            g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {3, 3}, 0));
            g.drawLine(xx, y0, xx, y1);
            g.fillPolygon(new int[] {xx - 4, xx + 4, xx}, new int[] {y0, y0, y0 + 6}, 3);
            String n = (String) e[1];
            if (n.length() > 34) n = n.substring(0, 33) + "…";
            int tx = Math.min(x1 - fm.stringWidth(n) - 2, xx + 6);
            if (tx <= letztesEnde + 4) continue;                         // dicht nacheinander: nur die Linie
            g.drawString(n, tx, y0 + 10);
            letztesEnde = tx + fm.stringWidth(n);
        }
        // seit W3: Fortschritt von Regiebuch oder Fahrt als Balken über der Leiste
        double[] fs = fortschritt;
        if (fs != null && fs[1] > 0) {
            int bx = x0, bw = x1 - x0, by = 4;
            g.setColor(LINIE);
            g.fillRect(bx, by, bw, 4);
            g.setColor(MARKE);
            g.fillRect(bx, by, (int) Math.round(bw * Math.max(0, Math.min(1, fs[0] / fs[1]))), 4);
            g.setColor(LEISE);
            g.drawString(spurName + "  " + com.dan.steuerhaus.regie.Regiebuch.zeit(fs[0]) + " / " + com.dan.steuerhaus.regie.Regiebuch.zeit(fs[1]), x0, by + 4 + fm.getAscent());
        }
        g.setColor(LEISE);
        String titel = pegelName.isEmpty() ? "" : "Pegel " + pegelName + (t.length == 0 ? " – noch keine Stände" : "");
        g.drawString(titel, x1 - fm.stringWidth(titel), y0 - 7);
        g.dispose();
    }
}
