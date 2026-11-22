package com.dan.steuerhaus.buehne;

import com.dan.lauf.Ansicht;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Der Katalog der Szenarien und Ansichten (Werkbuch HalligHooge 110, F181 A): in Gruppen aus dem Manifest
 * ({@code katalog.szenarien.N}, {@code katalog.ansichten.N}), je Eintrag eine Kachel mit Vorschaubild. Die Bilder legt
 * {@link Vorschau} einmal ohne Fenster an ({@code <Ordner der Szenendatei>/vorschau/}); fehlt eins, zeichnet die Kachel
 * einen Platzhalter. Einträge ohne Gruppe stehen unter „Weitere“.
 */
public final class Katalog {
    public Consumer<Szenario> szenario = s -> { };
    public Consumer<Ansicht> ansicht = a -> { };
    private final JPanel szenarienTeil, ansichtenTeil;
    private final Map<Object, Kachel> kacheln = new LinkedHashMap<>();
    private Kachel szenarioGewaehlt, ansichtGewaehlt;

    public Katalog(Manifest m, Szenen szenen) {
        File ordner = Vorschau.ordner(szenen);
        szenarienTeil = teil(m.gruppen("szenarien"), szenen.szenarien(), s -> s.schluessel, s -> s.name,
                s -> new File(ordner, "szenario_" + s.schluessel + ".png"), s -> s.live(), s -> szenario.accept(s));
        ansichtenTeil = teil(m.gruppen("ansichten"), szenen.ansichten(), a -> a.schluessel, a -> a.name,
                a -> new File(ordner, "ansicht_" + a.schluessel + ".png"), a -> false, a -> ansicht.accept(a));
    }

    public JComponent szenarien() { return szenarienTeil; }

    public JComponent ansichten() { return ansichtenTeil; }

    /** Markiert die Wahl, ohne etwas zu melden. */
    public void zeige(Szenario s, Ansicht a) {
        if (s != null) szenarioGewaehlt = markiere(szenarioGewaehlt, kacheln.get(s));
        if (a != null) ansichtGewaehlt = markiere(ansichtGewaehlt, kacheln.get(a));
    }

    private static Kachel markiere(Kachel alt, Kachel neu) {
        if (alt != null) alt.gewaehlt(false);
        if (neu != null) neu.gewaehlt(true);
        return neu != null ? neu : alt;
    }

    /** Die Einträge je Gruppe, für Menü und Befehlspalette (Gruppe → Einträge; „Weitere“ zuletzt). */
    public static <T> Map<String, List<T>> gruppiert(List<Manifest.Gruppe> gruppen, List<T> alle, Function<T, String> schluessel) {
        Map<String, List<T>> g = new LinkedHashMap<>();
        List<T> rest = new ArrayList<>(alle);
        for (Manifest.Gruppe gr : gruppen) {
            List<T> l = new ArrayList<>();
            for (String k : gr.schluessel())
                for (T t : alle)
                    if (schluessel.apply(t).equals(k)) {
                        l.add(t);
                        rest.remove(t);
                    }
            if (!l.isEmpty()) g.put(gr.name(), l);
        }
        if (!rest.isEmpty()) g.computeIfAbsent("Weitere", k -> new ArrayList<>()).addAll(rest);
        return g;
    }

