package com.dan.steuerhaus.gezeiten;

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
import java.io.File;
import java.time.ZoneId;

/** Das Fenster des Tidenkalenders (seit Z1, Werkbuch HalligHooge 155): ein FFrame mit der {@link Kalenderansicht}; Esc schließt es. */
public final class Kalenderfenster {
    public final FFrame fenster;
    public final Kalenderansicht ansicht;

    public Kalenderfenster(Window besitzer, String titel, Gezeitentafel t, double marke, String markeName, String hinweis, ZoneId zone, File fotos) {
        fenster = new FFrame(titel + " – " + t.ort);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE)
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        ansicht = new Kalenderansicht(t, marke, markeName, hinweis, zone, fotos);
        Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(ansicht, BorderLayout.CENTER);
        Dimension d = new Dimension(1460, 990);
        if (besitzer != null && besitzer.getGraphicsConfiguration() != null) {
            var r = besitzer.getGraphicsConfiguration().getBounds();
            d = new Dimension(Math.min(d.width, r.width - 60), Math.min(d.height, r.height - 50));
        }
        fenster.setPreferredFrameSize(d);
        fenster.setSize(d);
        fenster.setLocationRelativeTo(besitzer);
        ansicht.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "kalender.zu");
        ansicht.getActionMap().put("kalender.zu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { schliesse(); }
        });
    }

    public void oeffne() {
        fenster.setVisible(true);
        fenster.toFront();
    }

    public boolean offen() { return fenster.isDisplayable() && fenster.isVisible(); }

    public void schliesse() { fenster.dispose(); }
}
