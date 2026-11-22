package com.dan.steuerhaus.demo;

import com.dan.fbutton.FButton;
import com.dan.fstyle.FTheme;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Befehl;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.Einstellungen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Einblendungen;
import com.dan.steuerhaus.buehne.Orte;
import com.dan.steuerhaus.buehne.Szenenapp;
import com.dan.steuerhaus.thema.Faerber;
import com.dan.steuerhaus.thema.Thema;
import com.dan.steuerhaus.ui.Abschnitt;
import com.dan.steuerhaus.ui.Dock;
import com.dan.steuerhaus.ui.Menueleiste;
import com.dan.steuerhaus.ui.Startbild;
import com.dan.steuerhaus.ui.Statuszeile;

import javax.imageio.ImageIO;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prüfung des Steuerhauses ohne Fenster und ohne Grafikkarte (Werkbuch HalligHooge 110, W1, 5.2):
 * <ol>
 * <li>Manifest: Titel, Szenendatei, Quellen, keine doppelten Schlüssel, Katalog nennt nur Schlüssel der Szenendatei</li>
 * <li>Szenendatei ohne doppelte Schlüssel (Prüfstein 8)</li>
 * <li>Befehle: keine Taste doppelt oder bei der Kamera; die Palette findet jeden Befehl; jedes Szenario und jede
 * Ansicht hat einen Befehl (Prüfstein 7)</li>
 * <li>Einstellungen: schreiben und wieder lesen (Prüfstein 6, ohne App)</li>
 * <li>Importregel des Steuerhauses (Prüfstein 5)</li>
 * <li>Themen: FTheme folgt, FButton und FComboBox werden gefärbt; Labor hat genau die Farben bis Teil A</li>
 * <li>Bilder der Oberfläche in beiden Themen (ohne Leinwand) und das Startbild</li>
 * </ol>
 * <pre>
 * java -cp ... com.dan.steuerhaus.demo.SteuerhausCheck --manifest src\szenen\Hooge.steuerhaus --quellen ..\Steuerhaus\src --bilder docs\status
 * </pre>
 */
public final class SteuerhausCheck {
    static int steine, verstoesse;

