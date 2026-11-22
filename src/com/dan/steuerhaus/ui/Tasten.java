package com.dan.steuerhaus.ui;

import com.dan.steuerhaus.Befehl;
import com.dan.steuerhaus.Befehle;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import java.awt.KeyEventDispatcher;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Die Tasten der Befehle (Werkbuch HalligHooge 110, 5.1; ersetzt den {@code KeyEventDispatcher} der App bis Teil A):
 * gelten im ganzen Fenster, auch wenn die Leinwand den Fokus hat. In einem Textfeld (Befehlspalette) gelten nur
 * Funktionstasten und Tasten mit Strg oder Alt; alles andere bleibt Text.
 */
public final class Tasten implements KeyEventDispatcher {
    private static final int MASKE = InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK | InputEvent.META_DOWN_MASK;
    private final Befehle befehle;

    private Tasten(Befehle befehle) {
        this.befehle = befehle;
    }

    /** Meldet die Tasten aller Befehle an; spätere Befehle gelten mit (das Register wird je Taste gelesen). */
    public static Tasten anmelden(Befehle befehle) {
        Tasten t = new Tasten(befehle);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(t);
        return t;
    }

    public void abmelden() { KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this); }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getID() != KeyEvent.KEY_PRESSED) return false;
        int mod = e.getModifiersEx() & MASKE;
        boolean funktion = e.getKeyCode() >= KeyEvent.VK_F1 && e.getKeyCode() <= KeyEvent.VK_F12;
        if (e.getComponent() instanceof JTextComponent && !funktion && (mod & (InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK)) == 0) return false;
        for (Befehl b : befehle.alle()) {
            if (b.taste == null) continue;
            KeyStroke k = KeyStroke.getKeyStroke(b.taste);
            if (k == null || k.getKeyCode() != e.getKeyCode() || (k.getModifiers() & MASKE) != mod) continue;
            if (!b.geht()) return true;
            if (SwingUtilities.isEventDispatchThread()) b.ausfuehren();
            else SwingUtilities.invokeLater(b::ausfuehren);
            return true;
        }
        return false;
    }
}
