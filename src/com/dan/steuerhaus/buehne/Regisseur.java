package com.dan.steuerhaus.buehne;

import com.dan.core.raum.Blick;
import com.dan.kino.Kino;
import com.dan.lauf.Buehne;
import com.dan.lauf.Kamera;
import com.dan.render.overlay.Einblendung;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Regie;
import com.dan.steuerhaus.regie.Regiebuch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Der Regisseur (seit W3, Werkbuch HalligHooge 116): die Kamera-Quelle der Bühne ({@link Kamera}, F192 A) und der
 * Ausführende der Regiebücher ({@link Regie.Ctx}). Er läuft ganz im Faden der Bildschleife: Fahrten, Flüge, Regiebuch und
 * Kino gehen Bild für Bild weiter, ohne den Takt der Oberfläche. Aufträge aus der Oberfläche ({@link #spiele},
 * {@link #fahre}, {@link #fliege}, {@link #abbrechen}) kommen über eine Warteschlange in die Bildschleife.
 * <ul>
 * <li><b>Filmzeit</b> (F195 A): Fahrten laufen in echten Sekunden; steht ein {@code warte}, steht auch die Fahrt.</li>
 * <li><b>Schnitt im Kino:</b> Szenario, Ansicht, Fahrt und Sprung in der Zeit warten, bis die Einblendung das letzte Bild
 * gemerkt hat, dann blendet es 1,6 s über das neue (F194 A).</li>
 * <li><b>Schwenk:</b> im Kino bei stehender Kamera ±3° über 70 s (aus {@code com.dan.kino}).</li>
 * </ul>
 */
public final class Regisseur implements Kamera, Regie.Ctx {
    /** Was der Regisseur von der Anwendung braucht. Die Methoden dürfen aus der Bildschleife gerufen werden. */
    public interface App {
        void szenario(String schluessel);

        void ansicht(String schluessel);

        /** Eine Fahrt nach Namen, mit Höhen in m NHN (null: unbekannt). */
        Fahrt fahrt(String name);

        void raffer(double x);

        void halt(boolean halt);

        void ebene(String name, boolean an);

        void messblick(String arg);

        void linien(String art);

        void einblendung(String arg);

        void thema(String name);

        void lesezeichen(String name);

        void hinweis(String text);

        /** Höhe der Marke (Landunter), m NHN; NaN: keine. */
        double marke();

        ZoneId zone();

        /** Kopf der Titeltafel und die Zeilen des Abspanns. */
        String kopf();

        List<String> abspann();

        /** Das Kino ist ganz zu Ende (Oberfläche wiederherstellen). */
        void kinoEnde();

        /** Höhe der Balken des Kinos jetzt (Bildpunkte), je Bild; die Einblendungen am Rand rücken nach innen. */
        default void balken(int px) { }

        /** Seit X2: eine Sonde am Ort („Ost Nord [Name]“); {@code fertig} bekommt sie, wenn sie gelesen ist (aus irgendeinem Faden). */
        default void sonde(String arg, java.util.function.Consumer<com.dan.lauf.Sonde> fertig) { }

        /** Seit X2: ein Kapitel ist zu Ende (Oberfläche wiederherstellen). */
        default void kapitelEnde() { }
    }

    private final Buehne b;
    private final Einblendung e;
    private final App app;
    public final Kino kino = new Kino();
    private final Kinobild kinobild;
    /** Seit X2 (Werkbuch HalligHooge 146): Kapitel – Tafel, Abschnitte, Weiter und Zurück. */
    private final Kapitelbild kapitelbild;
    private volatile boolean kapitel, weiterKlick;
    private volatile String kapitelName;
    private final ConcurrentLinkedQueue<Runnable> auftraege = new ConcurrentLinkedQueue<>();

    private volatile Regie regie;
    private volatile boolean imKino;
    private volatile Fahrt fahrt;
    private volatile double fahrtT;
    private final List<Runnable> nachSchnitt = new ArrayList<>();
    private long merkStand = -1;
    private double szenenzeit = Double.NaN, schwenkAlt;
    private volatile Blick zuletzt;
    private volatile String meldung;
    /** Ein Sprung in der Zeit, der auf das neue Szenario wartet (sonst gälte er für die Uhr des alten). */
    private Instant zeitZiel;
    private int balkenAlt = -1;
    private int meldungenGesehen;
    /**
     * Seit W3b (Werkbuch HalligHooge 118, F199 A): Das Kino beginnt schwarz; erst gelten die Zeilen der ersten Sekunde
     * (Szenario, Zeit, Ansicht), dann läuft der Vorspann. -1: nicht im Vorbereiten, sonst die Bilder seit dem Sprung.
     */
    private int vorbereiten = -1;
    private long vorbereitetSeit;
    private volatile double vorbereitetS = Double.NaN;
    private volatile double blendeGroesst = Double.NaN, blendeSumme, merkenGroesst = Double.NaN;
    private volatile int blendeZahl;

    /**
     * Seit X3 (Werkbuch HalligHooge 148): Umsehen während einer Fahrt im Kapitel oder Rundgang. Was die Maus seit dem
     * vorigen Bild an Richtung und Neigung geändert hat (der Blick der Steuerung gegen den, den der Regisseur zuletzt
     * gesetzt hat), kommt auf die Fahrt; lässt man los, kehrt der Blick in rund {@link #UMSEHEN_ZURUECK} s zur Fahrt
     * zurück. Beginnt eine Fahrt, setzt sie dort an, wohin man gerade schaut (kein Sprung).
     */
    private Fahrt umsehenFahrt;
    private Blick umsehenAus;
    private double umsehenR, umsehenN, umsehenRuhe;
    /** Zeitkonstante (s), mit der der Blick nach dem Loslassen zur Fahrt zurückkehrt, und wie lange er vorher bleibt [G]. */
    static final double UMSEHEN_ZURUECK = 1.2, UMSEHEN_BLEIBT = 1.5;

    private void umsehen(Fahrt f, double dt, Blick jetzt, Blick aus) {
        if (f != umsehenFahrt || umsehenAus == null) {
            // ein Schnitt (die Fahrt steht still oder beginnt weit weg): ohne Umsehen; sonst vom jetzigen Blick aus zurückkehren
            boolean schnitt = f.ende() - f.anfang() <= 0 || Math.hypot(jetzt.ost - aus.ost, jetzt.nord - aus.nord) > 30;
            umsehenFahrt = f;
            umsehenR = schnitt ? 0 : winkel(jetzt.richtung - aus.richtung);
            umsehenN = schnitt ? 0 : jetzt.neigung - aus.neigung;
            umsehenRuhe = UMSEHEN_BLEIBT;
        } else {
            double dr = winkel(jetzt.richtung - umsehenAus.richtung), dn = jetzt.neigung - umsehenAus.neigung;
            if (Math.abs(dr) > 1e-6 || Math.abs(dn) > 1e-6) {
                umsehenR += dr;
                umsehenN += dn;
                umsehenRuhe = 0;
            } else umsehenRuhe += dt;
            if (umsehenRuhe >= UMSEHEN_BLEIBT) {
                double k = Math.exp(-dt / UMSEHEN_ZURUECK);
                umsehenR *= k;
                umsehenN *= k;
            }
        }
        aus.richtung = ((aus.richtung + umsehenR) % 360 + 360) % 360;
        aus.neigung = Math.max(-89, Math.min(89, aus.neigung + umsehenN));
        umsehenAus = aus.kopie();
    }

    /** Ein Winkel auf −180 … 180. */
    static double winkel(double d) { return ((d % 360) + 540) % 360 - 180; }

    public Regisseur(Buehne b, App app) {
        this.b = b;
        this.e = b.einblendung();
        this.app = app;
        kinobild = new Kinobild(e);
        kapitelbild = new Kapitelbild(e);
    }

    /** Seit X2: spielt ein Kapitel – ohne Kino, mit Tafeln; ein {@code warte klick} wartet auf {@link #weiterKlick}. */
    public void spieleKapitel(Regiebuch buch) {
        auftraege.add(() -> {
            fahrt = null;
            nachSchnitt.clear();
            merkStand = -1;
            vorbereiten = -1;
            imKino = false;
            kapitelbild.aus();
            kapitel = true;
            kapitelName = buch.name;
            weiterKlick = false;
            regie = new Regie(buch, this);
            meldungenGesehen = 0;
        });
    }

    /** Seit X2: im Kapitel zum nächsten Abschnitt (Klick, Eingabe). */
    public void weiterKlick() { auftraege.add(() -> weiterKlick = true); }

    /** Seit X2: im Kapitel zum Abschnitt davor (Rücktaste). */
    public void zurueck() {
        auftraege.add(() -> {
            Regie r = regie;
            if (r == null || !kapitel) return;
            fahrt = null;
            weiterKlick = false;
            r.springe(Math.max(0, r.abschnitt() - 1));
        });
    }

    /** Läuft ein Kapitel? */
    public boolean imKapitel() { return kapitel; }

    // ------------------------------------------------------------------ Aufträge (aus jedem Faden)

    /** Spielt ein Regiebuch, im Kino oder ohne. */
    public void spiele(Regiebuch buch, boolean mitKino) {
        auftraege.add(() -> {
            fahrt = null;
            nachSchnitt.clear();
            merkStand = -1;
            vorbereiten = -1;
            regie = new Regie(buch, this);
            meldungenGesehen = 0;
            imKino = mitKino;
            if (mitKino) {
                schwenkAlt = 0;
                kino.beginnen(app.kopf(), buch.name, buch.beschreibung);   // schwarz von Anfang an
                vorbereiten = 0;
                vorbereitetSeit = System.nanoTime();
                boolean teuer = false;
                for (Regiebuch.Zeile z : buch.zeilen)
                    if (z.sekunde() <= 1e-9 && (z.befehl().equals("zeit") || z.befehl().equals("szenario") || z.befehl().equals("lesezeichen"))) teuer = true;
                if (teuer) app.hinweis("„" + buch.name + "“: Szene wird vorbereitet …");
                regie.schritt(0);                                           // die Zeilen der ersten Sekunde, im Schwarz ohne Überblendung
            }
        });
    }

    /** Spielt eine Fahrt (Höhen in m NHN) ohne Regiebuch. */
    public void fahre(Fahrt f) {
        auftraege.add(() -> {
            fahrt = f;
            fahrtT = f.anfang();
        });
    }

    /** Fliegt in {@code dauer} s vom jetzigen Blick zu {@code nach} = {Ost, Nord, Höhe NHN, Richtung, Neigung, Bildwinkel}. */
    public void fliege(double[] nach, double dauer) {
        auftraege.add(() -> {
            Blick j = zuletzt;
            if (j == null) return;
            fahrt = Fahrt.flug("Flug", new double[] {j.ost, j.nord, j.hoehe, j.richtung, j.neigung, j.bildwinkel}, nach, dauer);
            fahrtT = 0;
        });
    }

    /** Bricht Regiebuch, Fahrt und Kino ab (Esc). */
    public void abbrechen() {
        auftraege.add(() -> {
            regie = null;
            fahrt = null;
            vorbereiten = -1;
            for (Runnable r : nachSchnitt) r.run();
            nachSchnitt.clear();
            merkStand = -1;
            e.blende = 0;
            if (imKino) kino.abbrechen();
            if (kapitel) {
                kapitel = false;
                kapitelbild.aus();
            }
        });
    }

    public boolean imKino() { return imKino; }

    /** Seit C1b (A53): ob gerade eine Überblendung läuft (dort darf die Qualitätsstufe wechseln). */
    public boolean blendet() { return blendet; }

    private volatile boolean blendet;

    /**
     * Seit W4 (F206 A): die Zeilen des Abspanns – der Titel der App, alle Quellen aus dem Manifest ({@code quelle.N}), die
     * Herkunft des Bilds. Rollt der Abspann, umbricht der Maler lange Zeilen; als feste Tafel (Kino.rollen = false)
     * höchstens neun Zeilen zu 110 Zeichen.
     */
    public static List<String> abspann(com.dan.steuerhaus.Manifest m) {
        List<String> l = new java.util.ArrayList<>();
        l.add(m.titel());
        for (String q : m.quellen()) l.add(q);
        l.add("Bild: Steuerhaus und Renderer");
        return l;
    }

    /** Seit W4: Tafeln sofort malen (für Filmbilder; im Fenster in einem eigenen Faden). */
    public void tafelnSofort(boolean s) { kinobild.sofort = s; }

    /** Seit W3b: Dauer des Vorbereitens vor dem Vorspann (s; NaN: noch nicht oder ohne Kino). */
    public double vorbereitet() { return vorbereitetS; }

    /** Seit W3b: Kosten der Blende im Fenster je Bild während der Überblendungen: {Mittel, Größte, Zahl der Bilder} (ms). */
    public double[] blendeKosten() { return new double[] {blendeZahl > 0 ? blendeSumme / blendeZahl : Double.NaN, blendeGroesst, blendeZahl}; }

    /** Seit W3b: größte Kosten des Merkens im Fenster (ms; NaN: nie gemessen). */
    public double merkenKosten() { return merkenGroesst; }

    /** Seit W3b: Kann die Ausgabe gemerkt werden (Swapchain mit TRANSFER_SRC)? Sonst sind die Schnitte hart. */
    public boolean merkenMoeglich() { return e.merkenMoeglich(); }

    public boolean faehrt() { return fahrt != null; }

    public Regie regie() { return regie; }

    /** Für die Zeitleiste: {Uhr, Länge} des laufenden Regiebuchs oder der Fahrt (null: nichts läuft). */
    public double[] fortschritt() {
        Regie r = regie;
        if (r != null) return new double[] {r.uhr(), r.buch.laenge};
        Fahrt f = fahrt;
        return f == null ? null : new double[] {fahrtT - f.anfang(), f.ende() - f.anfang()};
    }

    /** Letzte Meldung (Wartezeit überschritten, unbekannte Fahrt) oder null; einmal. */
    public String meldung() {
        String m = meldung;
        meldung = null;
        return m;
    }

    // ------------------------------------------------------------------ Kamera (Bildschleife)

    @Override public Blick weiter(double dt, double ts, Blick jetzt) {
        // seit W4b (A50): erst Szenenzeit und Blick, dann die Aufträge – die Zeilen der ersten Sekunde eines Regiebuchs
        // (zeit, lesezeichen) brauchen die Szenenzeit schon, wenn spiele() der erste Auftrag ist (Ausgabe ohne Fenster)
        szenenzeit = ts;
        zuletzt = jetzt;
        Runnable a;
        while ((a = auftraege.poll()) != null) a.run();
        if (zeitZiel != null && !b.wechselt() && b.uhr() != null) {
            b.zeit((zeitZiel.toEpochMilli() - b.uhr().start.toEpochMilli()) / 1000.0);
            zeitZiel = null;
        }
        // ein Schnitt im Kino: erst das Bild merken lassen, dann schneiden
        if (!nachSchnitt.isEmpty()) {
            if (merkStand < 0) {
                merkStand = e.gemerkt();
                e.merken();
            } else if (e.gemerkt() > merkStand) {
                for (Runnable r : nachSchnitt) r.run();
                nachSchnitt.clear();
                merkStand = -1;
                kino.geschnitten();
                schwenkAlt = 0;
            }
        }
        if (vorbereiten >= 0) {
            // das Kino steht schwarz, bis Szenario, Ansicht und Sprung in der Zeit gelten und zwei Bilder danach fertig sind
            boolean fertig = zeitZiel == null && nachSchnitt.isEmpty() && !b.wechselt() && b.uhr() != null;
            vorbereiten = fertig ? vorbereiten + 1 : 0;
            if (vorbereiten < 3) {
                kino.schritt(0);
                kinobild.aktualisiere(kino, b.breite(), b.hoehe());
                int bp = kino.balkenPx(b.breite(), b.hoehe());
                if (bp != balkenAlt) {
                    balkenAlt = bp;
                    app.balken(bp);
                }
                return null;
            }
            vorbereiten = -1;
            vorbereitetS = (System.nanoTime() - vorbereitetSeit) / 1e9;
            dt = 0;
        }
        if (imKino || kino.sichtbar()) {
            kino.schritt(dt);
            kinobild.aktualisiere(kino, b.breite(), b.hoehe());
            int bp = kino.balkenPx(b.breite(), b.hoehe());
            if (bp != balkenAlt) {
                balkenAlt = bp;
                app.balken(bp);
            }
            e.blende = kino.ueberblende();
            blendet = e.blende > 0;
            if (e.blende > 0) {
                double ms = b.fensterAbschnitt("Einblendung, Blende");
                if (!Double.isNaN(ms) && ms > 0) {
                    blendeGroesst = Double.isNaN(blendeGroesst) ? ms : Math.max(blendeGroesst, ms);
                    blendeSumme += ms;
                    blendeZahl++;
                }
                double mm = b.fensterAbschnitt("Einblendung, merken");
                if (!Double.isNaN(mm) && mm > 0) merkenGroesst = Double.isNaN(merkenGroesst) ? mm : Math.max(merkenGroesst, mm);
            }
            if (kino.endeMelden()) {
                imKino = false;
                balkenAlt = -1;
                app.balken(0);
                kinobild.aus();
                e.blende = 0;
                app.kinoEnde();
            }
        }
        Regie rg = regie;
        if (rg != null) {
            rg.schritt(dt);
            List<String> ml = rg.meldungen();
            if (ml.size() > meldungenGesehen) {
                meldung = ml.get(ml.size() - 1);
                meldungenGesehen = ml.size();
            }
            if (kapitel) kapitelbild.aktualisiere(b.breite(), b.hoehe(), szenenzeit);
            if (rg.fertig()) {
                regie = null;
                if (imKino) kino.beenden(app.abspann());
                if (kapitel) {
                    kapitel = false;
                    kapitelbild.aus();
                    app.kapitelEnde();
                }
            }
        }
        Blick aus = null;
        Fahrt f = fahrt;
        if (f != null) {
            boolean steht = rg != null && rg.wartet() != null;
            if (!steht) fahrtT += dt;
            double[] l = f.lage(fahrtT);
            aus = jetzt.kopie();
            aus.ost = l[0];
            aus.nord = l[1];
            aus.hoehe = l[2];
            aus.richtung = l[3];
            aus.neigung = l[4];
            aus.bildwinkel = l[5];
            if (kapitel) umsehen(f, dt, jetzt, aus);
            if (fahrtT >= f.ende()) fahrt = null;
        } else if (imKino) {
            // Schwenk bei stehender Kamera: die Änderung seit dem vorigen Bild auf die Richtung
            double sw = kino.schwenk();
            if (sw != schwenkAlt) {
                aus = jetzt.kopie();
                aus.richtung = ((jetzt.richtung + sw - schwenkAlt) % 360 + 360) % 360;
            }
            schwenkAlt = sw;
        }
        if (fahrt == null && f == null) {
            umsehenFahrt = null;
            umsehenAus = null;
        }
        return aus;
    }

    // ------------------------------------------------------------------ Regiebuch (Bildschleife)

    @Override public void ausfuehren(Regiebuch.Zeile z) {
        String arg = z.argumente();
        String text = z.befehl().equals("sage") ? arg : z.befehl().equals("tafel") || z.befehl().equals("unterwegs") ? "" : z.text();
        if (!text.isEmpty()) {
            if (imKino) kino.untertitel(text);
            else app.hinweis(text);
        }
        switch (z.befehl()) {
            case "szenario" -> schnitt(() -> {
                fahrt = null;
                app.szenario(arg);
            });
            case "ansicht" -> schnitt(() -> {
                fahrt = null;
                app.ansicht(arg);
            });
            case "fahrt" -> {
                Fahrt f = app.fahrt(arg);
                if (f == null) meldung = "Zeile " + z.nr() + ": Fahrt „" + arg + "“ gibt es nicht";
                else schnitt(() -> {
                    fahrt = f;
                    fahrtT = f.anfang();
                });
            }
            case "zeit" -> {
                Instant ziel = zeitpunkt(arg, false);
                if (ziel != null) schnitt(() -> zeitZiel = ziel);
            }
            case "raffer" -> app.raffer(Double.parseDouble(arg.replace(',', '.')));
            case "halt" -> app.halt(true);
            case "weiter" -> app.halt(false);
            case "ebene" -> {
                String[] t = arg.split("\\s+");
                app.ebene(t[0].toLowerCase(Locale.GERMAN), t[1].equals("an"));
            }
            case "messblick" -> app.messblick(arg);
            case "linien" -> app.linien(arg);
            case "einblendung" -> app.einblendung(arg);
            case "thema" -> app.thema(arg);
            case "lesezeichen" -> app.lesezeichen(arg);
            // seit X2: Kapitel
            case "tafel" -> {
                // Nummer der Tafel unter allen Tafeln des Kapitels (die gelaufenen Zeilen zählen bis hierher)
                Regie r = regie;
                int nr = 0, von = 0;
                if (r != null) {
                    List<Regiebuch.Zeile> zl = r.buch.zeilen;
                    for (int i = 0; i < zl.size(); i++)
                        if (zl.get(i).befehl().equals("tafel")) {
                            von++;
                            if (i < r.gelaufen()) nr++;
                        }
                }
                kapitelbild.zeige(kapitelName, arg, z.text(), Math.max(1, nr), Math.max(1, von));
            }
            // seit X3: die Zwischentafel eines Rundgangs – mit der Nummer des Halts davor, ohne Sonde
            case "unterwegs" -> {
                Regie r = regie;
                int nr = 0, von = 0;
                if (r != null) {
                    List<Regiebuch.Zeile> zl = r.buch.zeilen;
                    for (int i = 0; i < zl.size(); i++)
                        if (zl.get(i).befehl().equals("tafel")) {
                            von++;
                            if (i < r.gelaufen()) nr++;
                        }
                }
                kapitelbild.zeige(kapitelName, arg, z.text(), Math.max(1, nr), Math.max(1, von));
            }
            case "sonde" -> app.sonde(arg, kapitelbild::sonde);
            case "warte" -> {
                if (arg.strip().equals("klick")) {
                    weiterKlick = false;
                    kapitelbild.wartet(true);
                }
            }
            default -> { }
        }
    }

    @Override public boolean bereit(Regiebuch.Zeile w) {
        String[] t = w.argumente().split("\\s+");
        Buehne.Zustand z = b.zustand();
        return switch (t[0]) {
            case "uhr" -> {
                Instant ziel = zeitpunkt(w.argumente().substring(3).strip(), true);
                yield ziel != null && b.uhr() != null && !Double.isNaN(szenenzeit) && !b.uhr().bei(szenenzeit).isBefore(ziel);
            }
            case "pegel" -> Regiebuch.pegelErreicht(w.argumente(), z.pegel);   // seit Teil C auch „pegel < x“ (F227 A)
            case "landunter" -> !Double.isNaN(z.pegel) && !Double.isNaN(app.marke()) && z.pegel > app.marke();
            case "fahrt" -> fahrt == null && nachSchnitt.isEmpty();
            // seit X3b: das Szenario ist offen und der Sprung in der Zeit getan (danach setzt es keine eigene Ansicht mehr)
            case "szene" -> !b.wechselt() && b.uhr() != null && zeitZiel == null && nachSchnitt.isEmpty();
            case "klick" -> {
                boolean geklickt = weiterKlick;
                if (geklickt) {
                    weiterKlick = false;
                    kapitelbild.wartet(false);
                }
                yield geklickt;
            }
            default -> true;
        };
    }

    /** Ein Schnitt: im Kino mit Überblendung, sonst sofort. */
    private void schnitt(Runnable r) {
        if (imKino && kino.schnitt()) nachSchnitt.add(r);
        else r.run();
    }

    /**
     * Ein Zeitpunkt aus „[TT.MM.[JJJJ]] hh:mm“ in der Zone der Anwendung. Ohne Datum der Tag der Szenenzeit; mit
     * {@code naechster} und ohne Datum der nächste solche Zeitpunkt (liegt er mehr als 12 h zurück, der folgende Tag).
     */
    Instant zeitpunkt(String s, boolean naechster) {
        if (b.uhr() == null || Double.isNaN(szenenzeit)) return null;
        ZoneId zone = app.zone();
        ZonedDateTime jetzt = b.uhr().bei(szenenzeit).atZone(zone);
        String[] t = s.strip().split("\\s+");
        String uhr = t[t.length - 1];
        LocalTime lt = LocalTime.parse(uhr.length() == 4 ? "0" + uhr : uhr);
        LocalDate d = jetzt.toLocalDate();
        boolean mitDatum = t.length > 1;
        if (mitDatum) {
            String[] p = t[0].split("\\.");
            int jahr = p.length > 2 && !p[2].isEmpty() ? Integer.parseInt(p[2]) : d.getYear();
            d = LocalDate.of(jahr, Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }
        ZonedDateTime z = ZonedDateTime.of(d, lt, zone);
        if (naechster && !mitDatum && z.isBefore(jetzt.minusHours(12))) z = z.plusDays(1);
        return z.toInstant();
    }
}