    private <T> JPanel teil(List<Manifest.Gruppe> gruppen, List<T> alle, Function<T, String> schluessel, Function<T, String> name,
                            Function<T, File> bild, Function<T, Boolean> live, Consumer<T> gewaehlt) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        Faerber.rolle(p, "flaeche");
        gruppiert(gruppen, alle, schluessel).forEach((gruppe, eintraege) -> {
            JLabel kopf = new JLabel(gruppe.toUpperCase(java.util.Locale.GERMAN));
            kopf.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            Faerber.rolle(kopf, "leise");
            kopf.setBorder(BorderFactory.createEmptyBorder(8, 2, 4, 0));
            kopf.setAlignmentX(0);
            p.add(kopf);
            JPanel raster = new JPanel(new GridLayout(0, 2, 8, 8));
            Faerber.rolle(raster, "flaeche");
            raster.setAlignmentX(0);
            for (T t : eintraege) {
                Kachel k = new Kachel(name.apply(t), bild.apply(t), live.apply(t), () -> gewaehlt.accept(t));
                kacheln.put(t, k);
                raster.add(k);
            }
            p.add(raster);
        });
        p.add(Box.createVerticalStrut(4));
        return p;
    }

    /** Eine Kachel: Vorschaubild (16 : 9), darunter der Name in zwei Zeilen; gewählt mit Rahmen in der Primärfarbe. */
    static final class Kachel extends JComponent {
        private final String name;
        private final File datei;
        private final boolean live;
        private BufferedImage bild;
        private boolean geladen, ueber, gewaehlt;

        Kachel(String name, File datei, boolean live, Runnable klick) {
            this.name = name;
            this.datei = datei;
            this.live = live;
            setPreferredSize(new Dimension(128, 108));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(name);
            addMouseListener(new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) { klick.run(); }

                @Override public void mouseEntered(MouseEvent e) {
                    ueber = true;
                    repaint();
                }

                @Override public void mouseExited(MouseEvent e) {
                    ueber = false;
                    repaint();
                }
            });
        }

        void gewaehlt(boolean g) {
            gewaehlt = g;
            repaint();
        }

        private BufferedImage bild() {
            if (!geladen) {
                geladen = true;
                try {
                    if (datei.isFile()) bild = ImageIO.read(datei);
                } catch (IOException e) {
                    bild = null;
                }
            }
            return bild;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight(), bh = Math.min(h - 32, (int) Math.round(w * 9 / 16.0));
            g.setColor(t.flaeche);
            g.fillRect(0, 0, w, h);
            RoundRectangle2D.Double rahmen = new RoundRectangle2D.Double(0.5, 0.5, w - 1, bh - 1, 8, 8);
            Graphics2D gb = (Graphics2D) g.create();
            gb.clip(rahmen);
            BufferedImage b = bild();
            if (b != null) gb.drawImage(b, 0, 0, w, bh, null);
            else platzhalter(gb, t, w, bh);
            if (ueber && !gewaehlt) {
                gb.setColor(Thema.mitAlpha(t.text, 28));
                gb.fillRect(0, 0, w, bh);
            }
            gb.dispose();
            if (live) {
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
                g.setColor(t.akzent);
                g.fillRoundRect(6, 6, 30, 14, 7, 7);
                g.setColor(java.awt.Color.WHITE);
                g.drawString("LIVE", 10, 17);
            }
            g.setStroke(new BasicStroke(gewaehlt ? 2f : 1f));
            g.setColor(gewaehlt ? t.primaer : ueber ? t.leise : t.linie);
            g.draw(rahmen);
            g.setFont(new Font(Font.SANS_SERIF, gewaehlt ? Font.BOLD : Font.PLAIN, 11));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(gewaehlt ? t.text : Thema.mische(t.text, t.leise, 0.35));
            List<String> zeilen = umbrechen(name, fm, w - 2, 2);
            int y = bh + 4 + fm.getAscent();
            for (String z : zeilen) {
                g.drawString(z, 1, y);
                y += fm.getHeight() - 1;
            }
            g.dispose();
        }

        private static void platzhalter(Graphics2D g, Thema t, int w, int h) {
            g.setPaint(new GradientPaint(0, 0, t.tag, 0, h, t.tief));
            g.fillRect(0, 0, w, h);
            Path2D.Double p = new Path2D.Double();
            for (int x = 0; x <= w; x += 4) {
                double y = h * 0.62 + 5 * Math.sin(x / 13.0);
                if (x == 0) p.moveTo(x, y);
                else p.lineTo(x, y);
            }
            g.setColor(Thema.mitAlpha(t.kurve, 120));
            g.setStroke(new BasicStroke(1.4f));
            g.draw(p);
        }

        static List<String> umbrechen(String s, FontMetrics fm, int breite, int max) {
            List<String> z = new ArrayList<>();
            StringBuilder b = new StringBuilder();
            for (String w : s.split(" ")) {
                String neu = b.isEmpty() ? w : b + " " + w;
                if (fm.stringWidth(neu) <= breite || b.isEmpty()) b = new StringBuilder(neu);
                else {
                    z.add(b.toString());
                    b = new StringBuilder(w);
                }
            }
            if (!b.isEmpty()) z.add(b.toString());
            if (z.size() > max) {
                String letzte = z.get(max - 1);
                while (fm.stringWidth(letzte + " …") > breite && letzte.length() > 1) letzte = letzte.substring(0, letzte.length() - 1);
                z = new ArrayList<>(z.subList(0, max - 1));
                z.add(letzte + " …");
            }
            return z;
        }
    }
}
