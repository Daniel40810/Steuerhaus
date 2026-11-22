package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.analyse.Protokoll;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;

/** Der Abschnitt „Protokoll“ (seit W5, F213 A): die letzten Einträge, neue unten. */
final class ProtokollPaneel extends JPanel {
    private final JTextArea text = new JTextArea();

    ProtokollPaneel(Protokoll p) {
        super(new BorderLayout());
        text.setEditable(false);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        Faerber.rolle(this, "flaeche");
        JScrollPane sp = new JScrollPane(text);
        sp.setPreferredSize(new Dimension(300, 240));
        sp.setBorder(null);
        add(sp, BorderLayout.CENTER);
        StringBuilder sb = new StringBuilder();
        for (Protokoll.Eintrag e : p.letzte()) sb.append(kurz(e)).append('\n');
        text.setText(sb.toString());
        p.neu = e -> SwingUtilities.invokeLater(() -> {
            text.append(kurz(e) + "\n");
            if (text.getLineCount() > Protokoll.ZEILEN + 50) {
                try {
                    text.replaceRange("", 0, text.getLineEndOffset(50));
                } catch (javax.swing.text.BadLocationException x) {
                    // still
                }
            }
            text.setCaretPosition(text.getDocument().getLength());
        });
        farben();
    }

    void farben() {
        Thema t = Thema.aktuell();
        text.setBackground(t.tief);
        text.setForeground(t.text);
        text.setCaretColor(t.text);
    }

    private static String kurz(Protokoll.Eintrag e) { return e.zeit().substring(11, 19) + " " + e.art() + "  " + e.text(); }
}
