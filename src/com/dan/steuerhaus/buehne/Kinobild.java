package com.dan.steuerhaus.buehne;

import com.dan.kino.Kino;
import com.dan.kino.KinoMaler;
import com.dan.render.overlay.Einblendung;
import com.dan.render.overlay.Kachel;
import com.dan.steuerhaus.thema.Thema;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Das Kino auf der Karte (seit W3, Werkbuch HalligHooge 116, F194 A): der Ablauf von {@code com.dan.kino.Kino} als Kacheln
 * der {@link Einblendung}. Balken und Schwarz sind Kacheln aus 8 × 8 schwarzen Bildpunkten, auf die nötige Größe gezogen
 * (kein Hochladen außer beim ersten Mal); die Tafeln (Titel, Untertitel, Abspann) malt {@link KinoMaler} in Bildgröße,
 * in einem eigenen Faden, beschnitten auf das, was gemalt ist. Die Deckung der Tafel kommt je Bild aus dem Kino.
 * <p>
 * {@link #aktualisiere} ruft der Faden der Bildschleife (über den {@link Regisseur}).
 */
final class Kinobild {
    private final Einblendung e;
    private final KinoMaler maler = new KinoMaler();
    private final Kachel schwarz, oben, unten;
    private volatile Kachel tafel;
    private int tafelNr = -1, tafelW, tafelH;
    private volatile boolean maltGerade;
    /** Seit W4: Tafeln im Faden des Aufrufers malen, nicht im eigenen (Video: dieselbe Folge gibt dieselben Bilder). */
    volatile boolean sofort;
    /** Seit W4: Breite der Spalte des Rolltitels (links) und seine Höhe (Bildpunkte; 0: keiner). */
    private volatile int rollX, rollHoehe;
    private final ExecutorService faden = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Kino, Tafel");
        t.setDaemon(true);
        return t;
    });

    Kinobild(Einblendung e) {
        this.e = e;
        schwarz = schwarz("Kino, Schwarz");
        oben = schwarz("Kino, Balken oben");
        unten = schwarz("Kino, Balken unten");
    }

    private Kachel schwarz(String name) {
        Kachel k = e.neu(name, 8, 8).imBild(Kachel.Ecke.OBEN_LINKS, 0, 0);
        k.male(g -> {
            g.setColor(Color.BLACK);
            g.fillRect(0, 0, 8, 8);
        });
        k.sichtbar = false;
        return k;
    }

    /** Je Bild: Balken, Schwarz und Tafel nach dem Kino, für ein Bild W × H (Bildpunkte der Karte). */
    void aktualisiere(Kino kino, int W, int H) {
        boolean an = kino.sichtbar();
        int bar = kino.balkenPx(W, H);
        oben.imBild(Kachel.Ecke.OBEN_LINKS, 0, 0);
        unten.imBild(Kachel.Ecke.UNTEN_LINKS, 0, 0);
        for (Kachel k : new Kachel[] {oben, unten}) {
            k.zeigeBreite = W;
            k.zeigeHoehe = Math.max(1, bar);
            k.sichtbar = an && bar > 0;
        }
        double s = kino.schwarz();
        schwarz.zeigeBreite = W;
        schwarz.zeigeHoehe = H;
        schwarz.deckung = s;
        schwarz.sichtbar = an && s > 0.001;
        Kino.Tafel t = an ? kino.tafel() : null;
        Kachel alt = tafel;
        if (t == null) {
            if (alt != null) alt.sichtbar = false;
            return;
        }
        double rolle = kino.rolle();
        boolean roll = t.art() == Kino.Art.ABSPANN && !Double.isNaN(rolle);
        if ((t.nummer() != tafelNr || W != tafelW || H != tafelH) && !maltGerade) {
            tafelNr = t.nummer();
            tafelW = W;
            tafelH = H;
            maltGerade = true;
            if (alt != null) alt.sichtbar = false;
            if (sofort) malen(t, W, H, roll);
            else faden.submit(() -> malen(t, W, H, roll));
        }
        Kachel k = tafel;
        if (k != null && tafelNr == t.nummer()) {
            k.deckung = t.deckkraft();
            k.sichtbar = true;
            // seit W4 (F206 A): der Rolltitel zieht von unter dem Bild bis über das Bild hinaus
            if (roll && rollHoehe > 0) k.imBild(Kachel.Ecke.OBEN_LINKS, rollX, (int) Math.round(H - rolle * (H + rollHoehe)));
        }
    }

    /** Malt die Tafel, schneidet auf den bemalten Teil und legt sie als neue Kachel an (die alte geht). */
    private void malen(Kino.Tafel t, int W, int H, boolean roll) {
        try {
            maler.titelSchrift = Thema.aktuell().titelFamilie;
            if (roll) {
                malenRolle(t, W, H);
                return;
            }
            rollHoehe = 0;
            BufferedImage im = maler.malen(t, W, H);
            int x0 = W, y0 = H, x1 = -1, y1 = -1;
            int[] p = im.getRGB(0, 0, W, H, null, 0, W);
            for (int y = 0; y < H; y++)
                for (int x = 0; x < W; x++)
                    if ((p[x + W * y] >>> 24) != 0) {
                        if (x < x0) x0 = x;
                        if (x > x1) x1 = x;
                        if (y < y0) y0 = y;
                        if (y > y1) y1 = y;
                    }
            Kachel alt = tafel;
            if (x1 < 0) {
                tafel = null;
            } else {
                int bx = Math.max(0, x0 - 2), by = Math.max(0, y0 - 2), bw = Math.min(W, x1 + 3) - bx, bh = Math.min(H, y1 + 3) - by;
                BufferedImage aus = im.getSubimage(bx, by, bw, bh);
                Kachel k = e.neu("Kino, Tafel " + t.nummer(), bw, bh).imBild(Kachel.Ecke.OBEN_LINKS, bx, by);
                k.deckung = t.deckkraft();
                k.male(g -> g.drawImage(aus, 0, 0, null));
                tafel = k;
            }
            if (alt != null) e.weg(alt);
        } finally {
            maltGerade = false;
        }
    }

    /** Der Rolltitel: ein hohes Bild, links und rechts beschnitten, zuerst unter dem Bild. */
    private void malenRolle(Kino.Tafel t, int W, int H) {
        BufferedImage im = maler.rolle(t.zeilen(), W, H);
        int h = im.getHeight(), x0 = W, x1 = -1;
        int[] p = im.getRGB(0, 0, W, h, null, 0, W);
        for (int y = 0; y < h; y++)
            for (int x = 0; x < W; x++)
                if ((p[x + W * y] >>> 24) != 0) {
                    if (x < x0) x0 = x;
                    if (x > x1) x1 = x;
                }
        Kachel alt = tafel;
        if (x1 < 0) {
            tafel = null;
            rollHoehe = 0;
        } else {
            int bx = Math.max(0, x0 - 2), bw = Math.min(W, x1 + 3) - bx;
            BufferedImage aus = im.getSubimage(bx, 0, bw, h);
            Kachel k = e.neu("Kino, Rolltitel " + t.nummer(), bw, h).imBild(Kachel.Ecke.OBEN_LINKS, bx, H);
            k.deckung = t.deckkraft();
            k.male(g -> g.drawImage(aus, 0, 0, null));
            rollX = bx;
            rollHoehe = h;
            tafel = k;
        }
        if (alt != null) e.weg(alt);
    }

    /** Alles aus (nach dem Ende des Kinos). */
    void aus() {
        for (Kachel k : new Kachel[] {schwarz, oben, unten}) k.sichtbar = false;
        Kachel t = tafel;
        if (t != null) t.sichtbar = false;
    }

    /** Für Prüfungen: die Kacheln Schwarz, oben, unten und die Tafel (oder null). */
    Kachel[] kacheln() { return new Kachel[] {schwarz, oben, unten, tafel}; }
}
