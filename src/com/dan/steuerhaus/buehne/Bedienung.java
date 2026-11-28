package com.dan.steuerhaus.buehne;

import com.dan.fbutton.FButton;
import com.dan.fbutton.FButtonVariant;
import com.dan.fcombobox.FComboBox;
import com.dan.fslider.FSlider;
import com.dan.lauf.Szenario;
import com.dan.steuerhaus.thema.Faerber;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/**
 * Die Bedienung einer Bühne in Teilen für die Abschnitte des rechten Docks (Werkbuch HalligHooge 110, Umzug aus
 * {@code hallighooge.ui.Bedienfeld}): Zeit (Zeitraffer, Zurück, Halt, Vor), Ebenen (Häuser, Gestalt, Bewuchs,
 * Salzwiese, Brandung), Foto (Referenz) und Messblick (Partikel, LIC, Linien, Schnitt). Szenario und Ansicht wählt
 * der {@link Katalog}. Die Bedienung kennt die Bühne nicht; die App hängt sich an die Meldungen.
 */
public final class Bedienung {
    /** Richtung der Zeit, wie sie {@link #richtung} meldet. */
    public static final int ZURUECK = -1, HALT = 0, VOR = 1;

    public DoubleConsumer zeitraffer = r -> { };
    public IntConsumer richtung = r -> { };
    /** Foto wünschen oder abbrechen. */
    public Runnable foto = () -> { }, fotoAbbrechen = () -> { };
    /** Die Wahl im Messblick (Art: 0 Wasserstand, 1 Tiefe, 2 Trübung). */
    public record Messwahl(boolean partikel, boolean lic, boolean linien, int art) {
        public boolean an() { return partikel || lic || linien; }
    }
    public Consumer<Messwahl> messblick = m -> { };
    /** Einen Schnitt ziehen (zwei Klicks im Bild), ihn abbrechen oder wegnehmen. */
    public Runnable schnitt = () -> { }, schnittWeg = () -> { };
    /** Teile an und aus. */
    public final Schalter haeuser = new Schalter("Häuser"), gestalt = new Schalter("Gestalt"), bewuchs = new Schalter("Bewuchs"), salzwiese = new Schalter("Salzwiese"), brandung = new Schalter("Brandung"),
            seegang = new Schalter("Seegang");                             // seit S3d (SF37 A): die neue Fläche des Seegangs

    /** Ein Knopf, der einen Teil der Bühne an- und ausschaltet. */
    public final class Schalter {
        final FButton knopf;
        public final String name;
        boolean an = true, da = true;
        /** Wohin der Knopf schaltet. */
        public Consumer<Boolean> ziel = x -> { };

        Schalter(String name) {
            this.name = name;
            knopf = new FButton(name);
            knopf.setVariant(FButtonVariant.PRIMARY);
            knopf.addActionListener(e -> umschalten());
        }

        /** Wie ein Klick auf den Knopf (Befehl). */
        public void umschalten() {
            zeige(da, !an);
            if (!still) ziel.accept(an);
        }

        /** Zeigt, ob das Gebiet den Teil hat und ob er an ist (ohne den Knopf auszulösen). */
        public void zeige(boolean da, boolean an) {
            this.da = da;
            knopf.setVisible(da);
            this.an = an;
            knopf.setVariant(an ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        }

        public boolean an() { return an; }

        public boolean da() { return da; }
    }

    private final FSlider raffer = new FSlider(0, 0, 350, 250);
    private final JLabel rafferText = marke(" ");
    private final FButton zurueck = new FButton("Zurück"), halt = new FButton("Halt"), vor = new FButton("Vor");
    private final FButton fotoKnopf = new FButton("Foto");
    private static final int[] MINUTEN = {3, 15, 60};
    private final FComboBox fotoZeit = new FComboBox(new Object[] {"höchstens 3 min", "höchstens 15 min", "höchstens 60 min"});
    private final JLabel fotoText = marke("<html>Rechnet die Ansicht mit der Referenz;<br>die Zeit hält an.</html>");
    private boolean still, fotoLaeuft, live;
    private int richtungJetzt = VOR;
    private final FButton mPartikel = new FButton("Partikel"), mLic = new FButton("LIC"), mLinien = new FButton("Linien");
    private final boolean[] mAn = new boolean[3];
    private final FComboBox mArt = new FComboBox(new Object[] {"Linien: Wasserstand", "Linien: Tiefe", "Linien: Trübung"});
    private final FButton schnittKnopf = new FButton("Schnitt ziehen");
    private final JLabel messText = marke(" ");
    private int schnittStand;
    private final JPanel zeitTeil, ebenenTeil, fotoTeil, messTeil;

