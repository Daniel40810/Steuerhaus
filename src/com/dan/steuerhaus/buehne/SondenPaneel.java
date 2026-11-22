package com.dan.steuerhaus.buehne;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.fcombobox.FComboBox;
import com.dan.lauf.Sonde;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/**
 * Die Sonden im Abschnitt „Sonden“ (seit W5, Werkbuch HalligHooge 126, F209 A, F210 A): je Größe (Wasserstand, Tiefe,
 * Strömung, Wind) eine Kurve je Sonde in ihrer Farbe über die Zeit des Szenarios; gestrichelt dieselbe Sonde im
 * Vergleichsszenario, verschoben nach gleicher Uhrzeit oder gleichem Hochwasser. Ein Zeiger zeigt die Szenenzeit, ein
 * Klick springt dorthin. Oben: Vergleich, Ausrichtung, CSV, alle weg.
 */
final class SondenPaneel extends JPanel {
    static final Color[] FARBE = {new Color(255, 170, 60), new Color(80, 210, 230), new Color(230, 110, 210), new Color(150, 220, 90)};
    static final String[] GROESSEN = {Sonde.WASSERSTAND, Sonde.TIEFE, Sonde.STROEMUNG, Sonde.WIND};

    /** Eine Sonde mit Vergleich (oder null) und dessen Verschiebung (s, auf die Zeit des Vergleichs zu addieren). */
    record Eintrag(Sonde a, Sonde b, double verschiebung) { }

    final FComboBox vergleich = new FComboBox(new Object[] {"ohne Vergleich"});
    final FComboBox ausrichten = new FComboBox(new Object[] {"gleiche Uhrzeit", "gleiches Hochwasser"});
    final FButton csv = new FButton("CSV"), weg = new FButton("Alle weg");
    private final Kurven kurven = new Kurven();
    private final JLabel hinweis = new JLabel("Strg+Klick ins Bild setzt eine Sonde (bis 4).");
    /** Ein Klick in die Kurven: gewünschte Szenenzeit (s seit Start des Szenarios). */
    DoubleConsumer sprung = x -> { };
    /** Wechsel des Vergleichs (Index in der Liste, 0 = ohne) oder der Ausrichtung. */
    Consumer<int[]> vergleichWahl = x -> { };
    private boolean still;

