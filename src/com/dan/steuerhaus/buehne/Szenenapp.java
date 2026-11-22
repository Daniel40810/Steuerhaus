package com.dan.steuerhaus.buehne;

import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Foto;
import com.dan.lauf.Schnitt;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Befehle;
import com.dan.steuerhaus.Einstellungen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Thema;
import com.dan.steuerhaus.ui.Abschnitt;
import com.dan.steuerhaus.ui.Arbeitsbereich;
import com.dan.steuerhaus.ui.Hauptfenster;
import com.dan.steuerhaus.ui.Hinweis;
import com.dan.steuerhaus.ui.Startbild;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.regie.Regiebuch;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Eine Szenen-App im Steuerhaus (Werkbuch HalligHooge 110, W1): Manifest, Szenendatei und Bühne ergeben die ganze
 * Anwendung – Startbild, Hauptfenster mit Katalog links, Bedienung rechts, Zeitleiste unten, Menü, Tasten,
 * Befehlspalette, Arbeitsbereiche, Themen, Einstellungen, Foto, Messblick und Schnitt; seit W2 die Einblendungen auf der
 * Karte ({@link Einblendungen}, Menü Ansicht › Einblendungen, F6) und die Schritte der Bühne im Startbild. Eine App braucht nur:
 * <pre>
 * public static void main(String[] args) throws Exception {
 *     Szenenapp.starten(new File("src/szenen/Hooge.steuerhaus"), args);
 * }
 * </pre>
 * Aufrufoptionen: {@code --manifest}, {@code --szenen}, {@code --szenario}, {@code --sekunden} (Probe: Einblendung alle
 * 2 s, Bilder/s, Ende), {@code --messblick}, {@code --schnitt O1 N1 O2 N2}, {@code --thema}, {@code --arbeitsbereich},
 * {@code --einstellungen <datei>}, {@code --erwarte k=v,…} (Prüfstein 6), {@code --vorschau} (Bilder des Katalogs).
 * <p>
 * Aus {@code com.dan.hallighooge.HalligHooge} (Teil B bis P13) herausgezogen; Ablauf, Foto, Messblick und Schnitt
 * sind unverändert.
 */
