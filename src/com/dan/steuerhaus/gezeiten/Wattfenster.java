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
import java.io.IOException;
import java.time.ZoneId;

/** Das Fenster des Watt-Fensters (seit Z3, Werkbuch HalligHooge 158): ein FFrame mit der {@link Wattansicht}; Esc schließt es. */
public final class Wattfenster {
    public final FFrame fenster;
    public final Wattansicht ansicht;

    public Wattfenster(Window besitzer, String titel, Watttafel t, Gezeitentafel gt, double gehen, String hinweis, ZoneId zone, File fotos) throws IOException {
        fenster = new FFrame(titel + " – " + t.start + " – " + t.ziel);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE)
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        ansicht = new Wattansicht(t, gt.vorhersage(), gt, gehen, hinweis, zone, fotos);
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
        ansicht.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "watt.zu");
        ansicht.getActionMap().put("watt.zu", new AbstractAction() {
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
