package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.thema.Faerber;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/**
 * Die Statuszeile unter allem: links die Einblendung (Zeit, Pegel, Wind, Zeitraffer, Bilder/s), rechts Arbeitsbereich,
 * Thema und der Hinweis auf die Befehlspalette.
 */
public final class Statuszeile extends JPanel {
    private final JLabel links = new JLabel(" "), rechts = new JLabel(" ");

    public Statuszeile() {
        super(new BorderLayout());
        Faerber.rolle(this, "tief");
        setBorder(BorderFactory.createEmptyBorder(5, 12, 5, 12));
        Font f = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        links.setFont(f);
        rechts.setFont(f);
        Faerber.rolle(links, "text");
        Faerber.rolle(rechts, "leise");
        add(links, BorderLayout.CENTER);
        add(rechts, BorderLayout.EAST);
    }

    public void links(String text) {
        if (!text.equals(links.getText())) links.setText(text);
    }

    public void rechts(String text) {
        if (!text.equals(rechts.getText())) rechts.setText(text);
    }

    public String links() { return links.getText(); }
}
