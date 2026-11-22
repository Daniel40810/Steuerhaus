package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/**
 * Die Lesezeichen im Katalog (seit W3, Werkbuch HalligHooge 116, F196 A): je Zeile Name, darunter Szenario und Zeit;
 * ein Klick stellt Szenario, Zeit und Blick wieder her.
 */
final class LesezeichenListe extends JPanel {
    static final DateTimeFormatter ZEIT = DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN);
    private final ZoneId zone;
    /** Ein Klick auf die Zeile mit dieser Nummer. */
    IntConsumer klick = i -> { };

    LesezeichenListe(ZoneId zone) {
        this.zone = zone;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        Faerber.rolle(this, "flaeche");
        zeige(List.of());
    }

    void zeige(List<Lesezeichen> l) {
        removeAll();
        if (l.isEmpty()) {
            JLabel k = new JLabel("Noch keine. B setzt eines an der Stelle jetzt.");
            Faerber.rolle(k, "leise");
            k.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
            add(k);
        }
        for (int i = 0; i < l.size(); i++) {
            Lesezeichen z = l.get(i);
            int nr = i;
            JLabel k = new JLabel("<html><b>" + z.name() + "</b><br><span style='font-size:90%'>" + z.szenario() + " · " + ZEIT.withZone(zone).format(z.zeit()) + "</span></html>");
            Faerber.rolle(k, "text");
            k.setBorder(BorderFactory.createEmptyBorder(5, 8, 5, 8));
            k.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            k.setAlignmentX(Component.LEFT_ALIGNMENT);
            k.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { klick.accept(nr); }

                @Override public void mouseEntered(MouseEvent e) { k.setForeground(Thema.aktuell().primaer); }

                @Override public void mouseExited(MouseEvent e) { k.setForeground(Thema.aktuell().text); }
            });
            add(k);
        }
        revalidate();
        repaint();
    }
}
