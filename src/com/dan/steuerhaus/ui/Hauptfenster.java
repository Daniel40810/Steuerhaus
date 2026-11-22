package com.dan.steuerhaus.ui;

import com.dan.fframe.FFrame;
import com.dan.ficons.FIconType;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.Einstellungen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRootPane;
import javax.swing.RepaintManager;
import javax.swing.SwingUtilities;
import javax.swing.ToolTipManager;
import java.awt.BorderLayout;
import java.awt.Canvas;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Das Hauptfenster einer App im Steuerhaus (Werkbuch HalligHooge 110, 5.1): oben die Menüleiste, in der Mitte die
 * Leinwand mit den Docks links und rechts, darunter das Dock unten und die Statuszeile. Dazu Arbeitsbereiche,
 * Kinoblick, Befehlspalette, Hinweise, „Über“, das Thema und das Merken in den Einstellungen.
 * <p>
 * Alle Methoden im Faden der Oberfläche. Das Fenster öffnet maximiert über die nutzbare Fläche (wie bis Teil A).
 */
public final class Hauptfenster {
    public final Manifest manifest;
    public final Befehle befehle;
    public final Einstellungen einstellungen;
    public final FFrame fenster;
    public final Canvas leinwand = new Canvas();
    public final Dock links, rechts, unten;
    public final Statuszeile status = new Statuszeile();
    public final Menueleiste menue;
    private final Befehlspalette palette;
    private final Tasten tasten;
    private final Map<String, Arbeitsbereich> bereiche = new LinkedHashMap<>();
    private String bereich = "";
    private boolean kino;
    private boolean[] vorKino;
    private String einblendung = "", titel;
    private UeberFenster ueber;
    private List<String> fassungen = List.of();
    /** Seit W2: gerufen nach jedem Wechsel des Arbeitsbereichs und des Kinoblicks (die Einblendungen folgen). */
    public Runnable nachAnsicht;