    public Bedienung() {
        // Auswahllisten als eigenes Fenster: neben der Vulkan-Leinwand bauten sich leichte Listen nur Stück für Stück auf (P8b)
        fotoZeit.setLightWeightPopupEnabled(false);
        mArt.setLightWeightPopupEnabled(false);
        raffer.addChangeListener(e -> {
            rafferText.setText(String.format(Locale.GERMAN, "Zeitraffer %.0f", wert()));
            if (!still) zeitraffer.accept(wert());
        });
        zurueck.addActionListener(e -> richtung(ZURUECK));
        halt.addActionListener(e -> richtung(HALT));
        vor.addActionListener(e -> richtung(VOR));
        JPanel tasten = reihe(zurueck, halt, vor);
        zeitTeil = spalte(rafferText, raffer, Box.createVerticalStrut(6), tasten);

        JPanel eb = new JPanel(new GridLayout(0, 2, 6, 6));
        eb.setOpaque(false);
        for (Schalter k : new Schalter[] {haeuser, gestalt, bewuchs, salzwiese, brandung, seegang}) eb.add(k.knopf);
        ebenenTeil = spalte(eb);

        fotoKnopf.addActionListener(e -> {
            if (fotoLaeuft) fotoAbbrechen.run();
            else foto.run();
        });
        fotoTeil = spalte(fotoZeit, Box.createVerticalStrut(4), fotoKnopf, Box.createVerticalStrut(4), fotoText);

        FButton[] mk = {mPartikel, mLic, mLinien};
        for (int i = 0; i < 3; i++) {
            int k = i;
            mk[i].setVariant(FButtonVariant.GHOST);
            mk[i].addActionListener(e -> messUmschalten(k));
        }
        mArt.addActionListener(e -> {
            if (!still) messblick.accept(messwahl());
        });
        schnittKnopf.setVariant(FButtonVariant.GHOST);
        schnittKnopf.addActionListener(e -> schnittKnopf());
        messTeil = spalte(reihe(mk), Box.createVerticalStrut(4), mArt, Box.createVerticalStrut(4), schnittKnopf, Box.createVerticalStrut(4), messText);
        waehle(VOR, false);
    }

    public JComponent zeit() { return zeitTeil; }

    public JComponent ebenen() { return ebenenTeil; }

    public JComponent foto() { return fotoTeil; }

    public JComponent mess() { return messTeil; }

    private static JPanel reihe(JComponent... cs) {
        JPanel p = new JPanel(new GridLayout(1, cs.length, 6, 0));
        p.setOpaque(false);
        for (JComponent c : cs) p.add(c);
        return p;
    }

    private static JPanel spalte(Component... cs) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        Faerber.rolle(p, "flaeche");
        for (Component c : cs) {
            if (c instanceof JComponent j) {
                j.setAlignmentX(Component.LEFT_ALIGNMENT);
                if (!(c instanceof JLabel)) j.setMaximumSize(new Dimension(Integer.MAX_VALUE, c instanceof FSlider ? 44 : c instanceof JPanel q && q.getLayout() instanceof GridLayout gl && gl.getRows() == 0 ? 200 : 36));
            }
            p.add(c);
        }
        return p;
    }

    private static JLabel marke(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        Faerber.rolle(l, "leise");
        return l;
    }

    // ------------------------------------------------------------------ Zeit

    /** Wie ein Klick auf Zurück, Halt oder Vor (Befehl); live gibt es kein Zurück. */
    public void richtung(int r) {
        if (r == ZURUECK && live) return;
        waehle(r, true);
    }

    public int richtung() { return richtungJetzt; }

