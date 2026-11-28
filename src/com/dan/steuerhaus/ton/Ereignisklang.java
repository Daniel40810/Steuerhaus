package com.dan.steuerhaus.ton;

import com.dan.lauf.Buehne;
import com.dan.soundscape.Hum;
import com.dan.soundscape.Soundscape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Seit S8c (Seegang Werkbuch, Datei 104, SF159 A): was in der Szene einmal klingt, mit den Stimmen aus
 * {@code com.dan.soundscape} – Brecher (dumpfer Schlag 60 → 35 Hz, Krachen, zurückfallendes Wasser), Überlauf und Bugschlag
 * (Schwall), das Typhon beim Ablegen (220 Hz, KVR), dazu die Maschinen der Schiffe in Fahrt (Zündfrequenz). Ort, Laufzeit
 * (Abstand / c) und Pegel nach 1/r rechnet soundscape für ein Ohr am Auge mit der Blickrichtung (Stereo). Der Teppich aus Wind
 * und Brandung bleibt {@link Klang}; beide werden addiert.
 * <ul>
 * <li><b>Umkreis</b> [G]: Brecher, Überlauf und Bugschlag bis {@link #UMKREIS} m, das Typhon bis {@link #UMKREIS_HORN} m,
 * Maschinen bis {@link #UMKREIS_MASCHINE} m.</li>
 * <li><b>Stimmen</b> (R22): je Takt höchstens {@link #STIMMEN} neue Ereignisse, die lautesten (Stärke / Abstand) gewinnen.</li>
 * <li>Raum von soundscape: x Ost, y Höhe, z Süd; Ohr mit Gierwinkel von Nord über Ost.</li>
 * </ul>
 * {@link #setze} aus dem Faden der Oberfläche, {@link #rechne} aus dem Faden des Tons.
 */
public final class Ereignisklang {
    public static final double UMKREIS = 300, UMKREIS_HORN = 3000, UMKREIS_MASCHINE = 500;
    public static final int STIMMEN = 8;
    /** Mischung mit dem Klang [G]: ein Effektivwert von 1 in soundscape sind 104 dB; Brecher in 30 m (rund 90 dB) liegen um 0,2. */
    public static final double MISCHUNG = 1.0;

    public final Soundscape s = new Soundscape();
    private final int rate;
    private long letzte = -1;
    private final Map<Integer, Hum> maschinen = new HashMap<>();
    private float[] puffer = new float[0];
    /** Zähler für Prüfungen: Ereignisse gespielt, im Umkreis übergangen (zu viele), außerhalb. */
    public volatile int gespielt, zuViele, weit;

    public Ereignisklang(long saat, int rate) {
        this.rate = rate;
        s.quality = rate >= 48000 ? Soundscape.Quality.ULTRA : rate >= 44100 ? Soundscape.Quality.HIGH : rate >= 32000 ? Soundscape.Quality.MEDIUM : Soundscape.Quality.LOW;
        s.seed = saat;
        s.windSpeed = 0;
        s.gustiness = 0;
        s.waveHeight = 0;
        s.surfStrength = 0;
        s.rainRate = 0;
        s.autoGain = false;
    }

    /** Takt rate der Stimmen (soundscape) – gleich dem Takt des Klangs, sonst wird nicht gemischt. */
    public boolean passt() { return s.sampleRate() == rate; }

    /** Das Ohr am Auge (UTM, m NHN, Blickrichtung in Grad von Nord); die neuen Ereignisse und die Maschinen der Bühne. */
    public void setze(Buehne b, double ost, double nord, double hoehe, double richtung) {
        s.earX = ost;
        s.earY = hoehe;
        s.earZ = -nord;
        s.earYawDeg = richtung;
        if (b == null) return;
        List<Buehne.Ton> neu = b.toene(letzte < 0 ? Math.max(0, b.tonEreignisse.letzte()) : letzte);
        if (!neu.isEmpty()) letzte = neu.get(neu.size() - 1).nr();
        else if (letzte < 0) letzte = b.tonEreignisse.letzte();
        waehle(neu, ost, nord, hoehe);
        maschinen(b, ost, nord);
    }

    /** Für {@code TonCheck}: wie {@link #setze} mit einer Liste neuer Ereignisse statt der Bühne (ohne Maschinen). */
    public void setzeFuerPruefung(List<Buehne.Ton> neu, double ost, double nord, double hoehe) { waehle(neu, ost, nord, hoehe); }

    /** Im Umkreis die lautesten {@link #STIMMEN} (Stärke / Abstand) spielen. */
    private void waehle(List<Buehne.Ton> neu, double ost, double nord, double hoehe) {
        List<Buehne.Ton> nah = new ArrayList<>();
        for (Buehne.Ton t : neu) {
            double d = Math.sqrt(Math.pow(t.ost() - ost, 2) + Math.pow(t.nord() - nord, 2) + Math.pow(t.hoehe() - hoehe, 2));
            if (d <= (t.art().equals("Typhon") ? UMKREIS_HORN : UMKREIS)) nah.add(t);
            else weit++;
        }
        nah.sort(Comparator.comparingDouble((Buehne.Ton t) -> -t.staerke() / Math.max(1, Math.hypot(t.ost() - ost, t.nord() - nord))));
        for (int i = 0; i < nah.size(); i++) {
            if (i >= STIMMEN) { zuViele++; continue; }
            spiele(nah.get(i));
        }
    }

    /** Die Maschinen der Schiffe in Fahrt im Umkreis als Brummen. */
    private void maschinen(Buehne b, double ost, double nord) {
        double[] m = b.maschinen();
        java.util.Set<Integer> da = new java.util.HashSet<>();
        for (int i = 0; i + 5 < m.length; i += 6) {
            int nr = (int) m[i + 5];
            double d = Math.hypot(m[i] - ost, m[i + 1] - nord);
            if (d > UMKREIS_MASCHINE) continue;
            da.add(nr);
            Hum h = maschinen.computeIfAbsent(nr, k -> {
                Hum x = new Hum("Maschine " + k, 0, 0, 0);
                s.hums.add(x);
                return x;
            });
            h.x = m[i];
            h.y = m[i + 2];
            h.z = -m[i + 1];
            double laenge = m[i + 3];
            // Zündfrequenz nach der Größe [G]: Kutter (bis 25 m) 1200/min, 6 Zylinder; Fähre (bis 60 m) 750/min, 6; größer 600/min, 8
            h.firingHz = laenge < 25 ? Hum.firing(1200, 6) : laenge < 60 ? Hum.firing(750, 6) : Hum.firing(600, 8);
            h.levelDb = 100 + 12 * Math.min(1, m[i + 4]);
        }
        maschinen.entrySet().removeIf(e -> {
            if (da.contains(e.getKey())) return false;
            s.hums.remove(e.getValue());
            return true;
        });
    }

    /** Ein Ereignis an soundscape (Stärke 0 … 1); öffentlich für {@code TonCheck}. */
    public void spiele(Buehne.Ton t) {
        double x = t.ost(), y = t.hoehe(), z = -t.nord();
        switch (t.art()) {
            case "Brecher" -> s.event(Soundscape.Kind.BRECHER, x, y, z, t.staerke());
            case "Überlauf" -> s.event(Soundscape.Kind.SCHWALL, x, y, z, t.staerke());
            case "Bugschlag" -> s.event(Soundscape.Kind.SCHWALL, x, y, z, 0.5 * t.staerke());
            case "Typhon" -> s.event(Soundscape.Kind.TYFON, x, y, z, t.staerke());
            default -> { return; }
        }
        gespielt++;
    }

    /** Rechnet n Abtastwerte und zählt sie zu links und rechts ab {@code ab} dazu. */
    public void rechne(float[] links, float[] rechts, int ab, int n) {
        if (!passt()) return;
        if (puffer.length < 2 * n) puffer = new float[2 * n];
        s.render(puffer, n);
        for (int i = 0; i < n; i++) {
            links[ab + i] += (float) (MISCHUNG * puffer[2 * i]);
            rechts[ab + i] += (float) (MISCHUNG * puffer[2 * i + 1]);
        }
    }
}