    /** Runde Ecken als Form (Region), maximiert ohne; siehe den Konstruktor (X2b). */
    private void form() {
        boolean max = (fenster.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        try {
            fenster.setShape(max || fenster.getWidth() <= 0 ? null : new java.awt.geom.RoundRectangle2D.Double(0, 0, fenster.getWidth(), fenster.getHeight(), 20, 20));
        } catch (UnsupportedOperationException | java.awt.IllegalComponentStateException e) {
            // ohne Formen (manche Bildschirme, Prüfung ohne Anzeige): eckig
        }
    }

    public Hauptfenster(Manifest manifest, Befehle befehle, Einstellungen einstellungen) {
        this.manifest = manifest;
        this.befehle = befehle;
        this.einstellungen = einstellungen;
        // Menüs, Auswahllisten und Hilfen über einer Vulkan-Fläche: schwergewichtig, sonst liegen sie hinter ihr (Sichtprüfung P8b)
        JPopupMenu.setDefaultLightWeightPopupEnabled(false);
        ToolTipManager.sharedInstance().setLightWeightPopupEnabled(false);
        // AWT soll den Grund der Fenster nicht selbst löschen (weiß); das Bild malt die Leinwand, den Rest Swing
        System.setProperty("sun.awt.noerasebackground", "true");
        titel = manifest.titel();
        fenster = new FFrame(titel);
        // X2b (Werkbuch HalligHooge 147): FFrame ist durchscheinend (Grund mit Alpha 0, für die runden Ecken). Windows legt so
        // ein Fenster „layered“ an und prüft jeden Klick am Alpha seines Bilds. Über der Vulkan-Fläche ist das 0 (sie malt in
        // ein eigenes Kindfenster): jeder Klick in die Bühne fiel durch auf das Fenster dahinter – meist die Konsole, die dann
        // nach vorn kam. Darum ist das Hauptfenster undurchsichtig; die runden Ecken gibt eine Form, die Klicks nicht durchlässt.
        fenster.setBackground(Color.BLACK);
        fenster.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { form(); }
        });
        fenster.addWindowStateListener(e -> form());
        try {
            fenster.setLogoType(FIconType.valueOf(manifest.logo()));
        } catch (RuntimeException e) {
            // kein Logo dieses Namens: das von FFrame
        }
        // seit dem Abschluss (Werkbuch HalligHooge 142): das Programmsymbol für Fenster und Taskleiste aus app.icon (PNG)
        String symbol = manifest.text("app.icon", "");
        if (!symbol.isEmpty()) {
            try {
                java.awt.image.BufferedImage b = javax.imageio.ImageIO.read(manifest.relativ(symbol));
                if (b != null) {
                    java.util.List<java.awt.Image> l = new java.util.ArrayList<>();
                    for (int s : new int[] {16, 24, 32, 48, 64, 128, 256}) l.add(b.getScaledInstance(s, s, java.awt.Image.SCALE_SMOOTH));
                    fenster.setIconImages(l);
                }
            } catch (java.io.IOException | RuntimeException e) {
                // ohne Symbol: das von FFrame
            }
        }
        links = new Dock(Dock.Seite.LINKS, "links", "Katalog weg- oder aufklappen (F4) · ziehen: Breite", 300, 220, 560);
        rechts = new Dock(Dock.Seite.RECHTS, "rechts", "Bedienfeld weg- oder aufklappen (F2) · ziehen: Breite", 320, 260, 520);
        unten = new Dock(Dock.Seite.UNTEN, "unten", "Zeitleiste weg- oder aufklappen (F3) · ziehen: Höhe", 150, 90, 360);
        for (Dock d : new Dock[] {links, rechts, unten}) d.geaendert = this::nachDock;
        menue = new Menueleiste(befehle);
        JPanel root = fenster.getComponentPane();
        root.setLayout(new BorderLayout());
        Faerber.rolle(root, "grund");
        leinwand.setBackground(Color.BLACK);
        leinwand.setPreferredSize(new Dimension(1200, 700));
        JPanel mitte = new JPanel(new BorderLayout());
        Faerber.rolle(mitte, "grund");
        mitte.add(links, BorderLayout.WEST);
        mitte.add(leinwand, BorderLayout.CENTER);
        mitte.add(rechts, BorderLayout.EAST);
        JPanel fuss = new JPanel(new BorderLayout());
        Faerber.rolle(fuss, "grund");
        fuss.add(unten, BorderLayout.CENTER);
        fuss.add(status, BorderLayout.SOUTH);
        root.add(menue, BorderLayout.NORTH);
        root.add(mitte, BorderLayout.CENTER);
        root.add(fuss, BorderLayout.SOUTH);
        palette = new Befehlspalette(fenster, befehle);
        tasten = Tasten.anmelden(befehle);
        Thema.beiWechsel(this::nachThema);
        nachThema();                                   // das Thema stand meist schon vor dem Fenster (Startbild)
        fenster.addWindowStateListener(e -> SwingUtilities.invokeLater(this::neuMalen));
        fenster.addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) { SwingUtilities.invokeLater(Hauptfenster.this::neuMalen); }
        });
    }

    // ------------------------------------------------------------------ Arbeitsbereiche, Kinoblick

    public void bereich(Arbeitsbereich a) { bereiche.put(a.kennung(), a); }

    public Collection<Arbeitsbereich> bereiche() { return bereiche.values(); }

    public String arbeitsbereich() { return bereich; }

    /** Stellt die Docks und Abschnitte eines Arbeitsbereichs ein (verlässt den Kinoblick). */
    public void arbeitsbereich(String kennung) {
        Arbeitsbereich a = bereiche.get(kennung);
        if (a == null) return;
        if (kino) kinoblick(false);
        bereich = kennung;
        links.zeige(a.links());
        rechts.zeige(a.rechts());
        unten.zeige(a.unten());
        if (a.abschnitteOffen() != null)
            for (Dock d : new Dock[] {links, rechts}) for (Abschnitt s : d.abschnitte()) s.zeige(a.abschnitteOffen().contains(s.kennung));
        rechtsText();
        if (nachAnsicht != null) nachAnsicht.run();
    }

    public boolean imKino() { return kino; }

    /** F11: nur das Bild (Menü, Docks, Statuszeile weg; die Einblendung steht in der Titelleiste) oder zurück. */
    public void kinoblick(boolean an) {
        if (an == kino) return;
        if (an) {
            vorKino = new boolean[] {links.offen(), rechts.offen(), unten.offen()};
            links.zeige(false);
            rechts.zeige(false);
            unten.zeige(false);
            menue.setVisible(false);
            status.setVisible(false);
            for (Dock d : new Dock[] {links, rechts, unten}) d.setVisible(false);       // auch die Griffe: nur das Bild
        } else {
            for (Dock d : new Dock[] {links, rechts, unten}) d.setVisible(true);
            menue.setVisible(true);
            status.setVisible(true);
            if (vorKino != null) {
                links.zeige(vorKino[0]);
                rechts.zeige(vorKino[1]);
                unten.zeige(vorKino[2]);
            }
        }
        kino = an;
        fenster.getComponentPane().revalidate();
        titelSetzen();
        neuMalen();
        if (nachAnsicht != null) nachAnsicht.run();
    }

    private void nachDock() {
        fenster.getComponentPane().revalidate();
        SwingUtilities.invokeLater(this::neuMalen);
    }

    // ------------------------------------------------------------------ Anzeige

    /** Die Zeile der Einblendung: in der Statuszeile, im Kinoblick in der Titelleiste. */
    public void einblendung(String zeile) {
        einblendung = zeile;
        status.links(zeile);
        titelSetzen();
    }

    private void titelSetzen() {
        String neu = kino || !status.isShowing() ? manifest.titel() + " · " + einblendung : manifest.titel();
        if (!neu.equals(titel)) {
            titel = neu;
            fenster.setTitle(neu);
        }
    }

    private void rechtsText() {
        Arbeitsbereich a = bereiche.get(bereich);
        status.rechts((a != null ? a.name() + " · " : "") + Thema.aktuell().name + " · Strg+K Befehle");
    }

    public void hinweis(String text, Hinweis.Art art) {
        Hinweis.zeige(fenster, text, art);
        java.util.function.BiConsumer<String, Hinweis.Art> p = beiHinweis;
        if (p != null) p.accept(text, art);
    }

    /** Seit W5: bekommt jeden Hinweis (für das Protokoll). */
    public volatile java.util.function.BiConsumer<String, Hinweis.Art> beiHinweis;

    public void palette() { palette.oeffnen(); }

    /** Fassungen für „Über“ (App, Steuerhaus, Renderer, Gerät …). */
    public void fassungen(List<String> f) {
        fassungen = new ArrayList<>(f);
        ueber = null;
    }

    public void ueber() {
        if (ueber == null) ueber = new UeberFenster(manifest, befehle, fassungen);
        ueber.zeige();
    }

    /**
     * Malt das ganze Fenster neu. Nach dem Maximieren und nach dem Bau der Fläche auf der Leinwand (schwergewichtig)
     * blieben leichte Teile sonst weiß und malten sich nur Stück für Stück unter der Maus (Sichtprüfung P8b).
     */
    public void neuMalen() {
        JRootPane r = fenster.getRootPane();
        r.revalidate();
        RepaintManager.currentManager(r).markCompletelyDirty(r);
        r.repaint();
    }

    // ------------------------------------------------------------------ Thema

    public void thema(Thema t) {
        t.anwenden();
    }

    private void nachThema() {
        Faerber.fenster(fenster, Thema.aktuell());
        menue.bauen();
        Faerber.faerbe(fenster.getRootPane());
        ueber = null;
        rechtsText();
        neuMalen();
    }

    // ------------------------------------------------------------------ Öffnen, Merken

    /** Zeigt das Fenster: Lage und Größe aus den Einstellungen, sonst 1900 × 1040 zentriert; maximiert wie gemerkt (Vorgabe: ja). */
    public void oeffnen() {
        Einstellungen e = einstellungen;
        int b = e.ganz("fenster.breite", 1900), h = e.ganz("fenster.hoehe", 1040);
        fenster.setPreferredFrameSize(new Dimension(b, h));
        fenster.setSize(b, h);
        if (e.hat("fenster.x")) fenster.setLocation(e.ganz("fenster.x", 0), e.ganz("fenster.y", 0));
        else fenster.setLocationRelativeTo(null);
        for (Dock d : new Dock[] {links, rechts, unten}) {
            d.setzeMass(e.ganz("dock." + d.kennung + ".mass", d.mass()));
            for (Abschnitt s : d.abschnitte()) if (e.hat("abschnitt." + s.kennung + ".offen")) s.zeige(e.wahr("abschnitt." + s.kennung + ".offen", true));
        }
        fenster.setVisible(true);
        fenster.setMaximizedBounds(GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds());
        if (e.wahr("fenster.maximiert", true)) fenster.setExtendedState(JFrame.MAXIMIZED_BOTH);
        leinwand.requestFocus();
        rechtsText();
    }

    /** Die Docks wie in den Einstellungen gemerkt (nach dem Arbeitsbereich: was der Benutzer danach geändert hat). */
    public void docksAusEinstellungen() {
        for (Dock d : new Dock[] {links, rechts, unten}) if (einstellungen.hat("dock." + d.kennung + ".offen")) d.zeige(einstellungen.wahr("dock." + d.kennung + ".offen", true));
    }

    /** Schreibt Fenster, Docks, Abschnitte, Arbeitsbereich und Thema in die Einstellungen (ohne zu speichern). */
    public void merken() {
        Einstellungen e = einstellungen;
        boolean max = (fenster.getExtendedState() & JFrame.MAXIMIZED_BOTH) == JFrame.MAXIMIZED_BOTH;
        e.setze("fenster.maximiert", max);
        if (!max) {
            Rectangle r = fenster.getBounds();
            e.setze("fenster.x", r.x);
            e.setze("fenster.y", r.y);
            e.setze("fenster.breite", r.width);
            e.setze("fenster.hoehe", r.height);
        }
        boolean[] offen = kino && vorKino != null ? vorKino : new boolean[] {links.offen(), rechts.offen(), unten.offen()};
        Dock[] ds = {links, rechts, unten};
        for (int i = 0; i < 3; i++) {
            e.setze("dock." + ds[i].kennung + ".offen", offen[i]);
            e.setze("dock." + ds[i].kennung + ".mass", ds[i].mass());
            for (Abschnitt s : ds[i].abschnitte()) e.setze("abschnitt." + s.kennung + ".offen", s.offen());
        }
        e.setze("arbeitsbereich", bereich);
        e.setze("thema", Thema.aktuell().schluessel());
    }

    public void schliessen() {
        tasten.abmelden();
        palette.dispose();
    }
}
