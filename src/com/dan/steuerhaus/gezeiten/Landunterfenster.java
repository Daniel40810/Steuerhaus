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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.time.ZoneId;
import java.util.List;

/**
 * Das Fenster der Landunter-Uhr (seit Z2, Werkbuch HalligHooge 156): ein FFrame mit der {@link Landunteruhr}; Esc schließt
 * es, beim Schließen hält der Takt an.
 */
public final class Landunterfenster {
    public final FFrame fenster;
    public final Landunteruhr uhr;

    public Landunterfenster(Window besitzer, String titel, Gezeitentafel t, double krone, String kroneName, double nass, String nassName, List<Landunter.Warft> warften,
                            List<Object[]> wahl, String hinweis, ZoneId zone, File fotos) {
        fenster = new FFrame(titel + " – " + t.ort);
        // FFrame schließt sonst die ganze App (EXIT_ON_CLOSE)
        fenster.setDefaultCloseOperation(javax.swing.WindowConstants.DISPOSE_ON_CLOSE);
        uhr = new Landunteruhr(t, krone, kroneName, nass, nassName, warften, wahl, hinweis, zone, fotos);
        Faerber.fenster(fenster, Thema.aktuell());
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        root.setBackground(Thema.aktuell().grund);
        root.add(uhr, BorderLayout.CENTER);
        Dimension d = new Dimension(1460, 990);
        if (besitzer != null && besitzer.getGraphicsConfiguration() != null) {
            var r = besitzer.getGraphicsConfiguration().getBounds();
            d = new Dimension(Math.min(d.width, r.width - 60), Math.min(d.height, r.height - 50));
        }
        fenster.setPreferredFrameSize(d);
        fenster.setSize(d);
        fenster.setLocationRelativeTo(besitzer);
        uhr.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "landunter.zu");
        uhr.getActionMap().put("landunter.zu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { schliesse(); }
        });
        fenster.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) { uhr.ende(); }
        });
    }

    public void oeffne() {
        fenster.setVisible(true);
        fenster.toFront();
        uhr.starte();
    }

    public boolean offen() { return fenster.isDisplayable() && fenster.isVisible(); }

    public void schliesse() {
        uhr.ende();
        fenster.dispose();
    }
}
