package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.Befehl;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.UIManager;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Die Menüleiste (Werkbuch HalligHooge 110, F180 A), erzeugt aus dem Register der Befehle. Die Gruppe eines Befehls
 * ist ein Pfad: {@code "Szene/Szenario/Tide und Sturm"} legt Untermenüs an. Beim Öffnen eines Menüs gelten Stand
 * (grau, an/aus) neu. Tasten stehen nur zur Anzeige daneben; ausgelöst werden sie von {@link Tasten}, das vor allen
 * Komponenten an die Reihe kommt (auch wenn die Leinwand den Fokus hat).
 * <p>
 * Popups sind schwergewichtig (Hauptfenster), sonst lägen sie hinter der Vulkan-Fläche.
 */
public final class Menueleiste extends JMenuBar {
    private final Befehle befehle;
    private final Map<JMenuItem, Befehl> eintraege = new LinkedHashMap<>();

    public Menueleiste(Befehle befehle) {
        this.befehle = befehle;
        bauen();
    }

    /** Baut die Leiste im aktuellen Thema neu (nach einem Wechsel des Themas). */
    public void bauen() {
        Thema t = Thema.aktuell();
        UIManager.put("MenuItem.selectionBackground", Thema.mitAlpha(t.primaer, 255));
        UIManager.put("MenuItem.selectionForeground", t.tief);
        UIManager.put("Menu.selectionBackground", Thema.mische(t.grund, t.primaer, 0.35));
        UIManager.put("Menu.selectionForeground", t.text);
        UIManager.put("CheckBoxMenuItem.selectionBackground", Thema.mitAlpha(t.primaer, 255));
        UIManager.put("CheckBoxMenuItem.selectionForeground", t.tief);
        UIManager.put("MenuItem.acceleratorForeground", t.leise);
        UIManager.put("MenuItem.acceleratorSelectionForeground", t.tief);
        UIManager.put("CheckBoxMenuItem.acceleratorForeground", t.leise);
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(t.linie));
        removeAll();
        eintraege.clear();
        setOpaque(true);
        setBackground(t.grund);
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, t.linie));
        Map<String, JMenu> menues = new LinkedHashMap<>();
        for (Befehl b : befehle.alle()) {
            if (!b.imMenue()) continue;
            String[] pfad = b.gruppe.split("/");
            String schluessel = "";
            JMenu m = null;
            for (String teil : pfad) {
                schluessel += "/" + teil;
                JMenu n = menues.get(schluessel);
                if (n == null) {
                    n = menu(teil, t, m == null);
                    menues.put(schluessel, n);
                    if (m == null) add(n);
                    else m.add(n);
                }
                m = n;
            }
            JMenuItem i = b.istSchalter() ? new JCheckBoxMenuItem(b.name) : new JMenuItem(b.name);
            faerbe(i, t);
            if (b.taste != null) i.setAccelerator(KeyStroke.getKeyStroke(b.taste));
            if (!b.hilfe().isEmpty()) i.setToolTipText(b.hilfe());
            i.addActionListener(e -> b.ausfuehren());
            eintraege.put(i, b);
            m.add(i);
        }
        revalidate();
        repaint();
    }

    private JMenu menu(String name, Thema t, boolean oben) {
        JMenu m = new JMenu(name);
        faerbe(m, t);
        if (oben) m.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        m.getPopupMenu().setBackground(t.flaeche);
        m.getPopupMenu().setBorder(BorderFactory.createLineBorder(t.linie));
        m.addMenuListener(new MenuListener() {
            @Override public void menuSelected(MenuEvent e) { stand(m); }

            @Override public void menuDeselected(MenuEvent e) { }

            @Override public void menuCanceled(MenuEvent e) { }
        });
        return m;
    }

    private static void faerbe(JMenuItem i, Thema t) {
        i.setOpaque(true);
        i.setBackground(i instanceof JMenu && i.getParent() == null ? t.grund : t.flaeche);
        i.setForeground(t.text);
        i.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
        i.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
    }

    /** Stand der Einträge eines Menüs: grau, wenn der Befehl nicht geht; Haken bei Schaltern. */
    private void stand(JMenu m) {
        for (MenuElement me : m.getPopupMenu().getSubElements()) {
            Component c = me.getComponent();
            if (c instanceof JMenu) continue;
            if (c instanceof JMenuItem i && eintraege.get(i) instanceof Befehl b) {
                i.setEnabled(b.geht());
                if (i instanceof JCheckBoxMenuItem k) k.setSelected(b.an());
                i.setForeground(b.geht() ? Thema.aktuell().text : new Color(Thema.aktuell().leise.getRGB() & 0x7FFFFFFF, true));
            }
        }
    }
}
