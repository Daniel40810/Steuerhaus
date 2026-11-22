package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.Befehl;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Die Befehlspalette (Strg+K; Werkbuch HalligHooge 110, F180 A): ein Suchfeld über allen Befehlen, auch Szenarien und
 * Ansichten. Pfeile wählen, Eingabe führt aus, Esc schließt. Ein eigenes Fenster über dem Bild (schwergewichtig).
 */
public final class Befehlspalette extends JDialog {
    private final Befehle befehle;
    private final JTextField feld = new JTextField();
    private final DefaultListModel<Befehl> modell = new DefaultListModel<>();
    private final JList<Befehl> liste = new JList<>(modell);

    public Befehlspalette(Window besitzer, Befehle befehle) {
        super(besitzer);
        this.befehle = befehle;
        setUndecorated(true);
        setModal(false);
        setFocusableWindowState(true);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        feld.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
        liste.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        liste.setCellRenderer(new Zeile());
        liste.setFixedCellHeight(30);
        JScrollPane sp = new JScrollPane(liste);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(8, 8));
        p.add(feld, BorderLayout.NORTH);
        p.add(sp, BorderLayout.CENTER);
        setContentPane(p);
        setSize(620, 420);
        feld.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { suche(); }

            @Override public void removeUpdate(DocumentEvent e) { suche(); }

            @Override public void changedUpdate(DocumentEvent e) { suche(); }
        });
        feld.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int n = modell.size(), i = liste.getSelectedIndex();
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> waehle(Math.min(n - 1, i + 1));
                    case KeyEvent.VK_UP -> waehle(Math.max(0, i - 1));
                    case KeyEvent.VK_PAGE_DOWN -> waehle(Math.min(n - 1, i + 10));
                    case KeyEvent.VK_PAGE_UP -> waehle(Math.max(0, i - 10));
                    case KeyEvent.VK_ENTER -> ausfuehren(liste.getSelectedValue());
                    case KeyEvent.VK_ESCAPE -> setVisible(false);
                    default -> {
                        return;
                    }
                }
                e.consume();
            }
        });
        liste.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) {
                int i = liste.locationToIndex(e.getPoint());
                if (i >= 0) ausfuehren(modell.get(i));
            }
        });
        addWindowListener(new WindowAdapter() {
            @Override public void windowDeactivated(WindowEvent e) { setVisible(false); }
        });
    }

    /** Öffnet die Palette im oberen Drittel des Besitzers, leer, mit allen Befehlen. */
    public void oeffnen() {
        Thema t = Thema.aktuell();
        JComponent p = (JComponent) getContentPane();
        p.setBackground(t.flaeche);
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(t.primaer), BorderFactory.createEmptyBorder(10, 10, 10, 10)));
        feld.setBackground(t.tief);
        feld.setForeground(t.text);
        feld.setCaretColor(t.primaer);
        feld.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(t.linie), BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        liste.setBackground(t.flaeche);
        Window w = getOwner();
        if (w != null) {
            Rectangle r = w.getBounds();
            setLocation(r.x + (r.width - getWidth()) / 2, r.y + r.height / 6);
        }
        feld.setText("");
        suche();
        setVisible(true);
        toFront();
        SwingUtilities.invokeLater(feld::requestFocusInWindow);
    }

    private void suche() {
        modell.clear();
        for (Befehl b : befehle.suche(feld.getText())) modell.addElement(b);
        waehle(modell.isEmpty() ? -1 : 0);
    }

    private void waehle(int i) {
        if (i < 0) {
            liste.clearSelection();
            return;
        }
        liste.setSelectedIndex(i);
        liste.ensureIndexIsVisible(i);
    }

    private void ausfuehren(Befehl b) {
        if (b == null || !b.geht()) return;
        setVisible(false);
        SwingUtilities.invokeLater(b::ausfuehren);
    }

    /** Eine Zeile: Name, darunter leise der Pfad der Gruppe; rechts die Taste; Schalter mit Punkt. */
    static final class Zeile extends JComponent implements ListCellRenderer<Befehl> {
        private Befehl b;
        private boolean gewaehlt;

        @Override public java.awt.Component getListCellRendererComponent(JList<? extends Befehl> l, Befehl b, int i, boolean gewaehlt, boolean fokus) {
            this.b = b;
            this.gewaehlt = gewaehlt;
            return this;
        }

        @Override protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Thema t = Thema.aktuell();
            int w = getWidth(), h = getHeight();
            g.setColor(gewaehlt ? Thema.mische(t.flaeche, t.primaer, 0.30) : t.flaeche);
            g.fillRect(0, 0, w, h);
            if (gewaehlt) {
                g.setColor(t.primaer);
                g.fillRect(0, 0, 3, h);
            }
            int x = 12;
            if (b.istSchalter()) {
                g.setColor(b.an() ? t.primaer : t.leise);
                if (b.an()) g.fillOval(x, h / 2 - 4, 8, 8);
                else g.drawOval(x, h / 2 - 4, 8, 8);
                x += 16;
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(b.geht() ? t.text : t.leise);
            g.drawString(b.name, x, (h + fm.getAscent()) / 2 - 2);
            int nx = x + fm.stringWidth(b.name) + 10;
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            FontMetrics f2 = g.getFontMetrics();
            String taste = b.taste == null ? "" : b.taste.replace("ctrl", "Strg").replace("shift", "Umschalt").replace("SPACE", "Leertaste");
            int tx = w - 12 - f2.stringWidth(taste);
            g.setColor(t.leise);
            String gruppe = b.gruppe.replace("/", " › ");
            if (nx + f2.stringWidth(gruppe) < tx - 10) g.drawString(gruppe, nx, (h + f2.getAscent()) / 2 - 2);
            g.setColor(t.marke);
            g.drawString(taste, tx, (h + f2.getAscent()) / 2 - 2);
            g.dispose();
        }
    }
}