    private void waehle(int r, boolean melden) {
        richtungJetzt = r;
        zurueck.setVariant(r == ZURUECK ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        halt.setVariant(r == HALT ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        vor.setVariant(r == VOR ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        if (melden) richtung.accept(r);
    }

    /** Zeitraffer um einen Schritt (ein Viertel einer Zehnerpotenz) schneller (+1) oder langsamer (−1). */
    public void rafferSchritt(int s) { raffer.setValue(Math.max(raffer.getMinimum(), Math.min(raffer.getMaximum(), raffer.getValue() + 25 * s))); }

    /** Zeitraffer aus dem Regler: 10^(Wert / 100), 1 … 3 162. */
    public double wert() { return Math.pow(10, raffer.getValue() / 100.0); }

    /** Stellt den Zeitraffer, ohne etwas zu melden. */
    public void zeigeRaffer(double r) {
        still = true;
        raffer.setValue((int) Math.round(100 * Math.log10(Math.max(1, r))));
        rafferText.setText(String.format(Locale.GERMAN, "Zeitraffer %.0f", wert()));
        still = false;
    }

    /** Stellt die Bedienung auf ein Szenario ein, ohne etwas zu melden: Zeitraffer, vorwärts; live ohne „Zurück“. */
    public void zeige(Szenario s) {
        zeigeRaffer(s.zeitraffer);
        live = s.live();
        zurueck.setEnabled(!live);
        waehle(VOR, false);
    }

    /** Zeigt „Halt“ als gewählt, ohne etwas zu melden (das Foto hält die Zeit an). */
    public void halt() { waehle(HALT, false); }

    public boolean live() { return live; }

    // ------------------------------------------------------------------ Foto

    /** Die gewählte Zeitgrenze des Fotos in Sekunden (F114 B: 3, 15 oder 60 min). */
    public double fotoSekunden() { return 60.0 * MINUTEN[Math.max(0, fotoZeit.getSelectedIndex())]; }

    public boolean fotoLaeuft() { return fotoLaeuft; }

    /** Stand des Fotos: läuft es (dann bricht der Knopf ab), und eine Zeile dazu (HTML erlaubt). */
    public void fotoStand(boolean laeuft, String text) {
        fotoLaeuft = laeuft;
        fotoKnopf.setText(laeuft ? "Foto abbrechen" : "Foto");
        fotoZeit.setEnabled(!laeuft);
        fotoText.setText("<html><div style='width:230px'>" + text + "</div></html>");
    }

    // ------------------------------------------------------------------ Messblick

    /** Wie ein Klick auf Partikel (0), LIC (1) oder Linien (2) (Befehl). */
    public void messUmschalten(int k) {
        FButton[] mk = {mPartikel, mLic, mLinien};
        mAn[k] = !mAn[k];
        mk[k].setVariant(mAn[k] ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        if (!still) messblick.accept(messwahl());
    }

    /** Die Art der Linien wählen (0 Wasserstand, 1 Tiefe, 2 Trübung; Befehl). */
    public void art(int a) { mArt.setSelectedIndex(a); }

    public Messwahl messwahl() { return new Messwahl(mAn[0], mAn[1], mAn[2], Math.max(0, mArt.getSelectedIndex())); }

    /** Stellt die Wahl ein, ohne etwas zu melden. */
    public void zeigeMesswahl(Messwahl m) {
        still = true;
        boolean[] w = {m.partikel(), m.lic(), m.linien()};
        FButton[] mk = {mPartikel, mLic, mLinien};
        for (int i = 0; i < 3; i++) {
            mAn[i] = w[i];
            mk[i].setVariant(w[i] ? FButtonVariant.PRIMARY : FButtonVariant.GHOST);
        }
        mArt.setSelectedIndex(m.art());
        still = false;
    }

    /** Wie ein Klick auf „Schnitt ziehen / abbrechen / weg“ (Befehl). */
    public void schnittKnopf() {
        if (schnittStand == 0) schnitt.run();
        else schnittWeg.run();
    }

    public int schnittStand() { return schnittStand; }

    /** Stand des Schnitts (0 keiner, 1 wartet auf Klicks, 2 gezeigt) und eine Zeile zum Messblick (HTML erlaubt, null leer). */
    public void messStand(int schnitt, String text) {
        schnittStand = schnitt;
        schnittKnopf.setText(schnitt == 0 ? "Schnitt ziehen" : schnitt == 1 ? "Schnitt abbrechen" : "Schnitt weg");
        schnittKnopf.setVariant(schnitt == 0 ? FButtonVariant.GHOST : FButtonVariant.PRIMARY);
        messText.setText(text == null ? " " : "<html><div style='width:230px'>" + text + "</div></html>");
    }
}
