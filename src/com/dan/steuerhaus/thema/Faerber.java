package com.dan.steuerhaus.thema;

import com.dan.fbutton.FButton;
import com.dan.fcombobox.FComboBox;
import com.dan.fframe.FFrame;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;

/**
 * Färbt, was dem {@code FTheme} von FStyle nicht von selbst folgt (Werkbuch HalligHooge 110, F183 A): FButton,
 * FComboBox und Komponenten mit einer Rolle ({@link #rolle}). Läuft über den ganzen Baum unter einer Wurzel.
 * <p>
 * Rollen: {@code grund}, {@code flaeche}, {@code tief} (Hintergrund), {@code text}, {@code leise} (Schrift),
 * {@code titel} (Schrift und Titelschrift des Themas).
 */
public final class Faerber {
    public static final String ROLLE = "steuerhaus.rolle";

    private Faerber() { }

    /** Gibt einer Komponente eine Rolle und färbt sie sofort. */
    public static <T extends JComponent> T rolle(T c, String rolle) {
        c.putClientProperty(ROLLE, rolle);
        faerbe(c, Thema.aktuell(), false);
        return c;
    }

    /** Färbt den Baum unter c im aktuellen Thema. */
    public static void faerbe(Component c) { faerbe(c, Thema.aktuell(), true); }

    private static void faerbe(Component c, Thema t, boolean tief) {
        if (c instanceof FButton b) knopf(b, t);
        else if (c instanceof FComboBox k) liste(k, t);
        if (c instanceof JComponent j && j.getClientProperty(ROLLE) instanceof String r) {
            switch (r) {
                case "grund" -> hinten(j, t.grund);
                case "flaeche" -> hinten(j, t.flaeche);
                case "tief" -> hinten(j, t.tief);
                case "text" -> j.setForeground(t.text);
                case "leise" -> j.setForeground(t.leise);
                case "titel" -> {
                    j.setForeground(t.text);
                    j.setFont(t.titel(j.getFont() != null ? Math.max(12f, j.getFont().getSize2D()) : 13f));
                }
                default -> { }
            }
        }
        if (tief && c instanceof Container k) for (Component k2 : k.getComponents()) faerbe(k2, t, true);
        c.repaint();
    }

    private static void hinten(JComponent j, Color c) {
        j.setOpaque(true);
        j.setBackground(c);
    }

    /** FButton: PRIMARY und GHOST malen seit Steuerhaus W1 mit liquidColorTop/Bottom (FStyle, FButton). */
    public static void knopf(FButton b, Thema t) {
        b.liquidColorTop = t.primaer;
        b.liquidColorBottom = t.akzent;
        b.glassRim = t.glasRand;
        b.normalTextColor = t == Thema.LABOR ? new Color(0xCF, 0xE9, 0xF2) : t.text;
        b.hoverTextColor = Thema.mische(b.normalTextColor, Color.WHITE, 0.5);
        b.rippleColor = Thema.mitAlpha(Thema.mische(t.primaer, Color.WHITE, 0.6), 200);
        b.repaint();
    }

    /** Farben des FFrame im Labor (Vorgabe von FStyle), damit Labor genau wie bisher aussieht. */
    private static final Color LEISTE1 = new Color(0x01, 0x4B, 0x4E), LEISTE2 = new Color(0x5C, 0x00, 0x2A), GLUT = new Color(0x00, 0xB5, 0xAD), ZU = new Color(0x8B, 0x00, 0x24);

    /** Titelleiste und Grund eines FFrame im Thema; im Labor die Vorgaben von FStyle. */
    public static void fenster(FFrame f, Thema t) {
        f.setComponentPaneColor(t.grund);
        if (t == Thema.LABOR) {
            f.setTaskbarColors(LEISTE1, LEISTE2);
            f.setIconGlowColor(GLUT);
            f.setCloseGlowColor(ZU);
        } else {
            f.setTaskbarColors(Thema.mische(t.primaer, Color.BLACK, 0.62), Thema.mische(t.akzent, Color.BLACK, 0.62));
            f.setIconGlowColor(t.primaer);
            f.setCloseGlowColor(t.akzent);
        }
        f.repaint();
    }

    public static void liste(FComboBox k, Thema t) {
        k.setLiquidColorTop(t.primaer);
        k.setLiquidColorBottom(t.akzent);
        k.setGlassBody(t.glasKoerper);
        k.setGlassRim(t.glasRand);
        k.setHoverGlowColor(t.primaer);
        k.setTextColor(t == Thema.LABOR ? new Color(0xF0, 0xF6, 0xFF) : t.text);
        k.setPopupBackground(t == Thema.LABOR ? new Color(0x12, 0x14, 0x1E) : t.flaeche);
        k.setArrowColor(t.pfeil);
    }
}