    public static void main(String[] args) throws Exception {
        File manifest = null, quellen = null, bilder = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--quellen" -> quellen = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("SteuerhausCheck – Steuerhaus W3 (Werkbuch HalligHooge 110, 113, 116)");
        Manifest m = Manifest.lies(manifest);
        File szd = m.szenen();
        Szenen sz = Szenen.lies(szd);

        teil("1 Manifest");
        pruefe(!m.titel().isEmpty() && szd != null && szd.isFile(), "Titel und Szenendatei", m.titel() + ", " + szd, "vorhanden");
        pruefe(m.quellen().size() >= 1, "Quellennachweis", m.quellen().size() + " Zeilen", "≥ 1");
        pruefe(m.doppelt().isEmpty(), "Manifest ohne doppelte Schlüssel", m.doppelt().toString(), "keine");
        List<String> fremd = new ArrayList<>();
        for (Manifest.Gruppe g : m.gruppen("szenarien")) for (String k : g.schluessel()) if (sz.szenario(k) == null) fremd.add("Szenario " + k);
        for (Manifest.Gruppe g : m.gruppen("ansichten")) for (String k : g.schluessel()) if (sz.ansicht(k) == null) fremd.add("Ansicht " + k);
        pruefe(fremd.isEmpty(), "Katalog nennt nur Schlüssel der Szenendatei", fremd.isEmpty() ? m.gruppen("szenarien").size() + " + " + m.gruppen("ansichten").size() + " Gruppen" : fremd.toString(), "0 fremde");
        int ohne = 0;
        for (var s : sz.szenarien()) if (m.gruppen("szenarien").stream().noneMatch(g -> g.schluessel().contains(s.schluessel))) ohne++;
        for (var a : sz.ansichten()) if (m.gruppen("ansichten").stream().noneMatch(g -> g.schluessel().contains(a.schluessel))) ohne++;
        info("Einträge ohne Gruppe (stehen unter „Weitere“)", ohne + "; " + sz.szenarien().size() + " Szenarien, " + sz.ansichten().size() + " Ansichten");

        teil("2 Szenendatei");
        List<String> d = Manifest.doppelte(szd);
        pruefe(d.isEmpty(), "Prüfstein 8: Szenendatei ohne doppelte Schlüssel", d.isEmpty() ? "keine" : d.size() + ": " + d.subList(0, Math.min(6, d.size())), "0");

        teil("3 Befehle");
        Befehle b = Szenenapp.befehleOhneFenster(m, sz);
        List<String> tf = b.tastenFehler();
        pruefe(tf.isEmpty(), "Prüfstein 7: Tasten doppelt oder bei der Kamera", tf.isEmpty() ? "keine; " + b.alle().size() + " Befehle, " + b.alle().stream().filter(x -> x.taste != null).count() + " mit Taste" : tf.toString(), "0");
        List<String> nicht = new ArrayList<>();
        for (Befehl x : b.alle()) if (!b.suche(x.name).contains(x)) nicht.add(x.kennung);
        pruefe(nicht.isEmpty(), "Prüfstein 7: Die Palette findet jeden Befehl über seinen Namen", nicht.isEmpty() ? "alle " + b.alle().size() : nicht.toString(), "alle");
        int fehlt = 0;
        for (var s : sz.szenarien()) if (b.finde("szenario." + s.schluessel) == null) fehlt++;
        for (var a : sz.ansichten()) if (b.finde("ansicht." + a.schluessel) == null) fehlt++;
        pruefe(fehlt == 0, "Jedes Szenario und jede Ansicht als Befehl (Menü Szene, Palette)", fehlt + " fehlen", "0");
        List<String> ungueltig = new ArrayList<>();
        for (Befehl x : b.alle()) if (x.taste != null && javax.swing.KeyStroke.getKeyStroke(x.taste) == null) ungueltig.add(x.taste);
        pruefe(ungueltig.isEmpty(), "Tasten lesbar (KeyStroke)", ungueltig.isEmpty() ? "alle" : ungueltig.toString(), "alle");
        info("Gruppen der Menüleiste", String.join(" · ", b.gruppen().stream().map(g -> g.split("/")[0]).distinct().toList()));
        info("Palette: „lan“", b.suche("lan").stream().limit(4).map(x -> x.name).toList().toString());

        teil("4 Einstellungen");
        File tmp = File.createTempFile("steuerhaus_check", ".properties");
        tmp.delete();
        Einstellungen e1 = new Einstellungen(tmp);
        e1.setze("thema", "nautik");
        e1.setze("dock.rechts.mass", 333);
        e1.setze("zeitraffer", 316.2278);
        e1.setze("fenster.maximiert", false);
        boolean geschrieben = e1.speichern();
        Einstellungen e2 = new Einstellungen(tmp);
        pruefe(geschrieben && e2.text("thema", "").equals("nautik") && e2.ganz("dock.rechts.mass", 0) == 333 && Math.abs(e2.zahl("zeitraffer", 0) - 316.2278) < 1e-3
                && !e2.wahr("fenster.maximiert", true), "Schreiben und wieder lesen", e2.alle().toString(), "gleich");
        info("Ablage in der App", Einstellungen.fuer(m.titel()).datei.toString());
        tmp.delete();

        teil("5 Importregel");
        if (quellen != null) importregel(quellen);
        else info("Importregel", "ohne --quellen nicht geprüft");

        teil("6 Themen");
        Thema.LABOR.anwenden();
        FButton k = new FButton("Probe");
        com.dan.fcombobox.FComboBox l = new com.dan.fcombobox.FComboBox(new Object[] {"a"});
        Faerber.knopf(k, Thema.LABOR);
        boolean laborGleich = k.liquidColorTop.equals(new Color(0x00, 0xB5, 0xAD)) && k.liquidColorBottom.equals(new Color(0x8B, 0x00, 0x24))
                && Thema.LABOR.grund.equals(new Color(10, 14, 18)) && Thema.LABOR.tief.equals(new Color(8, 12, 15)) && Thema.LABOR.kurve.equals(new Color(127, 195, 223))
                && Thema.LABOR.marke.equals(new Color(226, 163, 90)) && Thema.LABOR.text.equals(new Color(220, 230, 235)) && Thema.LABOR.leise.equals(new Color(140, 160, 170));
        pruefe(laborGleich, "Labor: genau die Farben bis Teil A (App, Zeitleiste, FButton)", laborGleich ? "gleich" : "abweichend", "gleich");
        Thema.NAUTIK.anwenden();
        Faerber.knopf(k, Thema.NAUTIK);
        Faerber.liste(l, Thema.NAUTIK);
        boolean nautik = FTheme.getInstance().getPrimary().equals(Thema.NAUTIK.primaer) && k.liquidColorTop.equals(Thema.NAUTIK.primaer) && l.getLiquidColorTop().equals(Thema.NAUTIK.primaer);
        pruefe(nautik, "Nautik: FTheme, FButton und FComboBox in Messing", "FTheme " + hex(FTheme.getInstance().getPrimary()) + ", Knopf " + hex(k.liquidColorTop), "#c8a04a");
        Thema.LABOR.anwenden();
        pruefe(FTheme.getInstance().getPrimary().equals(new Color(0x00, 0xB5, 0xAD)), "Zurück zu Labor: FTheme wie die Vorgabe von FStyle", hex(FTheme.getInstance().getPrimary()), "#00b5ad");

        teil("6a Einblendungen ohne Karte (W2)");
        pruefe(b.finde("einblendung.alle") != null && "F6".equals(b.finde("einblendung.alle").taste), "Befehl „Alle Einblendungen“ auf F6", String.valueOf(b.finde("einblendung.alle")), "F6");
        int teile = 0;
        for (Einblendungen.Teil t : Einblendungen.Teil.values()) if (b.finde("einblendung." + t.kennung) != null) teile++;
        pruefe(teile == Einblendungen.Teil.values().length, "Je Teil ein Befehl (Menü Ansicht › Einblendungen)", teile + " von " + Einblendungen.Teil.values().length, "alle");
        pruefe(Einblendungen.vorgabe("betrachten").equals(java.util.EnumSet.of(Einblendungen.Teil.UHR, Einblendungen.Teil.KOMPASS))
                        && Einblendungen.vorgabe("messen").equals(java.util.EnumSet.of(Einblendungen.Teil.LEGENDE, Einblendungen.Teil.MASSSTAB))
                        && Einblendungen.vorgabe("steuern").size() == Einblendungen.Teil.values().length,
                "Vorgaben je Arbeitsbereich (Werkbuch 113, 4)", "Betrachten " + Einblendungen.vorgabe("betrachten") + ", Messen " + Einblendungen.vorgabe("messen"), "wie geplant");
        String[] om = new String[1];
        List<Orte.Ort> orte = Orte.lies(m, sz.gebiet(), om);
        info("Ortsnamen", om[0]);
        var zz = Orte.zeile("Warft; Hanswarft; 471000.5; 6046600.0; node/1", "OSM");
        pruefe(zz != null && zz.name().equals("Hanswarft") && zz.ost() == 471000.5 && Orte.zeile("# nur Kommentar", "x") == null, "Zeilen von orte.txt lesen", String.valueOf(zz), "Hanswarft");
        info("Orte nach dem Manifest", orte.size() + (orte.isEmpty() ? "" : ": " + orte.stream().limit(12).map(Orte.Ort::name).toList()));

        teil("6b Regie (W3)");
        for (String kb : List.of("regie.abbrechen", "regie.schluessel", "regie.abspielen", "regie.speichern", "lesezeichen.setzen"))
            pruefe(b.finde(kb) != null, "Befehl " + kb, String.valueOf(b.finde(kb)), "da");
        info("Regiebücher und Fahrten im Menü", b.alle().stream().filter(x -> x.kennung.startsWith("regie.buch.") || x.kennung.startsWith("regie.fahrt.")).map(x -> x.name).toList().toString());

        teil("6c Ausgabe, Analyse und Betrieb (W4, W5)");
        for (String kb : List.of("ausgabe.video", "ausgabe.standbild", "daten.ort", "sonden.weg", "sonden.csv", "vergleich.bild", "qualitaet.hoch", "qualitaet.mittel", "qualitaet.niedrig",
                "qualitaet.auto", "protokoll.oeffnen", "bereich.werkstatt"))
            pruefe(b.finde(kb) != null, "Befehl " + kb, String.valueOf(b.finde(kb)), "da");

        teil("6d Startseite und Atlas (X1)");
        pruefe(b.finde("startseite") != null && "HOME".equals(b.finde("startseite").taste), "Befehl „Startseite“ auf Pos1", String.valueOf(b.finde("startseite")), "HOME");
        boolean sl = com.dan.steuerhaus.atlas.Atlas.schluessel("Süderoogsand").equals("suederoogsand") && com.dan.steuerhaus.atlas.Atlas.schluessel("Hallig Hooge").equals("hallighooge");
        pruefe(sl, "Schlüssel der Orte für die Texte", com.dan.steuerhaus.atlas.Atlas.schluessel("Süderoogsand") + ", " + com.dan.steuerhaus.atlas.Atlas.schluessel("Hallig Hooge"), "suederoogsand, hallighooge");
        com.dan.steuerhaus.atlas.Atlas at = null;
        if (com.dan.steuerhaus.atlas.Atlas.da(szd)) {
            at = com.dan.steuerhaus.atlas.Atlas.lies(m, szd);
            int bilderDa = 0;
            for (var e : at.ebenen) if (e.bild().isFile()) bilderDa++;
            pruefe(at.ebenen.size() >= 3 && bilderDa == at.ebenen.size(), "X1 Prüfstein 1: Ebenen des Atlas mit Bild", bilderDa + " von " + at.ebenen.size() + " (" + at.erzeugt + ")", "≥ 3, alle mit Bild");
            boolean steigt = true;
            for (int i = 1; i < at.ebenen.size(); i++) if (!(at.ebenen.get(i).wasser() > at.ebenen.get(i - 1).wasser())) steigt = false;
            StringBuilder ws = new StringBuilder();
            for (var e : at.ebenen) ws.append(e.kennung()).append(String.format(java.util.Locale.GERMAN, " %.1f %%, Pegel %.2f; ", 100 * e.wasser(), e.pegel()));
            pruefe(steigt, "X1 Prüfstein 2: Wasser nimmt von Ebene zu Ebene zu (Niedrigwasser, Hochwasser, Landunter)", ws.toString(), "steigend");
            var hw = at.orte.stream().filter(o -> o.name().equals("Hanswarft")).findFirst().orElse(null);
            pruefe(hw != null && hw.kuppe() > 4 && hw.kuppe() < 7.5 && hw.haeuser() >= 5, "X1 Prüfstein 3: Hanswarft – Kuppe 4 … 7,5 m NHN, mindestens 5 Häuser",
                    hw == null ? "fehlt" : String.format(java.util.Locale.GERMAN, "Kuppe %.2f m NHN, Fuß %.2f, %d Häuser, Wasser am Fuß %s", hw.kuppe(), hw.fuss(), hw.haeuser(), hw.wasserAmFuss()), "plausibel");
            List<String> namen = at.orte.stream().map(com.dan.steuerhaus.atlas.Atlas.Ort::schluessel).toList();
            List<String> fremdT = new ArrayList<>();
            File texteDatei = com.dan.steuerhaus.atlas.Atlas.texte(m, szd);
            if (texteDatei.isFile())
                for (String z : Files.readAllLines(texteDatei.toPath(), StandardCharsets.UTF_8)) {
                    Matcher mt = Pattern.compile("^\\s*tafel\\.([a-z0-9]+)\\.").matcher(z);
                    if (mt.find() && !namen.contains(mt.group(1)) && !fremdT.contains(mt.group(1))) fremdT.add(mt.group(1));
                }
            long mitText = at.orte.stream().filter(at::hatText).count();
            pruefe(fremdT.isEmpty(), "X1 Prüfstein 4: Jede Tafel der Texte gehört zu einem Ort der Karte", fremdT.isEmpty() ? mitText + " von " + at.orte.size() + " Orten mit Text" : "ohne Ort: " + fremdT, "0 ohne Ort");
            List<String> fk = new ArrayList<>();
            for (var o : at.orte)
                for (String z : at.nummern("tafel." + o.schluessel() + ".knopf.")) {
                    String[] t = z.split(";");
                    if (t.length < 2 || sz.szenario(t[1].strip()) == null) fk.add(o.name() + ": Szenario " + (t.length > 1 ? t[1].strip() : "?"));
                    if (t.length > 2 && !t[2].isBlank() && sz.ansicht(t[2].strip()) == null) fk.add(o.name() + ": Ansicht " + t[2].strip());
                }
            pruefe(fk.isEmpty(), "X1 Prüfstein 5: Knöpfe der Tafeln nennen Szenarien und Ansichten der Szenendatei", fk.isEmpty() ? "alle" : fk.toString(), "alle");
            info("Orte des Atlas", at.orte.stream().map(o -> o.name() + " (" + o.art() + ")").toList().toString());
        } else info("Atlas", "noch nicht gerechnet (" + com.dan.steuerhaus.atlas.Atlas.ordner(szd) + ")");

        teil("6e Kapitel (X2)");
        pruefe(b.finde("kapitel.weiter") != null && "ENTER".equals(b.finde("kapitel.weiter").taste) && b.finde("kapitel.zurueck") != null
                && "BACK_SPACE".equals(b.finde("kapitel.zurueck").taste), "Befehle Kapitel weiter (Eingabe) und zurück (Rücktaste)", b.finde("kapitel.weiter") + "; " + b.finde("kapitel.zurueck"), "da");
        List<String> kf = new ArrayList<>(), kn = new ArrayList<>();
        int kapitelZahl = 0;
        for (String kd : m.nummern("kapitel.")) {
            File f = m.relativ(kd);
            if (!f.isFile()) {
                kf.add(kd + " fehlt");
                continue;
            }
            com.dan.steuerhaus.regie.Regiebuch rb = com.dan.steuerhaus.regie.Regiebuch.lies(f.toPath());
            kapitelZahl++;
            for (var x : rb.fehler) kf.add(f.getName() + ": " + x);
            long tafeln = rb.zeilen.stream().filter(z -> z.befehl().equals("tafel")).count();
            long klicks = rb.zeilen.stream().filter(z -> z.befehl().equals("warte") && z.argumente().strip().equals("klick")).count();
            if (tafeln < 1 || klicks < tafeln) kf.add(f.getName() + ": " + tafeln + " Tafeln, " + klicks + " Halte");
            for (var z : rb.zeilen) {
                if (z.befehl().equals("szenario") && sz.szenario(z.argumente().strip()) == null) kf.add(f.getName() + " Zeile " + z.nr() + ": Szenario " + z.argumente());
                if (z.befehl().equals("ansicht") && sz.ansicht(z.argumente().strip()) == null) kf.add(f.getName() + " Zeile " + z.nr() + ": Ansicht " + z.argumente());
                if (z.befehl().equals("fahrt") && !rb.fahrten.containsKey(z.argumente().strip())) kf.add(f.getName() + " Zeile " + z.nr() + ": Fahrt " + z.argumente());
            }
            // jeder Abschnitt mit Tafel stellt Szenario und Zeit selbst (sonst führt „zurück“ in eine falsche Szene)
            com.dan.steuerhaus.regie.Regie rg0 = new com.dan.steuerhaus.regie.Regie(rb, new com.dan.steuerhaus.regie.Regie.Ctx() {
                @Override public void ausfuehren(com.dan.steuerhaus.regie.Regiebuch.Zeile z) { }

                @Override public boolean bereit(com.dan.steuerhaus.regie.Regiebuch.Zeile w) { return true; }
            });
            List<Integer> ab = rg0.abschnitte();
            for (int i = 0; i < ab.size(); i++) {
                int von = ab.get(i), bis = i + 1 < ab.size() ? ab.get(i + 1) : rb.zeilen.size();
                List<String> bef = rb.zeilen.subList(von, bis).stream().map(com.dan.steuerhaus.regie.Regiebuch.Zeile::befehl).toList();
                if (bef.contains("tafel") && !(bef.contains("szenario") && bef.contains("zeit") && bef.contains("weiter"))) kf.add(f.getName() + ": Abschnitt " + (i + 1) + " ohne szenario, zeit oder weiter");
            }
            kn.add(rb.name + " (" + tafeln + ")");
        }
        pruefe(kapitelZahl >= 6 && kf.isEmpty(), "X2 Prüfstein 1: Kapitel lesbar, je Tafel ein Halt, jeder Abschnitt stellt Szenario, Zeit und weiter, alle Schlüssel bekannt",
                kf.isEmpty() ? kapitelZahl + " Kapitel: " + kn : kf.subList(0, Math.min(6, kf.size())).toString(), "≥ 6, 0 Fehler");
        // Regie ohne Bild: Halte, Weiter, Zurück
        String rtxt = "Regiebuch: Probe\nLänge: 0:30\n0:00 weiter\n0:00 tafel Eins | a\n0:10 halt\n0:10 warte klick\n0:10 weiter\n0:10 tafel Zwei | b\n0:20 halt\n0:20 warte klick\n0:20 weiter\n0:20 tafel Drei | c\n0:30 halt\n0:30 warte klick\n";
        com.dan.steuerhaus.regie.Regiebuch prb = com.dan.steuerhaus.regie.Regiebuch.lies(rtxt, "Probe");
        boolean[] klick = {false};
        List<String> gezeigt = new ArrayList<>();
        com.dan.steuerhaus.regie.Regie rp = new com.dan.steuerhaus.regie.Regie(prb, new com.dan.steuerhaus.regie.Regie.Ctx() {
            @Override public void ausfuehren(com.dan.steuerhaus.regie.Regiebuch.Zeile z) { if (z.befehl().equals("tafel")) gezeigt.add(z.argumente()); }

            @Override public boolean bereit(com.dan.steuerhaus.regie.Regiebuch.Zeile w) {
                if (!w.argumente().equals("klick")) return true;
                boolean k = klick[0];
                klick[0] = false;
                return k;
            }
        });
        for (int i = 0; i < 400; i++) rp.schritt(0.1);                     // 40 s: steht beim ersten Halt (0:10)
        boolean steht1 = rp.wartet() != null && Math.abs(rp.uhr() - 10) < 1e-6 && gezeigt.equals(List.of("Eins"));
        klick[0] = true;
        for (int i = 0; i < 400; i++) rp.schritt(0.1);
        boolean steht2 = rp.wartet() != null && Math.abs(rp.uhr() - 20) < 1e-6 && gezeigt.equals(List.of("Eins", "Zwei"));
        rp.springe(Math.max(0, rp.abschnitt() - 1));
        boolean zurueck = gezeigt.equals(List.of("Eins", "Zwei", "Eins")) && rp.abschnitt() == 0;
        pruefe(steht1 && steht2 && zurueck && prb.fehler.isEmpty(), "X2 Prüfstein 2: Regie ohne Bild – hält am Klick, weiter mit Klick, zurück an den Abschnitt davor",
                "Tafeln " + gezeigt + ", Uhr " + rp.uhr() + ", Fehler " + prb.fehler, "Eins, Zwei, Eins");

        teil("6f Rundgänge (X3)");
        rundgaenge(m, sz, szd, at);

        teil("6g Postkarte (X4)");
        postkarten(m, bilder);

        teil("6h Ton (X5)");
        toene(bilder);

        teil("6i Vergleich (Y1)");
        vergleiche(m, sz, bilder);

        teil("6j Regler (Y2)");
        regler(b, bilder);

        teil("6k Ein Jahr in einer Minute (Y3)");
        jahr(m, sz, b, bilder);

        teil("6l Tidenkalender (Z1)");
        kalender(m, sz, b, bilder);

        teil("6m Landunter-Uhr (Z2)");
        landunter(m, sz, b, bilder);

        teil("6n Watt-Fenster (Z3)");
        watt(m, sz, b, bilder);

        teil("7 Bilder der Oberfläche");
        if (bilder != null) {
            File ab = bilder;
            ab.mkdirs();
            for (Thema t : Thema.values()) {
                Thema th = t;
                File[] f = new File[2];
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        th.anwenden();
                        f[0] = new File(ab, "W5_Oberflaeche_" + th.name + ".png");
                        ImageIO.write(oberflaeche(m, sz, b), "png", f[0]);
                        f[1] = new File(ab, "W5_Startbild_" + th.name + ".png");
                        ImageIO.write(Startbild.bild(m, "Steuerhaus W3 · " + m.titel() + " " + m.version(), "Die Bühne öffnet: Gelände …", 0.24), "png", f[1]);
                    } catch (Exception x) {
                        throw new RuntimeException(x);
                    }
                });
                info("Bilder " + t.name, f[0].getName() + ", " + f[1].getName());
                File fk = new File(ab, "X2_Kapiteltafel_" + th.name + ".png");
                th.anwenden();
                ImageIO.write(com.dan.steuerhaus.buehne.Kapitelbild.bild("Ebbe und Flut", "Niedrigwasser",
                        "Um 10:15 steht der Pegel Hooge bei −1,72 m NHN. Das Watt zwischen Hooge und dem Japsand fällt weit trocken: Sand und Schlick, durchzogen von Prielen, die auch jetzt Wasser führen.",
                        2, 5, true, null, Double.NaN, 1080), "png", fk);
                info("Kapiteltafel " + t.name, fk.getName());
                if (at != null) {
                    File fs = new File(ab, "X1_Startseite_" + th.name + ".png");
                    ImageIO.write(startseite(m, sz, at, th), "png", fs);
                    info("Startseite " + t.name, fs.getName());
                }
            }
            Thema.LABOR.anwenden();
        } else info("Bilder", "ohne --bilder nicht gezeichnet");

        System.out.println();
        System.out.println(steine + " Prüfsteine. " + (verstoesse == 0 ? "Alles in Ordnung." : verstoesse + (verstoesse == 1 ? " Verstoß." : " Verstöße.")));
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    /** Seit X1: die Startseite ohne Fenster, die Karte bei der Ansicht {@code atlas.blick}, die Tafel der Hanswarft. */
    static BufferedImage startseite(Manifest m, Szenen sz, com.dan.steuerhaus.atlas.Atlas at, Thema th) throws Exception {
        com.dan.steuerhaus.atlas.Startseite[] s = new com.dan.steuerhaus.atlas.Startseite[1];
        SwingUtilities.invokeAndWait(() -> {
            th.anwenden();
            s[0] = new com.dan.steuerhaus.atlas.Startseite(null, m, java.time.ZoneId.of(m.text("app.zone", "Europe/Berlin")));
            s[0].karte.atlas(at);
            s[0].kacheln.gruppe("Ansichten", sz.ansichten().stream().map(a -> new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(a.name, "Vorschau",
                    new File(com.dan.steuerhaus.buehne.Vorschau.ordner(sz), "ansicht_" + a.schluessel + ".png"), () -> { })).toList());
            s[0].stand("Die Bühne öffnet: Gelände …", 0.4);
            s[0].bild(1600, 960);
            double[] bl = com.dan.steuerhaus.atlas.Atlas.zahlenAus(m.text("atlas.blick", ""));
            if (bl != null && bl.length >= 3) s[0].karte.hin(bl[0], bl[1], bl[2]);
            var o = at.orte.stream().filter(x -> x.name().equals("Hanswarft")).findFirst().orElse(at.orte.isEmpty() ? null : at.orte.get(0));
            if (o != null) {
                s[0].karte.waehle(o);
                s[0].tafel.zeige(com.dan.steuerhaus.atlas.Tafeln.ort(at, o, java.time.ZoneId.of("Europe/Berlin"), com.dan.steuerhaus.buehne.Vorschau.ordner(sz), (a, b2, c) -> { }));
            }
            s[0].karte.vorladen();
        });
        for (int i = 0; i < 400 && !s[0].karte.geladen(); i++) Thread.sleep(50);
        BufferedImage[] r = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> r[0] = s[0].bild(1600, 960));
        return r[0];
    }

    /** Die Oberfläche wie im Arbeitsbereich „Steuern“, ohne Fenster; an Stelle der Leinwand eine dunkle Fläche. */
    static BufferedImage oberflaeche(Manifest m, Szenen sz, Befehle b) throws Exception {
        JComponent[] t = Szenenapp.teileOhneFenster(m, sz);
        JPanel root = new JPanel(new BorderLayout());
        Faerber.rolle(root, "grund");
        Dock links = new Dock(Dock.Seite.LINKS, "links", "", 300, 220, 560), rechts = new Dock(Dock.Seite.RECHTS, "rechts", "", 320, 260, 520),
                unten = new Dock(Dock.Seite.UNTEN, "unten", "", 150, 90, 360);
        links.abschnitt(new Abschnitt("szenarien", "Szenarien", t[0]));
        links.abschnitt(new Abschnitt("ansichten", "Ansichten", t[1]));
        rechts.abschnitt(new Abschnitt("zeit", "Zeit", t[2]));
        rechts.abschnitt(new Abschnitt("ebenen", "Ebenen", t[3]));
        rechts.abschnitt(new Abschnitt("foto", "Foto (Referenz)", t[4]));
        rechts.abschnitt(new Abschnitt("messblick", "Messblick", t[5]));
        unten.inhalt(t[6], BorderLayout.CENTER);
        JComponent bild = new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setColor(new Color(16, 22, 28));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(new Color(90, 105, 115));
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 16));
                g.drawString("Leinwand (Vulkan) – hier liegt das Bild", getWidth() / 2 - 150, getHeight() / 2);
            }
        };
        JPanel mitte = new JPanel(new BorderLayout());
        mitte.add(links, BorderLayout.WEST);
        mitte.add(bild, BorderLayout.CENTER);
        mitte.add(rechts, BorderLayout.EAST);
        Statuszeile st = new Statuszeile();
        st.links("30.10.2026 08:30:00 MEZ · Pegel Hooge −0,42 m NHN · Wind 6,3 m/s · Zeitraffer 316 · 59,7 Bilder/s · Archiv");
        st.rechts("Steuern · " + Thema.aktuell().name + " · Strg+K Befehle");
        JPanel fuss = new JPanel(new BorderLayout());
        fuss.add(unten, BorderLayout.CENTER);
        fuss.add(st, BorderLayout.SOUTH);
        root.add(new Menueleiste(b), BorderLayout.NORTH);
        root.add(mitte, BorderLayout.CENTER);
        root.add(fuss, BorderLayout.SOUTH);
        Faerber.faerbe(root);
        int w = 1600, h = 960;
        root.setSize(w, h);
        for (int i = 0; i < 3; i++) layout(root);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        root.printAll(g);
        g.dispose();
        return img;
    }

    private static void layout(Component c) {
        if (c instanceof Container k) {
            k.doLayout();
            for (Component x : k.getComponents()) layout(x);
        }
    }

    /**
     * Prüfstein 5: Der Kern ({@code com.dan.steuerhaus}) kennt weder AWT, Swing, FStyle noch den Renderer; {@code thema}
     * und {@code ui} kennen FStyle, aber nicht den Renderer; nur {@code buehne} kennt {@code com.dan.lauf} (ohne demo),
     * {@code com.dan.core} (ohne gpu), seit W2 {@code com.dan.render.overlay} und seit W3 den Java2D-Kern von
     * {@code com.dan.kino}; {@code regie} ist wie der Kern (nur java.*); seit W4 {@code ausgabe} wie {@code buehne}, dazu der
     * Kern von {@code com.dan.film}, ohne {@code ui}; seit DB1 {@code messung} ohne AWT und Swing, mit {@code com.dan.store},
     * {@code core.pruef}, {@code core.astro}, {@code core.raum}, dem Kern und {@code gezeiten}; kein Paket kennt ein Projekt oder LWJGL.
     */
    static void importregel(File src) throws Exception {
        File wurzel = new File(src, "com" + File.separator + "dan" + File.separator + "steuerhaus");
        List<File> dateien = new ArrayList<>();
        sammle(wurzel, dateien);
        Pattern name = Pattern.compile("\\b(com\\.dan(\\.[a-z_][a-z0-9_]*)+|org\\.[a-z_][a-z0-9_]*|java\\.awt|javax\\.[a-z_][a-z0-9_]*)");
        List<String> schlecht = new ArrayList<>();
        int zeilen = 0;
        for (File f : dateien) {
            String paket = wurzel.toPath().relativize(f.getParentFile().toPath()).toString().replace(File.separatorChar, '.');
            int nr = 0;
            boolean kommentar = false;
            for (String z : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                nr++;
                zeilen++;
                String c = z;
                if (kommentar) {
                    int e = c.indexOf("*/");
                    if (e < 0) continue;
                    c = c.substring(e + 2);
                    kommentar = false;
                }
                for (int a; (a = c.indexOf("/*")) >= 0; ) {
                    int e = c.indexOf("*/", a + 2);
                    if (e < 0) {
                        c = c.substring(0, a);
                        kommentar = true;
                        break;
                    }
                    c = c.substring(0, a) + c.substring(e + 2);
                }
                int k = c.indexOf("//");
                if (k >= 0) c = c.substring(0, k);
                c = c.replaceAll("\"(\\\\.|[^\"\\\\])*\"", "\"\"");
                Matcher mt = name.matcher(c);
                while (mt.find()) if (!erlaubt(paket, mt.group())) schlecht.add(f.getName() + ":" + nr + " " + mt.group());
            }
        }
        pruefe(schlecht.isEmpty(), "Prüfstein 5: Importregel des Steuerhauses", schlecht.isEmpty() ? "0 Verstöße in " + dateien.size() + " Dateien, " + zeilen + " Zeilen"
                : schlecht.size() + ": " + schlecht.subList(0, Math.min(8, schlecht.size())), "0 Verstöße");
    }

    /**
     * X3 (Werkbuch HalligHooge 148): Prüfstein 1 die Quellen der Rundgänge, Prüfstein 2 die gerechneten Wege (nach
     * {@code --wege}), Prüfstein 3 der Bau eines Wegs ohne Daten (ein Netz aus zwei Wegen im rechten Winkel).
     */
    static void rundgaenge(com.dan.steuerhaus.Manifest m, com.dan.lauf.Szenen sz, File szd, com.dan.steuerhaus.atlas.Atlas at) throws Exception {
        List<String> f1 = new ArrayList<>(), f2 = new ArrayList<>(), namen = new ArrayList<>(), werte = new ArrayList<>();
        int zahl = 0, gerechnet = 0;
        for (String d : m.nummern("rundgang.")) {
            File q = m.relativ(d);
            if (!q.isFile()) {
                f1.add(d + " fehlt");
                continue;
            }
            var g = com.dan.steuerhaus.rundgang.Rundgang.lies(q);
            zahl++;
            for (String x : g.fehler) f1.add(q.getName() + ": " + x);
            if (sz.szenario(g.text("szenario", "ruhig")) == null) f1.add(q.getName() + ": Szenario " + g.text("szenario", "?"));
            String v = g.text("vorschau", "");
            if (!v.isEmpty() && sz.ansicht(v) == null) f1.add(q.getName() + ": Ansicht " + v);
            for (var h : g.halte) {
                boolean bekannt = h.hatLage() || at == null || at.orte.stream().anyMatch(o -> o.name().equalsIgnoreCase(h.ort()) || o.name().toLowerCase(java.util.Locale.GERMAN)
                        .startsWith(h.ort().toLowerCase(java.util.Locale.GERMAN)));
                if (!bekannt) f1.add(q.getName() + ": Ort „" + h.ort() + "“ nicht im Atlas");
                if (h.text().length() < 40) f1.add(q.getName() + ": Text zu „" + h.titel() + "“ fehlt oder ist kurz");
            }
            namen.add(g.name + " (" + g.halte.size() + " Halte)");
            // ---- der gerechnete Weg
            File w = com.dan.steuerhaus.rundgang.Rundgang.wegDatei(q);
            if (!w.isFile()) {
                f2.add(w.getName() + " fehlt (--wege)");
                continue;
            }
            if (q.lastModified() > w.lastModified()) f2.add(w.getName() + " ist älter als die Quelle (--wege)");
            var rb = com.dan.steuerhaus.regie.Regiebuch.lies(w.toPath());
            for (var x : rb.fehler) f2.add(w.getName() + ": " + x);
            long tafeln = rb.zeilen.stream().filter(z -> z.befehl().equals("tafel")).count();
            long klicks = rb.zeilen.stream().filter(z -> z.befehl().equals("warte") && z.argumente().strip().equals("klick")).count();
            if (tafeln != g.halte.size() || klicks != g.halte.size() || rb.fahrten.size() != g.halte.size())
                f2.add(w.getName() + ": " + tafeln + " Tafeln, " + klicks + " Halte, " + rb.fahrten.size() + " Fahrten bei " + g.halte.size() + " Halten");
            double tempo = g.zahl("tempo", 5), auge = g.zahl("auge", 1.8);
            for (String z : Files.readAllLines(w.toPath(), StandardCharsets.UTF_8)) {
                if (!z.matches("^# strecke\\.\\d+ =.*")) continue;
                String[] t = z.substring(z.indexOf('=') + 1).split(";");
                double weg = Double.parseDouble(t[2].strip()), luft = Double.parseDouble(t[3].strip()), auf = Double.parseDouble(t[4].strip());
                boolean verb = t[5].strip().equals("ja");
                double ueber = t[7].strip().equals("-") ? Double.NaN : Double.parseDouble(t[7].strip()), abst = Double.parseDouble(t[8].strip()), vt = Double.parseDouble(t[9].strip());
                String n = w.getName() + " " + t[0].strip() + " → " + t[1].strip();
                if (luft > 20 && (weg / luft < 0.99 || weg / luft > 3)) f2.add(n + ": Weg / Luftlinie " + String.format(java.util.Locale.GERMAN, "%.2f", weg / luft));
                if (verb && auf < 0.6) f2.add(n + ": nur " + Math.round(100 * auf) + " % auf Wegen");
                if (!verb) f2.add(n + ": gerade – kein Anschluss ans Wegenetz (OSM fehlt oder kein Weg in " + (int) com.dan.aufbereitung.Wegenetz.ANSCHLUSS + " m)");
                if (!Double.isNaN(ueber) && ueber < auge - 0.05) f2.add(n + ": Auge nur " + ueber + " m über dem Boden");
                if (abst > RUNDGANG_ABSTAND) f2.add(n + ": Schlüsselbilder " + abst + " m auseinander");
                if (vt > tempo * 1.05) f2.add(n + ": Tempo " + vt + " m/s");
                werte.add(String.format(java.util.Locale.GERMAN, "%s %.0f m, %.0f %% auf Wegen", t[1].strip(), weg, 100 * auf));
            }
            gerechnet++;
        }
        pruefe(zahl >= 2 && f1.isEmpty(), "X3 Prüfstein 1: Rundgänge lesbar, Orte im Atlas, Szenario und Vorschau bekannt, jeder Halt mit Text",
                f1.isEmpty() ? namen.toString() : f1.subList(0, Math.min(6, f1.size())).toString(), "≥ 2, 0 Fehler");
        pruefe(gerechnet == zahl && zahl > 0 && f2.isEmpty(), "X3 Prüfstein 2: Wege gerechnet – je Halt Tafel, Klick und Fahrt; Weg / Luftlinie 1 … 3, ≥ 60 % auf Wegen, Auge über dem Boden, Tempo",
                f2.isEmpty() ? gerechnet + " von " + zahl : f2.subList(0, Math.min(6, f2.size())).toString(), "alle, 0 Fehler");
        if (!werte.isEmpty()) info("Strecken", werte.toString());

        // ---- Prüfstein 3: ein Weg ohne Daten – zwei Wege im rechten Winkel, Halte an den Enden
        String json = "{\"osm3s\":{\"timestamp_osm_base\":\"Probe\"},\"elements\":["
                + "{\"type\":\"way\",\"id\":1,\"tags\":{\"highway\":\"track\"},\"geometry\":[{\"lat\":54.5700,\"lon\":8.5300},{\"lat\":54.5700,\"lon\":8.5400}]},"
                + "{\"type\":\"way\",\"id\":2,\"tags\":{\"highway\":\"track\"},\"geometry\":[{\"lat\":54.5700,\"lon\":8.5400},{\"lat\":54.5760,\"lon\":8.5400}]},"
                + "{\"type\":\"way\",\"id\":3,\"tags\":{\"highway\":\"proposed\"},\"geometry\":[{\"lat\":54.5700,\"lon\":8.5300},{\"lat\":54.5760,\"lon\":8.5400}]}]}";
        var osm = com.dan.aufbereitung.Osm.aus(json, 32);
        double[] a = {osm.wege.get(0).ost()[0], osm.wege.get(0).nord()[0]}, e = {osm.wege.get(1).ost()[1], osm.wege.get(1).nord()[1]};
        var netz = com.dan.aufbereitung.Wegenetz.aus(osm, new double[] {a[0] - 2000, a[1] - 2000, a[0] + 2000, a[1] + 2000});
        String rq = String.format(java.util.Locale.ROOT, "Rundgang: Probe%nBeschreibung: zwei Wege%ntempo = 5%nauge = 1.8%nwasser = 3%nboot = 1.2%n"
                + "halt.1 = %.2f %.2f | Anfang | Am Anfang des ersten Wegs, wo die Probe beginnt, steht die Kamera. | blick 90%n"
                + "halt.2 = %.2f %.2f | Ende | Am Ende des zweiten Wegs, nach der Ecke, hält die Probe. | blick 0 | sonde%n", a[0], a[1], e[0], e[1]);
        var pg = com.dan.steuerhaus.rundgang.Rundgang.lies(rq, null);
        String wt = com.dan.steuerhaus.rundgang.RundgangBau.regiebuch(pg, List.of(), netz);
        var prb = com.dan.steuerhaus.regie.Regiebuch.lies(wt, "Probe");
        var strecke = netz.weg(a[0], a[1], e[0], e[1]);
        double verh = strecke.laenge() / strecke.luftlinie();
        com.dan.steuerhaus.regie.Fahrt fa = prb.fahrten.get("Probe · 2");
        double zMin = Double.POSITIVE_INFINITY;
        if (fa != null) for (var k : fa.bilder()) zMin = Math.min(zMin, k.hoehe());
        boolean ok3 = prb.fehler.isEmpty() && netz.wege == 2 && strecke.verbunden() && Math.abs(verh - Math.sqrt(2)) < 0.15 && fa != null && Math.abs(zMin - 4.2) < 0.01
                && prb.zeilen.stream().anyMatch(z -> z.befehl().equals("unterwegs")) && prb.zeilen.stream().anyMatch(z -> z.befehl().equals("sonde"));
        pruefe(ok3, "X3 Prüfstein 3: Weg ohne Daten – um die Ecke statt quer (Weg / Luftlinie ≈ √2), ohne Gelände schwimmt das Auge auf Wasser + Boot, Zwischentafel und Sonde",
                String.format(java.util.Locale.GERMAN, "%d Wege, %d Knoten, Weg %.0f m, Luftlinie %.0f m (%.2f), Auge kleinst %.2f m NHN, Fehler %s", netz.wege, netz.knoten, strecke.laenge(),
                        strecke.luftlinie(), verh, zMin, prb.fehler), "≈ 1,41; 4,20 m; 0 Fehler");
    }

    /**
     * X4 (Werkbuch HalligHooge 150): Prüfstein 1 die Vorlagen des Manifests, Prüfstein 2 die Gestalt (Bildfläche in der
     * Karte, Schrift passt, A6 mit 300 dpi in der Datei), Prüfstein 3 Werte und Platzhalter. Mit {@code bilder} je Vorlage
     * eine Karte A6 mit einem gemalten Bild: X4_Postkarte_<n>.png.
     */
    static void postkarten(com.dan.steuerhaus.Manifest m, File bilder) throws Exception {
        List<String> vf = new ArrayList<>();
        var vl = com.dan.steuerhaus.ausgabe.Postkarte.vorlagen(m, vf);
        for (var v : vl)
            for (String muster : List.of(v.titel(), v.zeile())) {
                Matcher mt = Pattern.compile("\\{([a-z]+)\\}").matcher(muster);
                while (mt.find()) if (!com.dan.steuerhaus.ausgabe.Postkarte.PLATZHALTER.contains(mt.group(1))) vf.add(v.name() + ": {" + mt.group(1) + "} unbekannt");
            }
        pruefe(vl.size() >= 3 && vf.isEmpty(), "X4 Prüfstein 1: Vorlagen der Postkarte lesbar, nur bekannte Platzhalter", vf.isEmpty() ? vl.toString() : vf.toString(), "≥ 3, 0 Fehler");
        java.util.Map<String, String> w = new java.util.LinkedHashMap<>();
        w.put("titel", "Hallig Hooge");
        w.put("ort", "Lorenzwarft und Mitteltritt");
        w.put("ansicht", "Hanswarft nach Westen");
        w.put("szenario", "Ruhiger Tag, 30.10.2026");
        w.put("datum", "30. Oktober 2026");
        w.put("uhr", "16:20 Uhr");
        w.put("pegelname", "Pegel Hooge");
        w.put("pegel", "+1,23 m NHN");
        w.put("wind", "WSW 7 m/s");
        List<String> gf = new ArrayList<>();
        for (var f : com.dan.steuerhaus.ausgabe.Postkarte.Format.values())
            for (var v : vl) {
                java.awt.Rectangle r = com.dan.steuerhaus.ausgabe.Postkarte.bildflaeche(f.breite, f.hoehe, v.stil());
                double anteil = (double) r.width * r.height / (f.breite * f.hoehe);
                if (r.x < 0 || r.y < 0 || r.x + r.width > f.breite || r.y + r.height > f.hoehe || anteil < 0.7) gf.add(f.schluessel + "/" + v.name() + ": Bildfläche " + r + " (" + anteil + ")");
                String t = com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.titel(), w), z = com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.zeile(), w);
                if (!com.dan.steuerhaus.ausgabe.Postkarte.passt(f.breite, f.hoehe, v.stil(), t, z)) gf.add(f.schluessel + "/" + v.name() + ": Schrift passt nicht");
            }
        File tmp = File.createTempFile("postkarte", ".png");
        tmp.deleteOnExit();
        com.dan.steuerhaus.ausgabe.Postkarte.schreibe(new java.awt.image.BufferedImage(20, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), 300, tmp);
        int dpi = com.dan.steuerhaus.ausgabe.Postkarte.dpi(tmp);
        if (dpi != 300) gf.add("A6: " + dpi + " dpi in der Datei");
        pruefe(gf.isEmpty(), "X4 Prüfstein 2: Gestalt – Bildfläche in der Karte (≥ 70 %), Schrift passt in jedem Format, A6 mit 300 dpi", gf.isEmpty() ? "3 Formate × " + vl.size() + " Vorlagen, " + dpi + " dpi" : gf.toString(), "0 Fehler");
        String w1 = com.dan.steuerhaus.ausgabe.Postkarte.windRichtung(1, 0), w2 = com.dan.steuerhaus.ausgabe.Postkarte.windRichtung(0, -1), w3 = com.dan.steuerhaus.ausgabe.Postkarte.windRichtung(-1, -1);
        java.util.Map<String, String> ohneWind = new java.util.LinkedHashMap<>(w);
        ohneWind.put("wind", "");
        String f1 = com.dan.steuerhaus.ausgabe.Postkarte.fuelle("{ort} · {datum}, {uhr} · Wind {wind}", ohneWind);
        String f2 = com.dan.steuerhaus.ausgabe.Postkarte.fuelle("{ort} · {datum}, {uhr} · Wind {wind}", w);
        boolean ok3 = w1.equals("W") && w2.equals("N") && w3.equals("NO") && f1.equals("Lorenzwarft und Mitteltritt · 30. Oktober 2026, 16:20 Uhr")
                && f2.equals("Lorenzwarft und Mitteltritt · 30. Oktober 2026, 16:20 Uhr · Wind WSW 7 m/s");
        pruefe(ok3, "X4 Prüfstein 3: Werte – Wind „woher“ aus dem Vektor „wohin“ (W, N, NO), ein Teil ohne Wert fällt weg", w1 + ", " + w2 + ", " + w3 + "; „" + f1 + "“; „" + f2 + "“",
                "W, N, NO; ohne Wind ohne „Wind“");
        if (bilder != null) {
            bilder.mkdirs();
            java.awt.image.BufferedImage logo = null;
            String sy = m.text("app.icon", "");
            if (!sy.isEmpty() && m.relativ(sy).isFile()) logo = ImageIO.read(m.relativ(sy));
            var f = com.dan.steuerhaus.ausgabe.Postkarte.Format.A6;
            for (int i = 0; i < vl.size(); i++) {
                var v = vl.get(i);
                java.awt.Rectangle r = com.dan.steuerhaus.ausgabe.Postkarte.bildflaeche(f.breite, f.hoehe, v.stil());
                java.awt.image.BufferedImage foto = new java.awt.image.BufferedImage(r.width, r.height, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = foto.createGraphics();
                int hz = (int) (r.height * 0.55);
                g.setPaint(new java.awt.GradientPaint(0, 0, new java.awt.Color(0x3c5a80), 0, hz, new java.awt.Color(0xe9b98a)));
                g.fillRect(0, 0, r.width, hz);
                g.setPaint(new java.awt.GradientPaint(0, hz, new java.awt.Color(0x3f8c88), 0, r.height, new java.awt.Color(0x1d4b52)));
                g.fillRect(0, hz, r.width, r.height - hz);
                g.setColor(new java.awt.Color(0x4f6b3a));
                g.fillOval(r.width / 3, hz - r.height / 14, r.width / 3, r.height / 7);
                g.dispose();
                File d = new File(bilder, "X4_Postkarte_" + (i + 1) + ".png");
                com.dan.steuerhaus.ausgabe.Postkarte.schreibe(com.dan.steuerhaus.ausgabe.Postkarte.gestalte(foto, f.breite, f.hoehe, v.stil(),
                        com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.titel(), w), com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.zeile(), w), logo), f.dpi, d);
                info("Postkarte " + v.name(), d.getName());
            }
        }
    }

    /** Pegel (RMS) eines Klangs über {@code s} Sekunden nach 2 s Einschwingen; {Rufe, Spitze, RMS, gleich zum zweiten Lauf}. */
    static double[] klangPegel(com.dan.steuerhaus.ton.Klang.Werte w, double s, long saat) {
        int rate = 48000, n = (int) (s * rate);
        com.dan.steuerhaus.ton.Klang a = new com.dan.steuerhaus.ton.Klang(saat, rate), b = new com.dan.steuerhaus.ton.Klang(saat, rate);
        a.setze(w);
        b.setze(w);
        float[] l = new float[2 * rate], r = new float[2 * rate], l2 = new float[2 * rate], r2 = new float[2 * rate];
        a.rechne(l, r, 0, 2 * rate);
        b.rechne(l2, r2, 0, 2 * rate);
        boolean gleich = java.util.Arrays.equals(l, l2) && java.util.Arrays.equals(r, r2);
        float[] ll = new float[n], rr = new float[n];
        a.rechne(ll, rr, 0, n);
        double sum = 0, spitze = 0;
        boolean endlich = true;
        for (int i = 0; i < n; i++) {
            if (!Float.isFinite(ll[i]) || !Float.isFinite(rr[i])) endlich = false;
            sum += ll[i] * ll[i] + rr[i] * rr[i];
            spitze = Math.max(spitze, Math.max(Math.abs(ll[i]), Math.abs(rr[i])));
        }
        return new double[] {a.rufe(), endlich ? spitze : Double.NaN, Math.sqrt(sum / (2.0 * n)), gleich ? 1 : 0};
    }

    /**
     * X5 (Werkbuch HalligHooge 151): Prüfstein 1 der Klang (gleich bei gleichem Saatwert, endlich, höchstens 1),
     * Prüfstein 2 er folgt den Werten (Wind, Brandung, Möwen nur am Tag), Prüfstein 3 die Regeln von {@code Tonwerte} und
     * die WAV-Datei. Mit {@code bilder} zwei Hörproben: X5_Ton_ruhig.wav, X5_Ton_sturm.wav (je 20 s).
     */
    static void toene(File bilder) throws Exception {
        var sturm = new com.dan.steuerhaus.ton.Klang.Werte(20, 90, 1, 1, 1, 0.2, 0);
        var still = new com.dan.steuerhaus.ton.Klang.Werte(2, 0, 0, 0, 0, 0, 0);
        var windig = new com.dan.steuerhaus.ton.Klang.Werte(18, 0, 0, 0, 0, 0, 0);
        var brandung = new com.dan.steuerhaus.ton.Klang.Werte(2, 0, 1, 1, 0, 0, 0);
        var moewenTag = new com.dan.steuerhaus.ton.Klang.Werte(4, 0, 0, 0, 1, 1, 0);
        var moewenNacht = new com.dan.steuerhaus.ton.Klang.Werte(4, 0, 0, 0, 0, 1, 0);
        double[] ps = klangPegel(sturm, 10, 7), p0 = klangPegel(still, 6, 7), pw = klangPegel(windig, 6, 7), pb = klangPegel(brandung, 10, 7), pt = klangPegel(moewenTag, 60, 7),
                pn = klangPegel(moewenNacht, 60, 7);
        pruefe(ps[3] == 1 && !Double.isNaN(ps[1]) && ps[1] <= 1, "X5 Prüfstein 1: Klang – gleicher Saatwert gleiche Abtastwerte, endlich, höchstens 1 (Sturm, 10 s)",
                String.format(java.util.Locale.GERMAN, "gleich %s, Spitze %.3f, RMS %.3f", ps[3] == 1, ps[1], ps[2]), "gleich, ≤ 1");
        double dbWind = 20 * Math.log10(pw[2] / Math.max(1e-9, p0[2])), dbBrand = 20 * Math.log10(pb[2] / Math.max(1e-9, p0[2]));
        pruefe(dbWind >= 12 && dbBrand >= 6 && pt[0] >= 2 && pn[0] == 0, "X5 Prüfstein 2: Klang folgt den Werten – Wind 18 gegen 2 m/s, Brandung nah gegen keine, Möwen am Tag, nachts keine",
                String.format(java.util.Locale.GERMAN, "Wind +%.1f dB, Brandung +%.1f dB, Möwen in 60 s: Tag %.0f, Nacht %.0f", dbWind, dbBrand, pt[0], pn[0]), "≥ 12 dB, ≥ 6 dB, ≥ 2, 0");
        var luft = com.dan.steuerhaus.ton.Tonwerte.werte(6, 6, 0, 0, 300, 302, 0, Double.NaN, 20);       // Vogelblick über Wasser
        var ufer = com.dan.steuerhaus.ton.Tonwerte.werte(3, 0, -3, 90, 1.7, 1.9, 0, Double.NaN, 20);    // am Ufer, Wind von Nord, Blick nach Ost
        var land = com.dan.steuerhaus.ton.Tonwerte.werte(3, 0, 0, 0, 1.7, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.NaN, -10);
        var see = com.dan.steuerhaus.ton.Tonwerte.werte(15, 0, 0, 0, 2, 2, 0, 2.5, 10);
        File tmp = File.createTempFile("ton", ".wav");
        tmp.deleteOnExit();
        try (var wd = new com.dan.steuerhaus.ton.WavDatei(tmp, 48000)) {
            wd.schreibe(new float[480], new float[480], 0, 480);
        }
        long frames;
        try (var ais = javax.sound.sampled.AudioSystem.getAudioInputStream(tmp)) {
            frames = ais.getFrameLength();
        }
        boolean ok3 = luft.nah() < 0.01 && luft.moewen() < 0.05 && ufer.nah() > 0.9 && ufer.plaetschern() > 0.5 && Math.abs(ufer.windVon() - 270) < 1 && land.nah() == 0
                && land.tag() == 0 && see.brandung() == 1 && see.plaetschern() == 0 && frames == 480;
        pruefe(ok3, "X5 Prüfstein 3: Regeln – aus der Luft kein Ufer und kaum Möwen, am Ufer nah und Plätschern, Wind von links, nachts kein Tag, Hs 2,5 m volle Brandung; WAV lesbar",
                String.format(java.util.Locale.GERMAN, "Luft nah %.2f Möwen %.2f; Ufer nah %.2f Plätschern %.2f Wind von %.0f°; Land nah %.0f Tag %.0f; See Brandung %.2f; WAV %d Frames", luft.nah(),
                        luft.moewen(), ufer.nah(), ufer.plaetschern(), ufer.windVon(), land.nah(), land.tag(), see.brandung(), frames), "ja");
        if (bilder != null) {
            bilder.mkdirs();
            hoerprobe(new File(bilder, "X5_Ton_ruhig.wav"), new com.dan.steuerhaus.ton.Klang.Werte(4, 300, 0.1, 0.9, 1, 0.9, 0.7));
            hoerprobe(new File(bilder, "X5_Ton_sturm.wav"), new com.dan.steuerhaus.ton.Klang.Werte(19, 80, 1, 0.8, 0.6, 0.1, 0));
            info("Hörproben", "X5_Ton_ruhig.wav, X5_Ton_sturm.wav (je 20 s)");
        }
    }

    static void hoerprobe(File ziel, com.dan.steuerhaus.ton.Klang.Werte w) throws Exception {
        int rate = 48000;
        var k = new com.dan.steuerhaus.ton.Klang(1958, rate);
        k.setze(w);
        float[] l = new float[rate], r = new float[rate];
        try (var wd = new com.dan.steuerhaus.ton.WavDatei(ziel, rate)) {
            for (int s = 0; s < 20; s++) {
                k.rechne(l, r, 0, rate);
                for (int i = 0; i < rate; i++) {
                    l[i] *= 0.8f;
                    r[i] *= 0.8f;
                }
                wd.schreibe(l, r, 0, rate);
            }
        }
    }

    /**
     * Seit Y1: die Vergleiche ohne Archiv und ohne Karte – Prüfstein 1 die Datei (Szenarien mit Archiv, Ansichten, Grenzen),
     * Prüfstein 2 die Ausrichtung an erdachten Marken, Prüfstein 3 eine Mappe aus gemalten Bildern hin und zurück,
     * Prüfstein 4 der Spieler in allen drei Darstellungen (Bilder {@code Y1_Spieler_<Thema>.png}).
     */
    static void vergleiche(Manifest m, Szenen sz, File bilder) throws Exception {
        List<String> fehler = new ArrayList<>();
        List<com.dan.steuerhaus.vergleich.Vergleich> l = com.dan.steuerhaus.vergleich.Vergleich.lies(m, fehler);
        for (var v : l) {
            var a = sz.szenario(v.a());
            var b = sz.szenario(v.b());
            if (a == null || a.archiv == null) fehler.add(v.name() + ": A " + v.a() + (a == null ? " fehlt" : " ohne Archiv"));
            if (b == null || b.archiv == null) fehler.add(v.name() + ": B " + v.b() + (b == null ? " fehlt" : " ohne Archiv"));
            if (v.ansicht() != null && sz.ansicht(v.ansicht()) == null) fehler.add(v.name() + ": Ansicht " + v.ansicht() + " fehlt");
        }
        long schl = l.stream().map(com.dan.steuerhaus.vergleich.Vergleich::schluessel).distinct().count();
        if (schl != l.size()) fehler.add("Schlüssel doppelt");
        pruefe(fehler.isEmpty() && !l.isEmpty(), "Y1 Prüfstein 1: Datei der Vergleiche – Szenarien mit Archiv, Ansichten, Grenzen, Schlüssel einmal",
                fehler.isEmpty() ? l.size() + " Vergleiche, " + l.stream().mapToInt(com.dan.steuerhaus.vergleich.Vergleich::bilder).sum() + " Bilder je Seite" : fehler.toString(),
                "≥ 1, 0 Fehler");
        for (var v : l) info("Vergleich " + v.schluessel(), v.name() + " · " + v.a() + " | " + v.b() + " · " + v.ausrichtung() + " · " + v.bilder() + " Bilder");

        // Prüfstein 2: Ausrichtung an erdachten Marken (zwei Tiden je Tag)
        java.time.ZoneId zone = java.time.ZoneId.of("Europe/Berlin");
        Instant a0 = Instant.parse("2026-10-30T00:00:00Z"), b0 = Instant.parse("2026-10-25T06:00:00Z");
        Instant[] sa = {a0, a0.plusSeconds(24 * 3600)}, sb = {b0, b0.plusSeconds(18 * 3600)};
        List<com.dan.steuerhaus.vergleich.Vergleich.Marke> ma = List.of(new com.dan.steuerhaus.vergleich.Vergleich.Marke(true, Instant.parse("2026-10-30T02:25:00Z"), 1.30),
                new com.dan.steuerhaus.vergleich.Vergleich.Marke(false, Instant.parse("2026-10-30T09:15:00Z"), -1.72),
                new com.dan.steuerhaus.vergleich.Vergleich.Marke(true, Instant.parse("2026-10-30T14:50:00Z"), 1.25));
        List<com.dan.steuerhaus.vergleich.Vergleich.Marke> mb = List.of(new com.dan.steuerhaus.vergleich.Vergleich.Marke(false, Instant.parse("2026-10-25T06:30:00Z"), -0.4),
                new com.dan.steuerhaus.vergleich.Vergleich.Marke(true, Instant.parse("2026-10-25T12:20:00Z"), 2.45));
        var hb = new com.dan.steuerhaus.vergleich.Vergleich.Marke(true, Instant.parse("2026-10-25T23:45:00Z"), 2.97);
        var z1 = com.dan.steuerhaus.vergleich.Vergleich.Ausrichtung.lies("hochwasser 2026-10-30T15:00:00Z | hochwasser 2026-10-25T12:00:00Z").bezug(ma, null, sa, mb, hb, sb, -3 * 3600, 2.5 * 3600, zone);
        var z2 = com.dan.steuerhaus.vergleich.Vergleich.Ausrichtung.lies("zeit 2026-10-30T12:20:00Z | uhrzeit").bezug(ma, null, sa, mb, hb, sb, -2 * 3600, 2 * 3600, zone);
        var z3 = com.dan.steuerhaus.vergleich.Vergleich.Ausrichtung.lies("scheitel").bezug(ma, ma.get(2), sa, mb, hb, sb, -4 * 3600, 0.25 * 3600, zone);
        var z4 = com.dan.steuerhaus.vergleich.Vergleich.Ausrichtung.lies("hochwasser 2026-10-30T15:00:00Z | hochwasser").bezug(ma, null, sa, mb, hb, sb, -3 * 3600, 3 * 3600, zone);
        List<Double> f3 = com.dan.steuerhaus.vergleich.Vergleich.folge(z3, sa, sb, -4 * 3600, 0.25 * 3600, 300);
        boolean ok2 = z1.a().equals(Instant.parse("2026-10-30T14:50:00Z")) && z1.b().equals(Instant.parse("2026-10-25T12:20:00Z")) && z1.art().equals("Hochwasser")
                && z2.b().equals(Instant.parse("2026-10-25T12:20:00Z")) && z2.art().equals("gleiche Uhrzeit")
                && z3.a().equals(Instant.parse("2026-10-30T14:50:00Z")) && z3.b().equals(hb.zeit()) && z3.art().equals("Scheitel")
                && z4.b().equals(Instant.parse("2026-10-25T12:20:00Z")) && f3.size() == 52 && Math.abs(f3.get(f3.size() - 1) - 900) < 1e-9 && Math.abs(f3.get(0) + 14400) < 1e-9
                && com.dan.steuerhaus.vergleich.Vergleich.abstand(-2400).equals("−0:40 h") && com.dan.steuerhaus.vergleich.Vergleich.abstand(4800).equals("+1:20 h");
        pruefe(ok2, "Y1 Prüfstein 2: Ausrichtung – Hochwasser nächst der Zeit, gleiche Uhrzeit um ganze Tage, Scheitel am Ende der Reihe, Bereich beschnitten",
                String.format(java.util.Locale.ROOT, "HW %s|%s, Uhrzeit B %s, Scheitel %s|%s mit %d Bildern bis %s, HW ohne Zeit B %s", z1.a(), z1.b(), z2.b(), z3.a(), z3.b(), f3.size(),
                        com.dan.steuerhaus.vergleich.Vergleich.abstand(f3.isEmpty() ? 0 : f3.get(f3.size() - 1)), z4.b()), "wie erdacht");

        // Prüfstein 3: eine Mappe aus gemalten Bildern hin und zurück
        File d = java.nio.file.Files.createTempDirectory("vergleich").toFile();
        int w = 960, h = 540, n = 13;
        var sch = new com.dan.steuerhaus.vergleich.Mappe.Schreiber(d, 0.92f);
        Instant ta = Instant.parse("2026-10-30T14:50:00Z"), tb = Instant.parse("2026-10-25T12:20:00Z");
        BufferedImage[] ab = new BufferedImage[2];
        for (int k = 0; k < n; k++)
            for (int sd = 0; sd < 2; sd++) {
                BufferedImage im = probebild(w, h, k, n, sd == 1);
                sch.bild(k, sd == 1, im);
                if (k == n / 2) ab[sd] = im;
            }
        sch.vorschau(ab[0], ab[1], Thema.NAUTIK.marke);
        double[] tr = new double[31];
        double[] wa = new double[31], wb = new double[31], qa = new double[31], qb = new double[31];
        for (int i = 0; i < tr.length; i++) {
            tr[i] = -3600 + i * 240;
            wa[i] = 1.2 * Math.cos(tr[i] / 44712.0 * 2 * Math.PI);
            wb[i] = 2.4 + 0.5 * Math.cos(tr[i] / 44712.0 * 2 * Math.PI);
            qa[i] = 0.3 + 0.2 * Math.sin(tr[i] / 7000.0);
            qb[i] = i % 7 == 0 ? Double.NaN : 0.9 + 0.3 * Math.sin(tr[i] / 7000.0);
        }
        var reihen = List.of(new com.dan.steuerhaus.vergleich.Mappe.Reihe("Wasserstand", "m NHN", tr, wa, wb), new com.dan.steuerhaus.vergleich.Mappe.Reihe("Strömung", "m/s", tr, qa, qb));
        sch.sonde(1, com.dan.steuerhaus.vergleich.Mappe.csv("Probe", reihen));
        sch.setze("schluessel", "probe").setze("name", "Ruhiger Tag und Sturmflut (Probe)").setze("a.name", "Ruhiger Tag, 30.10.2026").setze("b.name", "Sturmflut, 25.10.2026")
                .setze("groesse", w + " " + h).setze("bezug", "Hochwasser").setze("bezug.a", ta + "; 1.250").setze("bezug.b", tb + "; 2.450").setze("pegel.name", "Pegel Hooge")
                .setze("schritt", "600");
        for (int k = 0; k < n; k++)
            sch.setze("bild." + (k + 1), String.format(java.util.Locale.ROOT, "%d; %s; %s; %.3f; %.3f", -3600 + k * 600, ta.plusSeconds(-3600 + k * 600L), tb.plusSeconds(-3600 + k * 600L),
                    1.2 * Math.cos((-3600 + k * 600) / 44712.0 * 2 * Math.PI), 2.4 + 0.5 * Math.cos((-3600 + k * 600) / 44712.0 * 2 * Math.PI)));
        sch.setze("sonde.1", "Fenne vor der Hanswarft; 470750.0; 6046500.0; 1.420; 620.0 330.0");
        sch.setze("sonde.2", "Japsand; 465986.7; 6046653.2; 1.390; -");
        sch.schliesse(true);
        var mp = com.dan.steuerhaus.vergleich.Mappe.lies(d);
        BufferedImage voll = mp.bild(3, true, 1), halb = mp.bild(3, true, 2);
        var r0 = mp.sonden.get(0).reihe("Strömung");
        boolean ok3 = mp.fertig && mp.bilder.size() == n && mp.sonden.size() == 2 && mp.sonden.get(0).reihen().size() == 2 && Double.isNaN(mp.sonden.get(1).x())
                && Math.abs(mp.sonden.get(0).x() - 620) < 1e-9 && voll != null && voll.getWidth() == w && halb != null && halb.getWidth() == w / 2
                && Double.isNaN(r0.b()[0]) && Math.abs(r0.a()[5] - qa[5]) < 1e-4 && mp.bezug.art().equals("Hochwasser") && mp.beimBezug() == 6 && mp.naechstes(1000) == 8
                && mp.vorschau().isFile() && Math.abs(mittel(voll, ab[1])) < 3;
        pruefe(ok3, "Y1 Prüfstein 3: Mappe hin und zurück – Index, Bilder (voll und halb), Sonden mit Lücken, Bezug, Vorschau",
                String.format(java.util.Locale.GERMAN, "%d Bilder, %d Sonden, Bild 4 B %d px (halb %d px), JPEG gegen Vorlage %.2f/255", mp.bilder.size(), mp.sonden.size(),
                        voll == null ? 0 : voll.getWidth(), halb == null ? 0 : halb.getWidth(), voll == null ? Double.NaN : mittel(voll, ab[1])), "wie geschrieben, < 3/255");

        // Prüfstein 4: der Spieler ohne Fenster in allen Darstellungen und beiden Themen
        List<String> pf = new ArrayList<>();
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[3];
            File[] ges = new File[1];
            SwingUtilities.invokeAndWait(() -> {
                th.anwenden();
                var va = new com.dan.steuerhaus.vergleich.Vergleichsansicht(mp, zone, d);
                va.zeige(8);
                va.teil(0.42);
                bi[0] = va.alsBild(1600, 1000);
                va.darstellung(com.dan.steuerhaus.vergleich.Vergleichsansicht.Darstellung.NEBENEINANDER);
                va.groesse("Strömung");
                bi[1] = va.alsBild(1600, 1000);
                va.darstellung(com.dan.steuerhaus.vergleich.Vergleichsansicht.Darstellung.WECHSEL);
                va.zeige(99);
                bi[2] = va.alsBild(1600, 1000);
                ges[0] = va.sichern();
                if (va.bild() != n - 1) pf.add(th.name + ": Bild " + va.bild() + " statt " + (n - 1));
                va.ende();
            });
            for (BufferedImage x : bi) if (x == null || x.getWidth() != 1600) pf.add(th.name + ": Bild fehlt");
            BufferedImage gs = ges[0] == null ? null : ImageIO.read(ges[0]);
            if (gs == null || gs.getWidth() != w || gs.getHeight() != h) pf.add(th.name + ": gesichertes Bild " + (gs == null ? "fehlt" : gs.getWidth() + " × " + gs.getHeight()));
            if (bilder != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "Y1_Spieler_" + th.name + ".png"));
                if (th == Thema.NAUTIK) ImageIO.write(bi[1], "png", new File(bilder, "Y1_Spieler_Nebeneinander.png"));
            }
        }
        Thema.LABOR.anwenden();
        pruefe(pf.isEmpty(), "Y1 Prüfstein 4: Spieler ohne Fenster – Schieber, nebeneinander, im Wechsel, Bild sichern in der Größe der Mappe, Grenzen der Folge",
                pf.isEmpty() ? "beide Themen" : pf.toString(), "0 Fehler");
        if (bilder != null) info("Bilder", "Y1_Spieler_Labor.png, Y1_Spieler_Nautik.png, Y1_Spieler_Nebeneinander.png (gemalte Probe)");
    }

    /**
     * Seit Z1: der Tidenkalender ohne Pegel und Archiv – Prüfstein 1 die Schlüssel {@code gezeiten.*}, Prüfstein 2 die
     * Vorhersage (gleich {@code Harmonik.Ergebnis.wert}; Extreme einer reinen M2 auf Zeit und Höhe; Spring und Nipp im
     * Abstand von 14,77 Tagen), Prüfstein 3 die Mondphasen gegen fünf veröffentlichte Zeiten, Prüfstein 4 die Ansicht ohne
     * Fenster (Tag, vier Wochen, acht Wochen, CSV; Bilder {@code Z1_Kalender_<Thema>.png}), Prüfstein 5 Menü und Taste.
     */
    static void kalender(Manifest m, Szenen sz, Befehle b, File bilder) throws Exception {
        var ga = com.dan.steuerhaus.gezeiten.Gezeitenauftrag.lies(m);
        List<String> f1 = new ArrayList<>();
        if (ga == null) f1.add("keine Schlüssel gezeiten.ort und gezeiten.bezug");
        else {
            if (Double.isNaN(ga.ost()) || Double.isNaN(ga.nord())) f1.add("Ort ohne Lage");
            for (String k : ga.modell()) {
                var s = sz.szenario(k);
                if (s == null || s.archiv == null) f1.add("Szenario " + k + (s == null ? " fehlt" : " ohne Archiv"));
            }
            if (ga.hinweis().length() < 60 || !ga.hinweis().contains("Navigation")) f1.add("Hinweis fehlt oder nennt die Navigation nicht");
            if (!(ga.stau() > 0) || !(ga.einschwingen() >= 0)) f1.add("stau/einschwingen");
        }
        pruefe(f1.isEmpty(), "Z1 Prüfstein 1: Schlüssel gezeiten.* – Ort mit Lage, Bezug, Prüfpegel, Szenarien mit Archiv, Hinweis mit „nicht zur Navigation“",
                ga == null ? "fehlt" : f1.isEmpty() ? ga.ort() + ", Bezug " + ga.bezug() + ", Prüfung " + ga.pruef() + ", Modell " + ga.modell() + ", Datei " + ga.datei().getName() : f1.toString(), "0 Fehler");

        // Prüfstein 2: Vorhersage
        List<String> f2 = new ArrayList<>();
        com.dan.core.astro.Partialtide[] pt = {com.dan.core.astro.Partialtide.M2, com.dan.core.astro.Partialtide.S2, com.dan.core.astro.Partialtide.K1,
                com.dan.core.astro.Partialtide.O1, com.dan.core.astro.Partialtide.M4, com.dan.core.astro.Partialtide.MS4, com.dan.core.astro.Partialtide.N2};
        double[][] ag = {{1.4, 352.8}, {0.49, 40.8}, {0.05, 11.2}, {0.10, 239.3}, {0.16, 213.1}, {0.12, 243.7}, {0.2, 338.8}};
        List<com.dan.core.pruef.Harmonik.Tide> ht = new ArrayList<>();
        List<com.dan.steuerhaus.gezeiten.Vorhersage.Konstante> vk = new ArrayList<>();
        for (int i = 0; i < pt.length; i++) {
            ht.add(new com.dan.core.pruef.Harmonik.Tide(pt[i], ag[i][0], ag[i][1]));
            vk.add(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(pt[i], ag[i][0], ag[i][1]));
        }
        var he = new com.dan.core.pruef.Harmonik.Ergebnis(0.08, ht, List.of(), 0, 0, 0);
        var vh = new com.dan.steuerhaus.gezeiten.Vorhersage(0.08, vk);
        double d2 = 0;
        for (int i = 0; i < 2000; i++) {
            Instant t = Instant.parse("2027-01-01T00:00:00Z").plusSeconds(i * 15_791L);
            d2 = Math.max(d2, Math.abs(he.wert(t) - vh.wert(t)));
        }
        if (d2 > 1e-9) f2.add("gegen Harmonik " + d2 + " m");
        var rein = new com.dan.steuerhaus.gezeiten.Vorhersage(0, List.of(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(com.dan.core.astro.Partialtide.M2, 1.5, 0)));
        var ex = rein.extreme(Instant.parse("2027-03-01T00:00:00Z"), Instant.parse("2027-03-08T00:00:00Z"));
        double per = 0, hmax = 0;
        int nh = 0;
        com.dan.steuerhaus.gezeiten.Vorhersage.Extrem vorig = null;
        for (var e : ex) {
            if (e.art() == com.dan.steuerhaus.gezeiten.Vorhersage.Art.HOCHWASSER) {
                if (vorig != null) {
                    per = Math.max(per, Math.abs((e.zeit().getEpochSecond() - vorig.zeit().getEpochSecond()) / 3600.0 - 12.4206));
                    nh++;
                }
                vorig = e;
            }
            double f = com.dan.core.astro.Partialtide.M2.f(e.zeit());
            hmax = Math.max(hmax, Math.abs(Math.abs(e.hoehe()) - 1.5 * f));
        }
        if (nh < 12 || per > 1.0 / 60 || hmax > 0.001) f2.add(String.format(Locale.ROOT, "reine M2: %d Abstände, Periode bis %.4f h daneben, Höhe bis %.4f m", nh, per, hmax));
        var gg = vh.gaenge(Instant.parse("2027-01-01T00:00:00Z"), Instant.parse("2027-04-01T00:00:00Z"));
        double gmax = 0;
        for (int i = 2; i < gg.size(); i++) gmax = Math.max(gmax, Math.abs((gg.get(i).zeit().getEpochSecond() - gg.get(i - 2).zeit().getEpochSecond()) / 86400.0 - 14.7653));
        if (gg.size() < 10 || gmax > 0.01) f2.add("Spring/Nipp: " + gg.size() + ", Abstand bis " + gmax + " d daneben");
        var tage = vh.tage(java.time.LocalDate.of(2027, 1, 4), 28, ZoneId.of("Europe/Berlin"));
        int z34 = 0;
        for (var t : tage) if (t.extreme().size() >= 3 && t.extreme().size() <= 4) z34++;
        if (z34 != 28) f2.add(z34 + " von 28 Tagen mit 3–4 Extremen");
        pruefe(f2.isEmpty(), "Z1 Prüfstein 2: Vorhersage – gleich Harmonik.Ergebnis.wert, reine M2 (Periode 12,42 h, Höhe f·A), Spring und Nipp alle 14,77 Tage, je Tag 3–4 Hoch- und Niedrigwasser",
                f2.isEmpty() ? String.format(Locale.GERMAN, "%.1e m; Periode ± %.1f s; %d Gänge", d2, per * 3600, gg.size()) : f2.toString(), "≤ 1e-9 m; ± 1 min; ± 1 mm; ± 0,01 d");

        // Prüfstein 3: Mondphasen gegen veröffentlichte Zeiten (UTC)
        String[][] ref = {{"VOLLMOND", "2026-02-01T22:09:00Z"}, {"LETZTES_VIERTEL", "2026-02-09T12:43:00Z"}, {"NEUMOND", "2026-02-17T12:01:00Z"}, {"NEUMOND", "2027-02-06T15:56:00Z"},
                {"VOLLMOND", "2027-02-20T23:23:00Z"}};
        double mmax = 0;
        List<String> f3 = new ArrayList<>();
        for (String[] r : ref) {
            Instant t = Instant.parse(r[1]);
            var ph = com.dan.steuerhaus.gezeiten.Mond.phasen(t.minusSeconds(2 * 86400), t.plusSeconds(2 * 86400));
            var e = ph.stream().filter(x -> x.phase().name().equals(r[0])).findFirst().orElse(null);
            if (e == null) {
                f3.add(r[0] + " " + r[1] + " fehlt");
                continue;
            }
            mmax = Math.max(mmax, Math.abs(e.zeit().getEpochSecond() - t.getEpochSecond()) / 60.0);
        }
        if (mmax > 2) f3.add(String.format(Locale.ROOT, "bis %.1f min daneben", mmax));
        pruefe(f3.isEmpty(), "Z1 Prüfstein 3: Mondphasen gegen veröffentlichte Zeiten (Neu- und Vollmond 2026/2027, Ephemeride des Kerns)", f3.isEmpty() ? String.format(Locale.GERMAN,
                "5 Phasen, höchstens %.1f min daneben", mmax) : f3.toString(), "± 2 min");

        // Prüfstein 4: die Ansicht ohne Fenster
        File d = java.nio.file.Files.createTempDirectory("kalender").toFile();
        File tf = new File(d, "probe.gezeiten");
        com.dan.steuerhaus.gezeiten.Gezeitentafel.schreibe(tf, "Probeort", 471381, 6047983, 0.08, vk, java.util.Map.of("morpho", "Probelauf"),
                List.of(new com.dan.steuerhaus.gezeiten.Gezeitentafel.Marke("morpho", com.dan.steuerhaus.gezeiten.Vorhersage.Art.HOCHWASSER, Instant.parse("2027-01-06T11:00:00Z"), 1.5)),
                java.util.Map.of("bezug", "Probe"), null);
        var tafel = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(tf);
        List<String> f4 = new ArrayList<>();
        if (tafel.tiden.size() != vk.size() || tafel.marken.size() != 1 || Math.abs(tafel.mittel - 0.08) > 1e-6) f4.add("Tafel hin und zurück");
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[1];
            File[] ges = new File[2];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    var k = new com.dan.steuerhaus.gezeiten.Kalenderansicht(tafel, 2.01, "Landunter", "Probe: astronomische Gezeit ohne Wind. Nicht zur Navigation.", ZoneId.of("Europe/Berlin"), d);
                    k.jetzt = () -> Instant.parse("2027-01-06T10:20:00Z");
                    k.zeige(k.heute());
                    if (!k.tag().toString().equals("2027-01-06")) f4.add(th.name + ": heute " + k.tag());
                    if (k.tage().size() != 56) f4.add(th.name + ": " + k.tage().size() + " Tage statt 56");
                    if (k.phasen().size() < 7) f4.add(th.name + ": " + k.phasen().size() + " Mondphasen in acht Wochen");
                    k.zeige(k.tag().plusDays(1));
                    if (!k.tag().toString().equals("2027-01-07")) f4.add(th.name + ": Tag vor");
                    k.zeige(k.heute());
                    bi[0] = k.alsBild(1460, 960);
                    ges[0] = k.sichern();
                    ges[1] = k.alsListe();
                } catch (Exception x) {
                    f4.add(th.name + ": " + x);
                }
            });
            if (bi[0] == null) f4.add(th.name + ": Bild fehlt");
            if (ges[0] == null || ges[1] == null) f4.add(th.name + ": nicht gesichert");
            else {
                List<String> z = java.nio.file.Files.readAllLines(ges[1].toPath());
                if (z.size() < 200) f4.add(th.name + ": Liste mit " + z.size() + " Zeilen");
            }
            if (bilder != null && bi[0] != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "Z1_Kalender_" + th.name + ".png"));
            }
        }
        Thema.LABOR.anwenden();
        pruefe(f4.isEmpty(), "Z1 Prüfstein 4: Kalender ohne Fenster – Tafel hin und zurück, heute, acht Wochen, Mondphasen, blättern, Bild und Liste sichern",
                f4.isEmpty() ? "beide Themen" : f4.toString(), "0 Fehler");
        if (bilder != null) info("Bilder", "Z1_Kalender_Labor.png, Z1_Kalender_Nautik.png (gemalte Probe)");

        // Prüfstein 5: Menü
        Befehl x = b.finde("begleiter.kalender");
        pruefe(ga == null || (x != null && "ctrl shift T".equals(x.taste)), "Z1 Prüfstein 5: Menü Begleiter › Tidenkalender (Strg Umschalt T)", x == null ? "fehlt" : x.name + ", " + x.taste,
                "vorhanden");
    }

    /**
     * Seit Z2: die Landunter-Uhr ohne Netz und ohne Archiv – Prüfstein 1 die Schlüssel {@code landunter.*} (Krone,
     * Hallig mit Warften im Atlas, Wahl, Hinweis), Prüfstein 2 die Rechnung an einer reinen M2 (Hochwasser, nötiger
     * Windstau, Zeit über der Krone gegen die geschlossene Lösung, Badewanne, Stellung der Uhr), Prüfstein 3 PEGELONLINE
     * ohne Netz (Adresse, Antwort deuten, Windstau der letzten Stunde), Prüfstein 4 die Ansicht ohne Fenster (Messung
     * ersetzt, Wahl, eigener Wert, nicht erreichbar, Bild sichern; Bilder {@code Z2_Landunter_<Thema>.png}), Prüfstein 5
     * Menü und Taste.
     */
    static void landunter(Manifest m, Szenen sz, Befehle b, File bilder) throws Exception {
        var la = com.dan.steuerhaus.gezeiten.Landunterauftrag.lies(m);
        var ga = com.dan.steuerhaus.gezeiten.Gezeitenauftrag.lies(m);
        List<String> f1 = new ArrayList<>();
        List<com.dan.steuerhaus.gezeiten.Landunter.Warft> wf = List.of();
        if (la == null) f1.add("kein landunter.krone");
        else {
            if (ga == null) f1.add("ohne gezeiten.*");
            if (!(la.krone() > 0 && la.krone() < 5)) f1.add("Krone " + la.krone());
            if (ga != null && !Double.isNaN(ga.marke()) && Math.abs(ga.marke() - la.krone()) > 1e-6) f1.add("Krone " + la.krone() + " anders als gezeiten.marke " + ga.marke());
            if (!(la.nass() > la.krone() && la.nass() < la.krone() + 1.5)) f1.add("Landunter " + la.nass() + " nicht über der Krone");
            for (String k : la.modell()) {
                var x = sz.szenario(k);
                if (x == null || x.archiv == null) f1.add("Sturm " + k + (x == null ? " fehlt" : " ohne Archiv"));
            }
            if (la.hinweis().length() < 60 || !la.hinweis().contains("Navigation")) f1.add("Hinweis fehlt oder nennt die Navigation nicht");
            for (Object[] w : la.wahl()) if (!((Double) w[1] > -1 && (Double) w[1] < 4)) f1.add("Wahl " + w[0]);
            if (com.dan.steuerhaus.atlas.Atlas.da(sz.datei)) {
                wf = Szenenapp.warften(com.dan.steuerhaus.atlas.Atlas.lies(m, sz.datei), la);
                if (wf.size() < 5) f1.add(wf.size() + " Warften im Atlas um " + la.hallig());
                for (var w : wf) if (!(w.kuppe() > la.krone() + 1 && w.fuss() < w.kuppe())) f1.add("Warft " + w.name() + " Kuppe " + w.kuppe() + ", Fuß " + w.fuss());
            } else f1.add("kein Atlas");
        }
        pruefe(f1.isEmpty(), "Z2 Prüfstein 1: Schlüssel landunter.* – Krone wie die Marke des Kalenders, Landunter darüber, Stürme mit Archiv, Warften der Hallig im Atlas, Wahl, Hinweis",
                la == null ? "fehlt" : f1.isEmpty() ? String.format(Locale.GERMAN, "%s %+.2f m, %s %+.2f m (Stürme %s); %d Warften um %s (%s); %d zur Wahl", la.kroneName(), la.krone(),
                        la.nassName(), la.nass(), la.modell(), wf.size(), la.hallig(),
                        String.join(", ", wf.stream().map(com.dan.steuerhaus.gezeiten.Landunter.Warft::name).toList()), la.wahl().size()) : f1.toString(), "0 Fehler");

        // Prüfstein 2: reine M2 mit 1,5 m, Z₀ 0,08 m; Krone 2,01 m, Landunter ab 2,50 m
        var m2 = com.dan.core.astro.Partialtide.M2;
        var rein = new com.dan.steuerhaus.gezeiten.Vorhersage(0.08, List.of(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(m2, 1.5, 0)));
        var probeW = List.of(new com.dan.steuerhaus.gezeiten.Landunter.Warft("Hochwarft", 5.9, 1.6, 30), new com.dan.steuerhaus.gezeiten.Landunter.Warft("Niedrigwarft", 3.4, 1.9, 2));
        var lu = new com.dan.steuerhaus.gezeiten.Landunter(rein, 2.01, 2.50, probeW);
        Instant a0 = Instant.parse("2027-01-04T00:00:00Z"), a1 = a0.plusSeconds(3 * 86400);
        List<String> f2 = new ArrayList<>();
        var ohne = lu.hochwasser(a0, a1, 0);
        double dmax = 0;
        for (var hw : ohne) {
            double fa = 1.5 * m2.f(hw.zeit());
            if (hw.ueberlauf() || hw.landunter()) f2.add("ohne Wind über der Krone " + hw.zeit());
            dmax = Math.max(dmax, Math.abs(hw.gezeit() - (0.08 + fa)));
            if (Math.abs(hw.noetigKrone() - (2.01 - hw.gezeit())) > 1e-9 || Math.abs(hw.noetigNass() - (2.50 - hw.gezeit())) > 1e-9 || Math.abs(hw.fehltKrone() - hw.noetigKrone()) > 1e-9)
                f2.add("nötig/fehlt " + hw.zeit());
        }
        if (ohne.size() < 5 || dmax > 0.001) f2.add(ohne.size() + " Hochwasser, Höhe bis " + dmax + " m daneben");
        // mit 0,9 m: über der Krone, kein Landunter; mit 1,2 m: beides – Dauer gegen die geschlossene Lösung
        double dz = 0;
        for (double s : new double[] {0.9, 1.2}) {
            for (var hw : lu.hochwasser(a0, a1, s)) {
                double fa = 1.5 * m2.f(hw.zeit());
                boolean nassSoll = 0.08 + fa + s > 2.50;
                if (!hw.ueberlauf() || hw.landunter() != nassSoll) {
                    f2.add(String.format(Locale.ROOT, "%.1f m: Überlauf %b, Landunter %b bei %s", s, hw.ueberlauf(), hw.landunter(), hw.zeit()));
                    continue;
                }
                for (double hoehe : nassSoll ? new double[] {2.01, 2.50} : new double[] {2.01}) {
                    Instant ab = hoehe == 2.01 ? hw.ueberAb() : hw.nassAb(), bis = hoehe == 2.01 ? hw.ueberBis() : hw.nassBis();
                    double halb = Math.acos((hoehe - 0.08 - s) / fa) / Math.toRadians(m2.gradJeStunde()) * 3600;
                    dz = Math.max(dz, Math.abs(bis.getEpochSecond() - ab.getEpochSecond() - 2 * halb));
                    if (!(lu.wert(ab, s) > hoehe) || lu.wert(ab.minusSeconds(2), s) > hoehe || !(lu.wert(bis, s) > hoehe) || lu.wert(bis.plusSeconds(2), s) > hoehe)
                        f2.add("Grenzen " + hoehe + " " + hw.zeit());
                }
            }
        }
        if (dz > 30) f2.add(String.format(Locale.ROOT, "Dauer über der Höhe bis %.0f s daneben", dz));
        var lg = lu.lagen(2.7);
        if (!lg.get(0).warft().name().equals("Niedrigwarft") || Math.abs(lg.get(0).amFuss() - 0.8) > 1e-9 || Math.abs(lg.get(1).kuppeUeber() - 3.2) > 1e-9) f2.add("Badewanne bei 2,7 m");
        for (var l : lu.lagen(2.3)) if (l.amFuss() != 0) f2.add("über der Krone, unter Landunter: Wasser am Fuß " + l.warft().name());
        var hw0 = ohne.get(1);
        double w0 = lu.winkel(hw0.zeit().plusSeconds(1)), w1 = lu.winkel(hw0.zeit().plusSeconds(Math.round(12.4206 * 1800))), w2 = lu.winkel(hw0.zeit().minusSeconds(60));
        if (w0 > 0.1 || Math.abs(w1 - 180) > 1 || w2 < 359) f2.add(String.format(Locale.ROOT, "Uhr %.2f / %.2f / %.2f Grad", w0, w1, w2));
        double[] vl = {1, 3, 6}, rm = {0.2, 0.3, 0.7};
        double b0 = com.dan.steuerhaus.gezeiten.Landunter.fehler(vl, rm, 0.5), b2 = com.dan.steuerhaus.gezeiten.Landunter.fehler(vl, rm, 2), b9 = com.dan.steuerhaus.gezeiten.Landunter.fehler(vl, rm, 9);
        if (Math.abs(b0 - 0.2) > 1e-12 || Math.abs(b2 - 0.25) > 1e-12 || Math.abs(b9 - 0.7) > 1e-12) f2.add("Band " + b0 + " " + b2 + " " + b9);
        pruefe(f2.isEmpty(), "Z2 Prüfstein 2: Rechnung an reiner M2 – Hochwasser (f·A + Z₀), nötiger Windstau, Zeiten über Krone und Landunter gegen acos-Lösung, Badewanne, Uhr, Band",
                f2.isEmpty() ? String.format(Locale.GERMAN, "%d Hochwasser; Höhe ± %.1e m; mit +0,90 m über der Krone, mit +1,20 m Landunter, Dauer ± %.0f s", ohne.size(), dmax, dz) : f2.toString(),
                "± 1 mm; ± 30 s; 0 Fehler");

        // Prüfstein 3: PEGELONLINE ohne Netz
        List<String> f3 = new ArrayList<>();
        String ad = com.dan.steuerhaus.gezeiten.Pegelonline.adresse("9550021", "P1D");
        if (!ad.equals("https://www.pegelonline.wsv.de/webservices/rest-api/v2/stations/9550021/W/measurements.json?start=P1D")) f3.add("Adresse " + ad);
        String json = "[\n  {\n    \"timestamp\": \"2026-11-22T01:17:00+01:00\",\n    \"value\": 549.0\n  },{\"timestamp\":\"2026-11-22T01:18:00+01:00\",\"value\":547}]";
        var dt = com.dan.steuerhaus.gezeiten.Pegelonline.deute(json, -5.027);
        if (dt.size() != 2 || !dt.get(0).zeit().equals(Instant.parse("2026-11-22T00:17:00Z")) || Math.abs(dt.get(0).hoehe() - 0.463) > 1e-9 || Math.abs(dt.get(1).hoehe() - 0.443) > 1e-9)
            f3.add("deuten: " + dt);
        List<com.dan.steuerhaus.gezeiten.Pegelonline.Messwert> mw = new ArrayList<>();
        Instant e0 = Instant.parse("2027-01-05T12:00:00Z");
        for (int i = 0; i < 24 * 60; i++) {
            Instant ti = e0.plusSeconds(60L * i);
            mw.add(new com.dan.steuerhaus.gezeiten.Pegelonline.Messwert(ti, rein.wert(ti) + (i >= 23 * 60 ? 0.37 : 0.9)));
        }
        var st = com.dan.steuerhaus.gezeiten.Pegelonline.stau(mw, rein);
        if (st == null || Math.abs(st.meter() - 0.37) > 0.02 || st.werte() != 1440) f3.add("Windstau " + st);
        if (com.dan.steuerhaus.gezeiten.Pegelonline.stau(List.of(), rein) != null) f3.add("ohne Werte");
        pruefe(f3.isEmpty(), "Z2 Prüfstein 3: PEGELONLINE ohne Netz – Adresse, Antwort deuten (cm über Pegelnull → m NHN, Zeitzone), Windstau = Messung − Gezeit der letzten Stunde",
                f3.isEmpty() ? String.format(Locale.GERMAN, "549 cm → %+.3f m NHN; Windstau %+.3f m aus %d Werten", dt.get(0).hoehe(), st.meter(), st.werte()) : f3.toString(), "0 Fehler");

        // Prüfstein 4: die Ansicht ohne Fenster
        File d = java.nio.file.Files.createTempDirectory("landunter").toFile();
        File tf = new File(d, "probe.gezeiten");
        var kon = List.of(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(m2, 1.5, 0), new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(com.dan.core.astro.Partialtide.S2, 0.45, 40),
                new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(com.dan.core.astro.Partialtide.M4, 0.15, 210));
        var kopf = new java.util.LinkedHashMap<String, String>();
        kopf.put("bezug", "Probe");
        kopf.put("bezug.pegel", "PELLWORM_ANLEGER; 9550021; -5.027");
        kopf.put("bezug.z0", "0.07");
        kopf.put("bezug2.pegel", "WITTDÜN; 9999999; -5.000; Wittdün (Amrum)");
        kopf.put("bezug2.z0", "0.05");
        kopf.put("wert.stau.vorlauf", "1 2 3 4 6");
        kopf.put("wert.stau.fehler", "0.20 0.23 0.29 0.42 0.69");
        var bt = new java.util.LinkedHashMap<String, List<com.dan.steuerhaus.gezeiten.Vorhersage.Konstante>>();
        bt.put("bezug", kon);
        bt.put("bezug2", kon.subList(0, 2));
        com.dan.steuerhaus.gezeiten.Gezeitentafel.schreibe(tf, "Probeort", 471381, 6047983, 0.08, kon, java.util.Map.of(), List.of(), kopf, bt);
        var tafel = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(tf);
        List<String> f4 = new ArrayList<>();
        var vb = tafel.bezugVorhersage();
        if (vb == null || tafel.bezugTiden.size() != 3 || !tafel.bezugNummer.equals("9550021") || Math.abs(tafel.bezugPegelnull + 5.027) > 1e-9) f4.add("Bezug in der Tafel");
        // seit Z2b: zwei Bezüge
        if (tafel.bezuege.size() != 2 || tafel.bezuege.get(1).tiden().size() != 2 || !tafel.bezuege.get(1).kurz().equals("Wittdün") || !tafel.bezuege.get(0).kurz().equals("Pellworm")
                || Math.abs(tafel.bezuege.get(1).z0() - 0.05) > 1e-9) f4.add("zwei Bezüge in der Tafel: " + tafel.bezuege);
        var vw = tafel.bezuege.size() > 1 ? tafel.bezuege.get(1).vorhersage() : vb;
        Instant jetzt = Instant.parse("2027-01-06T09:40:00Z");
        List<Object[]> wahl = List.of(new Object[] {"Sturmflut im Modell", 1.2}, new Object[] {"Sturmflut, BSH-Klasse", 1.5});
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[3];
            File[] ges = new File[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    var u = new com.dan.steuerhaus.gezeiten.Landunteruhr(tafel, 2.01, "Krone des Sommerdeichs", 2.50, "Fennen nass", probeW, wahl, "Probe: Gezeit plus Windstau. Nicht zur Navigation.",
                            ZoneId.of("Europe/Berlin"), d);
                    u.jetzt = () -> jetzt;
                    java.util.concurrent.Callable<List<com.dan.steuerhaus.gezeiten.Pegelonline.Messwert>> mp = () -> probeMessung(jetzt, vb, 0.55), mwt = () -> probeMessung(jetzt, vw, 0.35),
                            aus = () -> {
                                throw new java.io.IOException("kein Netz (Probe)");
                            };
                    u.messung = List.of(mp, mwt);
                    u.ladenJetzt();
                    if (u.quellen().size() != 5 || !u.quellen().get(0).equals("Gemessen (Pellworm, Wittdün)")) f4.add(th.name + ": Quellen " + u.quellen());
                    if (u.gemessen() == null || Math.abs(u.stau() - 0.45) > 0.06 || u.gemessenJe().size() != 2 || u.messFehler() != null) f4.add(th.name + ": gemessen " + u.stau());
                    if (Math.abs(u.band(2) - 0.23) > 1e-9) f4.add(th.name + ": Band " + u.band(2));
                    u.quelle(2);
                    if (Math.abs(u.stau() - 1.2) > 1e-9) f4.add(th.name + ": Wahl " + u.stau());
                    u.eigenerStau(0.73);
                    if (Math.abs(u.stau() - 0.75) > 1e-9) f4.add(th.name + ": eigener Wert " + u.stau());
                    u.quelle(1);
                    if (u.stau() != 0) f4.add(th.name + ": ohne Wind " + u.stau());
                    u.quelle(0);
                    bi[0] = u.alsBild(1460, 990);
                    ges[0] = u.sichern();
                    bi[1] = u.uhrbild(480, 270);
                    if (th == Thema.LABOR) bi[2] = u.alsBild(1460, 780);
                    // ein Pegel nicht erreichbar: der andere allein; keiner: Windstau 0
                    u.messung = List.of(mp, aus);
                    u.ladenJetzt();
                    if (Math.abs(u.stau() - 0.55) > 0.06 || u.messFehler() == null || !u.messFehler().startsWith("Wittdün")) f4.add(th.name + ": nur Pellworm " + u.stau() + " " + u.messFehler());
                    u.messung = List.of(aus, aus);
                    u.ladenJetzt();
                    if (u.messFehler() == null || u.stau() != 0 || u.gemessen() != null) f4.add(th.name + ": kein Fehler ohne Netz");
                } catch (Exception x) {
                    f4.add(th.name + ": " + x);
                }
            });
            if (bi[0] == null || bi[1] == null) f4.add(th.name + ": Bild fehlt");
            if (ges[0] == null || !ges[0].isFile()) f4.add(th.name + ": nicht gesichert");
            if (bilder != null && bi[0] != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "Z2_Landunter_" + th.name + ".png"));
                ImageIO.write(bi[1], "png", new File(bilder, "Z2_Landunter_Kachel_" + th.name + ".png"));
                if (bi[2] != null) ImageIO.write(bi[2], "png", new File(bilder, "Z2b_Landunter_klein.png"));
            }
        }
        Thema.LABOR.anwenden();
        pruefe(f4.isEmpty(), "Z2 Prüfstein 4: Uhr ohne Fenster – zwei Bezüge in der Tafel, Messung (ersetzt) als Mittel, ein Pegel oder keiner erreichbar, Wahl, eigener Wert, ohne Wind, "
                + "Bild und Kachel", f4.isEmpty() ? "beide Themen" : f4.toString(), "0 Fehler");
        if (bilder != null) info("Bilder", "Z2_Landunter_Labor.png, Z2_Landunter_Nautik.png, je eine Kachel, Z2b_Landunter_klein.png (1460 × 780; gemalte Probe)");

        // Prüfstein 5: Menü
        Befehl x = b.finde("begleiter.landunter");
        pruefe(la == null || (x != null && "ctrl shift U".equals(x.taste)), "Z2 Prüfstein 5: Menü Begleiter › Landunter-Uhr (Strg Umschalt U)", x == null ? "fehlt" : x.name + ", " + x.taste,
                "vorhanden");
    }

    /**
     * Seit Z3: das Watt-Fenster ohne Archiv und ohne Netz – Prüfstein 1 die Schlüssel {@code watt.*}, 2 die Fenster an
     * reiner M2 gegen die geschlossene Lösung (Beginn, Ende, Dauer, mit Windstau kürzer, mit viel Windstau keins; trocken,
     * Stand der Karte, Licht), 3 die Datei des Wegs hin und zurück (mit kleinen Bildern), 4 die Ansicht ohne Fenster in
     * beiden Themen (Quellen, Messung ersetzt, eigener Wert, Woche, Bild, Kachel; Bilder {@code Z3_Watt_<Thema>.png}),
     * 5 das Menü.
     */
    static void watt(Manifest m, Szenen sz, Befehle b, File bilder) throws Exception {
        var wa = com.dan.steuerhaus.gezeiten.Wattauftrag.lies(m);
        List<String> f1 = new ArrayList<>();
        if (wa == null) f1.add("kein watt.start / watt.ziel");
        else {
            double[] k = wa.karte();
            if (k == null || k[2] <= k[0] || k[3] <= k[1]) f1.add("watt.karte");
            else {
                if (!(wa.startOst() > k[0] && wa.startOst() < k[2] && wa.startNord() > k[1] && wa.startNord() < k[3])) f1.add("Start außerhalb der Karte");
                if (!(wa.zielOst() > k[0] && wa.zielOst() < k[2] && wa.zielNord() > k[1] && wa.zielNord() < k[3])) f1.add("Ziel außerhalb der Karte");
                if ((k[2] - k[0]) / wa.zelle() * (k[3] - k[1]) / wa.zelle() > 4e6) f1.add("Karte zu groß für Zelle " + wa.zelle());
            }
            if (!(wa.zelle() >= 1 && wa.zelle() <= 20)) f1.add("Zelle " + wa.zelle());
            if (!(wa.zielUmkreis() > 0 && wa.zielUmkreis() <= 1000) || !(wa.priel() > 0 && wa.priel() <= 500)) f1.add("Umkreise");
            if (!(wa.gehen() >= 1 && wa.gehen() <= 6)) f1.add("gehen " + wa.gehen());
            if (wa.modell().isEmpty()) f1.add("keine Läufe in watt.modell");
            for (String x : wa.modell()) if (sz.szenario(x) == null) f1.add("Lauf " + x + " fehlt in den Szenen");
            if (!wa.hinweis().contains("nicht zur Navigation")) f1.add("Hinweis ohne „nicht zur Navigation“");
            if (!wa.datei().getName().endsWith(".watt")) f1.add("Datei " + wa.datei().getName());
        }
        pruefe(f1.isEmpty(), "Z3 Prüfstein 1: Schlüssel watt.* – Start und Ziel in der Karte, Zelle, Umkreise, Gehen, Läufe in den Szenen, Hinweis",
                wa == null ? "fehlt" : f1.isEmpty() ? String.format(Locale.GERMAN, "%s → %s (Umkreis %.0f m), Karte %.0f × %.0f m in %.0f m, Läufe %s, %.0f km/h", wa.start(), wa.ziel(),
                        wa.zielUmkreis(), wa.karte()[2] - wa.karte()[0], wa.karte()[3] - wa.karte()[1], wa.zelle(), wa.modell(), wa.gehen()) : f1.toString(), "0 Fehler");

        // Prüfstein 2: reine M2 mit 1,5 m, Z₀ 0,08 m; trocken ab −0,50 m, nass ab +0,25 m, Sattel +0,09 m
        var m2 = com.dan.core.astro.Partialtide.M2;
        var rein = new com.dan.steuerhaus.gezeiten.Vorhersage(0.08, List.of(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(m2, 1.5, 0)));
        var w = new com.dan.steuerhaus.gezeiten.Watt(rein, -0.50, 0.25, 0.09, 8.55, 54.57);
        Instant a0 = Instant.parse("2027-01-04T00:00:00Z"), a1 = a0.plusSeconds(7 * 86400);
        List<String> f2 = new ArrayList<>();
        double om = Math.toRadians(m2.gradJeStunde()) / 3600;
        double dmax = 0;
        int[] anzahl = new int[3];
        double[] dauerMittel = new double[3];
        double[] staus = {0, 0.3, 1.0};
        for (int si = 0; si < staus.length; si++) {
            double st = staus[si];
            var fl = w.fenster(a0, a1, st);
            anzahl[si] = fl.size();
            Instant vorher = null;
            for (var f : fl) {
                double fa = 1.5 * m2.f(f.niedrig());
                double ta = Math.acos((-0.50 - 0.08 - st) / fa), tb = 2 * Math.PI - Math.acos((0.25 - 0.08 - st) / fa);
                double vor = (Math.PI - ta) / om, nach = (tb - Math.PI) / om;
                double d1 = Math.abs(f.niedrig().getEpochSecond() - f.ab().getEpochSecond() - vor), d2 = Math.abs(f.bis().getEpochSecond() - f.niedrig().getEpochSecond() - nach);
                dmax = Math.max(dmax, Math.max(d1, d2));
                dauerMittel[si] += f.dauer().getSeconds() / 3600.0 / fl.size();
                if (Math.abs(f.niedrigHoehe() - (0.08 - fa + st)) > 0.001) f2.add("Niedrigwasser " + f.niedrig() + ": " + f.niedrigHoehe());
                if (vorher != null && !f.ab().isAfter(vorher)) f2.add("Fenster überlappen bei " + f.ab());
                vorher = f.bis();
                if (f.tagMinuten() < 0 || f.tagMinuten() > f.dauer().toMinutes()) f2.add("Licht " + f.tagMinuten());
                Instant mitte = f.ab().plus(f.dauer().dividedBy(2));
                if (!w.trocken(mitte, st) || w.trocken(f.ab().minusSeconds(120), st) || w.trocken(f.bis().plusSeconds(120), st)) f2.add("trocken bei " + f.ab());
                if (w.kartenStand(mitte, st) > 0.08 + 1e-9 || w.kartenStand(f.bis().plusSeconds(600), st) < 0.10 - 1e-9) f2.add("Stand der Karte bei " + f.ab());
                if (w.jetztOderNaechstes(mitte, st) == null || !w.jetztOderNaechstes(mitte, st).ab().equals(f.ab())) f2.add("jetzt oder nächstes bei " + f.ab());
            }
        }
        if (anzahl[0] < 13 || anzahl[0] > 14) f2.add("ohne Wind " + anzahl[0] + " Fenster in 7 Tagen");
        if (anzahl[1] != anzahl[0] || !(dauerMittel[1] < dauerMittel[0] - 0.3)) f2.add(String.format(Locale.ROOT, "mit +0,30 m: %d Fenster, %.2f h", anzahl[1], dauerMittel[1]));
        if (anzahl[2] != 0) f2.add("mit +1,00 m noch " + anzahl[2] + " Fenster");
        if (dmax > 2) f2.add(String.format(Locale.ROOT, "Grenzen bis %.0f s daneben", dmax));
        // Licht: Mittag in Hooge im Januar hell, Mitternacht dunkel
        long hell = w.tagMinuten(Instant.parse("2027-01-06T10:30:00Z"), Instant.parse("2027-01-06T12:30:00Z")),
                dunkel = w.tagMinuten(Instant.parse("2027-01-06T22:30:00Z"), Instant.parse("2027-01-07T00:30:00Z"));
        if (hell != 120 || dunkel != 0) f2.add("Licht mittags " + hell + ", nachts " + dunkel);
        pruefe(f2.isEmpty(), "Z3 Prüfstein 2: Fenster an reiner M2 – Beginn und Ende gegen acos-Lösung, keine Überlappung, trocken, Stand der Karte, mit Windstau kürzer, mit viel keins, Licht",
                f2.isEmpty() ? String.format(Locale.GERMAN, "%d Fenster in 7 Tagen, im Mittel %.2f h; mit +0,30 m %.2f h; mit +1,00 m keins; Grenzen ± %.0f s", anzahl[0], dauerMittel[0],
                        dauerMittel[1], dmax) : f2.toString(), "0 Fehler");

        // Prüfstein 3: die Datei hin und zurück – eine kleine Probewelt (3 × 2 km, Zelle 25 m)
        File d = java.nio.file.Files.createTempDirectory("watt").toFile();
        File wf = new File(d, "probe.watt");
        double[] karte = {0, 0, 3000, 2000};
        double z = 25;
        int nx = 120, ny = 80;
        float[] h = new float[nx * ny];
        for (int j = 0; j < ny; j++)
            for (int i = 0; i < nx; i++) {
                double x = (i + 0.5) * z, y = (j + 0.5) * z;
                h[i + nx * j] = (float) probeHoehe(x, y);
            }
        for (int j = 0; j < 3; j++) for (int i = 0; i < 3; i++) h[i + nx * j] = Float.NaN;
        BufferedImage land = new BufferedImage(2 * nx, 2 * ny, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 2 * ny; y++)
            for (int x = 0; x < 2 * nx; x++) {
                double hh = probeHoehe(x * z / 2, (2 * ny - 1 - y) * z / 2);
                land.setRGB(x, y, hh > 1 ? 0x6E8A4E : hh > -0.5 ? 0xC8B88A : 0x8A8670);
            }
        List<com.dan.steuerhaus.gezeiten.Watttafel.Punkt> weg = new ArrayList<>();
        for (double s0 = 0; s0 <= 2100 + 1e-9; s0 += 25) weg.add(new com.dan.steuerhaus.gezeiten.Watttafel.Punkt(s0, 2500 - s0, 1000, probeHoehe(2500 - s0, 1000)));
        List<com.dan.steuerhaus.gezeiten.Watttafel.Ereignis> er = List.of(
                new com.dan.steuerhaus.gezeiten.Watttafel.Ereignis("probe", true, Instant.parse("2027-01-04T03:10:00Z"), -0.512, -0.60),
                new com.dan.steuerhaus.gezeiten.Watttafel.Ereignis("probe", false, Instant.parse("2027-01-04T08:20:00Z"), 0.241, 0.10));
        var kopfW = new java.util.LinkedHashMap<String, String>();
        kopfW.put("wert.watt.weg", "2100.0 0.090 2.000 1.200");
        kopfW.put("modell.probe", "Probe");
        com.dan.steuerhaus.gezeiten.Watttafel.schreibe(wf, "Hallig", 2500, 1000, "Sand", 400, 1000, 1.2, 1500, 1000, 0.09, 8.55, 54.57, -0.50, 0.25, karte, z, nx, ny, weg, er, kopfW, h,
                land);
        var tw = com.dan.steuerhaus.gezeiten.Watttafel.lies(wf);
        List<String> f3 = new ArrayList<>();
        if (!tw.start.equals("Hallig") || !tw.ziel.equals("Sand") || tw.startOst != 2500 || tw.zielNord != 1000 || Math.abs(tw.zielHoehe - 1.2) > 1e-9) f3.add("Start/Ziel");
        if (tw.sattelOst != 1500 || Math.abs(tw.sattel - 0.09) > 1e-9 || Math.abs(tw.schwelleAb + 0.5) > 1e-9 || Math.abs(tw.schwelleBis - 0.25) > 1e-9) f3.add("Sattel/Schwellen");
        if (Math.abs(tw.laenge - 8.55) > 1e-5 || Math.abs(tw.breite - 54.57) > 1e-5) f3.add("Lage");
        if (tw.nx != nx || tw.ny != ny || tw.zelle != z || !java.util.Arrays.equals(tw.karte, karte)) f3.add("Karte");
        if (tw.weg.size() != weg.size() || Math.abs(tw.wegLaenge() - 2100) > 1e-6 || Math.abs(tw.weg.get(40).hoehe() - weg.get(40).hoehe()) > 0.001) f3.add("Weg");
        if (tw.ereignisse.size() != 2 || !tw.ereignisse.get(0).ab() || tw.ereignisse.get(1).ab() || !tw.ereignisse.get(1).zeit().equals(er.get(1).zeit())
                || Math.abs(tw.ereignisse.get(0).anleger() + 0.512) > 1e-9) f3.add("Ereignisse");
        if (!"2100.0 0.090 2.000 1.200".equals(tw.kopf.get("wert.watt.weg"))) f3.add("Kopf");
        float[] hl = tw.hoehen();
        double hmax = 0;
        int nan = 0;
        for (int c = 0; c < h.length; c++) {
            if (Float.isNaN(h[c]) != Float.isNaN(hl[c])) f3.add("NaN bei " + c);
            if (Float.isNaN(hl[c])) nan++;
            else hmax = Math.max(hmax, Math.abs(h[c] - hl[c]));
        }
        if (hmax > 0.0006 || nan != 9) f3.add(String.format(Locale.ROOT, "Höhen bis %.4f m daneben, %d leer", hmax, nan));
        // Süden unten: die Zelle (5, 70) liegt im Norden
        if (Math.abs(hl[5 + nx * 70] - probeHoehe(5.5 * z, 70.5 * z)) > 0.001) f3.add("Höhen gespiegelt");
        BufferedImage ll = tw.land();
        if (ll.getWidth() != 2 * nx || ll.getHeight() != 2 * ny) f3.add("Land " + ll.getWidth() + " × " + ll.getHeight());
        pruefe(f3.isEmpty(), "Z3 Prüfstein 3: Datei des Wegs hin und zurück – Start, Ziel, Sattel, Schwellen, Weg, Ereignisse, Kopf; Höhen (16 Bit, mm, Süden unten, leer) und Land",
                f3.isEmpty() ? String.format(Locale.GERMAN, "%d Punkte, %d Ereignisse, Höhen %d × %d ± %.1f mm, %d leer", tw.weg.size(), tw.ereignisse.size(), nx, ny, hmax * 1000, nan)
                        : f3.toString(), "0 Fehler");

        // Prüfstein 4: die Ansicht ohne Fenster, mit der Tafel aus Prüfstein Z2 4 (zwei Bezüge)
        File tf = new File(d, "probe.gezeiten");
        var kon = List.of(new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(m2, 1.5, 0), new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(com.dan.core.astro.Partialtide.S2, 0.45, 40),
                new com.dan.steuerhaus.gezeiten.Vorhersage.Konstante(com.dan.core.astro.Partialtide.M4, 0.15, 210));
        var kopf = new java.util.LinkedHashMap<String, String>();
        kopf.put("bezug", "Probe");
        kopf.put("bezug.pegel", "PELLWORM_ANLEGER; 9550021; -5.027");
        kopf.put("bezug.z0", "0.07");
        kopf.put("bezug2.pegel", "WITTDÜN; 9999999; -5.000; Wittdün (Amrum)");
        kopf.put("bezug2.z0", "0.05");
        var bt = new java.util.LinkedHashMap<String, List<com.dan.steuerhaus.gezeiten.Vorhersage.Konstante>>();
        bt.put("bezug", kon);
        bt.put("bezug2", kon.subList(0, 2));
        com.dan.steuerhaus.gezeiten.Gezeitentafel.schreibe(tf, "Probeort", 471381, 6047983, 0.08, kon, java.util.Map.of(), List.of(), kopf, bt);
        var gt = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(tf);
        var vb = gt.bezuege.get(0).vorhersage();
        var vw = gt.bezuege.get(1).vorhersage();
        Instant jetzt = Instant.parse("2027-01-06T09:40:00Z");
        List<String> f4 = new ArrayList<>();
        int[] woche = new int[1];
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[3];
            File[] ges = new File[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    var v = new com.dan.steuerhaus.gezeiten.Wattansicht(tw, gt.vorhersage(), gt, 3, "Probe: Modell, nicht zur Navigation.", ZoneId.of("Europe/Berlin"), d);
                    v.jetzt = () -> jetzt;
                    v.zeige(jetzt);
                    if (!v.quellen().equals(List.of("Ohne Wind (nur Gezeit)", "Gemessen (Pellworm, Wittdün)", "Eigener Wert"))) f4.add(th.name + ": Quellen " + v.quellen());
                    if (v.gehMinuten() != 42) f4.add(th.name + ": zu Fuß " + v.gehMinuten() + " min");
                    java.util.concurrent.Callable<List<com.dan.steuerhaus.gezeiten.Pegelonline.Messwert>> mp = () -> probeMessung(jetzt, vb, 0.55), mwt = () -> probeMessung(jetzt, vw, 0.35),
                            aus = () -> {
                                throw new java.io.IOException("kein Netz (Probe)");
                            };
                    v.messung.messung = List.of(mp, mwt);
                    v.ladenJetzt();
                    v.quelle(1);
                    if (v.messung.gemessen() == null || Math.abs(v.stau() - 0.45) > 0.06 || v.messung.fehler() != null) f4.add(th.name + ": gemessen " + v.stau());
                    var fg = v.fensterWoche();
                    v.eigenerStau(0.73);
                    if (Math.abs(v.stau() - 0.75) > 1e-9) f4.add(th.name + ": eigener Wert " + v.stau());
                    v.quelle(0);
                    if (v.stau() != 0) f4.add(th.name + ": ohne Wind " + v.stau());
                    var fo = v.fensterWoche();
                    woche[0] = fo.size();
                    if (fo.size() < 12 || fo.size() > 15) f4.add(th.name + ": " + fo.size() + " Fenster in der Woche");
                    double so = 0, sg = 0;
                    for (var f : fo) so += f.dauer().toMinutes();
                    for (var f : fg) sg += f.dauer().toMinutes();
                    if (!(sg / Math.max(1, fg.size()) < so / Math.max(1, fo.size()))) f4.add(th.name + ": mit Windstau nicht kürzer");
                    bi[0] = v.alsBild(1460, 990);
                    ges[0] = v.sichern();
                    bi[1] = v.tagesbild(480, 270);
                    if (th == Thema.LABOR) bi[2] = v.alsBild(1460, 780);
                    // kein Pegel erreichbar: Windstau 0 und ein Fehler
                    v.messung.messung = List.of(aus, aus);
                    v.ladenJetzt();
                    v.quelle(1);
                    if (v.messung.fehler() == null || v.stau() != 0 || v.messung.gemessen() != null) f4.add(th.name + ": kein Fehler ohne Netz");
                    // nur einer: der allein
                    v.messung.messung = List.of(mp, aus);
                    v.ladenJetzt();
                    if (Math.abs(v.stau() - 0.55) > 0.06 || v.messung.fehler() == null) f4.add(th.name + ": nur Pellworm " + v.stau());
                } catch (Exception x) {
                    f4.add(th.name + ": " + x);
                }
            });
            if (bi[0] == null || bi[1] == null) f4.add(th.name + ": Bild fehlt");
            if (ges[0] == null || !ges[0].isFile() || !ges[0].getName().startsWith("Watt_Fenster_")) f4.add(th.name + ": nicht gesichert");
            if (bilder != null && bi[0] != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "Z3_Watt_" + th.name + ".png"));
                ImageIO.write(bi[1], "png", new File(bilder, "Z3_Watt_Kachel_" + th.name + ".png"));
                if (bi[2] != null) ImageIO.write(bi[2], "png", new File(bilder, "Z3_Watt_klein.png"));
            }
        }
        Thema.LABOR.anwenden();
        pruefe(f4.isEmpty(), "Z3 Prüfstein 4: Ansicht ohne Fenster – Quellen, Messung (ersetzt) als Mittel, einer oder keiner erreichbar, eigener Wert, Woche, mit Windstau kürzer, Bild, Kachel",
                f4.isEmpty() ? "beide Themen, " + woche[0] + " Fenster in der Woche" : f4.toString(), "0 Fehler");
        if (bilder != null) info("Bilder", "Z3_Watt_Labor.png, Z3_Watt_Nautik.png, je eine Kachel, Z3_Watt_klein.png (1460 × 780; gemalte Probewelt)");

        // Prüfstein 5: Menü
        Befehl x = b.finde("begleiter.watt");
        pruefe(wa == null || (x != null && "ctrl shift W".equals(x.taste)), "Z3 Prüfstein 5: Menü Begleiter › Watt-Fenster (Strg Umschalt W)", x == null ? "fehlt" : x.name + ", " + x.taste,
                "vorhanden");
        if (wa != null && wa.datei().isFile()) {
            var t = com.dan.steuerhaus.gezeiten.Watttafel.lies(wa.datei());
            info("Weg", String.format(Locale.GERMAN, "%s – %s, %.0f m, Sattel %+.2f m, trocken ab %+.2f m, nass ab %+.2f m (gerechnet %s)", t.start, t.ziel, t.wegLaenge(), t.sattel,
                    t.schwelleAb, t.schwelleBis, t.kopf.getOrDefault("gerechnet", "?")));
        } else if (wa != null) info("Weg", wa.datei().getName() + " noch nicht gerechnet (Ausgabe --watt)");
    }

    /** Die Probewelt: Hallig im Osten (+2 m), Sand im Westen (+1,2 m), dazwischen Watt mit dem Sattel +0,09 m bei x 1500 und einem Priel quer. */
    static double probeHoehe(double x, double y) {
        if (x >= 2400) return 2.0;
        if (x <= 500) return 1.2;
        double weg = 0.09 + 0.7 * Math.abs(x - 1500) / 900;
        double quer = Math.abs(y - 1000);
        return quer < 150 ? weg : Math.min(weg, -1.2 + quer / 1000 + 0.3 * Math.abs(x - 1500) / 900);
    }

    /** 26 h Probe-Messung: Gezeit plus Windstau mit einer kleinen Welle. */
    static List<com.dan.steuerhaus.gezeiten.Pegelonline.Messwert> probeMessung(Instant jetzt, com.dan.steuerhaus.gezeiten.Vorhersage v, double stau) {
        List<com.dan.steuerhaus.gezeiten.Pegelonline.Messwert> l = new ArrayList<>();
        for (int i = 0; i < 26 * 60; i++) {
            Instant ti = jetzt.minusSeconds(60L * (26 * 60 - i));
            l.add(new com.dan.steuerhaus.gezeiten.Pegelonline.Messwert(ti, v.wert(ti) + stau + 0.05 * Math.sin(i / 50.0)));
        }
        return l;
    }

    /**
     * Seit Y3: das Jahr ohne Archiv – Prüfstein 1 die Schlüssel {@code jahr.*} (Szenarien mit Archiv, Ansicht, Kasten,
     * Profil, Spielzeit), Prüfstein 2 eine kleine Mappe hin und zurück (Gitter, Abschnitte, Bilder auf 0,5 mm, ohne Boden,
     * Kennung), Prüfstein 3 der Zeitplan (eine Minute: 24, 24, 12 s), Prüfstein 4 die Ansicht ohne Fenster (Zwischenbild,
     * Profil, Farben, Linie der Schwelle um eine runde Insel, Schritte, Bild sichern; Bilder {@code Y3_Jahr_<Thema>.png},
     * {@code Y3_Jahr_Hoehe.png}), Prüfstein 5 Menü und Taste.
     */
    static void jahr(Manifest m, Szenen sz, Befehle b, File bilder) throws Exception {
        var ja = com.dan.steuerhaus.jahr.Jahresauftrag.lies(m);
        List<String> f1 = new ArrayList<>();
        if (ja == null) f1.add("keine Schlüssel jahr.N");
        else {
            for (String[] a : ja.abschnitte()) {
                var s = sz.szenario(a[0]);
                if (s == null || s.archiv == null) f1.add("Szenario " + a[0] + (s == null ? " fehlt" : " ohne Archiv"));
            }
            if (!ja.ansicht().isEmpty() && sz.ansicht(ja.ansicht()) == null) f1.add("Ansicht " + ja.ansicht());
            if (ja.kasten() == null || ja.kasten().length != 4 || ja.kasten()[2] <= ja.kasten()[0] || ja.kasten()[3] <= ja.kasten()[1]) f1.add("Kasten");
            if (ja.profil() == null || ja.profil().length != 4) f1.add("Profil");
            if (ja.dauer() == null || ja.dauer().length != ja.abschnitte().size()) f1.add("Dauer passt nicht zu den Abschnitten");
            if (ja.hinweis().length() < 40) f1.add("Hinweis fehlt oder ist kurz");
        }
        pruefe(f1.isEmpty(), "Y3 Prüfstein 1: Schlüssel jahr.* – Szenarien mit Archiv, Ansicht, Kasten, Profil, Dauer je Abschnitt, Hinweis",
                ja == null ? "fehlt" : f1.isEmpty() ? ja.abschnitte().size() + " Abschnitte (" + String.join(", ", ja.abschnitte().stream().map(x -> x[0]).toList()) + "), Ordner " + ja.ordner()
                        : f1.toString(), "0 Fehler");

        // Prüfstein 2: eine kleine Mappe – runde Insel (Kuppe +2 m, Schwelle +1 m in 300 m), der Boden kippt nach Osten
        File d = java.nio.file.Files.createTempDirectory("jahr").toFile();
        int nx = 120, ny = 80;
        double dx = 20, cx = 1200, cy = 800;
        float[] grund = new float[nx * ny];
        for (int j = 0; j < ny; j++) for (int i = 0; i < nx; i++) grund[i + nx * j] = (float) (2 - Math.hypot((i + 0.5) * dx - cx, (j + 0.5) * dx - cy) / 300.0);
        double[][] abt = {{0, 180, 10}, {180, 360, 10}, {360, 365, 6}};
        int zahl = 1;
        for (double[] a : abt) zahl += (int) a[2];
        List<float[]> felder = new ArrayList<>();
        var sw = new com.dan.steuerhaus.jahr.Jahresmappe.Schreiber(d, nx, ny, zahl);
        sw.grund(grund);
        Instant t0 = Instant.parse("2026-10-14T00:00:00Z");
        int nr = 0;
        java.util.Map<String, String> kopf = new java.util.LinkedHashMap<>();
        for (int k = 0; k < abt.length; k++) {
            int erstes = nr;
            for (int q = (k == 0 ? 0 : 1); q <= abt[k][2]; q++) {
                double tag = abt[k][0] + q * (abt[k][1] - abt[k][0]) / abt[k][2];
                float[] dz = new float[nx * ny];
                for (int c = 0; c < dz.length; c++) dz[c] = (float) (tag / 365 * 0.6 * Math.sin(((c % nx) + 0.5) * dx / 300.0));
                dz[5] = Float.NaN;
                double[] w = com.dan.steuerhaus.ausgabe.Jahresrechnung.kennzahlen(dz, grund, nx, ny, 0, 0, dx, dx, new double[] {600, 200, 1800, 1400}, 1.0);
                sw.bild(new com.dan.steuerhaus.jahr.Jahresmappe.Bild(nr++, k, tag, t0.plusSeconds((long) (tag * 1440)), w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8]), dz);
                felder.add(dz);
            }
            kopf.put("abschnitt." + (k + 1), String.join("; ", "morpho" + (k + 1), new String[] {"Erstes Halbjahr", "Zweites Halbjahr", "Sturm"}[k], k == 2 ? "4" : "60",
                    t0.toString(), t0.plusSeconds(259_200).toString(), String.valueOf(abt[k][0]), String.valueOf(abt[k][1]), String.valueOf(erstes), String.valueOf(nr - 1)));
        }
        kopf.put("name", "Probejahr");
        kopf.put("kennung", "probe");
        kopf.put("gitter", "0 0 20 20 " + nx + " " + ny);
        kopf.put("kasten", "600 200 1800 1400");
        kopf.put("schwelle", "1.0");
        kopf.put("profil", "100 800 2300 800");
        kopf.put("ort.1", "Sand; Probesand; 1200; 800");
        sw.schliesse(kopf);
        var jm = com.dan.steuerhaus.jahr.Jahresmappe.lies(d);
        List<String> f2 = new ArrayList<>();
        double fehler = 0;
        for (int k = 0; k < felder.size(); k++) {
            short[] sh = jm.boden(k);
            for (int c = 0; c < sh.length; c++) {
                if (c == 5) {
                    if (sh[c] != com.dan.steuerhaus.jahr.Jahresmappe.LEER) f2.add("ohne Boden nicht leer");
                    continue;
                }
                fehler = Math.max(fehler, Math.abs(sh[c] * 0.001 - felder.get(k)[c]));
            }
        }
        if (jm.bilder.size() != zahl || jm.bilderImBoden() != zahl) f2.add(jm.bilder.size() + " / " + jm.bilderImBoden() + " Bilder statt " + zahl);
        if (jm.abschnitte.size() != 3 || Math.abs(jm.tage() - 365) > 1e-9) f2.add("Abschnitte " + jm.abschnitte.size() + ", Tage " + jm.tage());
        if (fehler > 0.0005 + 1e-7) f2.add("Boden bis " + fehler + " m");
        float[] g2 = jm.grund();
        for (int c = 0; c < g2.length; c++) if (g2[c] != grund[c]) {
            f2.add("Grund");
            break;
        }
        if (jm.zelle(1199, 801) != 59 + nx * 40 || jm.zelle(-1, 5) != -1) f2.add("Zelle am Ort");
        if (!jm.fertig || jm.orte.size() != 1) f2.add("fertig/Orte");
        String k1 = com.dan.steuerhaus.jahr.Jahresmappe.kennung("a", List.of(d)), k2 = com.dan.steuerhaus.jahr.Jahresmappe.kennung("b", List.of(d));
        if (k1.equals(k2) || !k1.equals(com.dan.steuerhaus.jahr.Jahresmappe.kennung("a", List.of(d)))) f2.add("Kennung");
        pruefe(f2.isEmpty(), "Y3 Prüfstein 2: Mappe hin und zurück – Gitter, 3 Abschnitte, 365 Tage, Bilder auf 0,5 mm, Zelle ohne Boden, Grund, Kennung",
                f2.isEmpty() ? String.format(Locale.GERMAN, "%d Bilder, größter Fehler %.4f mm, %.1f kB je Bild", zahl, fehler * 1000, sw.bytes() / 1024.0 / zahl) : f2.toString(), "0 Fehler, ≤ 0,5 mm");

        // Prüfstein 3: Zeitplan
        var zp = new com.dan.steuerhaus.jahr.Zeitplan(jm.abschnitte, new double[] {24, 24, 12}, 60);
        double[][] soll = {{0, 0}, {12, 90}, {24, 180}, {36, 270}, {48, 360}, {54, 362.5}, {60, 365}};
        List<String> f3 = new ArrayList<>();
        for (double[] x : soll) {
            if (Math.abs(zp.tag(x[0]) - x[1]) > 1e-9) f3.add("tag(" + x[0] + ") = " + zp.tag(x[0]));
            if (Math.abs(zp.sekunde(x[1]) - x[0]) > 1e-9) f3.add("sekunde(" + x[1] + ") = " + zp.sekunde(x[1]));
        }
        var zt = new com.dan.steuerhaus.jahr.Zeitplan(jm.abschnitte, null, 73);
        if (Math.abs(zt.tag(36) - 180) > 1e-9) f3.add("ohne Dauer nicht im Verhältnis der Tage");
        pruefe(f3.isEmpty(), "Y3 Prüfstein 3: Zeitplan – eine Minute mit 24, 24, 12 s (Tag 180 bei 24 s, 360 bei 48 s, 365 bei 60 s), umkehrbar; ohne Angabe nach Tagen",
                f3.isEmpty() ? "genau" : f3.toString(), "± 10⁻⁹");

        // Prüfstein 4: Ansicht ohne Fenster
        List<String> f4 = new ArrayList<>();
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[2];
            File[] ges = new File[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    var va = new com.dan.steuerhaus.jahr.Jahresansicht(jm, new double[] {24, 24, 12}, "Probe: Modell, keine Messung. Der Boden kippt nach Osten.", "Probesand", ZoneId.of("Europe/Berlin"), d);
                    // Zwischenbild = Mittel der Nachbarn (Bild 3 bei Tag 54, Bild 4 bei Tag 72)
                    va.zeige(63);
                    int c = 70 + nx * 30;
                    double mitte = (felder.get(3)[c] + felder.get(4)[c]) / 2;
                    if (Math.abs(va.dz(c) - mitte) > 0.0006) f4.add(th.name + ": Zwischenbild " + va.dz(c) + " statt " + mitte);
                    // Profil am Punkt = Grund + dz (die Linie liegt auf Zeilenmitte 800 m; Probe bei 1210 m: Mitte der Zelle 60)
                    double[][] pp = va.profilProben();
                    double[] p = null;
                    for (double[] x : pp) if (Math.abs(x[0] - 1110) < 1e-6) p = x;
                    int cz = 60 + nx * 40;
                    if (p == null || Math.abs(p[2] - (grund[cz] + va.dz(cz))) > 0.001 || Math.abs(p[1] - grund[cz]) > 1e-4) f4.add(th.name + ": Profil " + (p == null ? "–" : p[1] + "/" + p[2]));
                    // Farben: dz ≈ 0 ohne Farbe; + Skala dunkel ocker, − Skala blau
                    va.zeige(0);
                    int[] px = va.gitterBild();
                    if (((px[0] >>> 24) & 255) != 0) f4.add(th.name + ": dz = 0 nicht durchsichtig");
                    int a = com.dan.steuerhaus.jahr.Jahresbild.differenz(0.5, 0.5), bb = com.dan.steuerhaus.jahr.Jahresbild.differenz(-0.5, 0.5);
                    if (((a >> 16) & 255) < 150 || (bb & 255) < 150 || ((a >>> 24) & 255) < 230) f4.add(th.name + ": Farben " + Integer.toHexString(a) + " " + Integer.toHexString(bb));
                    // Schritte
                    va.zeige(100);
                    va.ende();
                    va.zeige(100);
                    double vorher = va.tag();
                    // ein Bild vor: auf das nächste Bild (Tag 108), zurück: auf Tag 90
                    java.lang.reflect.Method sb = com.dan.steuerhaus.jahr.Jahresansicht.class.getDeclaredMethod("schrittBild", int.class);
                    sb.setAccessible(true);
                    sb.invoke(va, 1);
                    if (Math.abs(va.tag() - 108) > 1e-6) f4.add(th.name + ": vor auf " + va.tag());
                    va.zeige(vorher);
                    sb.invoke(va, -1);
                    if (Math.abs(va.tag() - 90) > 1e-6) f4.add(th.name + ": zurück auf " + va.tag());
                    va.zeige(365);
                    va.skala(1);
                    bi[0] = va.alsBild(1600, 1000);
                    va.darstellung(com.dan.steuerhaus.jahr.Jahresansicht.Darstellung.HOEHE);
                    bi[1] = va.alsBild(1600, 1000);
                    ges[0] = va.sichern();
                } catch (Exception x) {
                    f4.add(th.name + ": " + x);
                }
            });
            for (BufferedImage x : bi) if (x == null || x.getWidth() != 1600) f4.add(th.name + ": Bild fehlt");
            if (ges[0] == null || !ges[0].isFile()) f4.add(th.name + ": nicht gesichert");
            if (bilder != null && bi[0] != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "Y3_Jahr_" + th.name + ".png"));
                if (th == Thema.NAUTIK && bi[1] != null) ImageIO.write(bi[1], "png", new File(bilder, "Y3_Jahr_Hoehe.png"));
            }
        }
        Thema.LABOR.anwenden();
        // die Linie der Schwelle um die Insel: ein Kreis von 300 m (Umfang 1 885 m)
        List<double[]> li = com.dan.steuerhaus.jahr.Jahresbild.linie(jm, grund, null, 1.0, new int[] {0, 0, nx, ny});
        double laenge = 0, rmin = 1e9, rmax = 0;
        for (double[] x : li) {
            laenge += Math.hypot(x[2] - x[0], x[3] - x[1]);
            double rr = Math.hypot(x[0] - cx, x[1] - cy);
            rmin = Math.min(rmin, rr);
            rmax = Math.max(rmax, rr);
        }
        if (Math.abs(laenge / (2 * Math.PI * 300) - 1) > 0.03 || rmin < 290 || rmax > 310) f4.add(String.format(Locale.ROOT, "Linie: %.0f m, r %.1f–%.1f", laenge, rmin, rmax));
        pruefe(f4.isEmpty(), "Y3 Prüfstein 4: Ansicht ohne Fenster – Zwischenbild = Mittel, Profil = Grund + Änderung, Farben, Linie der Schwelle (Kreis 300 m), Schritte, Bild sichern",
                f4.isEmpty() ? String.format(Locale.GERMAN, "beide Themen; Linie %.0f m gegen %.0f m", laenge, 2 * Math.PI * 300) : f4.toString(), "0 Fehler; Linie ± 3 %");
        if (bilder != null) info("Bilder", "Y3_Jahr_Labor.png, Y3_Jahr_Nautik.png, Y3_Jahr_Hoehe.png (gemalte Probe)");

        // Prüfstein 5: Menü
        Befehl x = b.finde("labor.jahr");
        pruefe(ja == null || (x != null && "ctrl shift J".equals(x.taste)), "Y3 Prüfstein 5: Menü Labor › Ein Jahr in einer Minute (Strg Umschalt J)", x == null ? "fehlt" : x.name + ", " + x.taste, "vorhanden");
    }

    /**
     * Seit Y2: die Regler ohne Lauf – Prüfstein 1 die Werte (Aufruf, Grenzen, Text), Prüfstein 2 das Pult (Stellen meldet
     * genau, Zurück auf ERA5, Anzeige ohne und mit Live-Lauf), Prüfstein 3 Arbeitsbereich und Befehle; Bilder
     * {@code Y2_Reglerpult_<Thema>.png}.
     */
    static void regler(Befehle b, File bilder) throws Exception {
        var w = com.dan.steuerhaus.regler.Reglerwerte.lies("wind=2;drehung=30;aufschlag=1,5;meer=0.5");
        var zu = new com.dan.steuerhaus.regler.Reglerwerte(9, -400, -7, 4);
        boolean ok1 = w.windFaktor() == 2 && w.windDrehung() == 30 && w.aufschlag() == 1.5 && w.meeresspiegel() == 0.5 && Math.abs(w.amRand() - 2) < 1e-12
                && zu.windFaktor() == 3 && zu.windDrehung() == -180 && zu.aufschlag() == -1 && zu.meeresspiegel() == 1.5 && com.dan.steuerhaus.regler.Reglerwerte.NEUTRAL.neutral()
                && com.dan.steuerhaus.regler.Reglerwerte.NEUTRAL.text().equals("wie gemessen") && w.text().equals("Wind × 2,0 · Wind +30° · Aufschlag +1,50 m · Meeresspiegel +0,5 m");
        pruefe(ok1, "Y2 Prüfstein 1: Werte – Aufruf --regler, Grenzen (× 0–3, ±180°, −1 bis +3 m, 0 bis +1,5 m), Text, am Rand = Aufschlag + Meeresspiegel",
                w.text() + "; begrenzt " + zu.text(), "wie gesetzt");
        List<String> fehler = new ArrayList<>();
        for (Thema th : Thema.values()) {
            BufferedImage[] im = new BufferedImage[2];
            SwingUtilities.invokeAndWait(() -> {
                th.anwenden();
                var p = new com.dan.steuerhaus.regler.Reglerpult();
                List<com.dan.steuerhaus.regler.Reglerwerte> geaendert = new ArrayList<>(), eingriffe = new ArrayList<>();
                p.aenderung = geaendert::add;
                p.eingriff = eingriffe::add;
                p.stelle(w);
                if (!p.werte().equals(w) || geaendert.isEmpty() || !eingriffe.get(eingriffe.size() - 1).equals(w)) fehler.add(th.name + ": Stellen meldet " + eingriffe);
                p.zeige(null, false, Double.NaN, Double.NaN);
                im[0] = bild(p, 360, 600);
                p.zeige(new double[] {1.6, 18, 0.9}, true, 9.5, 4.2);
                im[1] = bild(p, 360, 600);
                p.stelle(com.dan.steuerhaus.regler.Reglerwerte.NEUTRAL);
                if (!p.werte().neutral()) fehler.add(th.name + ": zurück nicht neutral");
            });
            for (BufferedImage x : im) if (x == null) fehler.add(th.name + ": Bild fehlt");
            if (bilder != null && im[1] != null) {
                bilder.mkdirs();
                ImageIO.write(im[1], "png", new File(bilder, "Y2_Reglerpult_" + th.name + ".png"));
                if (th == Thema.NAUTIK) ImageIO.write(im[0], "png", new File(bilder, "Y2_Reglerpult_ohne_Live.png"));
            }
        }
        Thema.LABOR.anwenden();
        pruefe(fehler.isEmpty(), "Y2 Prüfstein 2: Pult – Stellen meldet genau die Werte, Zurück auf ERA5, Anzeige ohne und mit Live-Lauf, beide Themen",
                fehler.isEmpty() ? "ohne Fehler" : fehler.toString(), "0 Fehler");
        var labor = Szenenapp.arbeitsbereicheFuerPruefung().stream().filter(x -> x.kennung().equals("labor")).findFirst().orElse(null);
        Befehl bl = b.finde("bereich.labor"), live = b.finde("labor.live"), z = b.finde("labor.regler.zurueck");
        boolean ok3 = labor != null && labor.abschnitteOffen().containsAll(List.of("regler", "zeit", "sonden")) && bl != null && "ctrl 5".equals(bl.taste) && live != null && z != null;
        pruefe(ok3, "Y2 Prüfstein 3: Arbeitsbereich Labor (Regler, Zeit, Sonden; Strg 5), Befehle Live-Lauf starten und Regler zurück",
                labor == null ? "kein Arbeitsbereich labor" : labor.abschnitteOffen() + ", " + (bl == null ? "–" : bl.taste), "vorhanden");
        if (bilder != null) info("Bilder", "Y2_Reglerpult_Labor.png, Y2_Reglerpult_Nautik.png, Y2_Reglerpult_ohne_Live.png");
    }

    /** Eine Komponente ohne Fenster als Bild. */
    static BufferedImage bild(JComponent c, int w, int h) {
        c.setSize(w, h);
        for (int i = 0; i < 3; i++) layout(c);
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        c.printAll(g);
        g.dispose();
        return im;
    }

    /** Ein gemaltes Probebild: Himmel, Wasserlinie, die je Bild steigt (B höher), Beschriftung. */
    static BufferedImage probebild(int w, int h, int k, int n, boolean b) {
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = im.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setPaint(new java.awt.GradientPaint(0, 0, b ? new Color(70, 80, 95) : new Color(120, 170, 215), 0, h * 0.55f, b ? new Color(150, 150, 150) : new Color(220, 230, 235)));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(96, 130, 70));
        g.fillRect(0, (int) (h * 0.55), w, h);
        for (int i = 0; i < 3; i++) {
            g.setColor(new Color(150, 60, 45));
            int x = 180 + i * 260;
            g.fillRect(x, (int) (h * 0.45), 120, (int) (h * 0.12));
            g.setColor(new Color(190, 160, 90));
            g.fillPolygon(new int[] {x - 10, x + 60, x + 130}, new int[] {(int) (h * 0.45), (int) (h * 0.33), (int) (h * 0.45)}, 3);
        }
        double steigt = Math.sin(Math.PI * k / (n - 1));
        int wl = (int) (h * (b ? 0.66 - 0.12 * steigt : 0.86 - 0.04 * steigt));
        g.setColor(new Color(50, 95, 140, 220));
        g.fillRect(0, wl, w, h - wl);
        g.setColor(Color.WHITE);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 28));
        g.drawString((b ? "B" : "A") + " · Bild " + (k + 1), 30, h - 30);
        g.dispose();
        return im;
    }

    /** Mittlere Abweichung zweier Bilder gleicher Größe (je Kanal, 1/255). */
    static double mittel(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return Double.NaN;
        double s = 0;
        for (int y = 0; y < a.getHeight(); y++)
            for (int x = 0; x < a.getWidth(); x++) {
                int p = a.getRGB(x, y), q = b.getRGB(x, y);
                s += Math.abs((p >> 16 & 255) - (q >> 16 & 255)) + Math.abs((p >> 8 & 255) - (q >> 8 & 255)) + Math.abs((p & 255) - (q & 255));
            }
        return s / (3.0 * a.getWidth() * a.getHeight());
    }

    /** Größter Abstand der Schlüsselbilder eines Rundgangs (m) [G]. */
    static final double RUNDGANG_ABSTAND = 12;

    static boolean erlaubt(String paket, String p) {
        // seit DB1: messung (der Sammler ohne Fenster) kennt java.* und java.sql, com.dan.store aus dem Renderer, die rechnenden
        // Teile des Kerns (core.pruef, core.astro, core.raum), den Kern des Steuerhauses und gezeiten (Tafel, Vorhersage,
        // Pegelonline); kein AWT, kein Swing, kein FStyle, keine Bühne
        if (paket.equals("messung")) {
            if (p.startsWith("com.dan.store") || p.startsWith("com.dan.core.pruef") || p.startsWith("com.dan.core.astro") || p.startsWith("com.dan.core.raum")) return true;
            if (p.startsWith("com.dan.steuerhaus"))
                return p.equals("com.dan.steuerhaus") || p.startsWith("com.dan.steuerhaus.gezeiten") || p.startsWith("com.dan.steuerhaus.messung");
            return false;
        }
        // seit W5: analyse (Qualitätsstufe, Protokoll) ist rein wie regie
        // seit X1: atlas hat die Rechte der buehne (lauf, Orte), ohne Renderer, Kern, Kino und Film; tafel ist wie ui
        // seit X3: rundgang hat die Rechte von atlas, dazu das Werkzeug der Aufbereitung (Osm, Wegenetz) für --wege
        boolean rundgang = paket.equals("rundgang");
        if (p.startsWith("com.dan.aufbereitung")) return rundgang || paket.equals("demo");
        // seit X5: ton hat die Rechte von atlas, dazu javax.sound.sampled (Wiedergabe)
        boolean tonP = paket.equals("ton");
        if (p.startsWith("javax.sound")) return tonP || paket.equals("demo");
        // seit Y1: vergleich (Beschreibung, Mappe, Spieler) hat die Rechte von atlas; die Ausgabe kennt vergleich
        // seit Y2: regler (Werte, Pult) ebenso
        // seit Y3: jahr (Mappe, Zeitplan, Bild, Ansicht, Fenster) ebenso; die Ausgabe rechnet die Mappe (Jahresrechnung)
        // seit Z1: gezeiten (Tafel, Vorhersage, Mond, Kalender) ebenso, dazu die Partialtiden und Ephemeriden des Kerns (core.astro);
        // seit DB5 auch die harmonische Analyse und die Pegel (core.pruef) für Gezeitenanalyse
        boolean gez = paket.equals("gezeiten");
        if (gez && (p.startsWith("com.dan.core.astro") || p.startsWith("com.dan.core.pruef"))) return true;
        // seit DB2: chronik (Ansicht und Fenster der Chronik der Hochwasser) hat die Rechte von atlas
        // seit DB3: guete (Ansicht und Fenster Modell gegen Messung) ebenso
        boolean atlas = paket.equals("atlas") || rundgang || tonP || paket.equals("vergleich") || paket.equals("regler") || paket.equals("jahr") || gez || paket.equals("chronik")
                || paket.equals("guete");
        if (atlas && (p.startsWith("com.dan.render") || p.startsWith("com.dan.kino") || p.startsWith("com.dan.film") || p.startsWith("com.dan.core")
                || p.startsWith("com.dan.steuerhaus.demo") || p.startsWith("com.dan.steuerhaus.ausgabe"))) return false;
        boolean regie = paket.equals("regie") || paket.equals("analyse");
        boolean kern = paket.isEmpty() || regie, buehne = paket.equals("buehne") || atlas, demo = paket.equals("demo"), ausgabe = paket.equals("ausgabe");
        // seit W4: die Ausgabe ist eine Bühne ohne Fenster: dieselben Rechte wie buehne, dazu den Kern des Films (F203 A),
        // nicht die Einblendungen des Renderers (die laufen über buehne.Einblendungen)
        if (p.startsWith("com.dan.film")) return (ausgabe || demo) && !p.startsWith("com.dan.film.gl");
        if (ausgabe) buehne = true;
        // seit W2: buehne darf die Einblendungen des Renderers kennen (render.overlay; Abweichung von F175 A, Werkbuch 114)
        if (p.startsWith("com.dan.render.overlay")) return (buehne && !ausgabe) || demo;
        // seit W3: buehne darf den Java2D-Kern des Kinos kennen, nicht dessen OpenGL-Teil (F194 A, Werkbuch 116)
        if (p.startsWith("com.dan.kino")) return (buehne || demo) && !p.startsWith("com.dan.kino.gl");
        // seit W3: regie ist Kern (nur java.*), kennt dazu den Kern und sich selbst
        if (regie && p.startsWith("com.dan.steuerhaus")) return p.equals("com.dan.steuerhaus") || p.startsWith("com.dan.steuerhaus." + paket);
        if (p.startsWith("org.") || p.startsWith("com.dan.hallighooge") || p.startsWith("com.dan.render") || p.startsWith("com.dan.physics")) return false;
        if (p.startsWith("com.dan.steuerhaus")) {
            if (kern) return p.equals("com.dan.steuerhaus");
            if (ausgabe) return !p.startsWith("com.dan.steuerhaus.demo") && !p.startsWith("com.dan.steuerhaus.ui");
            if (!buehne && !demo) return !p.startsWith("com.dan.steuerhaus.buehne") && !p.startsWith("com.dan.steuerhaus.demo") && !p.startsWith("com.dan.steuerhaus.ausgabe");
            return true;
        }
        if (p.startsWith("com.dan.lauf")) return (buehne || demo) && !p.startsWith("com.dan.lauf.demo");
        if (p.startsWith("com.dan.core")) return (buehne || demo) && !p.startsWith("com.dan.core.gpu");
        if (p.startsWith("java.awt") || p.startsWith("javax.")) return !kern && (p.startsWith("java.awt") || p.startsWith("javax.swing") || p.startsWith("javax.imageio"));
        if (p.startsWith("com.dan.")) return !kern;                                  // FStyle
        return true;
    }

    static void sammle(File d, List<File> l) {
        File[] fs = d.listFiles();
        if (fs == null) return;
        java.util.Arrays.sort(fs);
        for (File f : fs) {
            if (f.isDirectory()) sammle(f, l);
            else if (f.getName().endsWith(".java")) l.add(f);
        }
    }

    static String hex(Color c) { return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue()); }

    static void teil(String t) {
        System.out.println();
        System.out.println(t);
    }

    static void info(String was, String wert) { System.out.println("  info   " + was + ": " + wert); }

    static void pruefe(boolean ok, String was, String wert, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + wert + "  [" + grenze + "]");
    }

    @SuppressWarnings("unused") private static Map<String, String> leer() { return Map.of(); }
}
