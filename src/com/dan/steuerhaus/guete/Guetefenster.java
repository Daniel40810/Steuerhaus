package com.dan.steuerhaus.guete;

import com.dan.fframe.FFrame;
import com.dan.steuerhaus.Guete;
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
import java.time.ZoneId;
import java.util.Map;

/** Das Fenster „Modell gegen Messung“ (seit DB3, Werkbuch HalligHooge 171): ein FFrame mit der {@link Gueteansicht}; Esc schließt es. */
public final class Guetefenster {
    public final FFrame fenster;
    public final Gueteansicht ansicht;

    public Guetefenster(Window besitzer, String titel, Guete g, Map<String, String> szenarioNamen, ZoneId zone, String hinweis, Gueteansicht.Sprung sprung) {
        fenster = new FFrame(titel);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE)
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        ansicht = new Gueteansicht(g, szenarioNamen, zone, hinweis, sprung);
        Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(ansicht, BorderLayout.CENTER);
        Dimension d = new Dimension(1320, 940);
        if (besitzer != null && besitzer.getGraphicsConfiguration() != null) {
            var r = besitzer.getGraphicsConfiguration().getBounds();
            d = new Dimension(Math.min(d.width, r.width - 60), Math.min(d.height, r.height - 50));
        }
        fenster.setPreferredFrameSize(d);
        fenster.setSize(d);
        fenster.setLocationRelativeTo(besitzer);
        ansicht.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "guete.zu");
        ansicht.getActionMap().put("guete.zu", new AbstractAction() {
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
