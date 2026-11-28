package com.dan.steuerhaus.buehne;

import com.dan.core.raum.Blick;
import com.dan.lauf.Ansicht;
import com.dan.lauf.Buehne;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Die Vorschaubilder des Katalogs (Werkbuch HalligHooge 110, F181 A): einmal ohne Fenster, auf dem Weg der Standbilder
 * der Bühne ({@code BuehneCheck}). Je Szenario aus dem Archiv seine erste Ansicht zum Beginn; je Ansicht ein Bild im
 * ersten Szenario aus dem Archiv zu dessen Beginn, oder wie im Manifest bestimmt:
 * <pre>
 * vorschau.ansicht.landunter_hanswarft = sturm 2026-10-25T23:30:00Z
 * </pre>
 * Ablage {@code <Ordner der Szenendatei>/vorschau/szenario_<schluessel>.png} und {@code ansicht_<schluessel>.png},
 * 480 × 270. Live-Szenarien bekommen kein Bild (der Lauf finge erst an); der Katalog zeichnet einen Platzhalter.
 * <p>
 * Seit Seegang S7d2 (Seegang Werkbuch 90): Für die Ansichten in {@code vorschau.schwimmer} (Manifest) laufen die Schwimmer
 * des Szenarios vor dem Bild {@link #SCHWIMMER_BILDER} Bilder lang (so liegen sie am Platz, und eine Fähre in Fahrt zieht
 * schon Kielwasser); Ansichten an Bord ({@code ansicht.<n>.bord}) bekommen das Bild der Bordkamera.
 */
public final class Vorschau {
    public static final int BREITE = 480, HOEHE = 270;
    /** Seit S7d2: Bilder der Schwimmer vor dem Vorschaubild (1/30 s je Bild) [G]. */
    public static final int SCHWIMMER_BILDER = 300;

    private Vorschau() { }

    public static File ordner(Szenen szenen) { return new File(szenen.datei.getAbsoluteFile().getParentFile(), "vorschau"); }

