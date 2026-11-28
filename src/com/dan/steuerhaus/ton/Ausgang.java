package com.dan.steuerhaus.ton;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Seit X5: spielt einen {@link Klang} über die Soundkarte (48 kHz, Stereo, 16 Bit) in einem eigenen Faden. Lautstärke
 * und Stumm gleiten (0,3 s), damit es nicht knackt. Gibt es kein Gerät, bleibt es still ({@link #fehler}).
 */
public final class Ausgang implements AutoCloseable {
    public static final int RATE = 48000;
    public final Klang klang;
    /** Seit S8c (Seegang Werkbuch 104): die Ereignisse der Szene (Brecher, Überlauf, Bugschlag, Typhon, Maschinen). */
    public final Ereignisklang ereignisse;
    private volatile double lautstaerke = 0.7;
    private volatile boolean stumm, zu;
    private volatile String fehler;
    private Thread faden;

    public Ausgang(long saat) {
        klang = new Klang(saat, RATE);
        ereignisse = new Ereignisklang(saat + 1, RATE);
    }

    /** Startet die Wiedergabe; false, wenn es kein Gerät gibt (dann sagt {@link #fehler}, warum). */
    public boolean starte() {
        try {
            AudioFormat af = new AudioFormat(RATE, 16, 2, true, false);
            SourceDataLine line = AudioSystem.getSourceDataLine(af);
            line.open(af, RATE / 10 * 4);                                   // rund 100 ms im Puffer
            line.start();
            faden = new Thread(() -> spiele(line), "Ton");
            faden.setDaemon(true);
            faden.start();
            return true;
        } catch (Exception x) {
            fehler = x.getMessage() != null ? x.getMessage() : x.toString();
            return false;
        }
    }

    private void spiele(SourceDataLine line) {
        int n = 1024;
        float[] l = new float[n], r = new float[n];
        byte[] b = new byte[4 * n];
        double g = 0;
        try {
            while (!zu) {
                klang.rechne(l, r, 0, n);
                ereignisse.rechne(l, r, 0, n);                                    // seit S8c
                double soll = stumm ? 0 : lautstaerke;
                for (int i = 0; i < n; i++) {
                    g += (soll - g) * (1.0 / (0.3 * RATE));
                    int a = (int) Math.round(Math.max(-1, Math.min(1, l[i] * g)) * 32767), c = (int) Math.round(Math.max(-1, Math.min(1, r[i] * g)) * 32767);
                    b[4 * i] = (byte) a;
                    b[4 * i + 1] = (byte) (a >> 8);
                    b[4 * i + 2] = (byte) c;
                    b[4 * i + 3] = (byte) (c >> 8);
                }
                line.write(b, 0, b.length);
            }
        } catch (RuntimeException x) {
            fehler = x.toString();
        } finally {
            line.stop();
            line.close();
        }
    }

    public void lautstaerke(double v) { lautstaerke = Math.max(0, Math.min(1, v)); }

    public double lautstaerke() { return lautstaerke; }

    /** Stumm (gleitend); der Klang rechnet weiter. */
    public void stumm(boolean s) { stumm = s; }

    public boolean stumm() { return stumm; }

    public String fehler() { return fehler; }

    @Override public void close() { zu = true; }
}