    SondenPaneel(ZoneId zone) {
        super(new BorderLayout(0, 6));
        kurven.zone = zone;
        vergleich.setLightWeightPopupEnabled(false);
        ausrichten.setLightWeightPopupEnabled(false);
        csv.setVariant(FButtonVariant.GHOST);
        weg.setVariant(FButtonVariant.GHOST);
        vergleich.addActionListener(e -> {
            if (!still) vergleichWahl.accept(new int[] {vergleich.getSelectedIndex(), ausrichten.getSelectedIndex()});
        });
        ausrichten.addActionListener(e -> {
            if (!still) vergleichWahl.accept(new int[] {vergleich.getSelectedIndex(), ausrichten.getSelectedIndex()});
        });
        JPanel oben = new JPanel(new GridLayout(3, 1, 0, 6)), knoepfe = new JPanel(new GridLayout(1, 2, 6, 0));
        oben.setOpaque(false);
        knoepfe.setOpaque(false);
        knoepfe.add(csv);
        knoepfe.add(weg);
        oben.add(vergleich);
        oben.add(ausrichten);
        oben.add(knoepfe);
        hinweis.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        Faerber.rolle(hinweis, "leise");
        Faerber.rolle(this, "flaeche");
        add(oben, BorderLayout.NORTH);
        add(kurven, BorderLayout.CENTER);
        add(hinweis, BorderLayout.SOUTH);
        kurven.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) {
                double x = kurven.zeitBei(e.getX());
                if (!Double.isNaN(x)) sprung.accept(x);
            }
        });
    }

    /** Die Namen der Szenarien für den Vergleich (der erste Eintrag ist „ohne Vergleich“). */
    void szenarien(List<String> namen) {
        still = true;
        try {
            vergleich.removeAllItems();
            vergleich.addItem("ohne Vergleich");
            for (String n : namen) vergleich.addItem(n);
        } finally {
            still = false;
        }
    }

    void sonden(List<Eintrag> e, Instant start, String text) {
        kurven.eintraege = List.copyOf(e);
        kurven.start = start;
        if (text != null) hinweis.setText(text);
        kurven.repaint();
    }

    /** Je Takt: der Bereich der Zeit (s seit Start des Szenarios) und die Szenenzeit. */
    void zeige(double t0, double t1, double jetzt) {
        if (t0 == kurven.t0 && t1 == kurven.t1 && jetzt == kurven.jetzt) return;
        kurven.t0 = t0;
        kurven.t1 = t1;
        kurven.jetzt = jetzt;
        kurven.repaint();
    }

    /** Die Kurven (für Prüfungen und Bilder). */
    JComponent kurven() { return kurven; }

    static final class Kurven extends JComponent {
        private static final int LINKS = 8, RECHTS = 8, KOPF = 16, FUSS = 16;
        List<Eintrag> eintraege = List.of();
        Instant start = Instant.EPOCH;
        double t0, t1 = 1, jetzt = Double.NaN;
        ZoneId zone = ZoneId.of("UTC");

        Kurven() {
            setPreferredSize(new Dimension(300, 4 * 92 + FUSS));
            setOpaque(true);
        }

        double zeitBei(int px) {
            int w = getWidth() - LINKS - RECHTS;
            if (w <= 0 || t1 <= t0) return Double.NaN;
            return t0 + (t1 - t0) * Math.max(0, Math.min(1, (px - LINKS) / (double) w));
        }

        @Override protected void paintComponent(Graphics g0) {
            Thema th = Thema.aktuell();
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(th.tief);
            g.fillRect(0, 0, getWidth(), getHeight());
            List<String> zeilen = new ArrayList<>();
            for (String gr : GROESSEN)
                for (Eintrag e : eintraege)
                    if (e.a.reihe(gr) != null) {
                        zeilen.add(gr);
                        break;
                    }
            if (zeilen.isEmpty() || t1 <= t0) {
                g.setColor(th.leise);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
                g.drawString("Noch keine Sonde.", LINKS + 4, KOPF + 14);
                g.dispose();
                return;
            }
            int w = getWidth() - LINKS - RECHTS, hz = (getHeight() - FUSS) / zeilen.size();
            Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            for (int z = 0; z < zeilen.size(); z++) {
                String gr = zeilen.get(z);
                int y0 = z * hz + KOPF, h = hz - KOPF - 4;
                double lo = Double.POSITIVE_INFINITY, hi = Double.NEGATIVE_INFINITY;
                String einheit = "";
                for (Eintrag e : eintraege)
                    for (int s = 0; s < 2; s++) {
                        Sonde so = s == 0 ? e.a : e.b;
                        Sonde.Reihe r = so == null ? null : so.reihe(gr);
                        if (r == null) continue;
                        einheit = r.einheit();
                        double sh = verschiebung(so, e, s);
                        for (int i = 0; i < r.t().length; i++) {
                            double x = r.t()[i] + sh, v = r.wert()[i];
                            if (x < t0 || x > t1 || Double.isNaN(v)) continue;
                            lo = Math.min(lo, v);
                            hi = Math.max(hi, v);
                        }
                    }
                if (!(hi >= lo)) {
                    lo = 0;
                    hi = 1;
                }
                if (hi - lo < 1e-3) hi = lo + 1e-3;
                double pad = 0.08 * (hi - lo);
                lo -= pad;
                hi += pad;
                g.setColor(th.flaeche);
                g.fillRect(LINKS, y0, w, h);
                g.setColor(th.linie);
                g.drawRect(LINKS, y0, w, h);
                // Kopf: Größe, Einheit, Werte am Zeiger je Sonde
                g.setColor(th.text);
                String kopf = gr + " (" + einheit + ")";
                g.drawString(kopf, LINKS, y0 - 4);
                int kx = LINKS + fm.stringWidth(kopf) + 8;
                for (int i = 0; i < eintraege.size(); i++) {
                    Sonde.Reihe r = eintraege.get(i).a.reihe(gr);
                    double v = r == null ? Double.NaN : r.bei(jetzt);
                    String s = Double.isNaN(v) ? "–" : String.format(Locale.GERMAN, "%.2f", v);
                    g.setColor(FARBE[i % FARBE.length]);
                    g.drawString(s, kx, y0 - 4);
                    kx += fm.stringWidth(s) + 8;
                }
                g.setColor(th.leise);
                String sh = String.format(Locale.GERMAN, "%.2f", hi - pad), sl = String.format(Locale.GERMAN, "%.2f", lo + pad);
                g.drawString(sh, LINKS + w - fm.stringWidth(sh) - 3, y0 + fm.getAscent() + 1);
                g.drawString(sl, LINKS + w - fm.stringWidth(sl) - 3, y0 + h - 3);
                for (int i = 0; i < eintraege.size(); i++) {
                    Eintrag e = eintraege.get(i);
                    for (int s = 1; s >= 0; s--) {
                        Sonde so = s == 0 ? e.a : e.b;
                        Sonde.Reihe r = so == null ? null : so.reihe(gr);
                        if (r == null) continue;
                        double v = verschiebung(so, e, s);
                        Path2D.Double p = new Path2D.Double();
                        boolean frei = true;
                        for (int k = 0; k < r.t().length; k++) {
                            double x = r.t()[k] + v, val = r.wert()[k];
                            if (x < t0 || x > t1 || Double.isNaN(val)) {
                                frei = true;
                                continue;
                            }
                            double px = LINKS + (x - t0) / (t1 - t0) * w, py = y0 + h - (val - lo) / (hi - lo) * h;
                            if (frei) p.moveTo(px, py); else p.lineTo(px, py);
                            frei = false;
                        }
                        Color c = FARBE[i % FARBE.length];
                        g.setColor(s == 0 ? c : Thema.mitAlpha(c, 170));
                        Stroke alt = g.getStroke();
                        g.setStroke(s == 0 ? new BasicStroke(1.6f) : new BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {5, 4}, 0));
                        g.draw(p);
                        g.setStroke(alt);
                    }
                }
                if (!Double.isNaN(jetzt) && jetzt >= t0 && jetzt <= t1) {
                    int px = (int) Math.round(LINKS + (jetzt - t0) / (t1 - t0) * w);
                    g.setColor(th.marke);
                    g.drawLine(px, y0, px, y0 + h);
                }
            }
            // Zeitachse
            DateTimeFormatter hm = DateTimeFormatter.ofPattern("dd.MM. HH:mm").withZone(zone);
            g.setColor(th.leise);
            String l = hm.format(start.plusMillis(Math.round(t0 * 1000))), r = hm.format(start.plusMillis(Math.round(t1 * 1000)));
            g.drawString(l, LINKS, getHeight() - 4);
            g.drawString(r, LINKS + w - fm.stringWidth(r), getHeight() - 4);
            g.dispose();
        }

        /** Verschiebung einer Reihe auf die Zeitachse des Szenarios (s seit {@link #start}). */
        private double verschiebung(Sonde so, Eintrag e, int s) {
            double rel = (so.start.toEpochMilli() - start.toEpochMilli()) / 1000.0;
            return rel + (s == 1 ? e.verschiebung : 0);
        }
    }
}
