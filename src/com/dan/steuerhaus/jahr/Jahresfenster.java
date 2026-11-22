package com.dan.steuerhaus.jahr;

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
 * Das Fenster „Ein Jahr in einer Minute“ (seit Y3, Werkbuch HalligHooge 154): ein FFrame mit der {@link Jahresansicht}.
 * Esc schließt es; beim Schließen hält das Abspielen an.
 */
public final class Jahresfenster {
    public final FFrame fenster;
    public final Jahresansicht ansicht;

    public Jahresfenster(Window besitzer, Jahresmappe m, double[] anteil, String hinweis, String sandName, ZoneId zone, File fotos) {
        fenster = new FFrame(m.name);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE); der Knopf in der Leiste schickt WINDOW_CLOSING
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        ansicht = new Jahresansicht(m, anteil, hinweis, sandName, zone, fotos);
        Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(ansicht, BorderLayout.CENTER);
        Dimension d = new Dimension(1460, 980);
        if (besitzer != null && besitzer.getGraphicsConfiguration() != null) {
            var r = besitzer.getGraphicsConfiguration().getBounds();
            d = new Dimension(Math.min(d.width, r.width - 60), Math.min(d.height, r.height - 50));
        }
        fenster.setPreferredFrameSize(d);
        fenster.setSize(d);
        fenster.setLocationRelativeTo(besitzer);
        ansicht.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "jahr.zu");
        ansicht.getActionMap().put("jahr.zu", new AbstractAction() {
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
        ansicht.karte.requestFocusInWindow();
    }

    public boolean offen() { return fenster.isDisplayable() && fenster.isVisible(); }

    public void schliesse() {
        ansicht.ende();
        fenster.dispose();
    }
}
