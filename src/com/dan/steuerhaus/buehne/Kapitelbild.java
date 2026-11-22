package com.dan.steuerhaus.buehne;

import com.dan.lauf.Sonde;
import com.dan.render.overlay.Einblendung;
import com.dan.render.overlay.Kachel;
import com.dan.steuerhaus.thema.Thema;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Tafel eines Kapitels auf der Karte (seit X2, Werkbuch HalligHooge 146, F244 A): Kapitel, Abschnitt, Titel, Text,
 * auf Wunsch die Wasserstandskurve einer Sonde mit der Szenenzeit, darunter die Tasten. Eine {@link Kachel} der
 * {@link Einblendung} unten links; neu gemalt, wenn sich Inhalt, Bildgröße oder (mit Sonde) die Zeit ändert, höchstens
 * zweimal je Sekunde. {@link #aktualisiere} ruft der Faden der Bildschleife (über den {@link Regisseur}).
 */
public final class Kapitelbild {
    private final Einblendung e;
    private volatile Kachel k;
    private volatile String kapitel, titel, text;
    private volatile int nr, von;
    private volatile boolean wartet, neu;
    private volatile Sonde sonde;
    private int W, H;
    private double tGemalt = Double.NaN;
    private long gemaltMs;

    Kapitelbild(Einblendung e) { this.e = e; }

    void zeige(String kapitel, String titel, String text, int nr, int von) {
        this.kapitel = kapitel;
        this.titel = titel;
        this.text = text;
        this.nr = nr;
        this.von = von;
        this.wartet = false;
        this.sonde = null;
        neu = true;
    }

    void wartet(boolean w) {
        if (w != wartet) {
            wartet = w;
            neu = true;
        }
    }

    void sonde(Sonde s) {
        sonde = s;
        neu = true;
    }

    boolean sichtbar() { return titel != null; }

    void aus() {
        titel = null;
        sonde = null;
        Kachel alt = k;
        k = null;
        if (alt != null) e.weg(alt);
    }

    /** Je Bild: malt neu, wenn nötig; ts = Szenenzeit (s seit Start der Quelle). */
    void aktualisiere(int w, int h, double ts) {
        if (titel == null || w < 50 || h < 50) return;
        long jetzt = System.currentTimeMillis();
        boolean zeit = sonde != null && !Double.isNaN(ts) && Math.abs(ts - tGemalt) > 30 && jetzt - gemaltMs > 500;
        if (!neu && w == W && h == H && !zeit) return;
        neu = false;
        W = w;
        H = h;
        tGemalt = ts;
        gemaltMs = jetzt;
        malen(ts);
    }

    private void malen(double ts) {
        BufferedImage im = bild(kapitel, titel, text, nr, von, wartet, sonde, ts, H);
        double s = Math.max(0.75, Math.min(1.6, H / 1080.0));
        Kachel neu = e.neu("Kapitel, Tafel", im.getWidth(), im.getHeight()).imBild(Kachel.Ecke.UNTEN_LINKS, (int) Math.round(28 * s), (int) Math.round(28 * s));
        neu.male(gg -> gg.drawImage(im, 0, 0, null));
        neu.sichtbar = true;
        Kachel alt = k;
        k = neu;
        if (alt != null) e.weg(alt);
    }

    /** Die Tafel als Bild für ein Bild der Höhe H (auch für die Prüfung, ohne Karte). */
    public static BufferedImage bild(String kapitel, String titel, String text, int nr, int von, boolean wartet, Sonde sonde, double ts, int H) {
        double s = Math.max(0.75, Math.min(1.6, H / 1080.0));
        int breite = (int) Math.round(560 * s), rand = (int) Math.round(22 * s);
        Thema t = Thema.aktuell();
        Font fKap = new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(12 * s)), fTitel = t.titel((float) (26 * s)),
                fText = new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(16 * s)), fFuss = new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(12 * s));
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        pg.setFont(fText);
        List<String> zeilen = umbrechen(text == null ? "" : text, pg.getFontMetrics(), breite - 2 * rand);
        int zh = pg.getFontMetrics().getHeight();
        pg.setFont(fTitel);
        List<String> tz = umbrechen(titel, pg.getFontMetrics(), breite - 2 * rand);
        int th = pg.getFontMetrics().getHeight();
        pg.dispose();
        Sonde so = sonde;
        int kurve = so != null ? (int) Math.round(120 * s) : 0;
        int hoehe = rand + (int) Math.round(18 * s) + tz.size() * th + (int) Math.round(8 * s) + zeilen.size() * zh + (kurve > 0 ? kurve + (int) Math.round(14 * s) : 0)
                + (int) Math.round(44 * s) + rand / 2;
        BufferedImage im = new BufferedImage(breite, hoehe, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(1, 1, breite - 2, hoehe - 2, 18 * s, 18 * s);
        g.setColor(Thema.mitAlpha(t.tief, 228));
        g.fill(r);
        g.setColor(Thema.mitAlpha(t.primaer, 200));
        g.setStroke(new BasicStroke((float) (1.6 * s)));
        g.draw(r);
        int y = rand + (int) Math.round(10 * s);
        g.setFont(fKap);
        g.setColor(t.primaer);
        g.drawString(((kapitel == null ? "" : kapitel) + "  ·  " + nr + " von " + von).toUpperCase(Locale.GERMAN), rand, y);
        y += (int) Math.round(8 * s);
        g.setFont(fTitel);
        g.setColor(t.text);
        for (String z : tz) {
            y += th;
            g.drawString(z, rand, y - g.getFontMetrics().getDescent());
        }
        y += (int) Math.round(8 * s);
        g.setFont(fText);
        g.setColor(Thema.mische(t.text, t.leise, 0.15));
        for (String z : zeilen) {
            y += zh;
            g.drawString(z, rand, y - g.getFontMetrics().getDescent());
        }
        if (so != null) {
            y += (int) Math.round(14 * s);
            kurve(g, t, so, rand, y, breite - 2 * rand, kurve, ts, s);
            y += kurve;
        }
        // Fuß: Tasten
        y += (int) Math.round(30 * s);
        g.setFont(fFuss);
        g.setColor(t.leise);
        g.drawString("Zurück: Rücktaste   ·   Ende: Esc", rand, y);
        String weiter = "Weiter  ›";
        g.setFont(fKap.deriveFont(Font.BOLD, (float) (13 * s)));
        FontMetrics fm = g.getFontMetrics();
        int bw = fm.stringWidth(weiter) + (int) Math.round(26 * s), bh = (int) Math.round(28 * s), bx = breite - rand - bw, by = y - bh + (int) Math.round(8 * s);
        RoundRectangle2D.Double kn = new RoundRectangle2D.Double(bx, by, bw, bh, bh, bh);
        g.setColor(wartet ? t.primaer : Thema.mitAlpha(t.linie, 255));
        g.fill(kn);
        g.setColor(wartet ? t.tief : t.leise);
        g.drawString(weiter, bx + (bw - fm.stringWidth(weiter)) / 2, by + (bh + fm.getAscent() - fm.getDescent()) / 2);
        g.dispose();
        return im;
    }

    /** Wasserstand der Sonde über das ganze Szenario, die Marke der Szenenzeit, trocken als Lücke. */
    private static void kurve(Graphics2D g, Thema t, Sonde so, int x, int y, int w, int h, double ts, double s) {
        Sonde.Reihe r = so.reihe(Sonde.WASSERSTAND);
        g.setColor(Thema.mitAlpha(t.flaeche, 200));
        g.fillRoundRect(x, y, w, h, (int) (10 * s), (int) (10 * s));
        if (r == null || r.t().length < 2) return;
        double lo = r.min(), hi = r.max();
        if (Double.isNaN(lo)) return;
        if (hi - lo < 0.2) hi = lo + 0.2;
        double t0 = r.t()[0], t1 = r.t()[r.t().length - 1];
        int px = x + (int) (8 * s), pw = w - (int) (16 * s), py = y + (int) (18 * s), ph = h - (int) (26 * s);
        Path2D.Double p = new Path2D.Double();
        boolean an = false;
        for (int i = 0; i < r.t().length; i++) {
            double v = r.wert()[i];
            if (Double.isNaN(v)) {
                an = false;
                continue;
            }
            double xx = px + (r.t()[i] - t0) / (t1 - t0) * pw, yy = py + ph - (v - lo) / (hi - lo) * ph;
            if (!an) p.moveTo(xx, yy);
            else p.lineTo(xx, yy);
            an = true;
        }
        g.setColor(t.kurve);
        g.setStroke(new BasicStroke((float) (2 * s)));
        g.draw(p);
        if (!Double.isNaN(ts) && ts >= t0 && ts <= t1) {
            double xx = px + (ts - t0) / (t1 - t0) * pw;
            g.setColor(t.primaer);
            g.setStroke(new BasicStroke((float) (1.5 * s)));
            g.draw(new Line2D.Double(xx, py - 4 * s, xx, py + ph));
            double v = r.bei(ts);
            if (!Double.isNaN(v)) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, (int) Math.round(12 * s)));
                String z = String.format(Locale.GERMAN, "%+.2f m NHN", v).replace("-", "−");
                int zw = g.getFontMetrics().stringWidth(z);
                g.drawString(z, (int) Math.min(xx + 6 * s, px + pw - zw), (int) (y + 14 * s));
            }
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, (int) Math.round(11 * s)));
        g.setColor(t.leise);
        g.drawString(so.name + ": Wasserstand", px, (int) (y + 14 * s));
        g.setColor(new Color(0, 0, 0, 0));
    }

    static List<String> umbrechen(String text, FontMetrics fm, int w) {
        List<String> l = new ArrayList<>();
        for (String absatz : text.split("\n")) {
            StringBuilder z = new StringBuilder();
            for (String wort : absatz.split("\\s+")) {
                if (wort.isEmpty()) continue;
                String probe = z.length() == 0 ? wort : z + " " + wort;
                if (fm.stringWidth(probe) > w && z.length() > 0) {
                    l.add(z.toString());
                    z = new StringBuilder(wort);
                } else z = new StringBuilder(probe);
            }
            if (z.length() > 0) l.add(z.toString());
        }
        return l;
    }
}
