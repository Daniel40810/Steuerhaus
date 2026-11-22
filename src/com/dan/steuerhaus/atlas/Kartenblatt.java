package com.dan.steuerhaus.atlas;

import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Das Kartenblatt des Atlas (seit X1, Werkbuch HalligHooge 145): die Karte einer Ebene, zum Verschieben (ziehen) und
 * Vergrößern (Rad, Doppelklick, Knöpfe), mit den Orten; ein Klick auf einen Ort meldet ihn ({@link #beiOrt}). Oben links
 * die Ebenen zum Umschalten, oben rechts Nordpfeil und Zoom, unten Maßstab und Quelle.
 * <p>
 * Die Bilder lädt ein eigener Faden; je Ebene halbiert er sie dreimal (Stufen), damit eine verkleinerte Karte sauber bleibt.
 */
public final class Kartenblatt extends JComponent {
    private Atlas atlas;
    private final ZoneId zone;
    private int ebene;
    /** Meter je Bildpunkt des Schirms und die Mitte (UTM). */
    private double mpp = Double.NaN, mitteO, mitteN;
    private final Map<Integer, BufferedImage[]> stufen = new HashMap<>();
    private final Map<Integer, Boolean> laedt = new HashMap<>();
    private Atlas.Ort gewaehlt, unter;
    private int ziehX, ziehY;
    private boolean gezogen;
    /** Klick auf einen Ort (null: ins Leere). */
    public Consumer<Atlas.Ort> beiOrt = o -> { };
    /** Wechsel der Ebene. */
    public IntConsumer beiEbene = i -> { };
    private final List<Object[]> knoepfe = new ArrayList<>();
    /** Name des Pegels in der Zeile unter den Ebenen (Szenendatei {@code pegel.name}). */
    public String pegelName = "Pegel";
    static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN);

    public Kartenblatt(ZoneId zone) {
        this.zone = zone;
        setOpaque(true);
        setFocusable(true);
        MouseAdapter m = new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                ziehX = e.getX();
                ziehY = e.getY();
                gezogen = false;
            }

            @Override public void mouseDragged(MouseEvent e) {
                if (Double.isNaN(mpp)) return;
                int dx = e.getX() - ziehX, dy = e.getY() - ziehY;
                if (Math.abs(dx) + Math.abs(dy) > 2) gezogen = true;
                mitteO -= dx * mpp;
                mitteN += dy * mpp;
                ziehX = e.getX();
                ziehY = e.getY();
                setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                repaint();
            }

            @Override public void mouseReleased(MouseEvent e) { setCursor(Cursor.getDefaultCursor()); }

            @Override public void mouseClicked(MouseEvent e) {
                if (gezogen || !SwingUtilities.isLeftMouseButton(e)) return;
                for (Object[] k : knoepfe)
                    if (((Shape) k[0]).contains(e.getPoint())) {
                        ((Runnable) k[1]).run();
                        return;
                    }
                if (e.getClickCount() == 2) {
                    zoom(0.5, e.getX(), e.getY());
                    return;
                }
                Atlas.Ort o = ortBei(e.getX(), e.getY());
                waehle(o);
                beiOrt.accept(o);
            }

            @Override public void mouseMoved(MouseEvent e) {
                Atlas.Ort o = ortBei(e.getX(), e.getY());
                boolean knopf = false;
                for (Object[] k : knoepfe) if (((Shape) k[0]).contains(e.getPoint())) knopf = true;
                setCursor(Cursor.getPredefinedCursor(o != null || knopf ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
                if (o != unter) {
                    unter = o;
                    setToolTipText(o == null ? null : o.name() + " (" + o.art() + ")");
                    repaint();
                }
            }

            @Override public void mouseWheelMoved(MouseWheelEvent e) { zoom(Math.pow(1.25, e.getPreciseWheelRotation()), e.getX(), e.getY()); }
        };
        addMouseListener(m);
        addMouseMotionListener(m);
        addMouseWheelListener(m);
    }

    /** Setzt den Atlas und zeigt die ganze Karte in der ersten Ebene. */
    public void atlas(Atlas a) {
        atlas = a;
        stufen.clear();
        laedt.clear();
        ebene = 0;
        mpp = Double.NaN;
        gewaehlt = null;
        repaint();
    }

    public Atlas atlas() { return atlas; }

    public int ebene() { return ebene; }

    public void ebene(int i) {
        if (atlas == null || i < 0 || i >= atlas.ebenen.size() || i == ebene) return;
        ebene = i;
        beiEbene.accept(i);
        repaint();
    }

    public Atlas.Ort gewaehlt() { return gewaehlt; }

    /** Hebt einen Ort hervor (null: keinen); rückt ihn ins Bild, wenn er außerhalb liegt. */
    public void waehle(Atlas.Ort o) {
        gewaehlt = o;
        if (o != null && !Double.isNaN(mpp)) {
            double[] p = bild(o.ost(), o.nord());
            if (p[0] < 40 || p[1] < 40 || p[0] > getWidth() - 40 || p[1] > getHeight() - 40) {
                mitteO = o.ost();
                mitteN = o.nord();
            }
        }
        repaint();
    }

    /** Die ganze Karte ins Bild. */
    public void ganz() {
        if (atlas == null || getWidth() < 10) return;
        double w = atlas.karte[2] - atlas.karte[0], h = atlas.karte[3] - atlas.karte[1];
        mpp = Math.max(w / getWidth(), h / getHeight()) * 1.02;
        mitteO = (atlas.karte[0] + atlas.karte[2]) / 2;
        mitteN = (atlas.karte[1] + atlas.karte[3]) / 2;
        repaint();
    }

    /** Zeigt den Ort mit einem Ausschnitt von etwa {@code meter} Breite. */
    public void hin(double ost, double nord, double meter) {
        if (getWidth() < 10) return;
        mitteO = ost;
        mitteN = nord;
        mpp = meter / getWidth();
        repaint();
    }

    void zoom(double f, int px, int py) {
        if (Double.isNaN(mpp) || atlas == null) return;
        double[] o = utm(px, py);
        double neu = Math.max(atlas.zelle / 4, Math.min(mppGanz() * 1.6, mpp * f));
        mitteO = o[0] - (px - getWidth() / 2.0) * neu;
        mitteN = o[1] + (py - getHeight() / 2.0) * neu;
        mpp = neu;
        repaint();
    }

    private double mppGanz() {
        double w = atlas.karte[2] - atlas.karte[0], h = atlas.karte[3] - atlas.karte[1];
        return Math.max(w / Math.max(1, getWidth()), h / Math.max(1, getHeight()));
    }

    double[] bild(double ost, double nord) { return new double[] {getWidth() / 2.0 + (ost - mitteO) / mpp, getHeight() / 2.0 - (nord - mitteN) / mpp}; }

    double[] utm(double px, double py) { return new double[] {mitteO + (px - getWidth() / 2.0) * mpp, mitteN - (py - getHeight() / 2.0) * mpp}; }

    /** Der Ort unter dem Bildpunkt (bis 14 Punkte daneben); null: keiner. */
    public Atlas.Ort ortBei(int px, int py) {
        if (atlas == null || Double.isNaN(mpp)) return null;
        Atlas.Ort best = null;
        double bd = 14;
        for (Atlas.Ort o : atlas.orte) {
            double[] p = bild(o.ost(), o.nord());
            double d = Math.hypot(p[0] - px, p[1] - py);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Bilder

    private BufferedImage[] stufen(int i) {
        BufferedImage[] s = stufen.get(i);
        if (s != null || Boolean.TRUE.equals(laedt.get(i)) || atlas == null) return s;
        laedt.put(i, true);
        java.io.File f = atlas.ebenen.get(i).bild();
        Thread t = new Thread(() -> {
            BufferedImage[] r = null;
            try {
                BufferedImage b = ImageIO.read(f);
                if (b != null) {
                    r = new BufferedImage[4];
                    r[0] = b;
                    for (int k = 1; k < 4; k++) r[k] = halb(r[k - 1]);
                }
            } catch (Exception e) {
                System.out.println("Atlas: Karte " + f + " nicht lesbar: " + e);
            }
            BufferedImage[] fertig = r;
            SwingUtilities.invokeLater(() -> {
                if (fertig != null) stufen.put(i, fertig);
                laedt.put(i, fertig == null);                          // nicht lesbar: nicht noch einmal versuchen
                repaint();
            });
        }, "Atlas-Karte");
        t.setDaemon(true);
        t.start();
        return null;
    }

    /** Ist die Karte der gewählten Ebene geladen? */
    public boolean geladen() { return stufen.containsKey(ebene); }

    /** Lädt alle Ebenen im Voraus (nach der ersten). */
    public void vorladen() {
        if (atlas == null) return;
        for (int i = 0; i < atlas.ebenen.size(); i++) stufen(i);
    }

    private static BufferedImage halb(BufferedImage a) {
        int w = Math.max(1, a.getWidth() / 2), h = Math.max(1, a.getHeight() / 2);
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(a, 0, 0, w, h, null);
        g.dispose();
        return b;
    }

    // ------------------------------------------------------------------ Malen

    @Override protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        Thema t = Thema.aktuell();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setColor(t.tief);
        g.fillRect(0, 0, getWidth(), getHeight());
        knoepfe.clear();
        if (atlas == null) {
            mitte(g, t, "Kein Atlas – einmal mit --atlas rechnen.");
            g.dispose();
            return;
        }
        if (Double.isNaN(mpp)) ganz();
        if (Double.isNaN(mpp)) {
            g.dispose();
            return;
        }
        BufferedImage[] s = atlas.ebenen.isEmpty() ? null : stufen(ebene);
        if (s == null && !atlas.ebenen.isEmpty()) {
            // solange die Ebene lädt: eine schon geladene zeigen
            for (Map.Entry<Integer, BufferedImage[]> e : stufen.entrySet()) {
                s = e.getValue();
                break;
            }
        }
        if (s != null) karte(g, s);
        else mitte(g, t, atlas.ebenen.isEmpty() ? "Der Atlas hat keine Ebene." : "Die Karte lädt …");
        orte(g, t);
        ebenenKnoepfe(g, t);
        nordUndZoom(g, t);
        massstab(g, t);
        g.dispose();
    }

    private void karte(Graphics2D g, BufferedImage[] s) {
        double z0 = atlas.zelle;
        int l = 0;
        while (l < s.length - 1 && z0 * Math.pow(2, l + 1) <= mpp) l++;
        BufferedImage b = s[l];
        double zl = (atlas.karte[2] - atlas.karte[0]) / b.getWidth();
        // Bildpunkte des Bilds im Fenster
        double[] a = utm(0, 0), e = utm(getWidth(), getHeight());
        int sx0 = (int) Math.floor((a[0] - atlas.karte[0]) / zl), sy0 = (int) Math.floor((atlas.karte[3] - a[1]) / zl);
        int sx1 = (int) Math.ceil((e[0] - atlas.karte[0]) / zl), sy1 = (int) Math.ceil((atlas.karte[3] - e[1]) / zl);
        sx0 = Math.max(0, sx0);
        sy0 = Math.max(0, sy0);
        sx1 = Math.min(b.getWidth(), sx1);
        sy1 = Math.min(b.getHeight(), sy1);
        if (sx1 <= sx0 || sy1 <= sy0) return;
        double[] p0 = bild(atlas.karte[0] + sx0 * zl, atlas.karte[3] - sy0 * zl), p1 = bild(atlas.karte[0] + sx1 * zl, atlas.karte[3] - sy1 * zl);
        g.drawImage(b, (int) Math.round(p0[0]), (int) Math.round(p0[1]), (int) Math.round(p1[0]), (int) Math.round(p1[1]), sx0, sy0, sx1, sy1, null);
    }

    private void orte(Graphics2D g, Thema t) {
        List<Atlas.Ort> folge = new ArrayList<>(atlas.orte);
        folge.sort((x, y) -> Integer.compare(rang(x.art()), rang(y.art())));
        List<Rectangle> belegt = new ArrayList<>();
        for (Object[] k : knoepfe) belegt.add(((Shape) k[0]).getBounds());
        for (Atlas.Ort o : folge) {
            double[] p = bild(o.ost(), o.nord());
            if (p[0] < -50 || p[1] < -50 || p[0] > getWidth() + 50 || p[1] > getHeight() + 50) continue;
            boolean punkt = AtlasBau.WARFT.contains(o.art());
            boolean hervor = o == gewaehlt || o == unter;
            if (punkt) {
                double d = hervor ? 11 : 8;
                g.setColor(new Color(0, 0, 0, 150));
                g.fill(new Ellipse2D.Double(p[0] - d / 2 - 1.5, p[1] - d / 2 - 1.5, d + 3, d + 3));
                g.setColor(o.art().equals("Kirche") ? t.akzent.brighter() : o.art().equals("Anleger") ? t.kurve : t.marke);
                g.fill(new Ellipse2D.Double(p[0] - d / 2, p[1] - d / 2, d, d));
            }
            if (o == gewaehlt) {
                g.setColor(t.primaer);
                g.setStroke(new BasicStroke(2.5f));
                g.draw(new Ellipse2D.Double(p[0] - 13, p[1] - 13, 26, 26));
            }
            // Namen der Warften erst, wenn man nah genug ist; Hallig, Inseln und Sände immer
            if (punkt && mpp > 9 && !hervor) continue;
            Font f = punkt ? new Font(Font.SANS_SERIF, Font.BOLD, 12) : t.titel(o.art().equals("Hallig") ? 19f : 15f).deriveFont(Font.BOLD | Font.ITALIC);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            int w = fm.stringWidth(o.name());
            int x = (int) Math.round(punkt ? p[0] + 9 : p[0] - w / 2.0), y = (int) Math.round(punkt ? p[1] + fm.getAscent() / 2.0 - 2 : p[1] + fm.getAscent() / 2.0);
            Rectangle r = new Rectangle(x - 2, y - fm.getAscent(), w + 4, fm.getHeight());
            boolean frei = true;
            for (Rectangle b : belegt) if (b.intersects(r)) frei = false;
            if (!frei && !hervor) continue;
            belegt.add(r);
            schrift(g, o.name(), x, y, hervor ? t.primaer.brighter() : punkt ? new Color(250, 246, 236) : new Color(236, 232, 220, 235));
        }
    }

    static int rang(String art) {
        return switch (art) {
            case "Hallig" -> 0;
            case "Warft" -> 1;
            case "Kirche" -> 2;
            case "Anleger" -> 3;
            case "Insel" -> 4;
            case "Sand" -> 5;
            default -> 6;
        };
    }

    /** Text mit dunklem Saum, lesbar auf dem Luftbild. */
    static void schrift(Graphics2D g, String s, int x, int y, Color c) {
        TextLayout tl = new TextLayout(s, g.getFont(), g.getFontRenderContext());
        Shape sh = tl.getOutline(AffineTransform.getTranslateInstance(x, y));
        g.setColor(new Color(0, 0, 0, 170));
        g.setStroke(new BasicStroke(3.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(sh);
        g.setColor(c);
        g.fill(sh);
    }

    private void ebenenKnoepfe(Graphics2D g, Thema t) {
        int x = 14, y = 14;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        FontMetrics fm = g.getFontMetrics();
        for (int i = 0; i < atlas.ebenen.size(); i++) {
            Atlas.Ebene e = atlas.ebenen.get(i);
            int w = fm.stringWidth(e.titel()) + 24, h = 28;
            RoundRectangle2D.Double r = new RoundRectangle2D.Double(x, y, w, h, h, h);
            boolean an = i == ebene;
            g.setColor(an ? t.primaer : Thema.mitAlpha(t.tief, 215));
            g.fill(r);
            g.setColor(an ? t.primaer.brighter() : Thema.mitAlpha(t.linie, 255));
            g.setStroke(new BasicStroke(1.2f));
            g.draw(r);
            g.setColor(an ? t.tief : t.text);
            g.drawString(e.titel(), x + 12, y + (h + fm.getAscent() - fm.getDescent()) / 2);
            int nr = i;
            knoepfe.add(new Object[] {r, (Runnable) () -> ebene(nr)});
            x += w + 8;
        }
        if (!atlas.ebenen.isEmpty()) {
            Atlas.Ebene e = atlas.ebenen.get(ebene);
            String z = (e.zeit() == null ? "" : ZEIT.withZone(zone).format(e.zeit()) + " Uhr")
                    + (Double.isNaN(e.pegel()) ? "" : String.format(Locale.GERMAN, " · %s %+.2f m NHN", pegelName, e.pegel()).replace("-", "−"));
            if (!z.isEmpty()) {
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                schrift(g, z, 16, y + 28 + 18, new Color(240, 236, 226));
            }
        }
    }

    private void nordUndZoom(Graphics2D g, Thema t) {
        int x = getWidth() - 46, y = 14;
        // Nordpfeil
        Path2D.Double p = new Path2D.Double();
        p.moveTo(x + 16, y + 4);
        p.lineTo(x + 25, y + 30);
        p.lineTo(x + 16, y + 24);
        p.lineTo(x + 7, y + 30);
        p.closePath();
        g.setColor(Thema.mitAlpha(t.tief, 200));
        g.fill(new Ellipse2D.Double(x - 2, y - 2, 36, 46));
        g.setColor(t.primaer);
        g.fill(p);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        g.setColor(t.text);
        g.drawString("N", x + 12, y + 42);
        y += 54;
        String[] zeichen = {"+", "−", "⌂"};
        Runnable[] tun = {() -> zoom(0.6, getWidth() / 2, getHeight() / 2), () -> zoom(1 / 0.6, getWidth() / 2, getHeight() / 2), this::ganz};
        String[] hilfe = {"Näher", "Weiter weg", "Ganze Karte"};
        for (int i = 0; i < 3; i++) {
            RoundRectangle2D.Double r = new RoundRectangle2D.Double(x, y, 32, 30, 10, 10);
            g.setColor(Thema.mitAlpha(t.tief, 215));
            g.fill(r);
            g.setColor(t.linie);
            g.draw(r);
            g.setColor(t.text);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 17));
            FontMetrics fm = g.getFontMetrics();
            g.drawString(zeichen[i], x + 16 - fm.stringWidth(zeichen[i]) / 2, y + 21);
            knoepfe.add(new Object[] {r, tun[i], hilfe[i]});
            y += 36;
        }
    }

    private void massstab(Graphics2D g, Thema t) {
        double ziel = 160 * mpp;
        double[] stufen = {50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10000};
        double m = stufen[0];
        for (double s : stufen) if (s <= ziel) m = s;
        int w = (int) Math.round(m / mpp), x = 16, y = getHeight() - 22;
        g.setColor(new Color(0, 0, 0, 150));
        g.fillRect(x - 1, y - 5, w + 2, 9);
        g.setColor(new Color(245, 242, 232));
        g.fillRect(x, y - 4, w / 2, 7);
        g.setColor(t.primaer);
        g.fillRect(x + w / 2, y - 4, w - w / 2, 7);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
        schrift(g, m >= 1000 ? String.format(Locale.GERMAN, "%.1f km", m / 1000).replace(",0", "") : String.format(Locale.GERMAN, "%.0f m", m), x + w + 8, y + 4,
                new Color(245, 242, 232));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        String q = "Karte: eigene Rechnung aus DGM1, DOP20, LoD2 (© GeoBasis-DE/LVermGeo SH, CC BY 4.0) und TrilaWatt (BAW); Wasser aus dem Modell";
        FontMetrics fm = g.getFontMetrics();
        schrift(g, q, getWidth() - fm.stringWidth(q) - 12, getHeight() - 10, new Color(225, 222, 212, 220));
    }

    private void mitte(Graphics2D g, Thema t, String s) {
        g.setFont(t.titel(18f));
        g.setColor(t.leise);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(s, (getWidth() - fm.stringWidth(s)) / 2, getHeight() / 2);
    }

    /** Für Bilder ohne Fenster (Prüfung): zeichnet mit der geladenen Ebene, wartet bis zu {@code ms} auf das Bild. */
    public BufferedImage bild(int w, int h, long ms) throws InterruptedException {
        setSize(w, h);
        ganz();
        stufen(ebene);
        long t0 = System.currentTimeMillis();
        while (!stufen.containsKey(ebene) && System.currentTimeMillis() - t0 < ms) {
            Thread.sleep(50);
            try {
                SwingUtilities.invokeAndWait(() -> { });
            } catch (java.lang.reflect.InvocationTargetException e) {
                break;
            }
        }
        BufferedImage b = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = b.createGraphics();
        paint(g);
        g.dispose();
        return b;
    }
}
