package com.dan.steuerhaus.ausgabe;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Einblendungen;
import com.dan.steuerhaus.buehne.Regisseur;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.regie.Regiebuch;
import com.dan.steuerhaus.thema.Thema;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Eine Aufnahme ohne Fenster (seit W4, Werkbuch HalligHooge 121, F200–F205 A): Bühne, Regisseur, Kino und Einblendungen
 * wie in der App, aber Bild für Bild mit festem Takt ({@link Buehne#filmbild}). Je Filmbild:
 * <ol>
 * <li>Einblendungen nachführen (ihre Uhr ist die Filmzeit),</li>
 * <li>Verschluss: {@code teilbilder} Teilbilder über den offenen Teil des Bildes (Verschluss in Grad, 180° = halbe
 * Bildzeit), im Linearen gemittelt; dann rückt die Zeit über den geschlossenen Teil vor, ohne zu zeichnen,</li>
 * <li>Korn ({@link Korn}),</li>
 * <li>zurück nach sRGB, 8 Bit, gerundet.</li>
 * </ol>
 * Alles hängt nur von der Folge ab, nicht von der Uhr des Rechners: Dieselbe Aufnahme gibt dieselben Bilder.
 */
public final class Aufnahme implements AutoCloseable {
    /**
     * @param verschluss Öffnung in Grad (180 = halbe Bildzeit, 360 = ganze)
     * @param korn Stärke des Korns (0 = aus, 1 = Vision3 250D)
     * @param einblendung Einblendungen (Uhr, Kompass, …) wie im Regiebuch; aus: nur Bild und Kino
     */
    public record Optionen(int breite, int hoehe, double bilderJeS, int teilbilder, double verschluss, double korn, boolean einblendung, long saat) {
        public static Optionen vorgabe(int breite, int hoehe) { return new Optionen(breite, hoehe, 24, 8, 180, 1, true, 1958); }
    }

    public final Manifest m;
    public final Szenen sz;
    public final Optionen o;
    // seit W5b nicht mehr final: frischeBuehne() baut sie neu
    public Buehne b;
    public Regisseur r;
    public Einblendungen ein;
    public final Map<String, Regiebuch> buecher = new LinkedHashMap<>();
    public final Map<String, Fahrt> fahrten = new LinkedHashMap<>();
    public final List<Lesezeichen> lesezeichen = new ArrayList<>();
    public final List<String> fehler = new ArrayList<>();
    public final ZoneId zone;
    public final Korn korn;
    private final double marke;
    private final Consumer<String> meldung;
    private Szenario jetzt;
    private long bildNr;
    private boolean begonnen, benutzt;

    /** Seit W5b: hat die Bühne schon ein Bild gezeichnet (seit dem Bau oder {@link #frischeBuehne})? */
    public boolean benutzt() { return benutzt; }
    private float[] linear;
    private final float[] lin = new float[256];
    /** Für Prüfungen: bekommt jedes Teilbild (RGBA8 sRGB, wie {@link Buehne#filmBytes}); null: keiner. */
    public Consumer<byte[]> teilbild;
    /** Szenenzeit (s seit Start der Quelle) jedes gezeichneten Teilbilds (für Prüfstein 2). */
    public final List<Double> zeiten = new ArrayList<>();

    public Aufnahme(Manifest m, Optionen o, Consumer<String> meldung) throws Exception {
        this.m = m;
        this.o = o;
        this.meldung = meldung == null ? s -> { } : meldung;
        sz = Szenen.lies(m.szenen());
        zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        marke = sz.zahl("pegel.marke", Double.NaN);
        laden();
        Thema.zu(m.thema()).anwenden();
        for (int i = 0; i < 256; i++) {
            double c = i / 255.0;
            lin[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
        }
        buehneBauen();
        korn = new Korn(o.breite(), o.hoehe(), o.korn(), o.saat());
    }

    /**
     * Seit W5b (Werkbuch HalligHooge 128, F217 A): schließt die Bühne und baut eine frische. Ein Bild hängt an der
     * Vorgeschichte der Bühne (Belichtung u. a.); das Vergleichsbild rechnet darum jede Hälfte in einer frischen Bühne,
     * so gleicht jede einem einzelnen Standbild.
     */
    public void frischeBuehne() throws Exception {
        b.close();
        b = null;
        jetzt = null;
        bildNr = 0;
        begonnen = false;
        zeiten.clear();
        benutzt = false;
        buehneBauen();
    }

    private void buehneBauen() throws Exception {
        b = Buehne.ohneFenster(sz.gebiet(), o.breite(), o.hoehe(), (name, anteil) -> this.meldung.accept(String.format(java.util.Locale.GERMAN, "Bühne: %s (%.0f %%)", name, 100 * anteil)));
        try {
            double[] p = sz.zahlen("pegel.ort");
            if (p != null) b.pegel(sz.text("pegel.name", "Pegel"), p[0], p[1], sz.zahl("pegel.umkreis", 300));
            String[] mel = new String[1];
            double mass = Math.max(1, o.hoehe() / 1080.0);
            ein = new Einblendungen(b, m, zone, sz.text("pegel.name", "Pegel"), marke, sz.text("pegel.marke.name", "Marke"), mass, mel);
            ein.uhrMs = () -> (long) Math.floor(b.filmZeit() * 1000);
            ein.setze(EnumSet.noneOf(Einblendungen.Teil.class));
            r = new Regisseur(b, app());
            r.tafelnSofort(true);
            b.kamera(r);
        } catch (Exception | Error e) {
            b.close();
            b = null;
            throw e;
        }
    }

    /** Regiebücher, Fahrten und Lesezeichen wie die App (Manifest {@code regie.N}, {@code regie.aufnahmen}, {@code app.lesezeichen}). */
    private void laden() {
        List<File> dateien = new ArrayList<>();
        for (String d : m.nummern("regie.")) dateien.add(m.relativ(d));
        File auf = m.relativ(m.text("regie.aufnahmen", "regie/aufnahmen.regie"));
        if (auf.isFile() && !dateien.contains(auf)) dateien.add(auf);
        for (File f : dateien)
            try {
                Regiebuch rb = Regiebuch.lies(f.toPath());
                for (Regiebuch.Fehler x : rb.fehler) fehler.add(f.getName() + ", " + x);
                if (!rb.zeilen.isEmpty()) buecher.put(rb.name, rb);
                fahrten.putAll(rb.fahrten);
            } catch (IOException x) {
                fehler.add(f + ": " + x.getMessage());
            }
        // seit Teil C (Werkbuch HalligHooge 134, F227 A): „folge“ auflösen – ein Gesamtfilm spielt andere Bücher
        java.util.Map<String, Regiebuch> roh = new java.util.LinkedHashMap<>(buecher);
        for (java.util.Map.Entry<String, Regiebuch> e : buecher.entrySet()) {
            Regiebuch a = e.getValue().aufgeloest(roh::get);
            if (a == e.getValue()) continue;
            for (Regiebuch.Fehler x : a.fehler) if (!e.getValue().fehler.contains(x)) fehler.add(a.name + ", " + x);
            e.setValue(a);
        }
        File szd = m.szenen();
        String lz = m.text("app.lesezeichen", "");
        File lzd = !lz.isEmpty() ? m.relativ(lz) : new File(szd.getParentFile(), szd.getName().replaceFirst("\\.[^.]*$", "") + ".lesezeichen");
        try {
            lesezeichen.addAll(Lesezeichen.lies(lzd, fehler));
        } catch (IOException x) {
            fehler.add("Lesezeichen: " + x.getMessage());
        }
    }

    /** Ein Regiebuch nach Namen oder Dateinamen (null: keins). */
    public Regiebuch buch(String name) {
        Regiebuch rb = buecher.get(name);
        if (rb != null) return rb;
        for (Regiebuch x : buecher.values()) if (x.quelle.endsWith(name) || x.quelle.endsWith(name + ".regie")) return x;
        return null;
    }

    /** Öffnet das Szenario jetzt (wartet) und setzt seine Ansicht. */
    public void oeffne(Szenario s) throws Exception {
        jetzt = s;
        b.oeffne(s);
        if (s.ansicht != null) {
            Ansicht a = sz.ansicht(s.ansicht);
            if (a != null) b.ansicht(a);
        }
    }

    /**
     * Spielt ein Regiebuch im Kino. Steht in der ersten Sekunde {@code szenario}, wird dieses vorher geöffnet, sonst das
     * erste der Szenendatei (falls noch keins offen ist).
     */
    public void spiele(Regiebuch rb) throws Exception { spiele(rb, true); }

    /** Wie {@link #spiele(Regiebuch)}; ohne Kino ohne Balken, Vorspann und Abspann (für Prüfungen). */
    public void spiele(Regiebuch rb, boolean mitKino) throws Exception {
        fahrten.putAll(rb.fahrten);
        String sk = null;
        for (Regiebuch.Zeile z : rb.zeilen) if (z.sekunde() <= 1e-9 && z.befehl().equals("szenario")) sk = z.argumente().strip();
        Szenario s = sk != null ? sz.szenario(sk) : null;
        if (s == null && jetzt == null) s = sz.szenarien().get(0);
        if (s != null && s != jetzt) {
            if (s.archiv == null) throw new IllegalArgumentException("live-Szenario „" + s.name + "“: Video nur aus dem Archiv (F205 A)");
            oeffne(s);
        }
        r.spiele(rb, mitKino);
    }

    /** Eine Fahrt ohne Regiebuch und ohne Kino (Szenario vorher öffnen). */
    public void fahre(Fahrt f) {
        r.fahre(f.aufgeloest(b::gelaendeHoehe));
    }

    /** Läuft noch etwas (Kino, Regiebuch, Fahrt)? Vor dem ersten Bild ja. */
    public boolean laeuft() {
        if (r.imKino() || r.faehrt() || r.regie() != null) begonnen = true;
        else if (begonnen || bildNr > 2) return false;
        return true;
    }

    /** Wie viele Filmbilder bisher. */
    public long bilder() { return bildNr; }

    /** Das letzte Filmbild linear (RGB, vor dem Runden; für Prüfungen). */
    public float[] linear() { return linear; }

    /** Ein Filmbild: RGB, 8 Bit, sRGB, zeilenweise von oben. */
    public byte[] bild() {
        int w = o.breite(), h = o.hoehe(), n = Math.max(1, o.teilbilder());
        double T = 1 / o.bilderJeS(), offen = T * Math.max(1, Math.min(360, o.verschluss())) / 360;
        Buehne.Zustand z0 = b.zustand();
        if (z0 != null && z0.blick != null) ein.takt(z0);
        float[] acc = new float[3 * w * h];
        for (int k = 0; k < n; k++) {
            benutzt = true;
            Buehne.Zustand z = b.filmbild(offen / n, true);
            zeiten.add(z.t);
            byte[] p = b.filmBytes();
            if (teilbild != null) teilbild.accept(p);
            java.util.stream.IntStream.range(0, h).parallel().forEach(y -> {
                for (int x = 0, i = 3 * w * y, j = 4 * w * y; x < w; x++, i += 3, j += 4) {
                    acc[i] += lin[p[j] & 0xFF];
                    acc[i + 1] += lin[p[j + 1] & 0xFF];
                    acc[i + 2] += lin[p[j + 2] & 0xFF];
                }
            });
        }
        if (T - offen > 1e-9) b.filmbild(T - offen, false);
        float f = 1f / n;
        for (int i = 0; i < acc.length; i++) acc[i] *= f;
        korn.anwenden(acc, bildNr);
        linear = acc;
        bildNr++;
        return srgb(acc);
    }

    /** Linear (RGB) nach sRGB, 8 Bit, gerundet: über die 255 Grenzen zwischen den Stufen (genau wie mit pow, schneller). */
    public static byte[] srgb(float[] rgb) {
        byte[] out = new byte[rgb.length];
        java.util.stream.IntStream.range(0, (rgb.length + 65535) / 65536).parallel().forEach(blk -> {
            int e = Math.min(rgb.length, (blk + 1) * 65536);
            for (int i = blk * 65536; i < e; i++) {
                float c = rgb[i];
                int lo = 0, hi = 255;                                       // Zahl der Grenzen unter c
                while (lo < hi) {
                    int mid = (lo + hi) >>> 1;
                    if (GRENZE[mid] <= c) lo = mid + 1; else hi = mid;
                }
                out[i] = (byte) lo;
            }
        });
        return out;
    }

    /** Linear an der Grenze zwischen sRGB-Stufe k und k + 1 ((k + 0,5)/255). */
    private static final float[] GRENZE = new float[255];

    static {
        for (int k = 0; k < 255; k++) {
            double s = (k + 0.5) / 255;
            GRENZE[k] = (float) (s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4));
        }
    }

    /** Ein Standbild der Bühne (zwölf Durchgänge, stehende Zeit) mit Korn: RGB, 8 Bit, sRGB. */
    public byte[] standbild(Blick blick, double t) {
        benutzt = true;
        float[] p = b.standbild(blick, t);
        int n = o.breite() * o.hoehe();
        float[] rgb = new float[3 * n];
        for (int i = 0; i < n; i++)
            for (int c = 0; c < 3; c++) rgb[3 * i + c] = lin[Math.round(p[4 * i + c] * 255)];
        korn.anwenden(rgb, 0);
        linear = rgb;
        return srgb(rgb);
    }

    private Regisseur.App app() {
        return new Regisseur.App() {
            private int linienArt;

            @Override public void szenario(String k) {
                Szenario s = sz.szenario(k);
                if (s == null) {
                    hinweis("Szenario „" + k + "“ gibt es nicht");
                    return;
                }
                if (s == jetzt) return;
                jetzt = s;
                b.szenario(s);
                if (s.ansicht != null && sz.ansicht(s.ansicht) != null) b.ansicht(sz.ansicht(s.ansicht));
            }

            @Override public void ansicht(String k) {
                Ansicht a = sz.ansicht(k);
                if (a != null) b.ansicht(a);
                else hinweis("Ansicht „" + k + "“ gibt es nicht");
            }

            @Override public Fahrt fahrt(String name) {
                Fahrt f = fahrten.get(name);
                return f == null ? null : f.aufgeloest(b::gelaendeHoehe);
            }

            @Override public void raffer(double x) { b.zeitraffer(x); }

            @Override public void halt(boolean h) { b.pause(h); }

            @Override public void ebene(String name, boolean an) {
                switch (name) {
                    case "häuser", "haeuser" -> b.haeuser(an);
                    case "gestalt" -> b.gestalt(an);
                    case "bewuchs" -> b.bewuchs(an);
                    case "salzwiese" -> b.salzwiese(an);
                    default -> b.brandung(an);
                }
            }

            @Override public void messblick(String arg) {
                boolean aus = arg.equals("aus");
                b.messblickEbenen(!aus && arg.contains("partikel"), !aus && arg.contains("lic"), !aus && arg.contains("linien"), linienArt);
            }

            @Override public void linien(String art) {
                linienArt = switch (art.toLowerCase(java.util.Locale.GERMAN)) {
                    case "tiefe" -> 1;
                    case "trübung", "truebung" -> 2;
                    default -> 0;
                };
            }

            @Override public void einblendung(String arg) {
                Set<Einblendungen.Teil> w = EnumSet.noneOf(Einblendungen.Teil.class);
                if (o.einblendung()) {
                    if (arg.equals("alle")) w = EnumSet.allOf(Einblendungen.Teil.class);
                    else if (!arg.equals("aus"))
                        for (String t : arg.split(",")) {
                            Einblendungen.Teil x = Einblendungen.Teil.von(t.strip());
                            if (x != null) w.add(x);
                        }
                }
                ein.setze(w);
            }

            @Override public void thema(String name) { Thema.zu(name).anwenden(); }

            @Override public void lesezeichen(String name) {
                for (Lesezeichen lz : lesezeichen)
                    if (lz.name().equals(name)) {
                        Szenario s = sz.szenario(lz.szenario());
                        if (s != null && s != jetzt) szenario(lz.szenario());
                        if (b.uhr() != null) b.zeit((lz.zeit().toEpochMilli() - b.uhr().start.toEpochMilli()) / 1000.0);
                        double[] v = lz.blick();
                        r.fahre(new Fahrt("Lesezeichen", List.of(new Fahrt.Schluesselbild(0, v[0], v[1], v[2], false, v[3], v[4], v[5]))));
                        return;
                    }
                hinweis("Lesezeichen „" + name + "“ gibt es nicht");
            }

            @Override public void hinweis(String text) { meldung.accept("Regie: " + text); }

            @Override public double marke() { return marke; }

            @Override public ZoneId zone() { return zone; }

            @Override public String kopf() { return m.titel() + (m.unterzeile().isEmpty() ? "" : " · " + m.unterzeile()); }

            @Override public List<String> abspann() { return Regisseur.abspann(m); }

            @Override public void balken(int px) { ein.rand(px); }

            @Override public void kinoEnde() { }
        };
    }

    @Override public void close() {
        if (b != null) b.close();
        b = null;
    }
}
