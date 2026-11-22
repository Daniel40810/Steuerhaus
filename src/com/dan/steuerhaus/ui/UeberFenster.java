package com.dan.steuerhaus.ui;

import com.dan.fframe.FFrame;
import com.dan.ficons.FIconType;
import com.dan.steuerhaus.Befehl;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.JEditorPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.List;

/**
 * „Über“ (F1; Werkbuch HalligHooge 110, 5.1): Titel, Fassungen, Quellennachweis aus dem Manifest und die Tasten aller
 * Befehle. Die Daten von GeoBasis-DE, Copernicus, OpenStreetMap und anderen verlangen die Nennung; sie steht hier und
 * kommt mit W4 in den Abspann der Videos.
 */
public final class UeberFenster {
    private final FFrame fenster;
    private final JEditorPane text = new JEditorPane("text/html", "");

    public UeberFenster(Manifest m, Befehle befehle, List<String> fassungen) {
        fenster = new FFrame("Über " + m.titel());
        // seit Y1: FFrame schließt sonst die ganze App (EXIT_ON_CLOSE); der Knopf in der Leiste schickt WINDOW_CLOSING
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.HIDE_ON_CLOSE);
        try {
            fenster.setLogoType(FIconType.valueOf(m.logo()));
        } catch (RuntimeException e) {
            // kein Logo dieses Namens
        }
        text.setEditable(false);
        text.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        JScrollPane sp = new JScrollPane(text);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 8));
        sp.getVerticalScrollBar().setUI(new Dock.Leiste());
        JPanel p = fenster.getComponentPane();
        p.setLayout(new BorderLayout());
        p.add(sp, BorderLayout.CENTER);
        fenster.setPreferredFrameSize(new Dimension(720, 640));
        fenster.setSize(720, 640);
        fenster.setLocationRelativeTo(null);
        inhalt(m, befehle, fassungen);
    }

    private void inhalt(Manifest m, Befehle befehle, List<String> fassungen) {
        Thema t = Thema.aktuell();
        com.dan.steuerhaus.thema.Faerber.fenster(fenster, t);
        text.setBackground(t.flaeche);
        fenster.getComponentPane().setBackground(t.flaeche);
        StringBuilder s = new StringBuilder();
        s.append("<html><body style='font-family:sans-serif;font-size:11pt;color:").append(hex(t.text)).append("'>");
        s.append("<div style='font-family:").append(t.titelFamilie).append(",serif;font-size:24pt;font-weight:bold'>").append(esc(m.titel())).append("</div>");
        s.append("<div style='color:").append(hex(t.leise)).append(";margin-bottom:10px'>").append(esc(m.unterzeile())).append("</div>");
        for (String f : fassungen) s.append("<div style='color:").append(hex(t.leise)).append("'>").append(esc(f)).append("</div>");
        s.append(kopf("Quellen", t));
        for (String q : m.quellen()) s.append("<div style='margin-bottom:4px'>").append(esc(q)).append("</div>");
        s.append(kopf("Tasten", t)).append("<table cellspacing='0' cellpadding='2'>");
        for (Befehl b : befehle.alle())
            if (b.taste != null) s.append("<tr><td style='color:").append(hex(t.marke)).append(";padding-right:14px'>").append(esc(b.taste.replace("ctrl", "Strg").replace("shift", "Umschalt").replace("SPACE", "Leertaste")))
                    .append("</td><td>").append(esc(b.name)).append("</td><td style='color:").append(hex(t.leise)).append("'>").append(esc(b.gruppe.replace("/", " › "))).append("</td></tr>");
        s.append("</table>");
        s.append(kopf("Kamera", t)).append("<div>Maus ziehen: drehen, neigen · Rad: Bildwinkel · W A S D: bewegen · Umschalt: schneller · Q E: höher, tiefer</div>");
        s.append("</body></html>");
        text.setText(s.toString());
        text.setCaretPosition(0);
    }

    private static String kopf(String titel, Thema t) {
        return "<div style='font-family:" + t.titelFamilie + ",serif;font-size:14pt;font-weight:bold;margin-top:16px;margin-bottom:6px;color:" + hex(t.primaer) + "'>" + titel + "</div>";
    }

    public void zeige() {
        fenster.setVisible(true);
        fenster.toFront();
    }

    private static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    private static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
