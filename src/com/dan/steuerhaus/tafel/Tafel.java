package com.dan.steuerhaus.tafel;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine Infotafel (seit X1, Werkbuch HalligHooge 145): Titel, Art, ein Bild, Kennzahlen als Zeilen, Absätze Text, die
 * Quelle und Knöpfe darunter. Allgemein – was auf der Tafel steht, gibt der Aufrufer. Die Farben folgen dem Thema.
 */
public final class Tafel extends JPanel {
    /** Eine Kennzahl: Name und Wert (als Text, schon mit Einheit). */
    public record Zeile(String name, String wert) { }

    /** Ein Knopf unter der Tafel. */
    public record Knopf(String name, Runnable tun) { }

    /** Der Inhalt einer Tafel. */
    public record Inhalt(String titel, String art, File bild, List<Zeile> zeilen, List<String> absaetze, String quelle, List<Knopf> knoepfe) {
        public Inhalt {
            zeilen = zeilen == null ? List.of() : List.copyOf(zeilen);
            absaetze = absaetze == null ? List.of() : List.copyOf(absaetze);
            knoepfe = knoepfe == null ? List.of() : List.copyOf(knoepfe);
        }
    }

    private final JEditorPane text = new JEditorPane();
    private final JScrollPane rolle;
    private final JPanel leiste = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
    private Inhalt inhalt;

    public Tafel() {
        super(new BorderLayout());
        text.setContentType("text/html");
        text.setEditable(false);
        text.setFocusable(false);
        text.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        rolle = new JScrollPane(text, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        rolle.setBorder(BorderFactory.createEmptyBorder());
        rolle.getVerticalScrollBar().setUI(new Rollbalken());
        rolle.getVerticalScrollBar().setPreferredSize(new java.awt.Dimension(10, 10));
        rolle.getVerticalScrollBar().setOpaque(false);
        rolle.getVerticalScrollBar().setUnitIncrement(16);
        add(rolle, BorderLayout.CENTER);
        leiste.setOpaque(false);
        add(leiste, BorderLayout.SOUTH);
        Thema.beiWechsel(() -> SwingUtilities.invokeLater(() -> zeige(inhalt)));
        zeige(null);
    }

    /** Zeigt einen Inhalt (null: leer). */
    public void zeige(Inhalt i) {
        inhalt = i;
        Thema t = Thema.aktuell();
        setBackground(t.flaeche);
        text.setBackground(t.flaeche);
        rolle.getViewport().setBackground(t.flaeche);
        setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, t.linie));
        text.setText(html(i, t));
        text.setCaretPosition(0);
        leiste.removeAll();
        if (i != null)
            for (Knopf k : i.knoepfe()) {
                FButton b = new FButton(k.name());
                b.setVariant(FButtonVariant.PRIMARY);
                Faerber.knopf(b, t);
                b.addActionListener(e -> k.tun().run());
                leiste.add(b);
            }
        leiste.revalidate();
        repaint();
    }

    public Inhalt inhalt() { return inhalt; }

    /** Der Inhalt als HTML (für den Editor und die Prüfung). */
    static String html(Inhalt i, Thema t) {
        StringBuilder h = new StringBuilder();
        String schrift = t.titelFamilie;
        h.append("<html><body style='margin:18px 20px 10px 20px; font-family:sans-serif; font-size:12pt; color:").append(hex(t.text)).append("'>");
        if (i == null) {
            h.append("</body></html>");
            return h.toString();
        }
        h.append("<div style='font-family:\"").append(schrift).append("\"; font-size:21pt; font-weight:bold; color:").append(hex(t.text)).append("'>")
                .append(esc(i.titel())).append("</div>");
        if (i.art() != null && !i.art().isEmpty())
            h.append("<div style='margin-top:2px; font-size:11pt; color:").append(hex(t.primaer)).append("; letter-spacing:1px'>").append(esc(i.art().toUpperCase(java.util.Locale.GERMAN)))
                    .append("</div>");
        if (i.bild() != null && i.bild().isFile())
            h.append("<div style='margin-top:12px'><img src='").append(i.bild().toURI()).append("' width='368' height='207'></div>");
        if (!i.zeilen().isEmpty()) {
            h.append("<table style='margin-top:12px; width:100%' cellspacing='0' cellpadding='4'>");
            for (Zeile z : i.zeilen())
                h.append("<tr><td style='color:").append(hex(t.leise)).append("; border-bottom:1px solid ").append(hex(t.linie)).append("'>").append(esc(z.name()))
                        .append("</td><td align='right' style='border-bottom:1px solid ").append(hex(t.linie)).append("'><b>").append(esc(z.wert())).append("</b></td></tr>");
            h.append("</table>");
        }
        for (String a : i.absaetze()) h.append("<p style='margin-top:10px; line-height:1.35'>").append(esc(a)).append("</p>");
        if (i.quelle() != null && !i.quelle().isEmpty())
            h.append("<p style='margin-top:12px; font-size:9pt; color:").append(hex(t.leise)).append("'>Quelle: ").append(esc(i.quelle())).append("</p>");
        h.append("</body></html>");
        return h.toString();
    }

    /** Ein schmaler Rollbalken in den Farben des Themas. */
    static final class Rollbalken extends javax.swing.plaf.basic.BasicScrollBarUI {
        @Override protected void paintTrack(java.awt.Graphics g, javax.swing.JComponent c, java.awt.Rectangle r) {
            g.setColor(Thema.aktuell().flaeche);
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override protected void paintThumb(java.awt.Graphics g0, javax.swing.JComponent c, java.awt.Rectangle r) {
            if (r.isEmpty()) return;
            java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Thema.mitAlpha(isThumbRollover() ? Thema.aktuell().primaer : Thema.aktuell().leise, 170));
            g.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 6, 6);
            g.dispose();
        }

        @Override protected javax.swing.JButton createDecreaseButton(int o) { return leer(); }

        @Override protected javax.swing.JButton createIncreaseButton(int o) { return leer(); }

        private static javax.swing.JButton leer() {
            javax.swing.JButton b = new javax.swing.JButton();
            b.setPreferredSize(new java.awt.Dimension(0, 0));
            b.setMinimumSize(new java.awt.Dimension(0, 0));
            b.setMaximumSize(new java.awt.Dimension(0, 0));
            return b;
        }
    }

    static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Hilfe für Aufrufer: eine Liste von Zeilen ohne leere Werte. */
    public static List<Zeile> zeilen(String... nameWert) {
        List<Zeile> l = new ArrayList<>();
        for (int k = 0; k + 1 < nameWert.length; k += 2) if (nameWert[k + 1] != null && !nameWert[k + 1].isEmpty()) l.add(new Zeile(nameWert[k], nameWert[k + 1]));
        return l;
    }
}