    /** Legt alle Bilder an; gibt die Zahl der Bilder zurück. Fehler einzelner Bilder werden gemeldet, nicht geworfen. */
    public static int erzeugen(Manifest m, Szenen szenen) throws Exception {
        File ordner = ordner(szenen);
        if (!ordner.isDirectory() && !ordner.mkdirs()) throw new IllegalStateException("Vorschau: Ordner nicht anlegbar: " + ordner);
        long n0 = System.nanoTime();
        int n = 0, fehler = 0;
        // je Szenario: seine Ansicht zum Beginn
        Map<String, List<Object>> auftraege = new LinkedHashMap<>();
        Szenario erstes = null;
        for (Szenario s : szenen.szenarien()) {
            if (s.live()) continue;
            if (erstes == null) erstes = s;
            auftraege.computeIfAbsent(s.schluessel, k -> new java.util.ArrayList<>()).add(new Auftrag("szenario_" + s.schluessel, s.ansicht != null ? szenen.ansicht(s.ansicht) : szenen.ansichten().get(0), null));
        }
        if (erstes == null) {
            System.out.println("Vorschau: kein Szenario aus dem Archiv – keine Bilder");
            return 0;
        }
        for (Ansicht a : szenen.ansichten()) {
            String[] v = m.text("vorschau.ansicht." + a.schluessel, "").split("\\s+");
            Szenario s = v.length > 0 && !v[0].isEmpty() && szenen.szenario(v[0]) != null && !szenen.szenario(v[0]).live() ? szenen.szenario(v[0]) : erstes;
            Instant t = v.length > 1 ? Instant.parse(v[1]) : null;
            auftraege.computeIfAbsent(s.schluessel, k -> new java.util.ArrayList<>()).add(new Auftrag("ansicht_" + a.schluessel, a, t));
        }
        java.util.Set<String> mitSchwimmern = new java.util.HashSet<>(java.util.Arrays.asList(m.text("vorschau.schwimmer", "").split("[,\\s]+")));
        File gebiet = szenen.gebiet();
        double[] po = szenen.zahlen("pegel.ort");
        for (Map.Entry<String, List<Object>> e : auftraege.entrySet()) {
            Szenario s = szenen.szenario(e.getKey());
            try (Buehne b = Buehne.ohneFenster(gebiet, BREITE, HOEHE)) {
                if (po != null) b.pegel(szenen.text("pegel.name", "Pegel"), po[0], po[1], szenen.zahl("pegel.umkreis", 300));
                b.oeffne(s);
                for (Object o : e.getValue()) {
                    Auftrag a = (Auftrag) o;
                    long n1 = System.nanoTime();
                    try {
                        double t = a.zeit() != null ? b.uhr().t(a.zeit()) : b.uhr().sekunden();
                        Blick bl = new Blick();
                        a.ansicht().setze(bl, b.gelaende, b.utm(), b.uhr().bei(t));
                        bl.groesse(BREITE, HOEHE);
                        if (mitSchwimmern.contains(a.ansicht().schluessel) || a.ansicht().bord != null) bl = schwimmer(b, a.ansicht(), bl, t);
                        float[] p = b.standbild(bl, t);
                        BufferedImage bild = new BufferedImage(BREITE, HOEHE, BufferedImage.TYPE_INT_RGB);
                        for (int y = 0; y < HOEHE; y++)
                            for (int x = 0; x < BREITE; x++) {
                                int i = 4 * (y * BREITE + x);
                                int r = Math.round(Math.max(0, Math.min(1, p[i])) * 255), g = Math.round(Math.max(0, Math.min(1, p[i + 1])) * 255), bb = Math.round(Math.max(0, Math.min(1, p[i + 2])) * 255);
                                bild.setRGB(x, y, (r << 16) | (g << 8) | bb);
                            }
                        ImageIO.write(bild, "png", new File(ordner, a.datei() + ".png"));
                        n++;
                        System.out.printf(Locale.GERMAN, "  Vorschau %-36s %s, %s in %.1f s%n", a.datei(), s.name, a.ansicht().name, (System.nanoTime() - n1) / 1e9);
                    } catch (Exception x) {
                        fehler++;
                        System.out.println("  FEHLT  Vorschau " + a.datei() + ": " + x);
                    }
                }
            } catch (Exception x) {
                fehler += e.getValue().size();
                System.out.println("  FEHLT  Vorschau, Szenario " + s.name + ": " + x);
            }
        }
        System.out.printf(Locale.GERMAN, "Vorschau: %d Bilder in %s, %d Fehler, %.1f min%n", n, ordner, fehler, (System.nanoTime() - n0) / 6e10);
        return n;
    }

    /**
     * Seit S7d2: die Schwimmer des Szenarios {@link #SCHWIMMER_BILDER} Bilder lang bis zur Zeit t (neu gesetzt bei
     * t − Dauer); an Bord der Blick der Bordkamera. Ohne Schwimmer der Blick unverändert.
     */
    static Blick schwimmer(Buehne b, Ansicht a, Blick bl, double t) throws InterruptedException {
        com.dan.lauf.Flotte f = b.flotte();
        if (f == null) return bl;
        double dt = 1.0 / 30, t0 = t - SCHWIMMER_BILDER * dt;
        b.standbild(bl, t0);                                                // Stände laden
        f.neuSetzen();
        com.dan.lauf.Kamera bk = a.bord != null ? b.bordKamera(a) : null;     // seit S8d auch der Schwimmerblick
        Blick aus = bl;
        for (int i = 0; i <= SCHWIMMER_BILDER; i++) {
            double ti = t0 + i * dt;
            b.flotteBild(ti, 1, dt);
            f.warte();
            if (bk != null) {
                Blick n = bk.weiter(dt, ti, bl);
                if (n != null) aus = n;
            }
        }
        if (bk != null) b.bord(null);
        aus.groesse(BREITE, HOEHE);
        return aus;
    }

    private record Auftrag(String datei, Ansicht ansicht, Instant zeit) { }
}