public final class Szenenapp {
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMAN);
    final ZoneId zone;
    final Manifest manifest;
    final Szenen szenen;
    final Einstellungen einstellungen;
    final Optionen o;
    final Befehle befehle = new Befehle();
    final Katalog katalog;
    final Bedienung bedienung = new Bedienung();
    final Zeitleiste leiste;
    final String pegel, markeName;
    final double marke;
    Hauptfenster hf;
    Startbild start;
    private volatile Buehne buehne;
    private volatile boolean zu;
    private volatile Szenario szenarioJetzt;
    private volatile Ansicht ansichtJetzt;
    private FotoFenster fotoFenster;
    private Foto fotoGezeigt;
    private int fotoRunden = -1;
    private boolean fotoEnde;
    private volatile int schnittKlicks;
    private volatile double[] schnittA;
    private volatile Schnitt schnittJetzt;
    private SchnittFenster schnittFenster;
    private volatile double[][] reihe;
    private String messAlt = "";
    private int messStandAlt = -1;
    private volatile boolean probeOk = true;
    /** Seit W2: die Einblendungen auf der Karte (null, bis die Bühne offen ist). */
    private volatile Einblendungen ein;
    private boolean taktFehler;
    // ---- W3: Regie, Fahrten, Lesezeichen
    private volatile Regisseur regisseur;
    final Map<String, Regiebuch> buecher = new LinkedHashMap<>();
    final Map<String, Fahrt> fahrten = new LinkedHashMap<>();
    final List<String> regieFehler = new ArrayList<>();
    List<Lesezeichen> lesezeichen = new ArrayList<>();
    final File lesezeichenDatei, aufnahmenDatei;
    final List<Fahrt.Schluesselbild> aufnahme = new ArrayList<>();
    final LesezeichenListe lzListe;
    /** Läuft ein Regiebuch im Kino (Oberfläche)? Dann gelten die Einblendungen des Regiebuchs. */
    private volatile boolean kinoAktiv;
    /** Seit X2 (Werkbuch HalligHooge 146): die Kapitel aus dem Manifest ({@code kapitel.N}) und ob eins läuft. */
    final Map<String, Regiebuch> kapitel = new LinkedHashMap<>();
    private volatile boolean kapitelAktiv;
    /** Seit X3 (Werkbuch HalligHooge 148): die Rundgänge ({@code rundgang.N}, gerechnet mit --wege) und das Bild ihrer Kachel. */
    final Map<String, Regiebuch> rundgaenge = new LinkedHashMap<>();
    private final Map<String, String> rundgangBild = new LinkedHashMap<>();
    private volatile Set<Einblendungen.Teil> regieEinblendung = Set.of();

    /** Die Aufrufoptionen. */
    record Optionen(String szenario, double sekunden, boolean messblick, double[] schnitt, String thema, String arbeitsbereich,
                    File einstellungen, Map<String, String> erwarte, boolean vorschau, String szenen, String regie, boolean atlas, Boolean startseite,
                    String kapitel, boolean wege, String rundgang, String vergleich, String regler, boolean jahr, boolean kalender, boolean landunter, boolean watt) {
        static Optionen lies(String[] a) {
            String sz = null, th = null, ab = null, szn = null, rg = null, kp = null, rd = null, vg = null, rl = null;
            double sek = 0;
            boolean mb = false, vs = false, at = false, wg = false, jr = false, kl = false, lu = false, wt = false;
            Boolean ss = null;
            double[] sn = null;
            File ei = null;
            Map<String, String> er = new LinkedHashMap<>();
            for (int i = 0; i < a.length; i++) {
                switch (a[i]) {
                    case "--szenario" -> sz = a[++i];
                    case "--sekunden" -> sek = Double.parseDouble(a[++i]);
                    case "--messblick" -> mb = true;
                    case "--schnitt" -> sn = new double[] {Double.parseDouble(a[++i]), Double.parseDouble(a[++i]), Double.parseDouble(a[++i]), Double.parseDouble(a[++i])};
                    case "--thema" -> th = a[++i];
                    case "--arbeitsbereich" -> ab = a[++i];
                    case "--einstellungen" -> ei = new File(a[++i]);
                    case "--szenen" -> szn = a[++i];
                    case "--vorschau" -> vs = true;
                    case "--regie" -> rg = a[++i];
                    // seit X1 (Werkbuch HalligHooge 145): den Atlas rechnen; die Startseite erzwingen oder auslassen
                    case "--atlas" -> at = true;
                    case "--startseite" -> ss = true;
                    case "--ohne-startseite" -> ss = false;
                    // seit X2: ein Kapitel nach dem ersten Bild; mit --sekunden geht es alle 12 s von selbst weiter (Probe)
                    case "--kapitel" -> kp = a[++i];
                    // seit X3: die Wege der Rundgänge rechnen; ein Rundgang nach dem ersten Bild (mit --sekunden: Probe)
                    case "--wege" -> wg = true;
                    case "--rundgang" -> rd = a[++i];
                    // seit Y1: einen gerechneten Vergleich nach dem ersten Bild zeigen (mit --sekunden: Probe des Spielers)
                    case "--vergleich" -> vg = a[++i];
                    // seit Y2: die Regler nach dem ersten Bild, etwa "wind=2;drehung=30;aufschlag=1;meer=0.5"
                    case "--regler" -> rl = a[++i];
                    // seit Y3: das Fenster „Ein Jahr in einer Minute“ nach dem ersten Bild (mit --sekunden: Probe, spielt einmal durch)
                    case "--jahr" -> jr = true;
                    // seit Z1: der Tidenkalender nach dem ersten Bild (mit --sekunden: Probe, blättert und sichert)
                    case "--kalender" -> kl = true;
                    // seit Z2: die Landunter-Uhr nach dem ersten Bild (mit --sekunden: Probe mit Windstaus, Messung und Bild)
                    case "--landunter" -> lu = true;
                    // seit Z3: das Watt-Fenster nach dem ersten Bild (mit --sekunden: Probe mit Zeiten, Windstau und Bild)
                    case "--wattfenster" -> wt = true;
                    case "--erwarte" -> {
                        for (String kv : a[++i].split(",")) {
                            int p = kv.indexOf('=');
                            if (p > 0) er.put(kv.substring(0, p).strip(), kv.substring(p + 1).strip());
                        }
                    }
                    default -> { }
                }
            }
            return new Optionen(sz, sek, mb, sn, th, ab, ei, er, vs, szn, rg, at, ss, kp, wg, rd, vg, rl, jr, kl, lu, wt);
        }
    }

    // ------------------------------------------------------------------ Start

    /** Startet die App; kehrt erst zurück, wenn sie endet (dann mit System.exit). */
    public static void starten(File manifestDatei, String[] args) throws Exception {
        for (int i = 0; i + 1 < args.length; i++) if (args[i].equals("--manifest")) manifestDatei = new File(args[i + 1]);
        manifestDatei = manifestSuchen(manifestDatei);
        Optionen o = Optionen.lies(args);
        Manifest m = Manifest.lies(manifestDatei);
        Einstellungen e = o.einstellungen() != null ? new Einstellungen(o.einstellungen()) : Einstellungen.fuer(m.titel());
        // seit W5 (F214 A): der Ort der Daten aus den Einstellungen, als ${daten} in der Szenendatei
        String daten = e.text("daten", null);
        if (daten != null && !daten.isBlank() && System.getProperty("szenen.daten") == null) System.setProperty("szenen.daten", daten);
        File szDatei = o.szenen() != null ? new File(o.szenen()) : m.szenen();
        if (szDatei == null) throw new IllegalArgumentException("Steuerhaus: keine Szenendatei (app.szenen im Manifest oder --szenen)");
        Szenen szenen = Szenen.lies(szDatei);
        if (o.vorschau()) {
            System.exit(Vorschau.erzeugen(m, szenen) > 0 ? 0 : 1);
            return;
        }
        if (o.atlas()) {
            System.exit(com.dan.steuerhaus.atlas.AtlasBau.erzeugen(m, szenen) > 0 ? 0 : 1);
            return;
        }
        if (o.wege()) {
            System.exit(com.dan.steuerhaus.rundgang.RundgangBau.erzeugen(m, szenen) > 0 ? 0 : 1);
            return;
        }
        new Szenenapp(m, szenen, e, o).laufen();
    }

    /**
     * Seit W5 (F214 A): Liegt das Manifest nicht am angegebenen Ort (die App läuft als Abbild, nicht im Projekt), sucht es
     * im Ordner {@code szenen} neben dem Arbeitsordner und neben den Jar-Dateien (im Abbild: {@code app/szenen}).
     */
    static File manifestSuchen(File m) {
        if (m.isFile()) return m;
        List<File> orte = new ArrayList<>(List.of(new File("szenen", m.getName())));
        try {
            File jar = new File(Szenenapp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File d = jar.isFile() ? jar.getParentFile() : jar;
            for (int i = 0; i < 3 && d != null; i++, d = d.getParentFile()) orte.add(new File(new File(d, "szenen"), m.getName()));
        } catch (Exception x) {
            // dann nur der Arbeitsordner
        }
        for (File f : orte) if (f.isFile()) return f;
        return m;
    }

    final com.dan.steuerhaus.analyse.Protokoll protokoll;
    final com.dan.steuerhaus.analyse.Qualitaet qualitaet = new com.dan.steuerhaus.analyse.Qualitaet();
    private final SondenPaneel sondenPaneel;
    private final LeistungPaneel leistungPaneel;
    /** Seit Y2 (Werkbuch HalligHooge 153): das Pult der Regler im Live-Lauf. */
    final com.dan.steuerhaus.regler.Reglerpult reglerPult = new com.dan.steuerhaus.regler.Reglerpult();
    private final ProtokollPaneel protokollPaneel;

    Szenenapp(Manifest m, Szenen szenen, Einstellungen e, Optionen o) throws Exception {
        this.manifest = m;
        this.szenen = szenen;
        this.einstellungen = e;
        this.o = o;
        zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        // seit W5: Protokoll neben den Einstellungen, Sonden, Leistung, Qualität
        protokoll = new com.dan.steuerhaus.analyse.Protokoll(e.datei.getAbsoluteFile().getParentFile());
        protokoll.schreibe("App", m.titel() + " " + m.version() + " startet; Szenendatei " + szenen.datei);
        sondenPaneel = new SondenPaneel(zone);
        leistungPaneel = new LeistungPaneel();
        protokollPaneel = new ProtokollPaneel(protokoll);
        String q = e.text("qualitaet", "hoch");
        if (q.equals("auto")) qualitaet.automatisch(true);
        else for (com.dan.steuerhaus.analyse.Qualitaet.Stufe st : com.dan.steuerhaus.analyse.Qualitaet.Stufe.values())
            if (st.name().equalsIgnoreCase(q)) qualitaet.setze(st);
        katalog = new Katalog(m, szenen);
        leiste = new Zeitleiste(zone);
        pegel = szenen.zahlen("pegel.ort") != null ? szenen.text("pegel.name", "Pegel") : null;
        marke = szenen.zahl("pegel.marke", Double.NaN);
        markeName = szenen.text("pegel.marke.name", "Marke");
        // W3: Regiebücher, Fahrten und Lesezeichen (Werkbuch HalligHooge 116, F193 A, F196 A)
        aufnahmenDatei = m.relativ(m.text("regie.aufnahmen", "regie/aufnahmen.regie"));
        File szd = m.szenen();
        String lz = m.text("app.lesezeichen", "");
        lesezeichenDatei = !lz.isEmpty() ? m.relativ(lz)
                : szd == null ? m.relativ("lesezeichen.txt") : new File(szd.getParentFile(), szd.getName().replaceFirst("\\.[^.]*$", "") + ".lesezeichen");
        regieLaden();
        try {
            lesezeichen = Lesezeichen.lies(lesezeichenDatei, regieFehler);
        } catch (java.io.IOException x) {
            regieFehler.add("Lesezeichen: " + x.getMessage());
        }
        lzListe = new LesezeichenListe(zone);
        lzListe.klick = this::lesezeichenWaehlen;
        lzListe.zeige(lesezeichen);
    }

    /** Liest die Regiebücher aus dem Manifest ({@code regie.N}) und die Aufnahmen; Fehler nach {@link #regieFehler}. */
    void regieLaden() {
        buecher.clear();
        fahrten.clear();
        List<File> dateien = new ArrayList<>();
        for (String d : manifest.nummern("regie.")) dateien.add(manifest.relativ(d));
        if (aufnahmenDatei.isFile() && !dateien.contains(aufnahmenDatei)) dateien.add(aufnahmenDatei);
        for (File f : dateien) {
            try {
                Regiebuch rb = Regiebuch.lies(f.toPath());
                for (Regiebuch.Fehler x : rb.fehler) regieFehler.add(f.getName() + ", " + x);
                if (!rb.zeilen.isEmpty()) buecher.put(rb.name, rb);
                fahrten.putAll(rb.fahrten);
            } catch (java.io.IOException x) {
                regieFehler.add(f + ": " + x.getMessage());
            }
        }
        // seit X2: die Kapitel (Regiebücher mit Tafeln und „warte klick“)
        kapitel.clear();
        for (String d : manifest.nummern("kapitel.")) {
            File f = manifest.relativ(d);
            try {
                Regiebuch rb = Regiebuch.lies(f.toPath());
                for (Regiebuch.Fehler x : rb.fehler) regieFehler.add(f.getName() + ", " + x);
                if (!rb.zeilen.isEmpty()) kapitel.put(rb.name, rb);
                fahrten.putAll(rb.fahrten);
            } catch (java.io.IOException x) {
                regieFehler.add(f + ": " + x.getMessage());
            }
        }
        // seit X3: die Rundgänge – das gerechnete Regiebuch neben der Quelle
        rundgaenge.clear();
        rundgangBild.clear();
        for (String d : manifest.nummern("rundgang.")) {
            File q = manifest.relativ(d), w = com.dan.steuerhaus.rundgang.Rundgang.wegDatei(q);
            if (!w.isFile()) {
                regieFehler.add("Rundgang " + q.getName() + ": der Weg fehlt – einmal mit --wege rechnen");
                continue;
            }
            try {
                Regiebuch rb = Regiebuch.lies(w.toPath());
                for (Regiebuch.Fehler x : rb.fehler) regieFehler.add(w.getName() + ", " + x);
                if (rb.zeilen.isEmpty()) continue;
                rundgaenge.put(rb.name, rb);
                fahrten.putAll(rb.fahrten);
                if (q.isFile() && q.lastModified() > w.lastModified()) regieFehler.add("Rundgang " + q.getName() + ": die Quelle ist neuer als der Weg – --wege neu rechnen");
                if (q.isFile()) {
                    com.dan.steuerhaus.rundgang.Rundgang g = com.dan.steuerhaus.rundgang.Rundgang.lies(q);
                    String v = g.text("vorschau", "");
                    rundgangBild.put(rb.name, !v.isEmpty() ? "ansicht_" + v : "szenario_" + g.text("szenario", "ruhig"));
                }
            } catch (java.io.IOException x) {
                regieFehler.add(w + ": " + x.getMessage());
            }
        }
        // seit Y1: die Vergleiche (Datei der Vergleiche neben der Szenendatei)
        vergleiche = com.dan.steuerhaus.vergleich.Vergleich.lies(manifest, regieFehler);
        // seit Y3: das Jahr (Schlüssel jahr.* im Manifest)
        jahrAuftrag = com.dan.steuerhaus.jahr.Jahresauftrag.lies(manifest);
        // seit Z1: der Tidenkalender (Schlüssel gezeiten.*)
        gezeitenAuftrag = com.dan.steuerhaus.gezeiten.Gezeitenauftrag.lies(manifest);
        // seit Z2: die Landunter-Uhr (Schlüssel landunter.*, rechnet mit den Konstanten des Kalenders)
        landunterAuftrag = gezeitenAuftrag == null ? null : com.dan.steuerhaus.gezeiten.Landunterauftrag.lies(manifest);
        // seit Z3: das Watt-Fenster (Schlüssel watt.*, rechnet mit der Gezeit des Kalenders)
        wattAuftrag = gezeitenAuftrag == null ? null : com.dan.steuerhaus.gezeiten.Wattauftrag.lies(manifest);
        // seit Teil C (Werkbuch HalligHooge 134, F227 A): „folge“ auflösen – ein Gesamtfilm spielt andere Bücher
        java.util.Map<String, Regiebuch> roh = new java.util.LinkedHashMap<>(buecher);
        for (java.util.Map.Entry<String, Regiebuch> e : buecher.entrySet()) {
            Regiebuch a = e.getValue().aufgeloest(roh::get);
            if (a == e.getValue()) continue;
            for (Regiebuch.Fehler x : a.fehler) if (!e.getValue().fehler.contains(x)) regieFehler.add(a.name + ", " + x);
            e.setValue(a);
        }
        for (Regiebuch rb : buecher.values())
            for (Regiebuch.Zeile z : rb.zeilen)
                if (z.befehl().equals("fahrt") && !fahrten.containsKey(z.argumente())) regieFehler.add(rb.name + ", Zeile " + z.nr() + ": Fahrt „" + z.argumente() + "“ gibt es nicht");
    }

    /** Die Befehle der App ohne Fenster und ohne Bühne (SteuerhausCheck): Register, Tasten, Palette. */
    public static Befehle befehleOhneFenster(Manifest m, Szenen szenen) throws Exception {
        File tmp = File.createTempFile("steuerhaus", ".properties");
        tmp.deleteOnExit();
        Szenenapp a = new Szenenapp(m, szenen, new Einstellungen(tmp), Optionen.lies(new String[0]));
        a.befehleAnlegen();
        return a.befehle;
    }

    /** Katalog, Bedienung und Zeitleiste ohne Fenster, für Bilder der Oberfläche (SteuerhausCheck). */
    public static javax.swing.JComponent[] teileOhneFenster(Manifest m, Szenen szenen) throws Exception {
        File tmp = File.createTempFile("steuerhaus", ".properties");
        tmp.deleteOnExit();
        Szenenapp a = new Szenenapp(m, szenen, new Einstellungen(tmp), Optionen.lies(new String[0]));
        Szenario s = szenen.szenarien().get(0);
        a.katalog.zeige(s, s.ansicht != null ? szenen.ansicht(s.ansicht) : null);
        a.bedienung.zeige(s);
        return new javax.swing.JComponent[] {a.katalog.szenarien(), a.katalog.ansichten(), a.bedienung.zeit(), a.bedienung.ebenen(), a.bedienung.foto(), a.bedienung.mess(), a.leiste};
    }

    private static double seitJvm() { return (System.currentTimeMillis() - ManagementFactory.getRuntimeMXBean().getStartTime()) / 1000.0; }

    private void laufen() throws Exception {
        long n0 = System.nanoTime();
        String themaName = o.thema() != null ? o.thema() : einstellungen.text("thema", manifest.thema());
        SwingUtilities.invokeAndWait(() -> {
            Thema.zu(themaName).anwenden();
            start = new Startbild(manifest, fassungSteuerhaus());
            start.zeige();
        });
        System.out.printf(Locale.GERMAN, "Steuerhaus: Prüfstein 1: Startbild nach %.2f s (seit Start der JVM)%n", seitJvm());
        Szenario s0 = waehleStart();
        SwingUtilities.invokeAndWait(this::aufbauen);
        if (startseiteGewuenscht()) SwingUtilities.invokeAndWait(this::startseiteAnlegen);
        Thread.sleep(400);
        Timer takt = new Timer(200, ev -> zeige());
        int ende = 0;
        List<Startbild.Schritt> schritte = new java.util.concurrent.CopyOnWriteArrayList<>();
        Buehne.Laden laden = (name, anteil) -> {
            schritte.add(new Startbild.Schritt(name, anteil));
            SwingUtilities.invokeLater(() -> {
                start.schritt(name, anteil);
                if (seite != null && !seiteBereit) seite.stand(anteil >= 1 ? "Die Bühne steht, das Szenario öffnet …" : "Die Bühne öffnet: " + name + " …", anteil);
            });
        };
        try (Buehne b = Buehne.oeffnen(szenen.gebiet(), hf.leinwand, laden)) {
            System.out.printf(Locale.GERMAN, "%s: Bühne offen nach %.1f s auf %s%n", manifest.titel(), (System.nanoTime() - n0) / 1e9, b.geraet);
            System.out.println("Steuerhaus: Prüfstein 7: " + (Startbild.folgeGut(schritte) ? "ok, " : "FEHLT, ") + schritte.size() + " Schritte " + schritte);
            einblendungenAnlegen(b);
            regisseurAnlegen(b);
            SwingUtilities.invokeLater(() -> hf.fassungen(fassungen(b.geraet)));
            SwingUtilities.invokeLater(() -> start.text("Das Szenario wird geöffnet …"));
            verdrahten(b);
            waehle(b, s0);
            Ansicht a0 = szenen.ansicht(einstellungen.text("ansicht", ""));
            if (o.szenario() == null && a0 != null && s0.schluessel.equals(einstellungen.text("szenario", ""))) {
                ansichtJetzt = a0;
                b.ansicht(a0);
                SwingUtilities.invokeLater(() -> katalog.zeige(null, a0));
            }
            if (o.szenario() == null && s0.schluessel.equals(einstellungen.text("szenario", "")) && einstellungen.hat("zeitraffer")) {
                double r = einstellungen.zahl("zeitraffer", s0.zeitraffer);
                b.zeitraffer(r);
                SwingUtilities.invokeLater(() -> bedienung.zeigeRaffer(r));
            }
            if (o.messblick()) {
                Bedienung.Messwahl m = new Bedienung.Messwahl(true, true, true, 0);
                SwingUtilities.invokeLater(() -> bedienung.zeigeMesswahl(m));
                messblick(b, m);
            }
            if (o.schnitt() != null) schnittNachStart(b);
            buehne = b;
            b.regler(regler.windFaktor(), regler.windDrehung(), regler.aufschlag(), regler.meeresspiegel());
            tonStarten();
            SwingUtilities.invokeLater(hf::neuMalen);
            Timer spaeter = new Timer(1500, ev -> hf.neuMalen());
            spaeter.setRepeats(false);
            spaeter.start();
            erstesBild(b, n0);
            if (zu) b.beenden();
            takt.start();
            if (o.regie() != null) regieNachStart(b, o.regie());
            if (o.kapitel() != null) kapitelNachStart(b, o.kapitel(), o.sekunden() > 0, kapitel, "Kapitel", ".kapitel");
            if (o.rundgang() != null) kapitelNachStart(b, o.rundgang(), o.sekunden() > 0, rundgaenge, "Rundgang", ".weg");
            if (o.vergleich() != null) vergleichNachStart(b, o.vergleich(), o.sekunden());
            if (o.jahr()) jahrNachStart(b, o.sekunden());
            if (o.kalender()) kalenderNachStart(b, o.sekunden());
            if (o.landunter()) landunterNachStart(b, o.sekunden());
            if (o.watt()) wattNachStart(b, o.sekunden());
            if (o.regler() != null) {
                com.dan.steuerhaus.regler.Reglerwerte rw = com.dan.steuerhaus.regler.Reglerwerte.lies(o.regler());
                SwingUtilities.invokeLater(() -> reglerPult.stelle(rw));
            }
            if (o.sekunden() > 0) probe(b, o.sekunden(), n0);
            b.laufen();
        } catch (Throwable e) {
            e.printStackTrace();
            ende = 1;
        } finally {
            takt.stop();
            if (ton != null) ton.close();
            SwingUtilities.invokeAndWait(() -> {
                if (start != null) start.schliesse();
                if (hf != null) {
                    merken();
                    hf.schliessen();
                    hf.fenster.dispose();
                }
            });
        }
        System.exit(ende != 0 ? ende : probeOk ? 0 : 2);
    }

    /** Das erste Szenario: aus dem Aufruf, sonst das zuletzt gewählte, sonst das erste der Datei. */
    private Szenario waehleStart() {
        String k = o.szenario() != null ? o.szenario() : einstellungen.text("szenario", null);
        Szenario s = k != null ? szenen.szenario(k) : null;
        if (o.szenario() != null && s == null) throw new IllegalArgumentException("Steuerhaus: das Szenario " + o.szenario() + " steht nicht in " + szenen.datei);
        return s != null ? s : szenen.szenarien().get(0);
    }

    /** Schließt das Startbild, sobald die Bühne ein Bild des Szenarios gezeigt hat (Prüfstein 1). */
    private void erstesBild(Buehne b, long n0) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && !zu && (b.wechselt() || b.zustand().bilder < 1); i++) Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            System.out.printf(Locale.GERMAN, "Steuerhaus: Prüfstein 1: erstes Bild des Szenarios %.2f s nach dem Start der App, %.2f s seit Start der JVM%n",
                    (System.nanoTime() - n0) / 1e9, seitJvm());
            SwingUtilities.invokeLater(() -> {
                start.schliesse();
                if (seite != null) {
                    // seit X1: die Startseite bleibt, bis man in die Szene geht; die Bühne ruht so lange
                    seiteBereit = true;
                    seite.bereit(true);
                    Runnable w = seiteWunsch;
                    seiteWunsch = null;
                    if (w != null) {
                        w.run();
                        startseiteVerlassen();
                    } else if (seite.sichtbar()) b.ruhen(true);
                    else startseiteVerlassen();
                    return;
                }
                hf.fenster.toFront();
                hf.leinwand.requestFocus();
                hf.neuMalen();
            });
        }, "Startbild");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Startseite (seit X1, Werkbuch HalligHooge 145)

    private com.dan.steuerhaus.atlas.Startseite seite;
    private com.dan.steuerhaus.atlas.Atlas atlas;
    private volatile boolean seiteBereit;
    private volatile Runnable seiteWunsch;

    /** Die Startseite, wenn das Manifest sie will, es einen Atlas gibt und der Aufruf nicht gleich in eine Szene führt. */
    private boolean startseiteGewuenscht() {
        if (o.startseite() != null) return o.startseite() && com.dan.steuerhaus.atlas.Atlas.da(szenen.datei);
        boolean direkt = o.szenario() != null || o.sekunden() > 0 || o.regie() != null || o.kapitel() != null || o.rundgang() != null || o.vergleich() != null || o.regler() != null || o.jahr() || o.kalender() || o.landunter() || o.watt()
                || o.messblick()
                || o.schnitt() != null;
        return !direkt && manifest.text("app.startseite", "aus").equals("an") && einstellungen.wahr("startseite", true) && com.dan.steuerhaus.atlas.Atlas.da(szenen.datei);
    }

    /** Legt die Startseite über das Hauptfenster (Oberfläche); das Startbild geht. */
    void startseiteAnlegen() {
        try {
            atlas = com.dan.steuerhaus.atlas.Atlas.lies(manifest, szenen.datei);
        } catch (java.io.IOException x) {
            System.out.println("Steuerhaus: Startseite ohne Atlas: " + x.getMessage());
            return;
        }
        seite = new com.dan.steuerhaus.atlas.Startseite(hf.fenster, manifest, zone);
        seite.karte.atlas(atlas);
        if (pegel != null) seite.karte.pegelName = "Pegel " + pegel;
        File vorschau = Vorschau.ordner(szenen);
        seite.tafel.zeige(com.dan.steuerhaus.atlas.Tafeln.start(atlas, manifest.titel(), zone));
        seite.karte.beiOrt = ort -> seite.tafel.zeige(ort == null ? com.dan.steuerhaus.atlas.Tafeln.start(atlas, manifest.titel(), zone)
                : com.dan.steuerhaus.atlas.Tafeln.ort(atlas, ort, zone, vorschau, this::szeneAusStartseite));
        seite.beiZurSzene = () -> ausStartseite(null);
        startseiteKacheln(vorschau);
        seite.anheften(hf.fenster, hf.fenster.getComponentPane());
        seite.stand(seiteBereit ? "Die Szene ist bereit." : "Die Bühne öffnet …", Double.NaN);
        seite.zeige();
        double[] blick = com.dan.steuerhaus.atlas.Atlas.zahlenAus(manifest.text("atlas.blick", ""));
        SwingUtilities.invokeLater(() -> {
            if (blick != null && blick.length >= 3) seite.karte.hin(blick[0], blick[1], blick[2]);
            seite.karte.vorladen();
        });
        // X2b: erst das Hauptfenster in den Vordergrund, dann das Startbild weg (sonst bekommt ein anderes Programm den Vordergrund)
        hf.fenster.toFront();
        start.schliesse();
        seite.fenster.toFront();
        protokoll.schreibe("App", "Startseite mit " + atlas.orte.size() + " Orten, " + atlas.ebenen.size() + " Ebenen");
    }

    private void startseiteKacheln(File vorschau) {
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> kp = new ArrayList<>();
        int knr = 0;
        for (Regiebuch r : kapitel.values()) {
            String bild = null;
            for (Regiebuch.Zeile z : r.zeilen)
                if (z.befehl().equals("ansicht")) {
                    bild = "ansicht_" + z.argumente().strip();
                    break;
                }
            knr++;
            kp.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(knr + "  " + r.name, r.beschreibung, bild == null ? null : new File(vorschau, bild + ".png"),
                    () -> ausStartseite(() -> kapitelStarten(r))));
        }
        seite.kacheln.gruppe("Kapitel", kp);
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> rg = new ArrayList<>();
        for (Regiebuch r : rundgaenge.values()) {
            String bild = rundgangBild.get(r.name);
            rg.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(r.name, r.beschreibung, bild == null ? null : new File(vorschau, bild + ".png"),
                    () -> ausStartseite(() -> kapitelStarten(r))));
        }
        if (!rg.isEmpty()) seite.kacheln.gruppe("Rundgänge", rg);
        // seit Y1: die Vergleiche des Labors; gerechnete mit ihrer Vorschau (links A, rechts B)
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> vg = new ArrayList<>();
        for (com.dan.steuerhaus.vergleich.Vergleich v : vergleiche) {
            com.dan.steuerhaus.vergleich.Mappe mp = mappe(v);
            File bild = mp != null && mp.vorschau().isFile() ? mp.vorschau() : v.ansicht() != null ? new File(vorschau, "ansicht_" + v.ansicht() + ".png") : null;
            Szenario sa = szenen.szenario(v.a()), sb = szenen.szenario(v.b());
            String unter = (sa != null ? kurz(sa.name) : v.a()) + " | " + (sb != null ? kurz(sb.name) : v.b()) + (mp == null ? " · noch zu rechnen" : "");
            vg.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(v.name(), unter, bild, () -> vergleichZeigen(v)));
        }
        // seit Y3: das Labor – ein Jahr in einer Minute (Kachel mit der Vorschau der Mappe, sonst der Ansicht)
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> labor = new ArrayList<>();
        if (jahrAuftrag != null) {
            String[] warum = new String[1];
            com.dan.steuerhaus.jahr.Jahresmappe jm = jahrAuftrag.mappe(szenen, warum);
            File bild = jm != null && jm.vorschau().isFile() ? jm.vorschau() : !jahrAuftrag.ansicht().isEmpty() ? new File(vorschau, "ansicht_" + jahrAuftrag.ansicht() + ".png") : null;
            String unter = jahrAuftrag.abschnitte().size() + " Läufe des Modelljahrs, Boden als Differenzkarte" + (jm == null ? " · noch zu rechnen" : "");
            labor.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(manifest.text("jahr.name", "Ein Jahr in einer Minute"), unter, bild, this::jahrZeigen));
        }
        // seit DB2: die Chronik der Hochwasser aus dem Messarchiv (Kachel mit dem jüngsten Ereignis)
        File cd = chronikDatei();
        if (cd != null)
            labor.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(chronikName(), chronikUnterzeile(), null, this::chronikZeigen));
        if (!labor.isEmpty()) seite.kacheln.gruppe("Labor", labor);
        if (!vg.isEmpty()) seite.kacheln.gruppe("Vergleiche", vg);
        // seit Z1: der Begleiter – Tidenkalender (Kachel: der heutige Tag, einmal gemalt)
        if (gezeitenAuftrag != null) {
            File bild = kalenderKachel(vorschau);
            String unter = gezeitenAuftrag.datei().isFile() ? "Hoch- und Niedrigwasser, Spring und Nipp, acht Wochen" : "noch zu rechnen";
            List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> bg = new ArrayList<>();
            bg.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(gezeitenAuftrag.name() + " " + gezeitenAuftrag.ort(), unter, bild, this::kalenderZeigen));
            // seit Z2: die Landunter-Uhr (Kachel: die Uhr ohne Wind, je Stunde neu gemalt)
            if (landunterAuftrag != null)
                bg.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(landunterAuftrag.name(), gezeitenAuftrag.datei().isFile()
                        ? "Wie weit das Wasser bis zur " + landunterAuftrag.kroneName() + " fehlt, Warften, Messung" : "noch zu rechnen", landunterKachel(vorschau), this::landunterZeigen));
            // seit Z3: das Watt-Fenster (Kachel: der heutige Tag mit den trockenen Zeiten)
            if (wattAuftrag != null)
                bg.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(wattAuftrag.name(), wattAuftrag.datei().isFile() ? "Wann der Weg zum " + wattAuftrag.ziel() + " trocken ist; Modell"
                        : "noch zu rechnen", wattKachel(vorschau), this::wattZeigen));
            seite.kacheln.gruppe("Begleiter", bg);
        }
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> rb = new ArrayList<>();
        for (Regiebuch r : buecher.values()) {
            if (r.quelle != null && r.quelle.endsWith(aufnahmenDatei.getName())) continue;          // die eigenen Aufnahmen nicht
            String bild = null;
            for (Regiebuch.Zeile z : r.zeilen) {
                if (z.befehl().equals("ansicht")) bild = "ansicht_" + z.argumente().strip();
                if (bild == null && z.befehl().equals("szenario")) bild = "szenario_" + z.argumente().strip();
                if (bild != null && bild.startsWith("ansicht_")) break;
            }
            rb.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(r.name, r.beschreibung, bild == null ? null : new File(vorschau, bild + ".png"),
                    () -> ausStartseite(() -> kinoStarten(r))));
        }
        seite.kacheln.gruppe("Regiebücher im Kino", rb);
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> sz = new ArrayList<>();
        for (Szenario s : szenen.szenarien())
            sz.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(s.name, s.live() ? "Live gerechnet" : "aus dem Archiv", new File(vorschau, "szenario_" + s.schluessel + ".png"),
                    () -> ausStartseite(() -> waehleAusOberflaeche(s))));
        seite.kacheln.gruppe("Szenarien", sz);
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> an = new ArrayList<>();
        for (Ansicht a : szenen.ansichten()) {
            String[] v = manifest.text("vorschau.ansicht." + a.schluessel, "").split("\\s+");
            String sk = v.length > 0 && !v[0].isEmpty() ? v[0] : null;
            Instant t = v.length > 1 ? Instant.parse(v[1]) : null;
            an.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(a.name, sk != null && szenen.szenario(sk) != null ? szenen.szenario(sk).name : "im gewählten Szenario",
                    new File(vorschau, "ansicht_" + a.schluessel + ".png"), () -> szeneAusStartseite(sk, a.schluessel, t)));
        }
        seite.kacheln.gruppe("Ansichten", an);
        List<com.dan.steuerhaus.atlas.Kachelleiste.Kachel> lz = new ArrayList<>();
        for (int i = 0; i < lesezeichen.size(); i++) {
            int nr = i;
            Lesezeichen x = lesezeichen.get(i);
            Szenario s = szenen.szenario(x.szenario());
            lz.add(new com.dan.steuerhaus.atlas.Kachelleiste.Kachel(x.name(), s != null ? s.name : x.szenario(), null, () -> ausStartseite(() -> lesezeichenWaehlen(nr))));
        }
        seite.kacheln.gruppe("Lesezeichen", lz);
    }

    /** Von der Startseite in die Szene; {@code tun} danach (null: nur hin). Ist die Bühne noch nicht bereit, wartet der Wunsch. */
    private void ausStartseite(Runnable tun) {
        if (!seiteBereit) {
            seiteWunsch = tun != null ? tun : () -> { };
            seite.stand("Gleich geht es in die Szene – die Bühne öffnet noch …", Double.NaN);
            return;
        }
        if (tun != null) tun.run();
        startseiteVerlassen();
    }

    /** Szenario, Ansicht und Zeit von einer Tafel oder Kachel (Werte null: bleiben). */
    void szeneAusStartseite(String szenario, String ansicht, Instant zeit) {
        ausStartseite(() -> szeneZeigen(szenario, ansicht, zeit));
    }

    /** Szenario, Ansicht und Zeit im Hauptfenster (Werte null: bleiben); seit Y3 auch aus dem Fenster des Jahrs. */
    void szeneZeigen(String szenario, String ansicht, Instant zeit) {
        {
            Buehne b = buehne;
            if (b == null) return;
            Szenario s = szenario != null ? szenen.szenario(szenario) : null;
            Ansicht a = ansicht != null ? szenen.ansicht(ansicht) : null;
            Thread t = new Thread(() -> {
                try {
                    if (s != null && szenarioJetzt != s) {
                        waehle(b, s);
                        Thread.sleep(200);
                        for (int i = 0; i < 1200 && (b.wechselt() || b.uhr() == null); i++) Thread.sleep(50);
                    }
                    if (zeit != null && b.uhr() != null) b.zeit((zeit.toEpochMilli() - b.uhr().start.toEpochMilli()) / 1000.0);
                    if (a != null) {
                        ansichtJetzt = a;
                        b.ansicht(a);
                        SwingUtilities.invokeLater(() -> katalog.zeige(null, a));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Startseite");
            t.setDaemon(true);
            t.start();
        }
    }

    private void startseiteVerlassen() {
        if (seite == null) return;
        seite.verberge();                                                   // holt das Hauptfenster vorher nach vorn (X2b)
        Buehne b = buehne;
        if (b != null && ausgabeStand == null) b.ruhen(false);
        hf.leinwand.requestFocus();
        hf.neuMalen();
        protokoll.schreibe("App", "Startseite verlassen");
    }

    /** Pos1: zurück zur Startseite (Kino und Regie hören auf); die Bühne ruht. */
    private void startseiteZeigen() {
        if (seite == null) {
            if (hf != null) hf.hinweis("Keine Startseite: es gibt keinen Atlas (einmal mit --atlas rechnen) oder app.startseite ist aus", Hinweis.Art.INFO);
            return;
        }
        if (seite.sichtbar()) return;
        regieAbbrechen();
        Buehne b = buehne;
        if (b != null && seiteBereit) b.ruhen(true);
        seite.zeige();
    }

    // ------------------------------------------------------------------ Aufbau (Oberfläche)

    void aufbauen() {
        befehleAnlegen();
        hf = new Hauptfenster(manifest, befehle, einstellungen);
        hf.links.abschnitt(new Abschnitt("szenarien", "Szenarien", katalog.szenarien()));
        hf.links.abschnitt(new Abschnitt("ansichten", "Ansichten", katalog.ansichten()));
        hf.links.abschnitt(new Abschnitt("lesezeichen", "Lesezeichen", lzListe));
        hf.rechts.abschnitt(new Abschnitt("zeit", "Zeit", bedienung.zeit()));
        hf.rechts.abschnitt(new Abschnitt("ebenen", "Ebenen", bedienung.ebenen()));
        hf.rechts.abschnitt(new Abschnitt("foto", "Foto (Referenz)", bedienung.foto()));
        hf.rechts.abschnitt(new Abschnitt("messblick", "Messblick", bedienung.mess()));
        // seit W5 (F215 A)
        hf.rechts.abschnitt(new Abschnitt("sonden", "Sonden", sondenPaneel));
        hf.rechts.abschnitt(new Abschnitt("leistung", "Leistung", leistungPaneel));
        hf.rechts.abschnitt(new Abschnitt("protokoll", "Protokoll", protokollPaneel));
        // seit Y2: das Pult der Regler (Arbeitsbereich Labor)
        hf.rechts.abschnitt(new Abschnitt("regler", "Regler (Labor)", reglerPult));
        reglerPult.aenderung = w -> reglerSetzen(w, false);
        reglerPult.eingriff = w -> reglerSetzen(w, true);
        reglerPult.live = this::liveStarten;
        hf.beiHinweis = (text, art) -> protokoll.schreibe(art == Hinweis.Art.FEHLER ? "Fehler" : "Hinweis", text);
        sondenPaneel.szenarien(szenen.szenarien().stream().filter(x -> x.archiv != null).map(x -> x.name).toList());
        sondenPaneel.vergleichWahl = w -> vergleichWaehlen(w[0], w[1]);
        sondenPaneel.csv.addActionListener(ev -> sondenCsv());
        sondenPaneel.weg.addActionListener(ev -> sondenWeg());
        hf.unten.inhalt(leiste, BorderLayout.CENTER);
        for (Arbeitsbereich a : arbeitsbereiche()) hf.bereich(a);
        String ab = o.arbeitsbereich() != null ? o.arbeitsbereich() : einstellungen.text("arbeitsbereich", manifest.arbeitsbereich());
        hf.arbeitsbereich(ab);
        if (o.arbeitsbereich() == null) hf.docksAusEinstellungen();
        hf.fassungen(fassungen(null));
        hf.fenster.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { schliessen(); }

            @Override public void windowClosed(WindowEvent e) { schliessen(); }
        });
        hf.nachAnsicht = this::einblendungenAnwenden;
        hf.oeffnen();
        start.text("Die Bühne öffnet …");
        List<String> f = befehle.tastenFehler();
        System.out.println("Steuerhaus: Prüfstein 7: " + befehle.alle().size() + " Befehle, " + (f.isEmpty() ? "keine Taste doppelt oder bei der Kamera" : f.size() + " Fehler: " + f));
        if (!f.isEmpty()) hf.hinweis("Tasten doppelt vergeben: " + f.get(0), Hinweis.Art.FEHLER);
    }

    /** Für Prüfungen (SteuerhausCheck Y2): die Arbeitsbereiche der App. */
    public static List<Arbeitsbereich> arbeitsbereicheFuerPruefung() { return arbeitsbereiche(); }

    static List<Arbeitsbereich> arbeitsbereiche() {
        return List.of(
                new Arbeitsbereich("betrachten", "Betrachten", false, false, true, null),
                new Arbeitsbereich("steuern", "Steuern", true, true, true, Set.of("szenarien", "ansichten", "lesezeichen", "zeit", "ebenen", "foto")),
                new Arbeitsbereich("messen", "Messen", false, true, true, Set.of("zeit", "messblick", "sonden")),
                new Arbeitsbereich("werkstatt", "Werkstatt", false, true, true, Set.of("leistung", "protokoll")),
                // seit Y2: das Labor – Regler, Zeit und Sonden; der Katalog bleibt zu
                new Arbeitsbereich("labor", "Labor", false, true, true, Set.of("regler", "zeit", "sonden")));
    }

    /** Alle Befehle der App (Menü, Tasten, Palette). Läuft ohne Fenster (SteuerhausCheck). */
    void befehleAnlegen() {
        Befehle b = befehle;
        // Datei
        b.neu("foto", "Datei", "Foto der Ansicht (Referenz)", "F9", this::fotoKnopf).hilfe("Rechnet die Ansicht mit der Referenz; die Zeit hält an.");
        b.neu("vorschau.hinweis", "Datei", "Vorschaubilder des Katalogs …", null,
                () -> hf.hinweis("Vorschaubilder: App mit --vorschau starten (rund 3–5 min, ohne Fenster)", Hinweis.Art.INFO));
        // seit W4: Ausgabe (Werkbuch HalligHooge 121, F200 A) – ein eigener Prozess ohne Fenster
        b.neu("ausgabe.video", "Datei", "Video ausgeben …", "ctrl E", this::videoDialog).hilfe("Ein Regiebuch Bild für Bild als Video (MP4); die App hält ihr Bild an, solange die Ausgabe rechnet.");
        b.neu("ton.an", "Ansicht/Ton", "Ton an/aus", "ctrl M", this::tonUmschalten).hilfe("Wind, Brandung, Plätschern und Möwen, gerechnet aus der Szene (X5).");
        b.neu("ton.lauter", "Ansicht/Ton", "Ton lauter", null, () -> tonLautstaerke(0.1));
        b.neu("ton.leiser", "Ansicht/Ton", "Ton leiser", null, () -> tonLautstaerke(-0.1));
        b.neu("ausgabe.postkarte", "Datei", "Postkarte …", "ctrl P", this::postkarteDialog).hilfe("Der Blick jetzt als Postkarte: Format, Vorlage, Unterschrift; nach " + manifest.fotos());
        b.neu("ausgabe.standbild", "Datei", "Standbild 4K", "ctrl F9", this::standbild4k).hilfe("Der Blick jetzt in 3840 × 2160, mit Korn, nach " + manifest.fotos());
        b.neu("ausgabe.abbrechen", "Datei", "Ausgabe abbrechen", null, this::ausgabeAbbrechen);
        // seit W5 (Werkbuch HalligHooge 126)
        b.neu("daten.ort", "Datei", "Ort der Daten …", null, this::datenOrt).hilfe("Wo Gebiet und Archive liegen (${daten} in der Szenendatei); gilt nach einem Neustart.");
        b.neu("sonden.weg", "Messen/Sonden", "Alle Sonden weg", null, this::sondenWeg);
        b.neu("sonden.csv", "Messen/Sonden", "Sonden als CSV ablegen", null, this::sondenCsv);
        b.neu("vergleich.bild", "Messen/Sonden", "Vergleichsbild …", null, this::vergleichsbild).hilfe("Der Blick jetzt im Vergleichsszenario (Abschnitt Sonden), nebeneinander mit Schieber.");
        // seit Y1 (Werkbuch HalligHooge 152): das Labor – Vergleiche als Bildfolge mit Schieber
        b.neu("labor.vergleich.jetzt", "Labor/Vergleich", "Vergleich vom Blick jetzt …", "ctrl L", this::vergleichDialog)
                .hilfe("Derselbe Blick in einem zweiten Szenario, Bild für Bild über die Tide; danach der Spieler mit Schieber und den Kurven der Sonden.");
        b.neu("labor.vergleich.mappen", "Labor/Vergleich", "Gerechnete Vergleiche …", null, this::vergleicheOeffnen).hilfe("Alle gerechneten Vergleiche (" + manifest.vergleiche() + ").");
        int vn = 0;
        for (com.dan.steuerhaus.vergleich.Vergleich v : vergleiche)
            b.neu("labor.vergleich." + (++vn), "Labor/Vergleich", v.name(), null, () -> vergleichZeigen(v)).hilfe(v.text());
        // seit Y3 (Werkbuch HalligHooge 154): ein Jahr in einer Minute
        if (jahrAuftrag != null)
            b.neu("labor.jahr", "Labor", manifest.text("jahr.name", "Ein Jahr in einer Minute") + " …", "ctrl shift J", this::jahrZeigen)
                    .hilfe("Der Boden der Läufe des Modelljahrs als Differenzkarte im Zeitraffer, mit Profil über den " + jahrAuftrag.sand() + ".");
        // seit DB2 (Werkbuch HalligHooge 169): die Chronik der Hochwasser aus dem Messarchiv
        if (chronikDatei() != null)
            b.neu("labor.chronik", "Labor", chronikName() + " …", "ctrl shift H", this::chronikZeigen)
                    .hilfe("Landunter und Wasser über der Krone am Ort, Sturmfluten an den Pegeln, gemessen seit Oktober 2026; mit dem Sprung ins Szenario, das die Zeit abdeckt.");
        // seit Z1 (Werkbuch HalligHooge 155): der Begleiter – Tidenkalender
        if (gezeitenAuftrag != null)
            b.neu("begleiter.kalender", "Begleiter", gezeitenAuftrag.name() + " …", "ctrl shift T", this::kalenderZeigen)
                    .hilfe("Hoch- und Niedrigwasser je Tag am " + gezeitenAuftrag.ort() + ", Spring- und Nipptide, Mond, Ausblick über acht Wochen; astronomisch, nicht zur Navigation.");
        // seit Z3 (Werkbuch HalligHooge 158): der Begleiter – Watt-Fenster
        if (wattAuftrag != null)
            b.neu("begleiter.watt", "Begleiter", wattAuftrag.name() + " …", "ctrl shift W", this::wattZeigen)
                    .hilfe("Wann der Weg von der " + wattAuftrag.start() + " zum " + wattAuftrag.ziel() + " trocken ist und wie lange, heute und die nächsten sieben Tage; Modell, nicht zur Navigation.");
        // seit Z2 (Werkbuch HalligHooge 156): der Begleiter – Landunter-Uhr
        if (landunterAuftrag != null)
            b.neu("begleiter.landunter", "Begleiter", landunterAuftrag.name() + " …", "ctrl shift U", this::landunterZeigen)
                    .hilfe("Abstand des nächsten Hochwassers zur " + landunterAuftrag.kroneName() + " mit Windstau (gemessen in " + gezeitenAuftrag.bezugName()
                            + " oder gewählt), die Warften beim Scheitel; nicht zur Navigation.");
        // seit Y2: die Regler im Live-Lauf
        b.neu("labor.live", "Labor/Regler", "Live-Lauf starten", null, this::liveStarten).hilfe("Das erste Live-Szenario der Szenendatei; die Regler wirken nur live.");
        b.neu("labor.regler.zurueck", "Labor/Regler", "Regler zurück auf ERA5", null, () -> reglerPult.stelle(com.dan.steuerhaus.regler.Reglerwerte.NEUTRAL))
                .hilfe("Wind wie gemessen, kein Aufschlag, Meeresspiegel wie heute; die Physik fährt zurück.");
        for (com.dan.steuerhaus.analyse.Qualitaet.Stufe st : com.dan.steuerhaus.analyse.Qualitaet.Stufe.values())
            b.neu("qualitaet." + st.name().toLowerCase(Locale.ROOT), "Ansicht/Qualität", st.name, null, () -> qualitaetSetzen(st, false))
                    .schalter(() -> !qualitaet.automatisch() && qualitaet.stufe() == st);
        b.neu("qualitaet.auto", "Ansicht/Qualität", "Automatisch", null, () -> qualitaetSetzen(null, true)).schalter(qualitaet::automatisch);
        b.neu("protokoll.oeffnen", "Hilfe", "Protokoll-Datei öffnen", null, () -> {
            try {
                java.awt.Desktop.getDesktop().open(protokoll.datei());
            } catch (Exception x) {
                hf.hinweis("Protokoll: " + protokoll.datei(), Hinweis.Art.INFO);
            }
        });
        b.neu("kapitel.weiter", "Regie/Kapitel", "Kapitel: weiter", "ENTER", this::kapitelWeiter).hilfe("Im Kapitel zum nächsten Abschnitt (auch ein Klick ins Bild).");
        b.neu("kapitel.zurueck", "Regie/Kapitel", "Kapitel: zurück", "BACK_SPACE", this::kapitelZurueck).hilfe("Im Kapitel zum Abschnitt davor.");
        int kn = 0;
        for (Regiebuch rb : kapitel.values()) {
            Regiebuch x = rb;
            b.neu("regie.kapitel." + (++kn), "Regie/Kapitel", x.name, null, () -> kapitelStarten(x)).hilfe(x.beschreibung);
        }
        int rn = 0;
        for (Regiebuch rb : rundgaenge.values()) {
            Regiebuch x = rb;
            b.neu("regie.rundgang." + (++rn), "Regie/Rundgang", x.name, null, () -> kapitelStarten(x)).hilfe(x.beschreibung + " – Klick oder Eingabe: weiter, Maus ziehen: umsehen.");
        }
        b.neu("startseite", "Datei", "Startseite", "HOME", this::startseiteZeigen).hilfe("Atlas, Infotafeln und Kacheln; die Szene ruht so lange.");
        b.neu("beenden", "Datei", "Beenden", "ctrl Q", this::schliessenUndZu);
        // Ansicht
        for (Arbeitsbereich a : arbeitsbereiche()) {
            int n = arbeitsbereiche().indexOf(a) + 1;
            b.neu("bereich." + a.kennung(), "Ansicht/Arbeitsbereich", a.name(), "ctrl " + n, () -> hf.arbeitsbereich(a.kennung()))
                    .schalter(() -> hf != null && hf.arbeitsbereich().equals(a.kennung()));
        }
        b.neu("dock.links", "Ansicht", "Katalog", "F4", () -> hf.links.klappe()).schalter(() -> hf != null && hf.links.offen());
        b.neu("dock.rechts", "Ansicht", "Bedienfeld", "F2", () -> hf.rechts.klappe()).schalter(() -> hf != null && hf.rechts.offen());
        b.neu("dock.unten", "Ansicht", "Zeitleiste", "F3", () -> hf.unten.klappe()).schalter(() -> hf != null && hf.unten.offen());
        b.neu("kino", "Ansicht", "Nur das Bild", "F11", () -> hf.kinoblick(!hf.imKino())).schalter(() -> hf != null && hf.imKino());
        for (Thema t : Thema.values()) b.neu("thema." + t.schluessel(), "Ansicht/Thema", t.name, null, () -> hf.thema(t)).schalter(() -> Thema.aktuell() == t);
        b.neu("palette", "Ansicht", "Befehlspalette", "ctrl K", () -> hf.palette());
        // Einblendungen (W2): alle an und aus, je Teil für den Arbeitsbereich
        b.neu("einblendung.alle", "Ansicht/Einblendungen", "Alle Einblendungen", "F6", this::einblendungenAlle)
                .schalter(() -> einstellungen.wahr("einblendung.alle", true)).hilfe("Blendet Uhr, Kompass, Maßstab, Minikarte, Ortsnamen und Legende ein oder aus.");
        for (Einblendungen.Teil t : Einblendungen.Teil.values())
            b.neu("einblendung." + t.kennung, "Ansicht/Einblendungen", t.name, null, () -> einblendungUmschalten(t)).schalter(() -> teile(bereichJetzt()).contains(t));
        // Szene: Szenarien und Ansichten nach den Gruppen des Katalogs
        Katalog.gruppiert(manifest.gruppen("szenarien"), szenen.szenarien(), s -> s.schluessel).forEach((g, l) -> {
            for (Szenario s : l) b.neu("szenario." + s.schluessel, "Szene/Szenario/" + g, s.name, null, () -> waehleAusOberflaeche(s)).schalter(() -> szenarioJetzt == s);
        });
        Katalog.gruppiert(manifest.gruppen("ansichten"), szenen.ansichten(), a -> a.schluessel).forEach((g, l) -> {
            for (Ansicht a : l) b.neu("ansicht." + a.schluessel, "Szene/Ansicht/" + g, a.name, null, () -> ansichtAusOberflaeche(a)).schalter(() -> ansichtJetzt == a);
        });
        for (Bedienung.Schalter k : new Bedienung.Schalter[] {bedienung.haeuser, bedienung.gestalt, bedienung.bewuchs, bedienung.salzwiese, bedienung.brandung})
            b.neu("ebene." + k.name.toLowerCase(Locale.GERMAN), "Szene/Ebenen", k.name, null, k::umschalten).schalter(k::an).wenn(k::da);
        // Zeit
        b.neu("zeit.vor", "Zeit", "Vor", "ctrl RIGHT", () -> bedienung.richtung(Bedienung.VOR)).schalter(() -> bedienung.richtung() == Bedienung.VOR);
        b.neu("zeit.halt", "Zeit", "Halt / weiter", "SPACE", () -> bedienung.richtung(bedienung.richtung() == Bedienung.HALT ? Bedienung.VOR : Bedienung.HALT))
                .schalter(() -> bedienung.richtung() == Bedienung.HALT);
        b.neu("zeit.zurueck", "Zeit", "Zurück", "ctrl LEFT", () -> bedienung.richtung(Bedienung.ZURUECK)).schalter(() -> bedienung.richtung() == Bedienung.ZURUECK)
                .wenn(() -> !bedienung.live());
        b.neu("zeit.schneller", "Zeit", "Zeitraffer schneller", "ctrl UP", () -> bedienung.rafferSchritt(1));
        b.neu("zeit.langsamer", "Zeit", "Zeitraffer langsamer", "ctrl DOWN", () -> bedienung.rafferSchritt(-1));
        // Messen
        String[] ebenen = {"Partikel", "LIC", "Linien"};
        for (int i = 0; i < 3; i++) {
            int k = i;
            b.neu("mess." + ebenen[i].toLowerCase(Locale.GERMAN), "Messen", ebenen[i], "ctrl shift " + (i + 1), () -> bedienung.messUmschalten(k)).schalter(() -> bedienung.messwahl().an() && new boolean[] {
                    bedienung.messwahl().partikel(), bedienung.messwahl().lic(), bedienung.messwahl().linien()}[k]);
        }
        String[] arten = {"Wasserstand", "Tiefe", "Trübung"};
        for (int i = 0; i < 3; i++) {
            int k = i;
            b.neu("mess.art." + i, "Messen/Linien", arten[i], null, () -> bedienung.art(k)).schalter(() -> bedienung.messwahl().art() == k);
        }
        b.neu("mess.schnitt", "Messen", "Schnitt ziehen / weg", "ctrl J", bedienung::schnittKnopf).schalter(() -> bedienung.schnittStand() != 0);
        // Hilfe
        // Regie (W3)
        int i = 0;
        for (Regiebuch rb : buecher.values())
            b.neu("regie.buch." + (++i), "Regie/Regiebuch im Kino", rb.name, null, () -> kinoStarten(rb)).hilfe(rb.beschreibung);
        fahrtBefehle();
        b.neu("regie.abbrechen", "Regie", "Kino, Regiebuch oder Fahrt abbrechen", "ESCAPE", this::regieAbbrechen);
        b.neu("regie.schluessel", "Regie/Aufnahme", "Schlüsselbild setzen", "K", this::schluesselbild).hilfe("Nimmt den Blick jetzt als nächstes Schlüsselbild der Aufnahme.");
        b.neu("regie.schluessel.weg", "Regie/Aufnahme", "Letztes Schlüsselbild weg", "alt K", this::schluesselbildWeg);
        b.neu("regie.abspielen", "Regie/Aufnahme", "Aufnahme abspielen", "P", this::aufnahmeAbspielen);
        b.neu("regie.speichern", "Regie/Aufnahme", "Aufnahme als Fahrt speichern", "ctrl S", this::aufnahmeSpeichern);
        b.neu("regie.verwerfen", "Regie/Aufnahme", "Aufnahme verwerfen", null, this::aufnahmeVerwerfen);
        b.neu("lesezeichen.setzen", "Szene/Lesezeichen", "Lesezeichen setzen", "B", this::lesezeichenSetzen).hilfe("Merkt Szenario, Zeit und Blick im Projekt.");
        lesezeichenBefehle();
        b.neu("ueber", "Hilfe", "Über " + manifest.titel() + ", Quellen und Tasten", "F1", () -> hf.ueber());
    }

    // ------------------------------------------------------------------ Verdrahtung mit der Bühne

    private void verdrahten(Buehne b) {
        b.steuerung.anbringen(hf.leinwand);
        double[] p;
        try {
            p = szenen.zahlen("pegel.ort");
            if (p != null) b.pegel(pegel, p[0], p[1], szenen.zahl("pegel.umkreis", 300));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        katalog.szenario = s -> waehle(b, s);
        katalog.ansicht = a -> {
            ansichtJetzt = a;
            b.ansicht(a);
            katalog.zeige(null, a);
        };
        bedienung.foto = () -> foto(b);
        bedienung.fotoAbbrechen = b::fotoAbbrechen;
        bedienung.zeitraffer = b::zeitraffer;
        bedienung.richtung = r -> {
            b.pause(r == Bedienung.HALT);
            b.rueckwaerts(r == Bedienung.ZURUECK);
        };
        leiste.sprung = b::zeit;
        bedienung.messblick = m -> messblick(b, m);
        bedienung.schnitt = () -> {
            schnittKlicks = 1;
            schnittA = null;
        };
        bedienung.schnittWeg = () -> schnittWeg(b);
        bedienung.haeuser.ziel = b::haeuser;
        bedienung.gestalt.ziel = an -> b.gestalt(an && qualitaet.stufe().erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.GESTALT));
        bedienung.bewuchs.ziel = an -> b.bewuchs(an && qualitaet.stufe().erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.BEWUCHS));
        bedienung.salzwiese.ziel = b::salzwiese;
        bedienung.brandung.ziel = an -> b.brandung(an && qualitaet.stufe().erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.BRANDUNG));
        SwingUtilities.invokeLater(() -> {
            bedienung.haeuser.zeige(b.hatHaeuser(), b.haeuserAn());
            bedienung.gestalt.zeige(b.hatGestalt(), b.gestaltAn());
            bedienung.bewuchs.zeige(b.hatBewuchs(), b.bewuchsAn());
            bedienung.salzwiese.zeige(b.hatSalzwiese(), b.salzwieseAn());
            bedienung.brandung.zeige(true, b.brandungAn());
        });
        hf.leinwand.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { klick(b, e); }
        });
    }

    private void waehleAusOberflaeche(Szenario s) {
        Buehne b = buehne;
        if (b != null) waehle(b, s);
    }

    private void ansichtAusOberflaeche(Ansicht a) {
        Buehne b = buehne;
        if (b == null) return;
        ansichtJetzt = a;
        b.ansicht(a);
        katalog.zeige(null, a);
    }

    private void fotoKnopf() {
        Buehne b = buehne;
        if (b == null) return;
        if (bedienung.fotoLaeuft()) b.fotoAbbrechen();
        else foto(b);
    }

    /** Wechselt das Szenario und stellt Blick, Katalog und Bedienung darauf ein. */
    private void waehle(Buehne b, Szenario s) {
        protokoll.schreibe("Szenario", s.name);
        schnittWeg(b);
        szenarioJetzt = s;
        Ansicht a = s.ansicht != null ? szenen.ansicht(s.ansicht) : null;
        ansichtJetzt = a;
        b.szenario(s);
        if (a != null) b.ansicht(a);
        SwingUtilities.invokeLater(() -> {
            katalog.zeige(s, a);
            bedienung.zeige(s);
        });
    }

    /** Je Takt der Oberfläche: Einblendung, Zeitleiste, Foto, Messblick. */
    private void zeige() {
        Buehne b = buehne;
        if (b == null) return;
        Buehne.Zustand z = b.zustand();
        tonTakt(b, z);
        String ag = ausgabeStand;
        hf.einblendung(ag != null ? ag : b.wechselt() ? "Das Szenario wird geöffnet …" : z.text(zone, pegel) + (z.live && !regler.neutral() ? " · Labor: " + regler.text() : ""));
        Einblendungen eb = ein;
        if (eb != null && !b.wechselt())
            try {
                eb.takt(z);
            } catch (RuntimeException x) {
                if (!taktFehler) x.printStackTrace();                         // einmal melden, die App läuft weiter
                taktFehler = true;
            }
        Regisseur rg = regisseur;
        List<Object[]> lz = new ArrayList<>();
        for (int k = 0; k < lesezeichen.size(); k++) {
            Lesezeichen x = lesezeichen.get(k);
            if (z.start != null && szenarioJetzt != null && x.szenario().equals(szenarioJetzt.schluessel))
                lz.add(new Object[] {(x.zeit().toEpochMilli() - z.start.toEpochMilli()) / 1000.0, k, x.name()});
        }
        String spur = rg == null ? "" : rg.regie() != null ? "Regie: " + rg.regie().buch.name : rg.faehrt() ? "Fahrt" : "";
        leiste.spuren(lz, rg == null ? null : rg.fortschritt(), spur + (aufnahme.isEmpty() ? "" : "  · Aufnahme: " + aufnahme.size() + " Schlüsselbilder"));
        if (rg != null) {
            String ml = rg.meldung();
            if (ml != null) hf.hinweis("Regie: " + ml, Hinweis.Art.FEHLER);
        }
        leiste.zeige(b.pegelreihe(), pegel, marke, markeName, z.start, z.tAnfang, z.tEnde, z.jetzt == null ? Double.NaN : z.t, !z.live);
        analyseTakt(b, z);
        fotoZeigen(b);
        messZeigen(b, z);
    }

    // ------------------------------------------------------------------ Analyse und Betrieb (W5)

    private final List<com.dan.lauf.Sonde> sonden = new ArrayList<>(), sondenB = new ArrayList<>();
    private final List<Double> verschiebungB = new ArrayList<>();
    private Szenario vergleichSz;
    private int ausrichtung;
    private String langAlt, fehlerAlt;
    private com.dan.steuerhaus.analyse.Qualitaet.Stufe stufeAlt;

    /** Je Takt: Sonden, Leistung, Qualitätsstufe (automatisch), lange Bilder und Fehler ins Protokoll. */
    private void analyseTakt(Buehne b, Buehne.Zustand z) {
        if (z.jetzt != null) sondenPaneel.zeige(z.tAnfang, z.tEnde, z.t);
        reglerTakt(b, z);
        if (leistungPaneel.isShowing())
            leistungPaneel.zeige(b.bildzeitVerlauf(60), b.fensterAbschnitte(), b.speicher(), b.langesBild(),
                    qualitaet.stufe().name + (qualitaet.automatisch() ? " (automatisch)" : ""));
        // seit C1b (Werkbuch HalligHooge 137, A53): die Automatik auch im Kino, dort höchstens bis Mittel (die Brandung
        // bleibt), und eine neue Stufe gilt erst in einer Überblendung, damit sie nicht mitten in einer Einstellung springt
        qualitaet.tiefste(kinoAktiv ? com.dan.steuerhaus.analyse.Qualitaet.Stufe.MITTEL : com.dan.steuerhaus.analyse.Qualitaet.Stufe.NIEDRIG);
        com.dan.steuerhaus.analyse.Qualitaet.Stufe s = qualitaet.takt(System.nanoTime() / 1e9, b.wechselt() ? Double.NaN : z.bilderJeS);
        Regisseur rq = regisseur;
        boolean imSchnitt = !kinoAktiv || stufeAlt == null || rq != null && rq.blendet();
        if (s != stufeAlt && imSchnitt) {
            if (stufeAlt != null) protokoll.schreibe("Qualität", s.name + (qualitaet.automatisch() ? " (automatisch, " + String.format(Locale.GERMAN, "%.0f", z.bilderJeS) + " B/s)" : ""));
            stufeAlt = s;
            qualitaetAnwenden(b);
        }
        String lang = b.langesBild();
        if (lang != null && !lang.equals(langAlt)) protokoll.schreibe("Bild", lang);
        langAlt = lang;
        if (z.fehler != null && !z.fehler.equals(fehlerAlt)) protokoll.schreibe("Fehler", z.fehler);
        fehlerAlt = z.fehler;
        // seit W5c (F221 A): die Uhr wartet auf Stände (der Vorleser liest von einer Platte, die aufwacht)
        if (z.datenWarten > 1 && !wartenGemeldet) {
            hf.hinweis("Die Stände werden gelesen – die Platte wacht auf? Die Uhr hält an, das Bild läuft weiter.", Hinweis.Art.INFO);
            wartenGemeldet = true;
        }
        if (z.datenWarteZahl < warteZahlAlt) warteZahlAlt = z.datenWarteZahl;
        if (z.datenWarteZahl > warteZahlAlt) {
            if (z.datenGewartet >= 0.5)
                protokoll.schreibe("Daten", String.format(Locale.GERMAN, "%.1f s auf die Stände gewartet; die Uhr hielt an, das Bild lief weiter", z.datenGewartet));
            warteZahlAlt = z.datenWarteZahl;
            wartenGemeldet = false;
        }
    }

    private boolean wartenGemeldet;
    private int warteZahlAlt;

    /** Die Stufe begrenzt die Schalter der Ebenen; was der Benutzer ausgeschaltet hat, bleibt aus. */
    private void qualitaetAnwenden(Buehne b) {
        if (b == null) return;
        var st = qualitaet.stufe();
        b.bewuchs(bedienung.bewuchs.an() && st.erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.BEWUCHS));
        b.brandung(bedienung.brandung.an() && st.erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.BRANDUNG));
        b.gestalt(bedienung.gestalt.an() && st.erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.GESTALT));
        b.kimmung.an = st.erlaubt(com.dan.steuerhaus.analyse.Qualitaet.Teil.KIMMUNG);
    }

    private void qualitaetSetzen(com.dan.steuerhaus.analyse.Qualitaet.Stufe st, boolean auto) {
        if (auto) qualitaet.automatisch(true);
        else qualitaet.setze(st);
        einstellungen.setze("qualitaet", auto ? "auto" : st.name().toLowerCase(Locale.ROOT));
        protokoll.schreibe("Qualität", auto ? "Automatisch ab " + qualitaet.stufe().name : st.name);
        stufeAlt = null;
        qualitaetAnwenden(buehne);
    }

    /** Strg+Klick: eine Sonde am Ort im Bild (bis 4; die fünfte ersetzt die erste). */
    private void sondeSetzen(Buehne b, MouseEvent e) {
        double[] p = b.ort(e.getX() / (double) Math.max(1, hf.leinwand.getWidth()), e.getY() / (double) Math.max(1, hf.leinwand.getHeight()));
        if (p == null) {
            hf.hinweis("Dort ist kein Boden und kein Wasser.", Hinweis.Art.INFO);
            return;
        }
        Szenario v = vergleichSz;
        int ab = ausrichtung;
        String name = "Sonde " + (sonden.size() % 4 + 1);
        new Thread(() -> {
            try {
                com.dan.lauf.Sonde s = b.sonde(name, p[0], p[1]);
                com.dan.lauf.Sonde sb = v == null ? null : com.dan.lauf.Sonde.ausArchiv(v, b.gebiet, name, p[0], p[1]);
                SwingUtilities.invokeLater(() -> {
                    if (sonden.size() == 4) {
                        sonden.remove(0);
                        sondenB.remove(0);
                        verschiebungB.remove(0);
                    }
                    sonden.add(s);
                    sondenB.add(sb);
                    verschiebungB.add(sb == null ? 0.0 : verschiebung(s, sb, ab));
                    sondenZeigen(b, String.format(Locale.GERMAN, "%s: O %.0f, N %.0f, Grund %+.2f m NHN (%.1f s)", name, p[0], p[1], s.grund, s.sekunden));
                    protokoll.schreibe("Sonde", String.format(Locale.GERMAN, "%s bei O %.1f N %.1f, %d Stände, %.2f s", name, p[0], p[1], s.reihe(com.dan.lauf.Sonde.WASSERSTAND).t().length, s.sekunden));
                });
            } catch (Exception x) {
                SwingUtilities.invokeLater(() -> hf.hinweis("Sonde: " + x.getMessage(), Hinweis.Art.FEHLER));
            }
        }, "Sonde").start();
    }

    /**
     * Verschiebung des Vergleichs auf die Zeit des Szenarios: gleiche Uhrzeit (ganze Tage zwischen den ersten Ständen) oder
     * gleiches Hochwasser (das erste Hochwasser der Pegelreihe beider Sonden).
     */
    private double verschiebung(com.dan.lauf.Sonde a, com.dan.lauf.Sonde b, int art) {
        if (art == 1) {
            var ma = a.pegel.marken().stream().filter(x -> x.art() == com.dan.lauf.Pegelreihe.Art.HOCHWASSER).findFirst();
            var mb = b.pegel.marken().stream().filter(x -> x.art() == com.dan.lauf.Pegelreihe.Art.HOCHWASSER).findFirst();
            if (ma.isPresent() && mb.isPresent())
                return (a.start.toEpochMilli() / 1000.0 + ma.get().t()) - (b.start.toEpochMilli() / 1000.0 + mb.get().t());
        }
        double ta = a.reihen.get(0).t().length > 0 ? a.start.toEpochMilli() / 1000.0 + a.reihen.get(0).t()[0] : 0;
        double tb = b.reihen.get(0).t().length > 0 ? b.start.toEpochMilli() / 1000.0 + b.reihen.get(0).t()[0] : 0;
        long tage = Math.round((java.time.LocalDate.ofInstant(Instant.ofEpochSecond((long) ta), zone).toEpochDay()
                - java.time.LocalDate.ofInstant(Instant.ofEpochSecond((long) tb), zone).toEpochDay()));
        return tage * 86400.0;
    }

    private void sondenZeigen(Buehne b, String text) {
        List<SondenPaneel.Eintrag> l = new ArrayList<>();
        List<double[]> orte = new ArrayList<>();
        for (int i = 0; i < sonden.size(); i++) {
            com.dan.lauf.Sonde s = sonden.get(i);
            l.add(new SondenPaneel.Eintrag(s, sondenB.get(i), verschiebungB.get(i)));
            double h = b.gelaendeHoehe(s.ost, s.nord);
            orte.add(new double[] {s.ost, s.nord, Double.isNaN(h) ? 0 : h});
        }
        Buehne.Zustand z = b.zustand();
        sondenPaneel.sonden(l, z.start != null ? z.start : Instant.EPOCH, text);
        Einblendungen eb = ein;
        if (eb != null) eb.sonden(orte, List.of(SondenPaneel.FARBE));
    }

    private void sondenWeg() {
        sonden.clear();
        sondenB.clear();
        verschiebungB.clear();
        Buehne b = buehne;
        if (b != null) sondenZeigen(b, "Strg+Klick ins Bild setzt eine Sonde (bis 4).");
    }

    /** Wahl im Abschnitt Sonden: Vergleichsszenario (0 = ohne) und Ausrichtung; rechnet die Vergleiche neu. */
    private void vergleichWaehlen(int nr, int art) {
        List<Szenario> archive = szenen.szenarien().stream().filter(x -> x.archiv != null).toList();
        Szenario v = nr <= 0 || nr > archive.size() ? null : archive.get(nr - 1);
        vergleichSz = v;
        ausrichtung = art;
        Buehne b = buehne;
        if (b == null) return;
        List<com.dan.lauf.Sonde> a = new ArrayList<>(sonden);
        new Thread(() -> {
            List<com.dan.lauf.Sonde> nb = new ArrayList<>();
            List<Double> nv = new ArrayList<>();
            try {
                for (com.dan.lauf.Sonde s : a) {
                    com.dan.lauf.Sonde x = v == null ? null : com.dan.lauf.Sonde.ausArchiv(v, b.gebiet, s.name, s.ost, s.nord);
                    nb.add(x);
                    nv.add(x == null ? 0.0 : verschiebung(s, x, art));
                }
            } catch (Exception x) {
                SwingUtilities.invokeLater(() -> hf.hinweis("Vergleich: " + x.getMessage(), Hinweis.Art.FEHLER));
                return;
            }
            SwingUtilities.invokeLater(() -> {
                if (sonden.size() != a.size()) return;
                sondenB.clear();
                sondenB.addAll(nb);
                verschiebungB.clear();
                verschiebungB.addAll(nv);
                sondenZeigen(b, v == null ? null : "Vergleich: " + v.name + (art == 1 ? ", gleiches Hochwasser" : ", gleiche Uhrzeit"));
                if (v != null) protokoll.schreibe("Vergleich", v.name);
            });
        }, "Vergleich").start();
    }

    private void sondenCsv() {
        if (sonden.isEmpty()) {
            hf.hinweis("Keine Sonde gesetzt (Strg+Klick ins Bild).", Hinweis.Art.INFO);
            return;
        }
        File ordner = new File("docs" + File.separator + "sonden");
        ordner.mkdirs();
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        try {
            for (int i = 0; i < sonden.size(); i++) {
                java.nio.file.Files.writeString(new File(ordner, "Sonde_" + (i + 1) + "_" + stempel + ".csv").toPath(), sonden.get(i).csv());
                if (sondenB.get(i) != null)
                    java.nio.file.Files.writeString(new File(ordner, "Sonde_" + (i + 1) + "_Vergleich_" + stempel + ".csv").toPath(), sondenB.get(i).csv());
            }
            hf.hinweis(sonden.size() + " Sonden als CSV in " + ordner.getAbsolutePath(), Hinweis.Art.OK);
        } catch (java.io.IOException x) {
            hf.hinweis("CSV: " + x.getMessage(), Hinweis.Art.FEHLER);
        }
    }

    /** Das Vergleichsbild: die Ausgabe rechnet den Blick jetzt in beiden Szenarien (1080p), dann das Fenster mit Schieber. */
    private void vergleichsbild() {
        Buehne b = buehne;
        Szenario s = szenarioJetzt, v = vergleichSz;
        if (b == null || s == null || b.zustand().jetzt == null) return;
        if (v == null) {
            hf.hinweis("Erst im Abschnitt Sonden ein Vergleichsszenario wählen.", Hinweis.Art.INFO);
            return;
        }
        Buehne.Zustand z = b.zustand();
        var bl = z.blick;
        String blick = s.schluessel + ";" + z.jetzt + ";" + String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f %.4f", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel);
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File ziel = new File(manifest.fotos(), "Vergleich_" + stempel + ".png");
        File za = new File(manifest.fotos(), "Vergleich_" + stempel + "_A.png"), zb = new File(manifest.fotos(), "Vergleich_" + stempel + "_B.png");
        starteAusgabe(new ArrayList<>(List.of("--standbild", "--groesse", "1080p", "--blick", blick, "--vergleich", v.schluessel, "--ausrichten", ausrichtung == 1 ? "hochwasser" : "uhrzeit",
                "--ziel", ziel.getPath())), "Vergleichsbild", () -> {
            try {
                new Vergleichsfenster(hf.fenster, za, s.name, zb, v.name).setVisible(true);
            } catch (java.io.IOException x) {
                hf.hinweis("Vergleichsbild: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        });
    }

    /** Datei › Ort der Daten: ein Ordner; gilt nach dem nächsten Start (${daten} in der Szenendatei). */
    private void datenOrt() {
        javax.swing.JFileChooser w = new javax.swing.JFileChooser(einstellungen.text("daten", szenen.text("daten", "")));
        w.setFileSelectionMode(javax.swing.JFileChooser.DIRECTORIES_ONLY);
        w.setDialogTitle("Ort der Daten (Gebiet und Archive)");
        if (w.showOpenDialog(hf.fenster) != javax.swing.JFileChooser.APPROVE_OPTION) return;
        einstellungen.setze("daten", w.getSelectedFile().getAbsolutePath());
        einstellungen.speichern();
        protokoll.schreibe("Daten", w.getSelectedFile().getAbsolutePath());
        hf.hinweis("Ort der Daten: " + w.getSelectedFile().getAbsolutePath() + " – gilt nach einem Neustart", Hinweis.Art.OK);
    }

    // ------------------------------------------------------------------ Einblendungen (W2)

    /** Legt die Einblendungen an (Faden der Bühne) und stellt sie nach Arbeitsbereich und Einstellungen ein. */
    private void einblendungenAnlegen(Buehne b) {
        String[] meldung = new String[1];
        double mass = manifest.text("einblendung.mass", "").isBlank() ? Math.max(1, hf.leinwand.getGraphicsConfiguration().getDefaultTransform().getScaleX())
                : Double.parseDouble(manifest.text("einblendung.mass", "1"));
        try {
            ein = new Einblendungen(b, manifest, zone, pegel, marke, markeName, mass, meldung);
            System.out.println("Steuerhaus: Einblendungen, Maß " + mass + "; " + meldung[0]);
        } catch (RuntimeException x) {
            System.out.println("Steuerhaus: Einblendungen nicht angelegt: " + x);
            x.printStackTrace();
        }
        SwingUtilities.invokeLater(this::einblendungenAnwenden);
    }

    private String bereichJetzt() { return hf != null ? hf.arbeitsbereich() : manifest.arbeitsbereich(); }

    /** Die Teile eines Arbeitsbereichs: aus den Einstellungen, sonst die Vorgabe. */
    Set<Einblendungen.Teil> teile(String bereich) {
        String v = einstellungen.text("einblendung." + bereich, null);
        if (v == null) return Einblendungen.vorgabe(bereich);
        Set<Einblendungen.Teil> r = java.util.EnumSet.noneOf(Einblendungen.Teil.class);
        for (String k : v.split(",")) {
            Einblendungen.Teil t = Einblendungen.Teil.von(k.strip());
            if (t != null) r.add(t);
        }
        return r;
    }

    private void einblendungUmschalten(Einblendungen.Teil t) {
        String bereich = bereichJetzt();
        Set<Einblendungen.Teil> l = java.util.EnumSet.noneOf(Einblendungen.Teil.class);
        l.addAll(teile(bereich));
        if (!l.remove(t)) l.add(t);
        StringBuilder sb = new StringBuilder();
        for (Einblendungen.Teil x : l) sb.append(sb.length() == 0 ? "" : ",").append(x.kennung);
        einstellungen.setze("einblendung." + bereich, sb.toString());
        if (!einstellungen.wahr("einblendung.alle", true)) einstellungen.setze("einblendung.alle", true);
        einblendungenAnwenden();
    }

    private void einblendungenAlle() {
        einstellungen.setze("einblendung.alle", !einstellungen.wahr("einblendung.alle", true));
        einblendungenAnwenden();
    }

    /** Wirksam: alles aus (F6), im Kinoblick nur die Ortsnamen, sonst die Teile des Arbeitsbereichs. */
    void einblendungenAnwenden() {
        Einblendungen eb = ein;
        if (eb == null) return;
        Set<Einblendungen.Teil> w;
        if (kinoAktiv || kapitelAktiv) w = regieEinblendung;
        else if (!einstellungen.wahr("einblendung.alle", true)) w = Set.of();
        else if (hf != null && hf.imKino()) w = Set.of(Einblendungen.Teil.ORTE);
        else w = teile(bereichJetzt());
        eb.setze(w);
    }

    // ------------------------------------------------------------------ Regie (W3)

    /** Der Regisseur: Kamera-Quelle der Bühne und Ausführender der Regiebücher (Faden der Bühne). */
    private void regisseurAnlegen(Buehne b) {
        Regisseur r = new Regisseur(b, new Regisseur.App() {
            @Override public void szenario(String k) {
                Szenario s = szenen.szenario(k);
                if (s != null && s == szenarioJetzt) return;                // schon offen: nicht neu laden
                if (s != null) waehle(b, s);
                else hinweisSpaeter("Regie: Szenario „" + k + "“ gibt es nicht");
            }

            @Override public void ansicht(String k) {
                Ansicht a = szenen.ansicht(k);
                if (a == null) {
                    hinweisSpaeter("Regie: Ansicht „" + k + "“ gibt es nicht");
                    return;
                }
                ansichtJetzt = a;
                b.ansicht(a);
                SwingUtilities.invokeLater(() -> katalog.zeige(null, a));
            }

            @Override public Fahrt fahrt(String name) {
                Fahrt f = fahrten.get(name);
                return f == null ? null : f.aufgeloest(b::gelaendeHoehe);
            }

            @Override public void raffer(double x) {
                b.zeitraffer(x);
                SwingUtilities.invokeLater(() -> bedienung.zeigeRaffer(x));
            }

            @Override public void halt(boolean h) { SwingUtilities.invokeLater(() -> bedienung.richtung(h ? Bedienung.HALT : Bedienung.VOR)); }

            @Override public void ebene(String name, boolean an) {
                Bedienung.Schalter k = switch (name) {
                    case "häuser", "haeuser" -> bedienung.haeuser;
                    case "gestalt" -> bedienung.gestalt;
                    case "bewuchs" -> bedienung.bewuchs;
                    case "salzwiese" -> bedienung.salzwiese;
                    default -> bedienung.brandung;
                };
                SwingUtilities.invokeLater(() -> {
                    if (k.an() != an) k.umschalten();
                });
            }

            @Override public void messblick(String arg) {
                Bedienung.Messwahl alt = bedienung.messwahl();
                boolean aus = arg.equals("aus");
                Bedienung.Messwahl m = new Bedienung.Messwahl(!aus && arg.contains("partikel"), !aus && arg.contains("lic"), !aus && arg.contains("linien"), alt.art());
                Szenenapp.this.messblick(b, m);
                SwingUtilities.invokeLater(() -> bedienung.zeigeMesswahl(m));
            }

            @Override public void linien(String art) {
                int a = switch (art.toLowerCase(Locale.GERMAN)) {
                    case "tiefe" -> 1;
                    case "trübung", "truebung" -> 2;
                    default -> 0;
                };
                Bedienung.Messwahl alt = bedienung.messwahl(), m = new Bedienung.Messwahl(alt.partikel(), alt.lic(), alt.linien(), a);
                Szenenapp.this.messblick(b, m);
                SwingUtilities.invokeLater(() -> bedienung.zeigeMesswahl(m));
            }

            @Override public void einblendung(String arg) {
                Set<Einblendungen.Teil> w = java.util.EnumSet.noneOf(Einblendungen.Teil.class);
                if (arg.equals("alle")) w = java.util.EnumSet.allOf(Einblendungen.Teil.class);
                else if (!arg.equals("aus"))
                    for (String t : arg.split(",")) {
                        Einblendungen.Teil x = Einblendungen.Teil.von(t.strip());
                        if (x != null) w.add(x);
                    }
                regieEinblendung = w;
                SwingUtilities.invokeLater(Szenenapp.this::einblendungenAnwenden);
            }

            @Override public void thema(String name) { SwingUtilities.invokeLater(() -> hf.thema(Thema.zu(name))); }

            @Override public void sonde(String arg, java.util.function.Consumer<com.dan.lauf.Sonde> fertig) {
                String[] t = arg.strip().split("\\s+", 3);
                double ost = Double.parseDouble(t[0].replace(',', '.')), nord = Double.parseDouble(t[1].replace(',', '.'));
                String name = t.length > 2 ? t[2] : "Sonde";
                Thread th = new Thread(() -> {
                    try {
                        for (int i = 0; i < 600 && b.wechselt(); i++) Thread.sleep(50);
                        fertig.accept(b.sonde(name, ost, nord));
                    } catch (Exception x) {
                        hinweisSpaeter("Kapitel: Sonde „" + name + "“: " + x.getMessage());
                    }
                }, "Kapitel, Sonde");
                th.setDaemon(true);
                th.start();
            }

            @Override public void kapitelEnde() {
                SwingUtilities.invokeLater(() -> {
                    kapitelAktiv = false;
                    regieEinblendung = Set.of();
                    hf.kinoblick(false);
                    einblendungenAnwenden();
                    if (seite != null) startseiteZeigen();
                });
            }

            @Override public void lesezeichen(String name) {
                for (int k = 0; k < lesezeichen.size(); k++)
                    if (lesezeichen.get(k).name().equals(name)) {
                        int nr = k;
                        SwingUtilities.invokeLater(() -> lesezeichenWaehlen(nr));
                        return;
                    }
                hinweisSpaeter("Regie: Lesezeichen „" + name + "“ gibt es nicht");
            }

            @Override public void hinweis(String text) { hinweisSpaeter(text); }

            @Override public double marke() { return marke; }

            @Override public ZoneId zone() { return zone; }

            @Override public String kopf() { return manifest.titel() + (manifest.unterzeile().isEmpty() ? "" : " · " + manifest.unterzeile()); }

            @Override public List<String> abspann() { return Regisseur.abspann(manifest); }

            @Override public void balken(int px) {
                Einblendungen eb = ein;
                if (eb != null) eb.rand(px);
            }

            @Override public void kinoEnde() {
                SwingUtilities.invokeLater(() -> {
                    kinoAktiv = false;
                    regieEinblendung = Set.of();
                    hf.kinoblick(false);
                    einblendungenAnwenden();
                    hf.leinwand.setCursor(java.awt.Cursor.getDefaultCursor());
                });
            }
        });
        regisseur = r;
        b.kamera(r);
        if (!regieFehler.isEmpty()) {
            for (String f : regieFehler) System.out.println("Steuerhaus: Regie: " + f);
            hinweisSpaeter("Regie: " + regieFehler.size() + " Fehler beim Lesen, erster: " + regieFehler.get(0));
        }
        System.out.println("Steuerhaus: Regie: " + buecher.size() + " Regiebücher " + buecher.keySet() + ", " + fahrten.size() + " Fahrten, " + lesezeichen.size() + " Lesezeichen");
    }

    private void hinweisSpaeter(String text) { SwingUtilities.invokeLater(() -> hf.hinweis(text, Hinweis.Art.INFO)); }

    /** Ein Regiebuch im Kino: nur das Bild, die Einblendungen des Regiebuchs. */
    void kinoStarten(Regiebuch rb) {
        Regisseur r = regisseur;
        if (r == null) return;
        kinoAktiv = true;
        regieEinblendung = Set.of();
        hf.kinoblick(true);
        einblendungenAnwenden();
        hf.leinwand.setCursor(java.awt.Toolkit.getDefaultToolkit().createCustomCursor(new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB),
                new java.awt.Point(), "leer"));
        hf.leinwand.requestFocus();
        r.spiele(rb, true);
    }

    /** Seit X2: ein Kapitel – nur das Bild, die Tafeln unten links; Klick oder Eingabe weiter, Rücktaste zurück, Esc Ende. */
    void kapitelStarten(Regiebuch rb) {
        Regisseur r = regisseur;
        if (r == null) return;
        kapitelAktiv = true;
        regieEinblendung = Set.of();
        hf.kinoblick(true);
        einblendungenAnwenden();
        hf.leinwand.requestFocus();
        r.spieleKapitel(rb);
        protokoll.schreibe("Kapitel", rb.name);
    }

    /** Für die Abnahme: ein Kapitel (seit X3 auch ein Rundgang) nach dem ersten Bild; mit {@code probe} alle 12 s weiter. */
    private void kapitelNachStart(Buehne b, String name, boolean probe, Map<String, Regiebuch> buecherDerArt, String art, String endung) {
        Regiebuch rb = buecherDerArt.get(name);
        if (rb == null)
            for (Regiebuch x : buecherDerArt.values()) if (x.quelle.endsWith(name) || x.quelle.endsWith(name + endung)) rb = x;
        if (rb == null) {
            System.out.println("Steuerhaus: " + art + " „" + name + "“ gibt es nicht; bekannt: " + buecherDerArt.keySet());
            return;
        }
        Regiebuch buch = rb;
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> kapitelStarten(buch));
                System.out.println("Steuerhaus: " + art + " „" + buch.name + "“ gestartet");
                for (int k = 1; probe && k < 40 && kapitelAktiv; k++) {
                    Thread.sleep(12_000);
                    Regisseur r = regisseur;
                    if (r == null || !r.imKapitel()) break;
                    r.weiterKlick();
                    var bl = b.zustand().blick;
                    System.out.printf(Locale.GERMAN, "Steuerhaus: %s: weiter (%d), %.1f B/s%s%n", art, k, b.zustand().bilderJeS,
                            bl == null ? "" : String.format(Locale.GERMAN, ", Blick %.0f %.0f %.1f m NHN, %.0f°", bl.ost, bl.nord, bl.hoehe, bl.richtung));
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Kapitel");
        t.setDaemon(true);
        t.start();
    }

    private void kapitelWeiter() {
        if (seite != null && seite.sichtbar()) {                            // X2b: Eingabe auf der Startseite = zur Szene
            ausStartseite(null);
            return;
        }
        Regisseur r = regisseur;
        if (r != null && r.imKapitel()) r.weiterKlick();
    }

    private void kapitelZurueck() {
        Regisseur r = regisseur;
        if (r != null && r.imKapitel()) r.zurueck();
    }

    private void regieAbbrechen() {
        if (seite != null && seite.sichtbar()) {                            // X2b: Esc auf der Startseite = zur Szene
            ausStartseite(null);
            return;
        }
        Regisseur r = regisseur;
        if (r != null) r.abbrechen();
        if (kapitelAktiv) {
            kapitelAktiv = false;
            regieEinblendung = Set.of();
            einblendungenAnwenden();
        }
        if (!kinoAktiv && hf.imKino()) hf.kinoblick(false);
    }

    /** Für die Abnahme: das Regiebuch nach dem ersten Bild im Kino abspielen. */
    private void regieNachStart(Buehne b, String name) {
        Regiebuch rb = buecher.get(name);
        if (rb == null)
            for (Regiebuch x : buecher.values()) if (x.quelle.endsWith(name) || x.quelle.endsWith(name + ".regie")) rb = x;
        if (rb == null) {
            System.out.println("Steuerhaus: Regiebuch „" + name + "“ gibt es nicht; bekannt: " + buecher.keySet());
            return;
        }
        Regiebuch buch = rb;
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                SwingUtilities.invokeAndWait(() -> kinoStarten(buch));
                System.out.println("Steuerhaus: Regiebuch „" + buch.name + "“ im Kino gestartet");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Regie");
        t.setDaemon(true);
        t.start();
    }

    private void fahrtBefehle() {
        int i = 0;
        for (Fahrt f : fahrten.values()) {
            String id = "regie.fahrt." + (++i);
            if (befehle.finde(id) != null) continue;
            befehle.neu(id, "Regie/Fahrten", f.name, null, () -> {
                Regisseur r = regisseur;
                Buehne b = buehne;
                if (r != null && b != null) r.fahre(f.aufgeloest(b::gelaendeHoehe));
            });
        }
    }

    private void lesezeichenBefehle() {
        for (int i = 0; i < lesezeichen.size(); i++) {
            String id = "lesezeichen." + (i + 1);
            if (befehle.finde(id) != null) continue;
            int nr = i;
            befehle.neu(id, "Szene/Lesezeichen", lesezeichen.get(i).name(), null, () -> lesezeichenWaehlen(nr));
        }
    }

    /** B: Szenario, Zeit und Blick jetzt als Lesezeichen im Projekt. */
    private void lesezeichenSetzen() {
        Buehne b = buehne;
        Szenario s = szenarioJetzt;
        if (b == null || s == null) return;
        Buehne.Zustand z = b.zustand();
        if (z.jetzt == null || z.blick == null) return;
        String name = s.name + " · " + java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm", Locale.GERMAN).withZone(zone).format(z.jetzt);
        double[] bl = {rund(z.blick.ost), rund(z.blick.nord), rund(z.blick.hoehe), rund(z.blick.richtung), rund(z.blick.neigung), rund(z.blick.bildwinkel)};
        lesezeichen.add(new Lesezeichen(name, s.schluessel, z.jetzt.truncatedTo(java.time.temporal.ChronoUnit.SECONDS), bl));
        lesezeichenSchreiben();
        lesezeichenBefehle();
        hf.menue.bauen();
        hf.hinweis("Lesezeichen gesetzt: " + name, Hinweis.Art.OK);
    }

    private static double rund(double v) { return Math.round(v * 100) / 100.0; }

    private void lesezeichenSchreiben() {
        try {
            Lesezeichen.schreibe(lesezeichenDatei, lesezeichen);
        } catch (java.io.IOException x) {
            hf.hinweis("Lesezeichen nicht gespeichert: " + x.getMessage(), Hinweis.Art.FEHLER);
        }
        lzListe.zeige(lesezeichen);
    }

    /** Stellt Szenario, Zeit und Blick eines Lesezeichens wieder her. */
    void lesezeichenWaehlen(int nr) {
        Buehne b = buehne;
        Regisseur r = regisseur;
        if (b == null || r == null || nr < 0 || nr >= lesezeichen.size()) return;
        Lesezeichen lz = lesezeichen.get(nr);
        Szenario s = szenen.szenario(lz.szenario());
        if (s == null) {
            hf.hinweis("Lesezeichen: Szenario „" + lz.szenario() + "“ gibt es nicht", Hinweis.Art.FEHLER);
            return;
        }
        Thread t = new Thread(() -> {
            try {
                if (szenarioJetzt != s) {
                    waehle(b, s);
                    Thread.sleep(200);
                    for (int i = 0; i < 1200 && (b.wechselt() || b.uhr() == null); i++) Thread.sleep(50);
                }
                if (b.uhr() == null) return;
                b.zeit((lz.zeit().toEpochMilli() - b.uhr().start.toEpochMilli()) / 1000.0);
                double[] v = lz.blick();
                r.fahre(new Fahrt("Lesezeichen", List.of(new Fahrt.Schluesselbild(0, v[0], v[1], v[2], false, v[3], v[4], v[5]))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Lesezeichen");
        t.setDaemon(true);
        t.start();
    }

    /** K: der Blick jetzt als nächstes Schlüsselbild; die Zeit bis dahin nach Weg und Drehung [G]. */
    private void schluesselbild() {
        Buehne b = buehne;
        if (b == null || b.zustand().blick == null) return;
        var bl = b.zustand().blick;
        double[] neu = {bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel};
        double t = 0;
        if (!aufnahme.isEmpty()) {
            Fahrt.Schluesselbild l = aufnahme.get(aufnahme.size() - 1);
            t = l.t() + Fahrt.dauer(new double[] {l.ost(), l.nord(), l.hoehe(), l.richtung(), l.neigung(), l.bildwinkel()}, neu);
            t = Math.round(t * 10) / 10.0;
        }
        aufnahme.add(new Fahrt.Schluesselbild(t, rund(neu[0]), rund(neu[1]), rund(neu[2]), false, rund(neu[3]), rund(neu[4]), rund(neu[5])));
        hf.hinweis("Schlüsselbild " + aufnahme.size() + " bei " + Regiebuch.zeit(t), Hinweis.Art.INFO);
    }

    private void schluesselbildWeg() {
        if (!aufnahme.isEmpty()) aufnahme.remove(aufnahme.size() - 1);
        hf.hinweis("Aufnahme: " + aufnahme.size() + " Schlüsselbilder", Hinweis.Art.INFO);
    }

    private void aufnahmeAbspielen() {
        Regisseur r = regisseur;
        if (r == null) return;
        if (aufnahme.size() < 2) {
            hf.hinweis("Aufnahme: mindestens zwei Schlüsselbilder (K)", Hinweis.Art.INFO);
            return;
        }
        r.fahre(new Fahrt("Aufnahme", aufnahme));
    }

    private void aufnahmeVerwerfen() {
        aufnahme.clear();
        hf.hinweis("Aufnahme verworfen", Hinweis.Art.INFO);
    }

    /** Strg+S: die Aufnahme als Fahrt in {@code regie/aufnahmen.regie} (im Projekt). */
    private void aufnahmeSpeichern() {
        if (aufnahme.size() < 2) {
            hf.hinweis("Aufnahme: mindestens zwei Schlüsselbilder (K)", Hinweis.Art.INFO);
            return;
        }
        String name = "Fahrt " + java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.GERMAN).format(java.time.LocalDateTime.now());
        try {
            Map<String, Fahrt> alle = new LinkedHashMap<>();
            if (aufnahmenDatei.isFile()) alle.putAll(Regiebuch.lies(aufnahmenDatei.toPath()).fahrten);
            alle.put(name, new Fahrt(name, aufnahme));
            Regiebuch rb = Regiebuch.aus("Aufnahmen", "Fahrten aus der App (Steuerhaus W3)", 0, List.of(), alle);
            aufnahmenDatei.getParentFile().mkdirs();
            java.nio.file.Files.writeString(aufnahmenDatei.toPath(), "# Aufnahmen aus der App (Steuerhaus W3); Format wie jedes Regiebuch\n" + rb.text(),
                    java.nio.charset.StandardCharsets.UTF_8);
            fahrten.put(name, new Fahrt(name, aufnahme));
            aufnahme.clear();
            fahrtBefehle();
            hf.menue.bauen();
            hf.hinweis("Fahrt gespeichert: " + name + " in " + aufnahmenDatei.getName(), Hinweis.Art.OK);
        } catch (Exception x) {
            hf.hinweis("Fahrt nicht gespeichert: " + x.getMessage(), Hinweis.Art.FEHLER);
        }
    }

    /** Klick ins Bild: auf die Minikarte (Sprung über den Ort) oder auf einen Ortsnamen (Flug, F197 A). */
    private void karteOderOrt(Buehne b, MouseEvent e) {
        Einblendungen eb = ein;
        Regisseur r = regisseur;
        if (eb == null || r == null || kinoAktiv) return;
        double s = hf.leinwand.getGraphicsConfiguration().getDefaultTransform().getScaleX();
        double px = e.getX() * s, py = e.getY() * s;
        var jetzt = b.zustand().blick;
        if (jetzt == null) return;
        double[] o = eb.karteOrt(px, py);
        if (o != null) {
            double boden = b.gelaendeHoehe(o[0], o[1]);
            double h = 250, neig = -35, abst = (h - (Double.isNaN(boden) ? 0 : boden)) / Math.tan(Math.toRadians(-neig)), a = Math.toRadians(jetzt.richtung);
            r.fahre(new Fahrt("Minikarte", List.of(new Fahrt.Schluesselbild(0, o[0] - Math.sin(a) * abst, o[1] - Math.cos(a) * abst, h, false, jetzt.richtung, neig, jetzt.bildwinkel))));
            return;
        }
        Object[] ort = eb.ortBei(px, py);
        if (ort != null) {
            double[] p = (double[]) ort[0];
            double dx = p[0] - jetzt.ost, dy = p[1] - jetzt.nord, d = Math.hypot(dx, dy);
            double richtung = d < 1 ? jetzt.richtung : (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360, a = Math.toRadians(richtung);
            double abst = 350, hoch = 87;
            r.fliege(new double[] {p[0] - Math.sin(a) * abst, p[1] - Math.cos(a) * abst, p[2] + hoch, richtung, -Math.toDegrees(Math.atan2(hoch, abst)), jetzt.bildwinkel}, 3);
            hf.hinweis("Flug nach " + ort[1], Hinweis.Art.INFO);
        }
    }

    // ------------------------------------------------------------------ Ausgabe (W4)

    private volatile Process ausgabe;
    private volatile String ausgabeStand;

    /** Datei › Video ausgeben: Regiebuch, Größe, Unschärfe, Korn, Einblendungen; dann ein eigener Prozess. */
    private void videoDialog() {
        if (ausgabe != null && ausgabe.isAlive()) {
            hf.hinweis("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)", Hinweis.Art.INFO);
            return;
        }
        if (buecher.isEmpty()) {
            hf.hinweis("Kein Regiebuch im Manifest (regie.N)", Hinweis.Art.FEHLER);
            return;
        }
        javax.swing.JComboBox<String> buch = new javax.swing.JComboBox<>(buecher.keySet().toArray(String[]::new));
        javax.swing.JComboBox<String> groesse = new javax.swing.JComboBox<>(new String[] {"1920 × 1080", "3840 × 2160 (4K)"});
        javax.swing.JCheckBox unschaerfe = new javax.swing.JCheckBox("Bewegungsunschärfe (8 Teilbilder, Verschluss 180°)", true);
        javax.swing.JCheckBox korn = new javax.swing.JCheckBox("Filmkorn (Vision3 250D)", manifest.korn() > 0);
        javax.swing.JCheckBox einbl = new javax.swing.JCheckBox("Einblendungen wie im Regiebuch", true);
        javax.swing.JCheckBox mitTon = new javax.swing.JCheckBox("Ton (Wind, Brandung, Möwen)", true);
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 6));
        p.add(new javax.swing.JLabel("Regiebuch"));
        p.add(buch);
        p.add(new javax.swing.JLabel("Größe (24 Bilder/s)"));
        p.add(groesse);
        p.add(unschaerfe);
        p.add(korn);
        p.add(einbl);
        p.add(mitTon);
        p.add(new javax.swing.JLabel("Ablage: " + manifest.videos().getAbsolutePath()));
        int r = javax.swing.JOptionPane.showConfirmDialog(hf.fenster, p, "Video ausgeben", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (r != javax.swing.JOptionPane.OK_OPTION) return;
        String name = (String) buch.getSelectedItem();
        List<String> a = new ArrayList<>(List.of("--regie", name, "--groesse", groesse.getSelectedIndex() == 1 ? "4k" : "1080p"));
        if (!unschaerfe.isSelected()) a.addAll(List.of("--teilbilder", "1", "--verschluss", "360"));
        if (!korn.isSelected()) a.add("--ohne-korn");
        if (!einbl.isSelected()) a.add("--ohne-einblendung");
        if (!mitTon.isSelected()) a.add("--ohne-ton");
        starteAusgabe(a, "Video „" + name + "“");
    }

    /** Datei › Standbild 4K: Szenario, Zeit und Blick jetzt. */
    private void standbild4k() {
        Buehne b = buehne;
        Szenario s = szenarioJetzt;
        if (b == null || s == null || b.zustand().jetzt == null) return;
        if (ausgabe != null && ausgabe.isAlive()) {
            hf.hinweis("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)", Hinweis.Art.INFO);
            return;
        }
        Buehne.Zustand z = b.zustand();
        var bl = z.blick;
        String blick = s.schluessel + ";" + z.jetzt + ";" + String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f %.4f", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel);
        starteAusgabe(new ArrayList<>(List.of("--standbild", "--groesse", "4k", "--blick", blick)), "Standbild 4K");
    }

    /**
     * Seit X4 (Werkbuch HalligHooge 150): Datei › Postkarte – Format, Vorlage und die zwei Zeilen der Unterschrift, gefüllt
     * aus dem Zustand jetzt (Ort, Datum, Uhrzeit, Pegel, Wind); die Vorschau zeigt Rahmen und Schrift mit dem Vorschaubild
     * der Ansicht. Die Ausgabe rechnet das Bild, danach öffnet sich die Datei.
     */
    private void postkarteDialog() {
        Buehne b = buehne;
        Szenario s = szenarioJetzt;
        if (b == null || s == null || b.zustand().jetzt == null) return;
        if (ausgabe != null && ausgabe.isAlive()) {
            hf.hinweis("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)", Hinweis.Art.INFO);
            return;
        }
        if (s.live()) {
            hf.hinweis("Postkarte: nur aus einem Szenario mit Archiv, nicht live", Hinweis.Art.INFO);
            return;
        }
        Buehne.Zustand z = b.zustand();
        if (postkartenOrte == null)
            try {
                postkartenOrte = Orte.lies(manifest, szenen.gebiet(), null);
            } catch (java.io.IOException x) {
                postkartenOrte = List.of();
            }
        List<String> vf = new ArrayList<>();
        List<com.dan.steuerhaus.ausgabe.Postkarte.Vorlage> vl = com.dan.steuerhaus.ausgabe.Postkarte.vorlagen(manifest, vf);
        for (String x : vf) hf.hinweis("Postkarte: " + x, Hinweis.Art.FEHLER);
        Map<String, String> werte = com.dan.steuerhaus.ausgabe.Postkarte.werte(manifest, pegel, z, ansichtJetzt != null ? ansichtJetzt.name : null, postkartenOrte, zone);
        javax.swing.JComboBox<com.dan.steuerhaus.ausgabe.Postkarte.Format> format = new javax.swing.JComboBox<>(com.dan.steuerhaus.ausgabe.Postkarte.Format.values());
        javax.swing.JComboBox<com.dan.steuerhaus.ausgabe.Postkarte.Vorlage> vorlage = new javax.swing.JComboBox<>(vl.toArray(com.dan.steuerhaus.ausgabe.Postkarte.Vorlage[]::new));
        String fw = einstellungen.text("postkarte.format", "a6"), vw = einstellungen.text("postkarte.vorlage", "");
        for (var f : com.dan.steuerhaus.ausgabe.Postkarte.Format.values()) if (f.schluessel.equals(fw)) format.setSelectedItem(f);
        for (var v : vl) if (v.name().equals(vw)) vorlage.setSelectedItem(v);
        javax.swing.JTextField titel = new javax.swing.JTextField(40), zeile = new javax.swing.JTextField(40);
        javax.swing.JLabel vorschau = new javax.swing.JLabel();
        java.awt.image.BufferedImage platz = null;
        File vo = Vorschau.ordner(szenen);
        for (File f : new File[] {ansichtJetzt != null ? new File(vo, "ansicht_" + ansichtJetzt.schluessel + ".png") : null, new File(vo, "szenario_" + s.schluessel + ".png")})
            if (platz == null && f != null && f.isFile())
                try {
                    platz = javax.imageio.ImageIO.read(f);
                } catch (java.io.IOException x) {
                    // ohne Bild: der Verlauf
                }
        java.awt.image.BufferedImage logo = null;
        if (!manifest.text("app.icon", "").isEmpty())
            try {
                logo = javax.imageio.ImageIO.read(manifest.relativ(manifest.text("app.icon", "")));
            } catch (java.io.IOException x) {
                // ohne Symbol
            }
        java.awt.image.BufferedImage platzBild = platz, logoBild = logo;
        Runnable zeichne = () -> {
            var f = (com.dan.steuerhaus.ausgabe.Postkarte.Format) format.getSelectedItem();
            var v = (com.dan.steuerhaus.ausgabe.Postkarte.Vorlage) vorlage.getSelectedItem();
            double k = 460.0 / Math.max(f.breite, f.hoehe);
            int w = (int) Math.round(f.breite * k), h = (int) Math.round(f.hoehe * k);
            java.awt.Rectangle r = com.dan.steuerhaus.ausgabe.Postkarte.bildflaeche(w, h, v.stil());
            java.awt.image.BufferedImage foto = null;
            if (platzBild != null) {
                // das Vorschaubild auf die Bildfläche zugeschnitten (die Mitte)
                double q = Math.max((double) r.width / platzBild.getWidth(), (double) r.height / platzBild.getHeight());
                int cw = (int) Math.round(r.width / q), ch = (int) Math.round(r.height / q);
                foto = platzBild.getSubimage(Math.max(0, (platzBild.getWidth() - cw) / 2), Math.max(0, (platzBild.getHeight() - ch) / 2), Math.min(cw, platzBild.getWidth()),
                        Math.min(ch, platzBild.getHeight()));
            }
            vorschau.setIcon(new javax.swing.ImageIcon(com.dan.steuerhaus.ausgabe.Postkarte.gestalte(foto, w, h, v.stil(), titel.getText(), zeile.getText(), logoBild)));
        };
        Runnable fuellen = () -> {
            var v = (com.dan.steuerhaus.ausgabe.Postkarte.Vorlage) vorlage.getSelectedItem();
            titel.setText(com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.titel(), werte));
            zeile.setText(com.dan.steuerhaus.ausgabe.Postkarte.fuelle(v.zeile(), werte));
            zeichne.run();
        };
        vorlage.addActionListener(e -> fuellen.run());
        format.addActionListener(e -> zeichne.run());
        javax.swing.event.DocumentListener dl = new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { zeichne.run(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { zeichne.run(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { zeichne.run(); }
        };
        fuellen.run();
        titel.getDocument().addDocumentListener(dl);
        zeile.getDocument().addDocumentListener(dl);
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.BorderLayout(0, 10));
        javax.swing.JPanel felder = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        felder.add(new javax.swing.JLabel("Format"));
        felder.add(format);
        felder.add(new javax.swing.JLabel("Vorlage"));
        felder.add(vorlage);
        felder.add(new javax.swing.JLabel("Unterschrift"));
        felder.add(titel);
        felder.add(zeile);
        p.add(felder, java.awt.BorderLayout.NORTH);
        p.add(vorschau, java.awt.BorderLayout.CENTER);
        p.add(new javax.swing.JLabel("<html>Vorschau mit dem Vorschaubild der Ansicht. Die Ausgabe rechnet den Blick jetzt in die Bildfläche (ohne Einblendungen)<br>und legt die Karte nach "
                + manifest.fotos().getAbsolutePath() + "</html>"), java.awt.BorderLayout.SOUTH);
        int antwort = javax.swing.JOptionPane.showConfirmDialog(hf.fenster, p, "Postkarte", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (antwort != javax.swing.JOptionPane.OK_OPTION) return;
        var f = (com.dan.steuerhaus.ausgabe.Postkarte.Format) format.getSelectedItem();
        var v = (com.dan.steuerhaus.ausgabe.Postkarte.Vorlage) vorlage.getSelectedItem();
        einstellungen.setze("postkarte.format", f.schluessel);
        einstellungen.setze("postkarte.vorlage", v.name());
        var bl = z.blick;
        String blick = s.schluessel + ";" + z.jetzt + ";" + String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f %.4f", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel);
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File ziel = new File(manifest.fotos(), "Postkarte_" + stempel + "_" + f.schluessel + ".png");
        starteAusgabe(new ArrayList<>(List.of("--postkarte", f.schluessel, "--vorlage", v.name(), "--zeilen", titel.getText().replace("|", "/") + "|" + zeile.getText().replace("|", "/"),
                "--blick", blick, "--ziel", ziel.getPath())), "Postkarte", () -> {
            try {
                java.awt.Desktop.getDesktop().open(ziel);
            } catch (Exception x) {
                hf.hinweis("Postkarte: " + ziel, Hinweis.Art.OK);
            }
        });
    }

    private List<Orte.Ort> postkartenOrte;

    // ------------------------------------------------------------------ Vergleich (Y1, Werkbuch HalligHooge 152)

    private List<com.dan.steuerhaus.vergleich.Vergleich> vergleiche = List.of();
    private com.dan.steuerhaus.vergleich.Spieler spieler;
    /** Größe der Bilder eines Vergleichs aus der Datei [G] (die Kennung der Mappe hängt daran). */
    static final int VERGLEICH_B = 1920, VERGLEICH_H = 1080;
    /** Grobe Kosten je Bild (s) für die Ansage vor dem Rechnen [G]; nach dem ersten Lauf aus der Mappe. */
    private double vergleichJeBild = 1.2;

    /** „Sturmflut, 25.10.2026“ → „Sturmflut“ (für die Kachel). */
    static String kurz(String name) {
        int p = name.indexOf(',');
        return p > 0 ? name.substring(0, p) : name;
    }

    /** Die gerechnete Mappe eines Vergleichs, wenn sie fertig ist und zur Beschreibung passt; sonst null. */
    com.dan.steuerhaus.vergleich.Mappe mappe(com.dan.steuerhaus.vergleich.Vergleich v) {
        File d = new File(manifest.vergleiche(), v.schluessel());
        if (!new File(d, "vergleich.txt").isFile()) return null;
        try {
            com.dan.steuerhaus.vergleich.Mappe m = com.dan.steuerhaus.vergleich.Mappe.lies(d);
            return m.fertig && m.kennung.equals(v.kennung(VERGLEICH_B, VERGLEICH_H)) ? m : null;
        } catch (java.io.IOException | RuntimeException x) {
            return null;
        }
    }

    /** Kachel und Menü: zeigt den Vergleich; ist er noch nicht gerechnet, fragt die App und rechnet ihn mit der Ausgabe. */
    void vergleichZeigen(com.dan.steuerhaus.vergleich.Vergleich v) {
        com.dan.steuerhaus.vergleich.Mappe m = mappe(v);
        if (m != null) {
            spielerOeffnen(m);
            return;
        }
        if (ausgabe != null && ausgabe.isAlive()) {
            hinweisUeberall("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)");
            return;
        }
        int n = v.bilder();
        long min = Math.max(1, Math.round((2 * n * vergleichJeBild + 40) / 60));
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        int a = javax.swing.JOptionPane.showConfirmDialog(w, "<html><b>" + v.name() + "</b><br>" + (v.text().isEmpty() ? "" : v.text() + "<br>")
                        + "<br>Noch nicht gerechnet. Die Ausgabe rechnet 2 × " + n + " Bilder, rund " + min + " min;<br>die Szene ruht so lange. Danach öffnet sich der Vergleich.</html>",
                "Vergleich rechnen", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (a != javax.swing.JOptionPane.OK_OPTION) return;
        vergleichRechnen(new ArrayList<>(List.of("--vergleichsfolge", v.schluessel(), "--groesse", VERGLEICH_B + "x" + VERGLEICH_H)), new File(manifest.vergleiche(), v.schluessel()));
    }

    private void hinweisUeberall(String text) {
        if (seite != null && seite.sichtbar()) seite.stand(text, Double.NaN);
        hf.hinweis(text, Hinweis.Art.INFO);
    }

    /** Startet die Ausgabe einer Bildfolge; danach der Spieler. Auf der Startseite steht der Stand der Ausgabe im Kopf. */
    private void vergleichRechnen(List<String> args, File ordner) {
        starteAusgabe(args, "Vergleich", () -> {
            try {
                spielerOeffnen(com.dan.steuerhaus.vergleich.Mappe.lies(ordner));
                if (seite != null && seite.sichtbar()) startseiteKacheln(Vorschau.ordner(szenen));
            } catch (java.io.IOException x) {
                hf.hinweis("Vergleich: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        });
        if (seite != null && seite.sichtbar()) {
            Timer t = new Timer(400, null);
            t.addActionListener(e -> {
                String st = ausgabeStand;
                if (st == null || !seite.sichtbar()) {
                    t.stop();
                    if (seite.sichtbar()) seite.stand(seiteBereit ? "Die Szene ist bereit." : "Die Bühne öffnet …", Double.NaN);
                } else seite.stand(st, Double.NaN);
            });
            t.start();
        }
    }

    void spielerOeffnen(com.dan.steuerhaus.vergleich.Mappe m) {
        if (spieler != null && spieler.offen()) spieler.schliesse();
        spieler = new com.dan.steuerhaus.vergleich.Spieler(hf.fenster, m, zone, manifest.fotos());
        spieler.ansicht.gesichert = f -> protokoll.schreibe("Vergleich", "Bild gesichert: " + f.getName());
        spieler.oeffne();
        if (!Double.isNaN(m.rechenzeit) && m.bilder.size() > 0) vergleichJeBild = Math.max(0.3, (m.rechenzeit - 30) / (2.0 * m.bilder.size()));
        protokoll.schreibe("Vergleich", m.name + ": " + m.bilder.size() + " Bilder je Seite, " + m.sonden.size() + " Sonden, Bezug " + (m.bezug != null ? m.bezug.art() : "–"));
    }

    /** Labor › Vergleich vom Blick jetzt: Szenario B, Ausrichtung, Bereich, Schritt, Größe; dann die Ausgabe, dann der Spieler. */
    private void vergleichDialog() {
        Buehne b = buehne;
        Szenario s = szenarioJetzt;
        if (b == null || s == null || b.zustand().jetzt == null) return;
        if (ausgabe != null && ausgabe.isAlive()) {
            hf.hinweis("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)", Hinweis.Art.INFO);
            return;
        }
        if (s.live()) {
            hf.hinweis("Vergleich: nur aus einem Szenario mit Archiv, nicht live", Hinweis.Art.INFO);
            return;
        }
        List<Szenario> archive = szenen.szenarien().stream().filter(x -> x.archiv != null && x != s).toList();
        if (archive.isEmpty()) return;
        javax.swing.JComboBox<Szenario> zweites = new javax.swing.JComboBox<>(archive.toArray(Szenario[]::new));
        Szenario vor = vergleichSz != null && vergleichSz != s ? vergleichSz : szenen.szenario(einstellungen.text("vergleich.b", ""));
        if (vor != null && archive.contains(vor)) zweites.setSelectedItem(vor);
        javax.swing.JComboBox<String> aus = new javax.swing.JComboBox<>(new String[] {"gleiches Hochwasser (das nächste)", "gleiche Uhrzeit"});
        aus.setSelectedIndex(einstellungen.ganz("vergleich.ausrichtung", ausrichtung == 1 ? 0 : 1));
        String[] be = {"± 1 h", "± 2 h", "± 3 h", "± 6 h"};
        double[] beh = {1, 2, 3, 6};
        javax.swing.JComboBox<String> bereich = new javax.swing.JComboBox<>(be);
        bereich.setSelectedIndex(Math.max(0, Math.min(3, einstellungen.ganz("vergleich.bereich", 2))));
        String[] sc = {"alle 2 min", "alle 5 min", "alle 10 min"};
        double[] scm = {2, 5, 10};
        javax.swing.JComboBox<String> schritt = new javax.swing.JComboBox<>(sc);
        schritt.setSelectedIndex(Math.max(0, Math.min(2, einstellungen.ganz("vergleich.schritt", 1))));
        javax.swing.JComboBox<String> groesse = new javax.swing.JComboBox<>(new String[] {"1280 × 720", "1920 × 1080"});
        groesse.setSelectedIndex(Math.max(0, Math.min(1, einstellungen.ganz("vergleich.groesse", 1))));
        javax.swing.JLabel dauer = new javax.swing.JLabel(" ");
        List<String> so = new ArrayList<>();
        for (com.dan.lauf.Sonde x : sonden) so.add(String.format(Locale.ROOT, "%s; %.1f; %.1f", x.name, x.ost, x.nord));
        Runnable rechne = () -> {
            int n = (int) Math.floor(2 * beh[bereich.getSelectedIndex()] * 60 / scm[schritt.getSelectedIndex()]) + 1;
            double je = vergleichJeBild * (groesse.getSelectedIndex() == 0 ? 0.5 : 1);
            dauer.setText(String.format(Locale.GERMAN, "2 × %d Bilder, rund %d min; die Szene ruht so lange.", n, Math.max(1, Math.round((2 * n * je + 40) / 60))));
        };
        for (javax.swing.JComboBox<?> c : List.of(bereich, schritt, groesse)) c.addActionListener(e -> rechne.run());
        rechne.run();
        javax.swing.JPanel p = new javax.swing.JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        p.add(new javax.swing.JLabel("A: " + s.name + ", der Blick jetzt"));
        p.add(new javax.swing.JLabel("B: das zweite Szenario"));
        p.add(zweites);
        p.add(new javax.swing.JLabel("Ausrichtung"));
        p.add(aus);
        p.add(new javax.swing.JLabel("Bereich und Abstand der Bilder"));
        p.add(bereich);
        p.add(schritt);
        p.add(new javax.swing.JLabel("Größe"));
        p.add(groesse);
        p.add(new javax.swing.JLabel(so.isEmpty() ? "Sonden: Pegel und Bildmitte (Strg+Klick setzt eigene)" : "Sonden: die " + so.size() + " gesetzten"));
        p.add(dauer);
        int antwort = javax.swing.JOptionPane.showConfirmDialog(hf.fenster, p, "Vergleich vom Blick jetzt", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (antwort != javax.swing.JOptionPane.OK_OPTION) return;
        Szenario sb = (Szenario) zweites.getSelectedItem();
        einstellungen.setze("vergleich.b", sb.schluessel);
        einstellungen.setze("vergleich.ausrichtung", aus.getSelectedIndex());
        einstellungen.setze("vergleich.bereich", bereich.getSelectedIndex());
        einstellungen.setze("vergleich.schritt", schritt.getSelectedIndex());
        einstellungen.setze("vergleich.groesse", groesse.getSelectedIndex());
        Buehne.Zustand z = b.zustand();
        var bl = z.blick;
        String blick = s.schluessel + ";" + z.jetzt + ";" + String.format(Locale.ROOT, "%.3f %.3f %.3f %.4f %.4f %.4f", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel);
        String stempel = java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        File ordner = new File(manifest.vergleiche(), "jetzt_" + stempel);
        double h = beh[bereich.getSelectedIndex()];
        List<String> args = new ArrayList<>(List.of("--vergleichsfolge", "jetzt", "--blick", blick, "--vergleich", sb.schluessel, "--ausrichten",
                aus.getSelectedIndex() == 0 ? "hochwasser" : "uhrzeit", "--bereich", (-h) + " " + h, "--schritt", String.valueOf(scm[schritt.getSelectedIndex()]),
                "--groesse", groesse.getSelectedIndex() == 0 ? "1280x720" : "1080p", "--name", kurz(s.name) + " und " + kurz(sb.name), "--ziel", ordner.getPath()));
        for (String x : so) {
            args.add("--sonde");
            args.add(x);
        }
        vergleichRechnen(args, ordner);
    }

    /** Labor › Gerechnete Vergleiche: eine Mappe wählen. */
    private void vergleicheOeffnen() {
        List<com.dan.steuerhaus.vergleich.Mappe> l = com.dan.steuerhaus.vergleich.Mappe.alle(manifest.vergleiche()).stream().filter(x -> x.fertig).toList();
        if (l.isEmpty()) {
            hf.hinweis("Noch kein Vergleich gerechnet (Labor › Vergleich vom Blick jetzt, Strg L).", Hinweis.Art.INFO);
            return;
        }
        String[] namen = new String[l.size()];
        for (int i = 0; i < l.size(); i++)
            namen[i] = l.get(i).name + "  ·  " + l.get(i).bilder.size() + " Bilder je Seite  ·  " + l.get(i).ordner.getName();
        Object w = javax.swing.JOptionPane.showInputDialog(hf.fenster, "Vergleich öffnen", "Gerechnete Vergleiche", javax.swing.JOptionPane.PLAIN_MESSAGE, null, namen, namen[0]);
        if (w == null) return;
        for (int i = 0; i < namen.length; i++) if (namen[i].equals(w)) spielerOeffnen(l.get(i));
    }

    /** Seit Y1: {@code --vergleich Name} – nach dem ersten Bild den Spieler; mit --sekunden spielt er und wechselt die Darstellung. */
    private void vergleichNachStart(Buehne b, String name, double sekunden) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                com.dan.steuerhaus.vergleich.Vergleich v = vergleiche.stream().filter(x -> x.name().equals(name) || x.schluessel().equals(name)).findFirst().orElse(null);
                com.dan.steuerhaus.vergleich.Mappe m = v != null ? mappe(v) : null;
                if (m == null) {
                    File d = new File(manifest.vergleiche(), name);
                    if (new File(d, "vergleich.txt").isFile()) m = com.dan.steuerhaus.vergleich.Mappe.lies(d);
                }
                if (m == null) {
                    System.out.println("Steuerhaus: Vergleich „" + name + "“ ist nicht gerechnet; bekannt: " + vergleiche.stream().map(com.dan.steuerhaus.vergleich.Vergleich::name).toList());
                    return;
                }
                com.dan.steuerhaus.vergleich.Mappe mm = m;
                SwingUtilities.invokeAndWait(() -> spielerOeffnen(mm));
                System.out.printf(Locale.GERMAN, "Steuerhaus: Vergleich „%s“ offen: %d Bilder je Seite, %d Sonden, Bezug %s%n", mm.name, mm.bilder.size(), mm.sonden.size(),
                        mm.bezug != null ? mm.bezug.art() + " " + mm.bezug.a() + " | " + mm.bezug.b() : "–");
                if (sekunden <= 0) return;
                var sp = spieler;
                // seit Y3: von Bild 1 an messen (in Y2 begann es beim Bezug und stand nach der Hälfte am Ende), bis es hält
                SwingUtilities.invokeAndWait(() -> sp.ansicht.zeige(0));
                Thread.sleep(2000);
                SwingUtilities.invokeAndWait(() -> sp.ansicht.spiele(true));
                long n0 = System.nanoTime(), bis = n0 + (long) (Math.min(12, sekunden * 0.3) * 1e9);
                int k0 = sp.ansicht.bild();
                while (System.nanoTime() < bis && sp.ansicht.spielt()) Thread.sleep(20);
                int k1 = sp.ansicht.bild();
                double s = (System.nanoTime() - n0) / 1e9;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Vergleich: abgespielt Bild %d bis %d in %.1f s (%.1f Bilder/s)%n", k0 + 1, k1 + 1, s, (k1 - k0) / s);
                for (var d : com.dan.steuerhaus.vergleich.Vergleichsansicht.Darstellung.values()) {
                    SwingUtilities.invokeAndWait(() -> {
                        sp.ansicht.spiele(false);
                        sp.ansicht.zeige(mm.beimBezug());
                        sp.ansicht.darstellung(d);
                        sp.ansicht.teil(0.5);
                    });
                    Thread.sleep(3000);
                    File[] f = new File[1];
                    SwingUtilities.invokeAndWait(() -> f[0] = sp.ansicht.sichern());
                    System.out.println("Steuerhaus: Vergleich: " + d.name + " gesichert: " + (f[0] != null ? f[0].getName() : "–"));
                }
                SwingUtilities.invokeAndWait(() -> sp.ansicht.darstellung(com.dan.steuerhaus.vergleich.Vergleichsansicht.Darstellung.SCHIEBER));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Vergleich");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Jahr (Y3, Werkbuch HalligHooge 154)

    private com.dan.steuerhaus.jahr.Jahresauftrag jahrAuftrag;
    private com.dan.steuerhaus.jahr.Jahresfenster jahrFenster;

    /** Kachel und Menü: öffnet das Jahr; ist die Mappe nicht gerechnet oder passt nicht, fragt die App und rechnet sie. */
    void jahrZeigen() {
        if (jahrAuftrag == null) {
            hinweisUeberall("Kein Jahr im Manifest (jahr.1 …)");
            return;
        }
        String[] warum = new String[1];
        com.dan.steuerhaus.jahr.Jahresmappe jm = jahrAuftrag.mappe(szenen, warum);
        if (jm != null) {
            jahrOeffnen(jm);
            return;
        }
        if (ausgabe != null && ausgabe.isAlive()) {
            hinweisUeberall("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)");
            return;
        }
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        int a = javax.swing.JOptionPane.showConfirmDialog(w, "<html><b>" + manifest.text("jahr.name", "Ein Jahr in einer Minute") + "</b><br><br>Die Mappe ist " + warum[0]
                        + ".<br>Die Ausgabe rechnet sie aus den " + jahrAuftrag.abschnitte().size() + " Läufen des Modelljahrs, rund 3 min;<br>die Szene ruht so lange. Danach öffnet sich das Fenster.</html>",
                "Jahr rechnen", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (a != javax.swing.JOptionPane.OK_OPTION) return;
        starteAusgabe(new ArrayList<>(List.of("--jahr")), "Jahr", () -> {
            try {
                jahrOeffnen(com.dan.steuerhaus.jahr.Jahresmappe.lies(jahrAuftrag.ordner()));
                if (seite != null && seite.sichtbar()) startseiteKacheln(Vorschau.ordner(szenen));
            } catch (java.io.IOException x) {
                hf.hinweis("Jahr: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        });
    }

    void jahrOeffnen(com.dan.steuerhaus.jahr.Jahresmappe jm) {
        if (jahrFenster != null && jahrFenster.offen()) jahrFenster.schliesse();
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        jahrFenster = new com.dan.steuerhaus.jahr.Jahresfenster(w, jm, jahrAuftrag.dauer(), jahrAuftrag.hinweis(), jahrAuftrag.sand(), zone, manifest.fotos());
        jahrFenster.ansicht.imBild = this::jahrImBild;
        jahrFenster.ansicht.gesichert = f -> protokoll.schreibe("Jahr", "Bild gesichert: " + f.getName());
        jahrFenster.oeffne();
        protokoll.schreibe("Jahr", jm.name + ": " + jm.bilder.size() + " Bilder, " + Math.round(jm.tage()) + " Tage, " + jm.abschnitte.size() + " Abschnitte");
    }

    /** „Im Bild zeigen“: das Szenario des Abschnitts zur Zeit im Lauf, mit der Ansicht des Jahrs, im Hauptfenster. */
    private void jahrImBild(String szenario, Instant zeit) {
        String an = jahrAuftrag.ansicht().isEmpty() ? null : jahrAuftrag.ansicht();
        protokoll.schreibe("Jahr", "Im Bild: " + szenario + " " + zeit);
        if (seite != null && seite.sichtbar()) szeneAusStartseite(szenario, an, zeit);
        else {
            hf.fenster.toFront();
            szeneZeigen(szenario, an, zeit);
        }
    }

    /** Nach dem Start (--jahr): das Fenster; mit --sekunden spielt es einmal durch und misst (Probe), dann zwei Bilder. */
    private void jahrNachStart(Buehne b, double sekunden) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                String[] warum = new String[1];
                com.dan.steuerhaus.jahr.Jahresmappe jm = jahrAuftrag == null ? null : jahrAuftrag.mappe(szenen, warum);
                if (jm == null) {
                    System.out.println("Steuerhaus: Jahr: keine passende Mappe (" + (jahrAuftrag == null ? "kein jahr.* im Manifest" : warum[0]) + ")");
                    return;
                }
                SwingUtilities.invokeAndWait(() -> jahrOeffnen(jm));
                System.out.printf(Locale.GERMAN, "Steuerhaus: Jahr offen: %d Bilder, %.1f Tage, %d Abschnitte, Kennung %s%n", jm.bilder.size(), jm.tage(), jm.abschnitte.size(), jm.kennung);
                if (sekunden <= 0) return;
                var f = jahrFenster;
                Thread.sleep(2000);
                SwingUtilities.invokeAndWait(() -> {
                    f.ansicht.zeige(0);
                    f.ansicht.spiele(true);
                });
                long n0 = System.nanoTime(), g0 = f.ansicht.gemalt, bis = n0 + (long) (Math.min(150, sekunden - 8) * 1e9);
                while (System.nanoTime() < bis && f.ansicht.spielt()) Thread.sleep(50);
                double s = (System.nanoTime() - n0) / 1e9, tg = f.ansicht.tag();
                long gm = f.ansicht.gemalt - g0;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Jahr: abgespielt bis Tag %.1f in %.1f s (Spielzeit %.0f s), %d Bilder gemalt (%.1f Bilder/s)%n", tg, s, f.ansicht.plan().gesamt, gm, gm / s);
                Object[][] auf = {{com.dan.steuerhaus.jahr.Jahresansicht.Darstellung.DIFFERENZ, jm.tage()}, {com.dan.steuerhaus.jahr.Jahresansicht.Darstellung.HOEHE, jm.tage()},
                        {com.dan.steuerhaus.jahr.Jahresansicht.Darstellung.DIFFERENZ, jm.abschnitte.get(0).tag1()}};
                for (Object[] x : auf) {
                    SwingUtilities.invokeAndWait(() -> {
                        f.ansicht.darstellung((com.dan.steuerhaus.jahr.Jahresansicht.Darstellung) x[0]);
                        f.ansicht.zeige((Double) x[1]);
                    });
                    Thread.sleep(1500);
                    File[] d = new File[1];
                    SwingUtilities.invokeAndWait(() -> d[0] = f.ansicht.sichern());
                    System.out.println("Steuerhaus: Jahr: " + x[0] + " bei Tag " + Math.round((Double) x[1]) + " gesichert: " + (d[0] != null ? d[0].getName() : "–"));
                }
                SwingUtilities.invokeAndWait(() -> f.ansicht.darstellung(com.dan.steuerhaus.jahr.Jahresansicht.Darstellung.DIFFERENZ));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Jahr");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Tidenkalender (Z1, Werkbuch HalligHooge 155)

    private com.dan.steuerhaus.gezeiten.Gezeitenauftrag gezeitenAuftrag;
    private com.dan.steuerhaus.gezeiten.Kalenderfenster kalenderFenster;

    /** Höhe und Name der Marke im Tagesbild: gezeiten.marke, sonst die Marke des Pegels aus der Szenendatei. */
    private double[] kalenderMarke(String[] name) {
        double mk = gezeitenAuftrag.marke();
        name[0] = gezeitenAuftrag.markeName();
        if (Double.isNaN(mk)) {
            try {
                mk = szenen.zahl("pegel.marke", Double.NaN);
            } catch (java.io.IOException x) {
                mk = Double.NaN;
            }
            name[0] = szenen.text("pegel.marke.name", "");
        }
        return new double[] {mk};
    }

    /** Menü und Kachel: öffnet den Kalender; fehlen die Konstanten, fragt die App und rechnet sie mit der Ausgabe. */
    void kalenderZeigen() {
        if (gezeitenAuftrag == null) {
            hinweisUeberall("Kein Tidenkalender im Manifest (gezeiten.*)");
            return;
        }
        if (gezeitenAuftrag.datei().isFile()) {
            try {
                kalenderOeffnen(com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei()));
                return;
            } catch (java.io.IOException x) {
                hf.hinweis("Tidenkalender: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        }
        if (ausgabe != null && ausgabe.isAlive()) {
            hinweisUeberall("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)");
            return;
        }
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        int a = javax.swing.JOptionPane.showConfirmDialog(w, "<html><b>" + gezeitenAuftrag.name() + "</b><br><br>Die Konstanten sind noch nicht gerechnet (" + gezeitenAuftrag.datei().getName()
                        + ").<br>Die Ausgabe rechnet sie aus der Messung am Pegel " + gezeitenAuftrag.bezugName() + " und dem Modell, rund 1 min.</html>",
                "Gezeiten rechnen", javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (a != javax.swing.JOptionPane.OK_OPTION) return;
        starteAusgabe(new ArrayList<>(List.of("--gezeiten")), "Gezeiten", () -> {
            try {
                kalenderOeffnen(com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei()));
                if (seite != null && seite.sichtbar()) startseiteKacheln(Vorschau.ordner(szenen));
            } catch (java.io.IOException x) {
                hf.hinweis("Tidenkalender: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        });
    }

    void kalenderOeffnen(com.dan.steuerhaus.gezeiten.Gezeitentafel t) {
        if (kalenderFenster != null && kalenderFenster.offen()) kalenderFenster.schliesse();
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        String[] mn = new String[1];
        double mk = kalenderMarke(mn)[0];
        kalenderFenster = new com.dan.steuerhaus.gezeiten.Kalenderfenster(w, gezeitenAuftrag.name(), t, mk, mn[0], gezeitenAuftrag.hinweis(), zone, manifest.fotos());
        kalenderFenster.ansicht.gesichert = f -> protokoll.schreibe("Tidenkalender", "gesichert: " + f.getName());
        kalenderFenster.oeffne();
        protokoll.schreibe("Tidenkalender", t.ort + ": " + t.tiden.size() + " Tiden, " + t.marken.size() + " Marken des Modells");
    }

    /** Die Kachel der Startseite: der heutige Tag, gemalt in den Ordner der Vorschaubilder (null ohne Konstanten). */
    private File kalenderKachel(File vorschau) {
        if (!gezeitenAuftrag.datei().isFile()) return null;
        File f = new File(vorschau, "begleiter_kalender.png");
        try {
            String heute = java.time.LocalDate.now(zone).toString();
            if (f.isFile() && heute.equals(einstellungen.text("kalender.kachel", "")) && f.lastModified() >= gezeitenAuftrag.datei().lastModified()) return f;
            var t = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
            String[] mn = new String[1];
            var k = new com.dan.steuerhaus.gezeiten.Kalenderansicht(t, kalenderMarke(mn)[0], mn[0], "", zone, manifest.fotos());
            javax.imageio.ImageIO.write(k.tagesbild(480, 270), "png", f);
            einstellungen.setze("kalender.kachel", heute);
            return f;
        } catch (Exception x) {
            protokoll.schreibe("Tidenkalender", "Kachel: " + x.getMessage());
            return f.isFile() ? f : null;
        }
    }

    /** Nach dem Start (--kalender): das Fenster; mit --sekunden blättert es (heute, +1 Tag, +1 Woche), sichert Bild und Liste. */
    private void kalenderNachStart(Buehne b, double sekunden) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                if (gezeitenAuftrag == null || !gezeitenAuftrag.datei().isFile()) {
                    System.out.println("Steuerhaus: Tidenkalender: keine Konstanten (" + (gezeitenAuftrag == null ? "kein gezeiten.* im Manifest" : gezeitenAuftrag.datei()) + ")");
                    return;
                }
                var tafel = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
                long n0 = System.nanoTime();
                SwingUtilities.invokeAndWait(() -> kalenderOeffnen(tafel));
                var k = kalenderFenster.ansicht;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Tidenkalender offen in %.2f s: %s, %d Tiden, %d Marken des Modells%n", (System.nanoTime() - n0) / 1e9, tafel.ort, tafel.tiden.size(),
                        tafel.marken.size());
                var td = k.tage().stream().filter(x -> x.datum().equals(k.tag())).findFirst().orElse(null);
                if (td != null)
                    for (var e : td.extreme())
                        System.out.printf(Locale.GERMAN, "Steuerhaus: Tidenkalender %s: %s %s %+.2f m%n", k.tag(), e.art() == com.dan.steuerhaus.gezeiten.Vorhersage.Art.HOCHWASSER ? "HW" : "NW",
                                java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(e.zeit().atZone(zone)), e.hoehe());
                if (sekunden <= 0) return;
                Thread.sleep(1500);
                long n1 = System.nanoTime();
                for (int i = 0; i < 8; i++) {
                    SwingUtilities.invokeAndWait(() -> k.zeige(k.tag().plusWeeks(1)));
                    Thread.sleep(150);
                }
                double s8 = (System.nanoTime() - n1) / 1e9 - 8 * 0.15;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Tidenkalender: acht Wochen weiter geblättert, je Woche %.0f ms (neu gerechnet)%n", s8 / 8 * 1000);
                SwingUtilities.invokeAndWait(() -> k.zeige(k.heute()));
                Thread.sleep(800);
                File[] f = new File[2];
                SwingUtilities.invokeAndWait(() -> {
                    f[0] = k.sichern();
                    f[1] = k.alsListe();
                });
                System.out.println("Steuerhaus: Tidenkalender gesichert: " + (f[0] != null ? f[0].getName() : "–") + ", " + (f[1] != null ? f[1].getName() : "–"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Tidenkalender");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Watt-Fenster (Z3, Werkbuch HalligHooge 158)

    private com.dan.steuerhaus.gezeiten.Wattauftrag wattAuftrag;
    private com.dan.steuerhaus.gezeiten.Wattfenster wattFenster;

    /** Menü und Kachel: öffnet das Watt-Fenster; fehlt der Weg, fragt die App und rechnet ihn (Ausgabe --watt). */
    void wattZeigen() {
        if (wattAuftrag == null) {
            hinweisUeberall("Kein Watt-Fenster im Manifest (watt.*)");
            return;
        }
        if (!gezeitenAuftrag.datei().isFile()) {
            kalenderZeigen();
            return;
        }
        if (wattAuftrag.datei().isFile()) {
            try {
                wattOeffnen(com.dan.steuerhaus.gezeiten.Watttafel.lies(wattAuftrag.datei()));
                return;
            } catch (java.io.IOException x) {
                hf.hinweis("Watt-Fenster: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        }
        if (ausgabe != null && ausgabe.isAlive()) {
            hinweisUeberall("Eine Ausgabe läuft schon (Datei › Ausgabe abbrechen)");
            return;
        }
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        int a = javax.swing.JOptionPane.showConfirmDialog(w, "<html><b>" + wattAuftrag.name() + "</b><br><br>Der Weg ist noch nicht gerechnet (" + wattAuftrag.datei().getName()
                        + ").<br>Die Ausgabe rechnet ihn aus dem Gelände und dem Modell, rund 1 min.</html>", "Watt rechnen", javax.swing.JOptionPane.OK_CANCEL_OPTION,
                javax.swing.JOptionPane.PLAIN_MESSAGE);
        if (a != javax.swing.JOptionPane.OK_OPTION) return;
        starteAusgabe(new ArrayList<>(List.of("--watt")), "Watt", () -> {
            try {
                wattOeffnen(com.dan.steuerhaus.gezeiten.Watttafel.lies(wattAuftrag.datei()));
                if (seite != null && seite.sichtbar()) startseiteKacheln(Vorschau.ordner(szenen));
            } catch (java.io.IOException x) {
                hf.hinweis("Watt-Fenster: " + x.getMessage(), Hinweis.Art.FEHLER);
            }
        });
    }

    void wattOeffnen(com.dan.steuerhaus.gezeiten.Watttafel t) throws java.io.IOException {
        if (wattFenster != null && wattFenster.offen()) wattFenster.schliesse();
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        var gt = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
        wattFenster = new com.dan.steuerhaus.gezeiten.Wattfenster(w, wattAuftrag.name(), t, gt, wattAuftrag.gehen(), wattAuftrag.hinweis(), zone, manifest.fotos());
        wattFenster.ansicht.gesichert = f -> protokoll.schreibe("Watt-Fenster", "gesichert: " + f.getName());
        wattFenster.oeffne();
        protokoll.schreibe("Watt-Fenster", String.format(Locale.GERMAN, "%s – %s, %.0f m, Sattel %+.2f m; Schwellen %+.2f / %+.2f m", t.start, t.ziel, t.wegLaenge(), t.sattel,
                t.schwelleAb, t.schwelleBis));
    }

    /** Die Kachel der Startseite: der heutige Tag, je Stunde neu gemalt (null ohne Weg). */
    private File wattKachel(File vorschau) {
        if (!wattAuftrag.datei().isFile() || !gezeitenAuftrag.datei().isFile()) return null;
        File f = new File(vorschau, "begleiter_watt.png");
        try {
            String stunde = java.time.LocalDateTime.now(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
            if (f.isFile() && stunde.equals(einstellungen.text("watt.kachel", "")) && f.lastModified() >= wattAuftrag.datei().lastModified()) return f;
            var gt = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
            var v = new com.dan.steuerhaus.gezeiten.Wattansicht(com.dan.steuerhaus.gezeiten.Watttafel.lies(wattAuftrag.datei()), gt.vorhersage(), gt, wattAuftrag.gehen(), "", zone,
                    manifest.fotos());
            javax.imageio.ImageIO.write(v.tagesbild(480, 270), "png", f);
            einstellungen.setze("watt.kachel", stunde);
            return f;
        } catch (Exception x) {
            protokoll.schreibe("Watt-Fenster", "Kachel: " + x.getMessage());
            return f.isFile() ? f : null;
        }
    }

    /**
     * Nach dem Start (--wattfenster): das Fenster; mit --sekunden die Probe: die Fenster der Woche, zum Niedrigwasser
     * gesprungen, gemessener Windstau (sofern erreichbar), eigener Wert +0,30 m, Bild.
     */
    private void wattNachStart(Buehne b, double sekunden) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                if (wattAuftrag == null || !wattAuftrag.datei().isFile()) {
                    System.out.println("Steuerhaus: Watt-Fenster: " + (wattAuftrag == null ? "kein watt.* im Manifest" : "kein Weg (" + wattAuftrag.datei() + ")"));
                    return;
                }
                var tafel = com.dan.steuerhaus.gezeiten.Watttafel.lies(wattAuftrag.datei());
                long n0 = System.nanoTime();
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        wattOeffnen(tafel);
                    } catch (java.io.IOException x) {
                        throw new java.io.UncheckedIOException(x);
                    }
                });
                var v = wattFenster.ansicht;
                java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("EE dd.MM. HH:mm", Locale.GERMAN);
                System.out.printf(Locale.GERMAN, "Steuerhaus: Watt-Fenster offen in %.2f s: %s – %s, %.0f m, Sattel %+.2f m, Schwellen %+.2f / %+.2f m%n", (System.nanoTime() - n0) / 1e9, tafel.start,
                        tafel.ziel, tafel.wegLaenge(), tafel.sattel, tafel.schwelleAb, tafel.schwelleBis);
                for (var f : v.fensterWoche())
                    System.out.printf(Locale.GERMAN, "Steuerhaus: Watt-Fenster: trocken %s – %s (%d min, %d min bei Tag)%n", hm.format(f.ab().atZone(zone)), hm.format(f.bis().atZone(zone)),
                            f.dauer().toMinutes(), f.tagMinuten());
                if (sekunden <= 0) return;
                var f0 = v.watt.jetztOderNaechstes(v.zeit(), 0);
                if (f0 != null) {
                    SwingUtilities.invokeAndWait(() -> v.zeige(f0.niedrig()));
                    Thread.sleep(600);
                    System.out.println("Steuerhaus: Watt-Fenster: Niedrigwasser " + hm.format(f0.niedrig().atZone(zone)) + ", Weg " + (v.watt.trocken(v.zeit(), 0) ? "trocken" : "nass"));
                }
                if (!v.messung.bezuege.isEmpty()) {
                    SwingUtilities.invokeAndWait(() -> v.quelle(1));
                    for (int i = 0; i < 250 && v.messung.gemessen() == null && v.messung.fehler() == null; i++) Thread.sleep(100);
                    var g = v.messung.gemessen();
                    System.out.println("Steuerhaus: Watt-Fenster: Windstau gemessen " + (g == null ? "nicht erreicht (" + v.messung.fehler() + ")" : String.format(Locale.GERMAN, "%+.2f m", g.meter())));
                    var fg = v.watt.jetztOderNaechstes(v.jetzt.get(), v.stau());
                    if (fg != null) System.out.printf(Locale.GERMAN, "Steuerhaus: Watt-Fenster [gemessen]: nächstes trocken %s – %s%n", hm.format(fg.ab().atZone(zone)), hm.format(fg.bis().atZone(zone)));
                }
                SwingUtilities.invokeAndWait(() -> v.eigenerStau(0.3));
                Thread.sleep(400);
                var fe = v.watt.jetztOderNaechstes(v.jetzt.get(), 0.3);
                System.out.println("Steuerhaus: Watt-Fenster [Eigener Wert +0,30 m]: " + (fe == null ? "kein Fenster" : "nächstes trocken " + hm.format(fe.ab().atZone(zone)) + " – "
                        + hm.format(fe.bis().atZone(zone)) + " (" + fe.dauer().toMinutes() + " min)"));
                SwingUtilities.invokeAndWait(() -> {
                    v.quelle(0);
                    v.zeige(v.jetzt.get());
                });
                Thread.sleep(800);
                File[] fs = new File[1];
                SwingUtilities.invokeAndWait(() -> fs[0] = v.sichern());
                System.out.println("Steuerhaus: Watt-Fenster gesichert: " + (fs[0] != null ? fs[0].getName() : "–"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Watt-Fenster");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Landunter-Uhr (Z2, Werkbuch HalligHooge 156)

    private com.dan.steuerhaus.gezeiten.Landunterauftrag landunterAuftrag;
    private com.dan.steuerhaus.gezeiten.Landunterfenster landunterFenster;

    /** Die Warften der Hallig aus dem Atlas (mit Kuppe), nach Namen; leer ohne Atlas. */
    List<com.dan.steuerhaus.gezeiten.Landunter.Warft> landunterWarften() { return warften(landunterAtlas(), landunterAuftrag); }

    /** Die Orte der Art Warft mit Kuppe und Fuß im Umkreis um die Hallig des Auftrags (ohne die Hallig im Atlas: alle), nach Namen. */
    public static List<com.dan.steuerhaus.gezeiten.Landunter.Warft> warften(com.dan.steuerhaus.atlas.Atlas a, com.dan.steuerhaus.gezeiten.Landunterauftrag la) {
        List<com.dan.steuerhaus.gezeiten.Landunter.Warft> l = new ArrayList<>();
        if (a == null || la == null) return l;
        com.dan.steuerhaus.atlas.Atlas.Ort h = null;
        for (var o : a.orte) if (o.name().equals(la.hallig()) && o.art().equals("Hallig")) h = o;
        for (var o : a.orte) {
            if (!o.art().equals("Warft") || Double.isNaN(o.kuppe()) || Double.isNaN(o.fuss())) continue;
            if (h != null && Math.hypot(o.ost() - h.ost(), o.nord() - h.nord()) > la.umkreis()) continue;
            l.add(new com.dan.steuerhaus.gezeiten.Landunter.Warft(o.name(), o.kuppe(), o.fuss(), o.haeuser()));
        }
        l.sort(java.util.Comparator.comparing(com.dan.steuerhaus.gezeiten.Landunter.Warft::name));
        return l;
    }

    private com.dan.steuerhaus.atlas.Atlas landunterAtlas() {
        if (atlas != null) return atlas;
        if (!com.dan.steuerhaus.atlas.Atlas.da(szenen.datei)) return null;
        try {
            atlas = com.dan.steuerhaus.atlas.Atlas.lies(manifest, szenen.datei);
        } catch (java.io.IOException x) {
            protokoll.schreibe("Landunter-Uhr", "Atlas: " + x.getMessage());
        }
        return atlas;
    }

    /**
     * Die Windstaus zur Wahl: der Sturm des Atlas (Scheitel am Pegel minus Gezeit des Hochwassers nächst dem Scheitel, auf
     * 5 cm gerundet) und die aus landunter.wahl.N.
     */
    List<Object[]> landunterWahl(com.dan.steuerhaus.gezeiten.Gezeitentafel t) {
        List<Object[]> w = new ArrayList<>();
        com.dan.steuerhaus.atlas.Atlas a = landunterAuftrag.sturm() ? landunterAtlas() : null;
        if (a != null && a.scheitelZeit != null && !Double.isNaN(a.scheitel)) {
            var v = t.vorhersage();
            com.dan.steuerhaus.gezeiten.Vorhersage.Extrem hw = null;
            for (var e : v.extreme(a.scheitelZeit.minusSeconds(9 * 3600), a.scheitelZeit.plusSeconds(9 * 3600)))
                if (e.art() == com.dan.steuerhaus.gezeiten.Vorhersage.Art.HOCHWASSER
                        && (hw == null || Math.abs(e.zeit().getEpochSecond() - a.scheitelZeit.getEpochSecond()) < Math.abs(hw.zeit().getEpochSecond() - a.scheitelZeit.getEpochSecond()))) hw = e;
            if (hw != null) w.add(new Object[] {a.sturmName + " im Modell", Math.round((a.scheitel - hw.hoehe()) / 0.05) * 0.05});
        }
        // seit DB2: der Windstau des jüngsten Landunters aus der Chronik, gemessen
        com.dan.steuerhaus.Chronik ch = chronik();
        com.dan.steuerhaus.Chronik.Ereignis lu = ch == null ? null : ch.letztes(com.dan.steuerhaus.Chronik.Art.LANDUNTER);
        if (lu != null && !Double.isNaN(lu.stau()))
            w.add(new Object[] {"Landunter " + java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy").format(lu.hoechst().atZone(zone)) + ", gemessen", Math.round(lu.stau() / 0.05) * 0.05});
        w.addAll(landunterAuftrag.wahl());
        return w;
    }

    // ------------------------------------------------------------------ seit DB2: die Chronik der Hochwasser

    private com.dan.steuerhaus.chronik.Chronikfenster chronikFenster;

    /** Die Datei der Chronik ({@code chronik.datei}, relativ zum Manifest) oder null, wenn das Manifest keine nennt. */
    File chronikDatei() {
        String d = manifest.text("chronik.datei", "");
        return d.isEmpty() ? null : manifest.relativ(d);
    }

    String chronikName() { return manifest.text("chronik.name", "Chronik der Hochwasser"); }

    /** Die Chronik oder null (keine Datei, unlesbar). */
    com.dan.steuerhaus.Chronik chronik() {
        File f = chronikDatei();
        if (f == null || !f.isFile()) return null;
        try {
            return com.dan.steuerhaus.Chronik.lies(f);
        } catch (java.io.IOException | RuntimeException x) {
            protokoll.schreibe("Chronik", "nicht lesbar: " + x.getMessage());
            return null;
        }
    }

    /** „Zuletzt Landunter am …; über der Krone am … · Messarchiv“ oder null. */
    String chronikZeile(com.dan.steuerhaus.Chronik c) { return c == null ? null : c.zuletzt(zone) + " · Messarchiv"; }

    String chronikUnterzeile() {
        com.dan.steuerhaus.Chronik c = chronik();
        if (c == null) return "noch keine Chronik (der Sammler des Messarchivs schreibt sie)";
        String z = chronikZeile(c);
        return c.ereignisse.size() + (c.ereignisse.size() == 1 ? " Ereignis" : " Ereignisse") + " · " + z.replace(" · Messarchiv", "");
    }

    /** Die Szenarien aus dem Archiv mit der Zeit, die sie abdecken (in der Folge der Szenendatei). */
    List<com.dan.steuerhaus.chronik.Chronikansicht.Szene> chronikSzenen() {
        List<com.dan.steuerhaus.chronik.Chronikansicht.Szene> l = new ArrayList<>();
        for (Szenario s : szenen.szenarien()) {
            if (s.live() || s.archiv == null) continue;
            java.time.Instant[] ab = com.dan.steuerhaus.chronik.Abdeckung.lies(s.archiv);
            if (ab != null) l.add(new com.dan.steuerhaus.chronik.Chronikansicht.Szene(s.schluessel, s.name, ab[0], ab[1]));
        }
        return l;
    }

    /** Menü und Kachel: das Fenster der Chronik; der Knopf eines Ereignisses führt ins Szenario zur Zeit des höchsten Wassers. */
    void chronikZeigen() {
        com.dan.steuerhaus.Chronik c = chronik();
        if (c == null) {
            hinweisUeberall("Noch keine Chronik (" + (chronikDatei() == null ? "chronik.datei fehlt" : chronikDatei().getName())
                    + "): der Sammler des Messarchivs schreibt sie, tools\\sammler.cmd --chronik");
            return;
        }
        if (chronikFenster != null && chronikFenster.offen()) chronikFenster.schliesse();
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        String ansicht = manifest.text("chronik.ansicht", "");
        String an = ansicht.isEmpty() || szenen.ansicht(ansicht) == null ? null : ansicht;
        List<com.dan.steuerhaus.chronik.Chronikansicht.Szene> sz = chronikSzenen();
        chronikFenster = new com.dan.steuerhaus.chronik.Chronikfenster(w, chronikName(), c, sz, zone, manifest.text("chronik.hinweis", ""), (s, e) -> {
            java.time.Instant t = e.hoechst().minusSeconds(3600);
            if (t.isBefore(s.von())) t = s.von();
            protokoll.schreibe("Chronik", e.art().anzeige + " " + e.hoechst() + " → Szenario " + s.schluessel() + (an == null ? "" : ", Ansicht " + an));
            if (seite != null && seite.sichtbar()) szeneAusStartseite(s.schluessel(), e.art() == com.dan.steuerhaus.Chronik.Art.STURMFLUT ? null : an, t);
            else szeneZeigen(s.schluessel(), e.art() == com.dan.steuerhaus.Chronik.Art.STURMFLUT ? null : an, t);
            if (hf.fenster != null) hf.fenster.toFront();
        });
        chronikFenster.oeffne();
        long mit = c.ereignisse.stream().filter(e -> chronikFenster.ansicht.szene(e) != null).count();
        protokoll.schreibe("Chronik", c.ereignisse.size() + " Ereignisse, " + mit + " mit Szenario, " + sz.size() + " Szenarien aus dem Archiv");
    }

    /** Menü und Kachel: öffnet die Uhr; fehlen die Konstanten, rechnet sie der Kalender (wie dort). */
    void landunterZeigen() {
        if (landunterAuftrag == null) {
            hinweisUeberall("Keine Landunter-Uhr im Manifest (landunter.*)");
            return;
        }
        if (!gezeitenAuftrag.datei().isFile()) {
            kalenderZeigen();
            return;
        }
        try {
            landunterOeffnen(com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei()));
        } catch (java.io.IOException x) {
            hf.hinweis("Landunter-Uhr: " + x.getMessage(), Hinweis.Art.FEHLER);
        }
    }

    void landunterOeffnen(com.dan.steuerhaus.gezeiten.Gezeitentafel t) {
        if (landunterFenster != null && landunterFenster.offen()) landunterFenster.schliesse();
        java.awt.Window w = seite != null && seite.sichtbar() ? javax.swing.SwingUtilities.getWindowAncestor(seite.karte) : hf.fenster;
        var wf = landunterWarften();
        landunterFenster = new com.dan.steuerhaus.gezeiten.Landunterfenster(w, landunterAuftrag.name(), t, landunterAuftrag.krone(), landunterAuftrag.kroneName(), landunterAuftrag.nass(),
                landunterAuftrag.nassName(), wf, landunterWahl(t),
                landunterAuftrag.hinweis(), zone, manifest.fotos());
        landunterFenster.uhr.gesichert = f -> protokoll.schreibe("Landunter-Uhr", "gesichert: " + f.getName());
        landunterFenster.uhr.chronikZeile = chronikZeile(chronik());
        landunterFenster.oeffne();
        protokoll.schreibe("Landunter-Uhr", t.ort + ": " + wf.size() + " Warften, Windstau " + (t.bezuege.isEmpty() ? "ohne Konstanten" : "aus " + pegelNamen(t)));
    }

    /** „Pellworm Anleger 9550021, Wittdün (Amrum) …“. */
    static String pegelNamen(com.dan.steuerhaus.gezeiten.Gezeitentafel t) {
        StringBuilder b = new StringBuilder();
        for (var x : t.bezuege) b.append(b.length() > 0 ? ", " : "").append(x.anzeige()).append(' ').append(x.nummer());
        return b.toString();
    }

    /** Die Kachel der Startseite: die Uhr ohne Wind, je Stunde neu gemalt (null ohne Konstanten). */
    private File landunterKachel(File vorschau) {
        if (!gezeitenAuftrag.datei().isFile()) return null;
        File f = new File(vorschau, "begleiter_landunter.png");
        try {
            String stunde = java.time.LocalDateTime.now(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toString();
            if (f.isFile() && stunde.equals(einstellungen.text("landunter.kachel", "")) && f.lastModified() >= gezeitenAuftrag.datei().lastModified()) return f;
            var t = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
            var u = new com.dan.steuerhaus.gezeiten.Landunteruhr(t, landunterAuftrag.krone(), landunterAuftrag.kroneName(), landunterAuftrag.nass(), landunterAuftrag.nassName(), List.of(),
                    List.of(), "", zone, manifest.fotos());
            u.quelle(u.quellen().indexOf("Ohne Wind (nur Gezeit)"));
            javax.imageio.ImageIO.write(u.uhrbild(480, 270), "png", f);
            einstellungen.setze("landunter.kachel", stunde);
            return f;
        } catch (Exception x) {
            protokoll.schreibe("Landunter-Uhr", "Kachel: " + x.getMessage());
            return f.isFile() ? f : null;
        }
    }

    /**
     * Nach dem Start (--landunter): das Fenster; mit --sekunden die Probe: die Messung (PEGELONLINE, sofern erreichbar),
     * jede Wahl des Windstaus einmal, ein eigener Wert, gesichertes Bild.
     */
    private void landunterNachStart(Buehne b, double sekunden) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 1200 && (b.wechselt() || b.zustand().bilder < 30); i++) Thread.sleep(50);
                if (landunterAuftrag == null || !gezeitenAuftrag.datei().isFile()) {
                    System.out.println("Steuerhaus: Landunter-Uhr: " + (landunterAuftrag == null ? "kein landunter.* im Manifest" : "keine Konstanten (" + gezeitenAuftrag.datei() + ")"));
                    return;
                }
                var tafel = com.dan.steuerhaus.gezeiten.Gezeitentafel.lies(gezeitenAuftrag.datei());
                long n0 = System.nanoTime();
                SwingUtilities.invokeAndWait(() -> landunterOeffnen(tafel));
                var u = landunterFenster.uhr;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Landunter-Uhr offen in %.2f s: %s, %d Warften, Krone %+.2f m, Bezug %s%n", (System.nanoTime() - n0) / 1e9, tafel.ort,
                        u.lu.warften.size(), u.lu.krone, tafel.bezuege.isEmpty() ? "ohne Konstanten" : pegelNamen(tafel));
                if (sekunden <= 0) return;
                java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("dd.MM. HH:mm");
                // die Messung: im Takt geladen; hier höchstens 25 s warten (seit Z2b je Pegel, dazu das Mittel)
                if (!tafel.bezuege.isEmpty()) {
                    for (int i = 0; i < 250 && u.gemessen() == null && u.messFehler() == null; i++) Thread.sleep(100);
                    var je = u.gemessenJe();
                    for (int i = 0; i < je.size(); i++) {
                        var x = je.get(i);
                        if (x != null)
                            System.out.printf(Locale.GERMAN, "Steuerhaus: Landunter-Uhr: PEGELONLINE %s (%s): %d Werte bis %s, Windstau der letzten Stunde %+.2f m%n", tafel.bezuege.get(i).anzeige(),
                                    tafel.bezuege.get(i).nummer(), x.werte(), hm.format(x.stand().atZone(zone)), x.meter());
                        else System.out.println("Steuerhaus: Landunter-Uhr: PEGELONLINE " + tafel.bezuege.get(i).anzeige() + " nicht erreichbar");
                    }
                    var g = u.gemessen();
                    if (g != null) System.out.printf(Locale.GERMAN, "Steuerhaus: Landunter-Uhr: Windstau gemessen (Mittel) %+.2f m%n", g.meter());
                    if (u.messFehler() != null) System.out.println("Steuerhaus: Landunter-Uhr: " + u.messFehler());
                }
                for (int i = 0; i < u.quellen().size() - 1; i++) {
                    int k = i;
                    SwingUtilities.invokeAndWait(() -> u.quelle(k));
                    Thread.sleep(400);
                    double s = u.stau();
                    var hw = u.lu.naechstes(u.jetzt.get(), s);
                    System.out.printf(Locale.GERMAN, "Steuerhaus: Landunter-Uhr [%s]: Windstau %+.2f m, nächstes HW %s %+.2f m, %s%n", u.quellen().get(k), s,
                            hm.format(hw.zeit().atZone(zone)), hw.scheitel(), hw.landunter() ? "Landunter " + hm.format(hw.nassAb().atZone(zone)) + "–" + hm.format(hw.nassBis().atZone(zone))
                                    : hw.ueberlauf() ? String.format(Locale.GERMAN, "über der Krone, zum Landunter fehlen %.2f m", hw.fehltNass())
                                    : String.format(Locale.GERMAN, "bis zur Krone fehlen %.2f m", hw.fehltKrone()));
                }
                SwingUtilities.invokeAndWait(() -> u.eigenerStau(1.0));
                Thread.sleep(400);
                int n = 0;
                java.time.Instant jt = u.jetzt.get();
                for (var hw : u.lu.hochwasser(jt, jt.plusSeconds(14 * 86400L), 1.0)) if (hw.landunter()) n++;
                System.out.printf(Locale.GERMAN, "Steuerhaus: Landunter-Uhr [Eigener Wert +1,00 m]: Landunter bei %d Hochwassern der nächsten 14 Tage%n", n);
                SwingUtilities.invokeAndWait(() -> u.quelle(0));
                Thread.sleep(800);
                File[] f = new File[1];
                SwingUtilities.invokeAndWait(() -> f[0] = u.sichern());
                System.out.println("Steuerhaus: Landunter-Uhr gesichert: " + (f[0] != null ? f[0].getName() : "–"));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }, "Landunter-Uhr");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Regler (Y2, Werkbuch HalligHooge 153)

    private volatile com.dan.steuerhaus.regler.Reglerwerte regler = com.dan.steuerhaus.regler.Reglerwerte.NEUTRAL;
    /** Eingriffe im laufenden Live-Szenario: {Szenenzeit (s), Text} für die Zeitleiste. */
    private final List<Object[]> eingriffe = new ArrayList<>();
    private Szenario eingriffeSz;
    private int reglerZaehler;

    /** Vom Pult: die Ziele an die Bühne; beim Loslassen ins Protokoll und als Marke in die Zeitleiste. */
    private void reglerSetzen(com.dan.steuerhaus.regler.Reglerwerte w, boolean eingriff) {
        regler = w;
        Buehne b = buehne;
        boolean live = b != null && b.regler(w.windFaktor(), w.windDrehung(), w.aufschlag(), w.meeresspiegel());
        if (!eingriff) return;
        protokoll.schreibe("Regler", w.text() + (live ? "" : " (gilt im nächsten Live-Lauf)"));
        if (o.sekunden() > 0) System.out.println("Steuerhaus: Regler gestellt: " + w.text() + (live ? "" : " (noch kein Live-Lauf)"));
        if (live) {
            Buehne.Zustand z = b.zustand();
            if (z.jetzt != null && z.live) {
                eingriffe.add(new Object[] {z.t, w.text()});
                leiste.eingriffe(eingriffe);
            }
        }
    }

    private void liveStarten() {
        Szenario s = szenen.szenarien().stream().filter(Szenario::live).findFirst().orElse(null);
        if (s == null) {
            hf.hinweis("Kein Live-Szenario in der Szenendatei.", Hinweis.Art.INFO);
            return;
        }
        waehleAusOberflaeche(s);
    }

    /** Je Takt: das Pult zeigt, was wirkt; ein neues Szenario löscht die Marken; in der Probe eine Zeile alle 50 Takte. */
    private void reglerTakt(Buehne b, Buehne.Zustand z) {
        if (z.szenario != eingriffeSz) {
            eingriffe.clear();
            eingriffeSz = z.szenario;
            leiste.eingriffe(eingriffe);
        }
        double[] ist = b.reglerIst();
        if (reglerPult.isShowing()) reglerPult.zeige(ist, z.live, z.windU, z.windV);
        if (o.sekunden() > 0 && z.live && ist != null && reglerZaehler++ % 50 == 0)
            System.out.printf(Locale.GERMAN, "Steuerhaus: Regler: Ziel %s · wirksam Wind × %.2f %+.0f°, am Rand %+.2f m · Pegel %+.2f m NHN · Wind am Auge %.1f m/s · %s%n", regler.text(), ist[0], ist[1],
                    ist[2], z.pegel, z.wind, z.jetzt);
    }

    // ------------------------------------------------------------------ Ton (X5, Werkbuch HalligHooge 151)

    private com.dan.steuerhaus.ton.Ausgang ton;
    private int tonZaehler;

    /** Startet den Ton, wenn er nicht abgeschaltet ist (Einstellung ton); ohne Gerät ein Hinweis, die App läuft weiter. */
    private void tonStarten() {
        if (!einstellungen.wahr("ton", true)) return;
        com.dan.steuerhaus.ton.Ausgang a = new com.dan.steuerhaus.ton.Ausgang(1958);
        a.lautstaerke(einstellungen.zahl("ton.lautstaerke", 0.7));
        if (!a.starte()) {
            hinweisSpaeter("Ton: kein Gerät für die Wiedergabe (" + a.fehler() + ")");
            protokoll.schreibe("Ton", "kein Gerät: " + a.fehler());
            return;
        }
        ton = a;
        protokoll.schreibe("Ton", "an, Lautstärke " + a.lautstaerke());
    }

    /** Je Takt der Oberfläche: die Werte des Klangs aus dem Zustand; stumm, solange die Szene ruht (Startseite, Ausgabe). */
    private void tonTakt(Buehne b, Buehne.Zustand z) {
        com.dan.steuerhaus.ton.Ausgang t = ton;
        if (t == null) return;
        com.dan.steuerhaus.ton.Klang.Werte w = b.wechselt() ? com.dan.steuerhaus.ton.Klang.Werte.STILL : com.dan.steuerhaus.ton.Tonwerte.aus(b, z);
        t.klang.setze(w);
        t.stumm(b.ruht() || (seite != null && seite.sichtbar()) || !einstellungen.wahr("ton", true));
        if (o.sekunden() > 0 && tonZaehler++ % 50 == 0)
            System.out.printf(Locale.GERMAN, "Steuerhaus: Ton: Wind %.1f m/s von %.0f°, Brandung %.2f, nah %.2f, Tag %.2f, Möwen %.2f, Plätschern %.2f, Rufe bisher %d%n", w.wind(),
                    w.windVon(), w.brandung(), w.nah(), w.tag(), w.moewen(), w.plaetschern(), t.klang.rufe());
    }

    private void tonUmschalten() {
        boolean an = !einstellungen.wahr("ton", true);
        einstellungen.setze("ton", an);
        if (an && ton == null) tonStarten();
        hf.hinweis(an ? "Ton an" + (ton == null ? " – kein Gerät" : "") : "Ton aus", Hinweis.Art.INFO);
    }

    private void tonLautstaerke(double d) {
        double v = Math.max(0, Math.min(1, einstellungen.zahl("ton.lautstaerke", 0.7) + d));
        einstellungen.setze("ton.lautstaerke", v);
        if (ton != null) ton.lautstaerke(v);
        hf.hinweis(String.format(Locale.GERMAN, "Ton: Lautstärke %.0f %%", 100 * v), Hinweis.Art.INFO);
    }

    private void ausgabeAbbrechen() {
        Process p = ausgabe;
        if (p != null && p.isAlive()) p.destroy();
    }

    /** Startet die Ausgabe als eigenen Prozess (dieselbe Java, derselbe Klassenpfad); die Bühne ruht so lange. */
    private void starteAusgabe(List<String> args, String was) { starteAusgabe(args, was, null); }

    private void starteAusgabe(List<String> args, String was, Runnable danach) {
        Buehne b = buehne;
        List<String> c = new ArrayList<>(List.of(java.nio.file.Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx12g", "--enable-native-access=ALL-UNNAMED",
                "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8"));
        if (System.getProperty("szenen.daten") != null) c.add("-Dszenen.daten=" + System.getProperty("szenen.daten"));
        c.addAll(List.of("-cp", System.getProperty("java.class.path"), "com.dan.steuerhaus.ausgabe.Ausgabe", "--manifest", manifest.datei.getPath()));
        protokoll.schreibe("Ausgabe", was + ": " + String.join(" ", args));
        c.addAll(args);
        Process p;
        try {
            p = new ProcessBuilder(c).redirectErrorStream(true).start();
        } catch (java.io.IOException x) {
            hf.hinweis(was + ": " + x.getMessage(), Hinweis.Art.FEHLER);
            return;
        }
        ausgabe = p;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (p.isAlive()) p.destroy();                                   // ohne die App liest niemand mehr ihre Ausgabe
        }));
        if (b != null) b.ruhen(true);
        ausgabeStand = was + ": beginnt …";
        hf.hinweis(was + ": die Ausgabe rechnet; das Bild hier steht so lange", Hinweis.Art.INFO);
        Thread t = new Thread(() -> {
            String letzte = null;
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                for (String l; (l = r.readLine()) != null; ) {
                    System.out.println(l);
                    if (l.startsWith("Ausgabe: ")) {
                        letzte = l.substring(9);
                        ausgabeStand = was + " · " + letzte;
                    }
                }
                int e = p.waitFor();
                String ende = letzte;
                protokoll.schreibe("Ausgabe", was + " – Ende " + e + (ende != null ? ": " + ende : ""));
                if (e == 0 && danach != null) SwingUtilities.invokeLater(danach);
                SwingUtilities.invokeLater(() -> hf.hinweis(was + (e == 0 ? " – " + ende : " – abgebrochen oder Fehler (" + e + ")" + (ende != null ? ": " + ende : "")),
                        e == 0 ? Hinweis.Art.INFO : Hinweis.Art.FEHLER));
            } catch (Exception x) {
                SwingUtilities.invokeLater(() -> hf.hinweis(was + ": " + x.getMessage(), Hinweis.Art.FEHLER));
            } finally {
                ausgabeStand = null;
                if (b != null) b.ruhen(false);
            }
        }, "Ausgabe");
        t.setDaemon(true);
        t.start();
    }

    // ------------------------------------------------------------------ Messblick und Schnitt (P11c3)

    private void messblick(Buehne b, Bedienung.Messwahl m) {
        b.messblickEbenen(m.partikel(), m.lic(), m.linien(), m.art());
    }

    private void klick(Buehne b, MouseEvent e) {
        if (kapitelAktiv) {
            kapitelWeiter();
            return;
        }
        int k = schnittKlicks;
        if (k != 1 && k != 2 && e.isControlDown()) {
            sondeSetzen(b, e);
            return;
        }
        if (k != 1 && k != 2) {
            karteOderOrt(b, e);
            return;
        }
        double[] p = b.ort(e.getX() / (double) Math.max(1, hf.leinwand.getWidth()), e.getY() / (double) Math.max(1, hf.leinwand.getHeight()));
        if (p == null) {
            bedienung.messStand(1, "Dort ist kein Boden und kein Wasser – noch einmal klicken.");
            return;
        }
        if (k == 1) {
            schnittA = p;
            schnittKlicks = 2;
        } else {
            double[] a = schnittA;
            new Thread(() -> schnittSetzen(b, a, p), "Schnitt").start();
        }
    }

    private void schnittNachStart(Buehne b) {
        Thread t = new Thread(() -> {
            try {
                for (int i = 0; i < 600 && (b.zustand().bilder < 10 || b.messGitter() == null); i++) Thread.sleep(100);
                double[] c = o.schnitt();
                schnittSetzen(b, new double[] {c[0], c[1]}, new double[] {c[2], c[3]});
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Schnitt");
        t.setDaemon(true);
        t.start();
    }

    private void schnittSetzen(Buehne b, double[] a, double[] bb) {
        Schnitt q;
        try {
            q = b.schnitt(a, bb);
        } catch (IllegalArgumentException x) {
            q = null;
        }
        if (q == null) {
            schnittKlicks = 0;
            SwingUtilities.invokeLater(() -> bedienung.messStand(0, "Kein Schnitt möglich: A und B liegen zu nah oder das Szenario hat kein Gitter dafür."));
            return;
        }
        Schnitt qs = q;
        schnittJetzt = qs;
        reihe = null;
        schnittKlicks = 3;
        String titel = String.format(Locale.GERMAN, "Schnitt von %.0f, %.0f nach %.0f, %.0f (UTM 32)", qs.ostA, qs.nordA, qs.ostB, qs.nordB);
        SwingUtilities.invokeLater(() -> {
            if (schnittFenster == null) schnittFenster = new SchnittFenster();
            schnittFenster.schnitt(titel, qs);
            schnittFenster.oeffne();
        });
        double[][] r = b.schnittReihe(qs);
        if (schnittJetzt != qs) return;
        reihe = r;
        SwingUtilities.invokeLater(() -> {
            if (schnittFenster != null) schnittFenster.reihe(r);
        });
    }

    private void schnittWeg(Buehne b) {
        schnittKlicks = 0;
        schnittJetzt = null;
        reihe = null;
        b.schnittWeg();
        if (schnittFenster != null) SwingUtilities.invokeLater(() -> schnittFenster.schnitt("Kein Schnitt", null));
    }

    private void messZeigen(Buehne b, Buehne.Zustand z) {
        int k = schnittKlicks;
        String text = k == 1 ? "Punkt A im Bild klicken." : k == 2 ? "Punkt B im Bild klicken." : null;
        if (text == null && (b.messblickAn() || k == 3) && z.jetzt != null) text = b.messHinweis(z.t);
        int stand = k == 0 ? 0 : k == 3 ? 2 : 1;
        if (stand != messStandAlt || !java.util.Objects.equals(text, messAlt)) {
            messStandAlt = stand;
            messAlt = text;
            bedienung.messStand(stand, text);
        }
        Schnitt q = schnittJetzt;
        if (q != null && schnittFenster != null && schnittFenster.offen() && z.jetzt != null)
            schnittFenster.profil(b.schnittProfil(q), z.t, z.start, zone, b.messHinweis(z.t));
    }

    // ------------------------------------------------------------------ Foto (P11b)

    private void foto(Buehne b) {
        Szenario s = szenarioJetzt;
        Ansicht a = ansichtJetzt;
        String t = (s != null ? s.name : "Foto") + (a != null ? " · " + a.name : "");
        b.foto(t, b.breite() / 2, b.hoehe() / 2, manifest.fotos(), bedienung.fotoSekunden());
        SwingUtilities.invokeLater(() -> {
            bedienung.halt();
            bedienung.fotoStand(true, "Das Foto wird vorbereitet …");
            if (fotoFenster == null) fotoFenster = new FotoFenster();
            fotoFenster.zeige(null, "Das Foto wird vorbereitet …");
            fotoFenster.oeffne();
            fotoGezeigt = null;
        });
    }

    private void fotoZeigen(Buehne b) {
        Foto f = b.foto();
        if (f == null || fotoFenster == null) return;
        if (f != fotoGezeigt) {
            fotoGezeigt = f;
            fotoRunden = -1;
            fotoEnde = false;
        }
        boolean fertig = f.fertig(), abgelegt = !f.ablage.isEmpty();
        boolean endeJetzt = fertig && (abgelegt || f.ende() == Foto.Ende.ABGEBROCHEN || f.ende() == Foto.Ende.FEHLER);
        if (f.runden() == fotoRunden && fotoEnde == endeJetzt) return;
        boolean neuFertig = endeJetzt && !fotoEnde;
        fotoRunden = f.runden();
        fotoEnde = endeJetzt;
        String text = f.text() + (abgelegt ? " · abgelegt in " + manifest.fotos() + ": " + f.ablage.get(0).getFileName() : "");
        fotoFenster.zeige(f.zwischenbild(), f.titel + " · " + text);
        bedienung.fotoStand(!fertig, text);
        if (neuFertig) hf.hinweis(abgelegt ? "Foto abgelegt: " + f.ablage.get(0).getFileName() : "Foto: " + f.text(),
                f.ende() == Foto.Ende.FEHLER ? Hinweis.Art.FEHLER : abgelegt ? Hinweis.Art.OK : Hinweis.Art.INFO);
    }

    // ------------------------------------------------------------------ Ende, Merken

    private void schliessen() {
        if (!zu) protokoll.schreibe("App", "Ende");
        zu = true;
        Buehne b = buehne;
        if (b != null) b.beenden();
    }

    private void schliessenUndZu() {
        schliessen();
        if (buehne == null) System.exit(0);
    }

    /** Schreibt Fenster, Docks, Thema, Arbeitsbereich, Szenario, Ansicht und Zeitraffer in die Einstellungen und speichert. */
    private void merken() {
        hf.merken();
        Szenario s = szenarioJetzt;
        Ansicht a = ansichtJetzt;
        if (s != null) einstellungen.setze("szenario", s.schluessel);
        if (a != null) einstellungen.setze("ansicht", a.schluessel);
        einstellungen.setze("zeitraffer", bedienung.wert());
        boolean ok = einstellungen.speichern();
        System.out.println("Steuerhaus: Einstellungen " + (ok ? "gespeichert in " : "NICHT gespeichert: ") + einstellungen.datei);
    }

    // ------------------------------------------------------------------ Fassungen, Probe

    static String fassungSteuerhaus() { return "Steuerhaus W3 · " + datumDesJars(Szenenapp.class); }

    private List<String> fassungen(String geraet) {
        List<String> l = new ArrayList<>();
        if (!manifest.version().isEmpty()) l.add(manifest.titel() + " " + manifest.version());
        l.add(fassungSteuerhaus());
        l.add("Renderer · " + datumDesJars(Buehne.class));
        l.add("FStyle · " + datumDesJars(com.dan.fframe.FFrame.class));
        l.add("Java " + System.getProperty("java.version") + (geraet != null ? " · " + geraet : ""));
        return l;
    }

    /** Datum der Datei, aus der eine Klasse geladen ist (Jar oder Ordner). */
    static String datumDesJars(Class<?> c) {
        try {
            File f = new File(c.getProtectionDomain().getCodeSource().getLocation().toURI());
            return "Stand " + TAG.format(java.time.Instant.ofEpochMilli(f.lastModified()).atZone(ZoneId.systemDefault()));
        } catch (Exception e) {
            return "Stand unbekannt";
        }
    }

    /**
     * Für die Abnahme (W1, Prüfsteine 4 und 6): gibt die Einblendung alle zwei Sekunden aus, mittelt Bilder/s und
     * Bildpunkte/s über die zweite Hälfte der Zeit, prüft die erwarteten Einstellungen und beendet die App.
     */
    private void probe(Buehne b, double sekunden, long n0) {
        Thread t = new Thread(() -> {
            double fSumme = 0, fKleinst = Double.POSITIVE_INFINITY, kSumme = 0, kKleinst = Double.POSITIVE_INFINITY;
            int fZahl = 0, kZahl = 0;
            List<double[]> proben = new ArrayList<>();                          // {s, Bilder/s} für Prüfstein 4 (A45)
            long bilderAlt = -1;
            double gelaufen = 0;
            com.dan.steuerhaus.regie.Regie gesehen = null;
            boolean imKinoGesehen = false;
            try {
                for (double s = 2; s <= sekunden; s += 2) {
                    Thread.sleep(2000);
                    Buehne.Zustand z = b.zustand();
                    Regisseur rg = regisseur;
                    boolean faehrt = rg != null && rg.faehrt();
                    gelaufen = s;
                    long neu = bilderAlt < 0 ? 0 : z.bilder - bilderAlt;                  // seit W3b (A47): Bilder in den 2 s
                    bilderAlt = z.bilder;
                    System.out.println("         " + z.text(zone, pegel) + " · " + neu + " Bilder in 2 s" + (faehrt ? " · Fahrt" : "") + (rg != null && rg.regie() != null
                            ? String.format(Locale.GERMAN, " · Regie %.0f s", rg.regie().uhr()) + (rg.regie().wartet() != null ? " (wartet: " + rg.regie().wartet().befehl() + " " + rg.regie().wartet().argumente() + ")" : "") : ""));
                    if (z.bilder > 0) proben.add(new double[] {s, z.bilderJeS});
                    if (faehrt && z.bilder > 0 && s > 4) {                              // Prüfstein 9 (W3)
                        fSumme += z.bilderJeS;
                        fKleinst = Math.min(fKleinst, z.bilderJeS);
                        fZahl++;
                    }
                    // seit C1b: ohne Fahrt im Buch gilt Prüfstein 9 für das ganze Kino; dazu je 10 s die teuersten Abschnitte
                    if (rg != null && rg.imKino() && z.bilder > 0 && s > 4) {
                        kSumme += z.bilderJeS;
                        kKleinst = Math.min(kKleinst, z.bilderJeS);
                        kZahl++;
                        if (((int) s) % 10 == 0) {
                            java.util.List<java.util.Map.Entry<String, Double>> ab = new ArrayList<>(b.fensterAbschnitte().entrySet());
                            ab.sort((x, y) -> Double.compare(y.getValue(), x.getValue()));
                            StringBuilder sb = new StringBuilder();
                            for (int k = 0; k < Math.min(6, ab.size()); k++) sb.append(k > 0 ? ", " : "").append(ab.get(k).getKey()).append(String.format(Locale.GERMAN, " %.2f ms", ab.get(k).getValue()));
                            System.out.println("         Abschnitte: " + sb + " · Qualität " + qualitaet.stufe().name);
                        }
                    }
                    if (rg != null && rg.regie() != null) gesehen = rg.regie();
                    if (rg != null && rg.imKino()) imKinoGesehen = true;
                    if (o.regie() != null && imKinoGesehen && rg != null && !rg.imKino()) {
                        System.out.println("         Kino zu Ende nach " + (int) s + " s");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            Buehne.Zustand z = b.zustand();
            // A45: die zweite Hälfte der Zeit, die wirklich gelaufen ist (mit --regie endet die Probe mit dem Kino)
            double summe = 0;
            int zahl = 0;
            for (double[] p : proben)
                if (p[0] > gelaufen / 2) {
                    summe += p[1];
                    zahl++;
                }
            double mittel = zahl > 0 ? summe / zahl : 0, mpx = mittel * b.breite() * b.hoehe() / 1e6;
            if (o.messblick()) System.out.println("         Messblick: " + (b.messblickAn() ? "an" : "aus") + ", " + b.messGitter());
            if (o.schnitt() != null) schnittAblegen();
            System.out.printf(Locale.GERMAN, "  %s Prüfstein 4: %s, Bild %d × %d: %.1f Bilder/s, %.1f Mio. Bildpunkte/s im Mittel der zweiten Hälfte von %.0f s%s  [≥ 30 Bilder/s [G]; Vergleich mit der alten App im Lauf]%n",
                    mittel >= 30 ? "ok    " : "FEHLT ", z.szenario != null ? z.szenario.name : "kein Szenario", b.breite(), b.hoehe(), mittel, mpx, gelaufen,
                    z.fehler != null ? "; Fehler: " + z.fehler : "");
            // seit D1 (Werkbuch HalligHooge 162): wie lange die Uhr auf Stände gewartet hat (gepackte Archive: der Vorleser entpackt)
            if (z.szenario != null && !z.szenario.live())
                System.out.printf(Locale.GERMAN, "         Daten: die Uhr wartete %d-mal auf Stände, zusammen %.2f s%n", z.datenWarteZahl, z.datenGewartetSumme);
            Einblendungen eb = ein;
            if (eb != null) System.out.println("         Einblendungen: " + eb.wirksam() + ", zuletzt " + b.einblendung().zuletztGezeichnet + " Kacheln im Bild, "
                    + eb.orte() + " Ortsnamen; Maßstab " + java.util.Arrays.toString(eb.massstab()) + "; Wasser der Minikarte " + eb.wasserAnteil());
            System.out.printf(Locale.GERMAN, "         Längstes Bild der Bildschleife: %.2f s%s%n", b.laengstesBild(), b.langesBild() != null ? "; " + b.langesBild() : "");
            boolean ok = mittel >= 30 && z.fehler == null;
            if (o.regie() != null) {
                boolean mitFahrt = fZahl > 0;
                if (!mitFahrt) {
                    fSumme = kSumme;
                    fKleinst = kKleinst;
                    fZahl = kZahl;
                }
                double fm = fZahl > 0 ? fSumme / fZahl : 0;
                boolean f9 = fZahl > 0 && fm >= 30;
                System.out.printf(Locale.GERMAN, "  %s Prüfstein 9: Bildrate im Fenster %s: %.1f Bilder/s im Mittel, kleinste %.1f (%d Proben zu 2 s)  [≥ 30 Bilder/s]%n",
                        f9 ? "ok    " : "FEHLT ", mitFahrt ? "während der Fahrt" : "im Kino (Buch ohne Fahrt)", fm, fZahl > 0 ? fKleinst : 0, fZahl);
                boolean durch = gesehen != null && gesehen.gelaufen() == gesehen.buch.zeilen.size();
                System.out.printf(Locale.GERMAN, "  %s Regiebuch „%s“: %d von %d Zeilen gelaufen, Kino %s%s%n", durch ? "ok    " : "FEHLT ",
                        gesehen != null ? gesehen.buch.name : o.regie(), gesehen != null ? gesehen.gelaufen() : 0, gesehen != null ? gesehen.buch.zeilen.size() : 0,
                        imKinoGesehen ? "gesehen" : "nicht gesehen", gesehen != null && !gesehen.meldungen().isEmpty() ? "; Meldungen: " + gesehen.meldungen() : "");
                ok &= f9 && durch;
                Regisseur rx = rg0();
                double[] bk = rx == null ? new double[] {Double.NaN, Double.NaN, 0} : rx.blendeKosten();
                if (rx != null)
                System.out.printf(Locale.GERMAN, "         Kino: vorbereitet in %.1f s; Überblendungen %s; Blende im Fenster %.3f ms im Mittel, größte %.3f ms (%.0f Bilder) [Auskunft, F198 A]; Merken größte %.3f ms%n",
                        rx.vorbereitet(), rx.merkenMoeglich() ? "weich (Swapchain mit TRANSFER_SRC)" : "hart (Swapchain ohne TRANSFER_SRC)", bk[0], bk[1], bk[2], rx.merkenKosten());
            }
            if (!o.erwarte().isEmpty()) ok &= erwartung();
            probeOk = ok;
            b.beenden();
        }, "probe");
        t.setDaemon(true);
        t.start();
    }

    private Regisseur rg0() { return regisseur; }

    /** Prüfstein 6: Was beim Start aus den Einstellungen kam, gegen die Erwartung aus dem Aufruf. */
    private boolean erwartung() {
        Map<String, String> ist = new LinkedHashMap<>();
        try {
            SwingUtilities.invokeAndWait(() -> {
                ist.put("szenario", szenarioJetzt == null ? "" : szenarioJetzt.schluessel);
                ist.put("ansicht", ansichtJetzt == null ? "" : ansichtJetzt.schluessel);
                ist.put("thema", Thema.aktuell().schluessel());
                ist.put("arbeitsbereich", hf.arbeitsbereich());
                ist.put("links", String.valueOf(hf.links.offen()));
                ist.put("rechts", String.valueOf(hf.rechts.offen()));
                ist.put("unten", String.valueOf(hf.unten.offen()));
                ist.put("rechts.mass", String.valueOf(hf.rechts.mass()));
            });
        } catch (Exception e) {
            System.out.println("  FEHLT  Prüfstein 6: " + e);
            return false;
        }
        List<String> falsch = new ArrayList<>();
        o.erwarte().forEach((k, v) -> {
            if (!v.equals(ist.get(k))) falsch.add(k + " = " + ist.get(k) + " statt " + v);
        });
        System.out.println((falsch.isEmpty() ? "  ok     " : "  FEHLT  ") + "Prüfstein 6: Einstellungen nach dem Neustart " + ist + (falsch.isEmpty() ? "" : "; falsch: " + falsch)
                + "  [wie erwartet: " + o.erwarte() + "]");
        return falsch.isEmpty();
    }

    /** Für die Abnahme: wartet auf die Reihe Q(t) (höchstens 120 s), legt das Fenster des Schnitts als Bild ab. */
    private void schnittAblegen() {
        try {
            for (int i = 0; i < 1200 && reihe == null; i++) Thread.sleep(100);
            Thread.sleep(400);
            SchnittFenster f = schnittFenster;
            if (f == null || reihe == null) {
                System.out.println("  FEHLT  Schnitt: kein Fenster oder keine Reihe Q(t)");
                probeOk = false;
                return;
            }
            double[][] r = reihe;
            double qmax = 0;
            int n = 0;
            for (double v : r[1])
                if (!Double.isNaN(v)) {
                    qmax = Math.max(qmax, Math.abs(v));
                    n++;
                }
            Schnitt.Bilanz bl = f.bilanz();
            File png = new File("docs" + File.separator + "status" + File.separator + "P11c3_Schnitt.png");
            png.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(f.bild(1200, 700), "png", png);
            System.out.printf(Locale.GERMAN, "         Schnitt: %d Stände, |Q| bis %.0f m³/s; über die Tide um jetzt nach links %.2f, nach rechts %.2f Mio. m³; Bild %s%n", n, qmax,
                    bl == null ? Double.NaN : bl.links() / 1e6, bl == null ? Double.NaN : bl.rechts() / 1e6, png.getPath());
        } catch (Exception e) {
            System.out.println("  FEHLT  Schnitt: " + e);
            probeOk = false;
        }
    }
}
