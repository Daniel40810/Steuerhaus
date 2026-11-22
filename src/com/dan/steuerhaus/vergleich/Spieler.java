package com.dan.steuerhaus.vergleich;

import com.dan.fframe.FFrame;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.ZoneId;

/**
 * Das Fenster eines Vergleichs (seit Y1, Werkbuch HalligHooge 152): ein FFrame mit der {@link Vergleichsansicht}. Esc
 * schließt es; beim Schließen hält das Abspielen an.
 */
public final class Spieler {
    public final FFrame fenster;
    public final Vergleichsansicht ansicht;

    public Spieler(Window besitzer, Mappe m, ZoneId zone, File fotos) {
        fenster = new FFrame("Vergleich: " + m.name);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE); der Knopf in der Leiste schickt WINDOW_CLOSING
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        ansicht = new Vergleichsansicht(m, zone, fotos);
        Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(ansicht, BorderLayout.CENTER);
        Dimension d = new Dimension(1440, 940);
        if (besitzer != null && besitzer.getGraphicsConfiguration() != null) {
            var r = besitzer.getGraphicsConfiguration().getBounds();
            d = new Dimension(Math.min(d.width, r.width - 80), Math.min(d.height, r.height - 60));
        }
        fenster.setPreferredFrameSize(d);
        fenster.setSize(d);
        fenster.setLocationRelativeTo(besitzer);
        ansicht.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "vergleich.zu");
        ansicht.getActionMap().put("vergleich.zu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { schliesse(); }
        });
        fenster.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { ansicht.ende(); }

            @Override public void windowClosing(WindowEvent e) { ansicht.ende(); }
        });
    }

    public void oeffne() {
        fenster.setVisible(true);
        fenster.toFront();
        ansicht.flaeche.requestFocusInWindow();
        ansicht.vorladen();
    }

    public boolean offen() { return fenster.isDisplayable() && fenster.isVisible(); }

    public void schliesse() {
        ansicht.ende();
        fenster.dispose();
    }
}
