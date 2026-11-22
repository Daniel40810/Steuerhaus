package com.dan.steuerhaus.buehne;

import com.dan.core.raum.Blick;
import com.dan.lauf.Buehne;
import com.dan.lauf.Pegelreihe;
import com.dan.render.overlay.Einblendung;
import com.dan.render.overlay.Kachel;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.thema.Thema;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Die Einblendungen einer Szenen-App auf der Karte (seit W2, Werkbuch HalligHooge 113, F186–F188 A): Uhr und Pegel,
 * Kompass, Maßstab, Minikarte, Ortsnamen und Farblegende als {@link Kachel}n der {@link Einblendung} der Bühne.
 * <ul>
 * <li><b>Takt:</b> {@link #takt} im Faden der Oberfläche (alle 200 ms). Gemalt wird nur, was sich geändert hat: die Uhr
 * je Szenenminute, der Maßstab, wenn sich der Blick ändert, das Wasser der Minikarte alle 2 s (F188 A). Der Kompass
 * dreht auf der Karte mit dem Blick, ohne Verzug ({@code Kachel.nachNorden}).</li>
 * <li><b>Teile:</b> welche an sind, entscheidet die App ({@link #setze}); hier gibt es nur das Malen.</li>
 * <li><b>Maß:</b> alle Größen mal {@code mass} (Skalierung der Anzeige), damit die Kacheln auf jedem Bildschirm gleich
 * groß wirken.</li>
 * </ul>
 * Die Bindung an {@code com.dan.render.overlay} und {@code com.dan.core.raum} ist eine Abweichung von F175 A (nur
 * {@code com.dan.lauf}), in Werkbuch 114 begründet: die Kacheln sind die Schnittstelle der Einblendungen.
 */
public final class Einblendungen {
    /** Die Teile; Kennung für Einstellungen und Befehle. */
    public enum Teil {
        UHR("uhr", "Uhr und Pegel"), KOMPASS("kompass", "Kompass"), MASSSTAB("massstab", "Maßstab"), MINIKARTE("minikarte", "Minikarte"),
        ORTE("orte", "Ortsnamen"), LEGENDE("legende", "Farblegende");

        public final String kennung, name;

        Teil(String kennung, String name) {
            this.kennung = kennung;
            this.name = name;
        }

        public static Teil von(String k) {
            for (Teil t : values()) if (t.kennung.equals(k)) return t;
            return null;
        }
    }

    /** Vorgaben je Arbeitsbereich (Werkbuch 113, 4): Betrachten Uhr und Kompass, Messen Legende und Maßstab, sonst alle. */
    public static Set<Teil> vorgabe(String bereich) {
        return switch (bereich == null ? "" : bereich) {
            case "betrachten" -> EnumSet.of(Teil.UHR, Teil.KOMPASS);
            case "messen" -> EnumSet.of(Teil.LEGENDE, Teil.MASSSTAB);
            default -> EnumSet.allOf(Teil.class);
        };
    }

    static final DateTimeFormatter UHRZEIT = DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN);
    static final DateTimeFormatter TAG = DateTimeFormatter.ofPattern("EEE dd.MM.yyyy zzz", Locale.GERMAN);
    static final String[] STRICH = {"N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
    /** Abstand (m), bis zu dem ein Ortsname groß steht [G]. */
    static final double NAH = 1500;
    /** Zeile des Maßstabs im Bild (Anteil der Höhe von oben): das untere Drittel. */
    static final double MASS_ZEILE = 2.0 / 3;
    /** Wasser der Minikarte neu nach so vielen Millisekunden (F188 A). */
    static final long WASSER_MS = 2000;
    /** Längste Seite des Rasters der Minikarte (F188 A). */
    static final int RASTER = 256;

    private final Buehne b;
    private final Einblendung e;
    private final double s;
    private final ZoneId zone;
    private final String pegelName, markeName;
    private final double marke;
    private final List<Orte.Ort> orte;
    private final double[] karte;
    private final int kx, ky;
    private final float[] grund;

    private final Kachel uhr, rose, glas, massK, minikarte, legende;
    private final List<OrtKachel> ortK = new ArrayList<>();

    record OrtKachel(Orte.Ort ort, double h, Kachel gross, Kachel klein) { }

    private final EnumSet<Teil> wirksam = EnumSet.noneOf(Teil.class);
    private String uhrAlt, glasAlt, massAlt, legendeAlt, kegelAlt;
    private Thema themaAlt;
    private long wasserZeit;
    private float[] wasser;
    private BufferedImage kartenBild;
    private double[] massWert;
    private double wasserAnteil = Double.NaN;
    private Pegelreihe reiheAlt;
    private int reiheLaenge = -1;
    private List<Pegelreihe.Marke> marken = List.of();

    /**
     * Legt die Kacheln an. {@code mass}: Skalierung der Anzeige (1 bei 100 %). {@code meldung[0]} bekommt, woher die
     * Ortsnamen kommen.
     */
    public Einblendungen(Buehne b, Manifest m, ZoneId zone, String pegelName, double marke, String markeName, double mass, String[] meldung) {
        this.b = b;
        this.e = b.einblendung();
        this.s = mass;
        this.zone = zone;
        this.pegelName = pegelName;
        this.marke = marke;
        this.markeName = markeName;
        // die Minikarte: aus dem Manifest, sonst das Gitter der Nässe, sonst das Gitter in UTM
        double[] k = zahlen(m.text("minikarte", ""));
        if (k == null || k.length < 4) k = b.nassGebiet();
        if (k == null && b.utm() != null) k = new double[] {b.utm().x0, b.utm().y0, b.utm().x0 + b.utm().nx * b.utm().dx, b.utm().y0 + b.utm().ny * b.utm().dy};
        karte = k;
        if (karte != null) {
            double w = karte[2] - karte[0], h = karte[3] - karte[1];
            kx = w >= h ? RASTER : Math.max(16, (int) Math.round(RASTER * w / h));
            ky = w >= h ? Math.max(16, (int) Math.round(RASTER * h / w)) : RASTER;
            grund = new float[kx * ky];
            for (int j = 0; j < ky; j++)
                for (int i = 0; i < kx; i++) {
                    double g = b.gelaendeHoehe(karte[0] + (i + 0.5) * w / kx, karte[1] + (j + 0.5) * h / ky);
                    grund[i + kx * j] = (float) g;
                }
        } else {
            kx = ky = 0;
            grund = null;
        }
        orte = Orte.lies(m, b.ordner, meldung);

        uhr = e.neu("Uhr und Pegel", px(340), px(150)).imBild(Kachel.Ecke.OBEN_LINKS, px(16), px(16));
        rose = e.neu("Kompass, Rose", px(132), px(132)).imBild(Kachel.Ecke.OBEN_RECHTS, px(16), px(16));
        rose.nachNorden = true;
        glas = e.neu("Kompass, Glas", px(132), px(132)).imBild(Kachel.Ecke.OBEN_RECHTS, px(16), px(16));
        massK = e.neu("Maßstab", px(320), px(50)).imBild(Kachel.Ecke.UNTEN_LINKS, px(16), px(16));
        legende = e.neu("Farblegende", px(320), px(86)).imBild(Kachel.Ecke.UNTEN_LINKS, px(16), px(16 + 50 + 10));
        minikarte = karte == null ? null : e.neu("Minikarte", px(kx) + px(14), px(ky) + px(14)).imBild(Kachel.Ecke.UNTEN_RECHTS, px(16), px(16));
        for (Orte.Ort o : orte) {
            double h = b.gelaendeHoehe(o.ost(), o.nord());
            h = (Double.isNaN(h) ? 0 : Math.max(0, h)) + 3;
            Kachel gr = ortKachel(o, true, h), kl = ortKachel(o, false, h);
            ortK.add(new OrtKachel(o, h, gr, kl));
        }
        for (Kachel x : List.of(uhr, rose, glas, massK, legende)) x.sichtbar = false;
        if (minikarte != null) minikarte.sichtbar = false;
        for (OrtKachel o : ortK) o.gross.sichtbar = o.klein.sichtbar = false;
    }

    private Kachel ortKachel(Orte.Ort o, boolean gross, double h) {
        // groß genug für die Schrift jedes Themas (die Größe einer Kachel steht fest)
        BufferedImage m = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = m.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        int w = 1, hh = 1;
        for (Thema t : Thema.values()) {
            FontMetrics fm = g.getFontMetrics(schrift(o, gross, t));
            w = Math.max(w, (int) Math.ceil(fm.getStringBounds(o.name(), g).getWidth()) + px(10));
            hh = Math.max(hh, fm.getHeight() + px(10));
        }
        g.dispose();
        Kachel k = e.neu("Ort " + o.name() + (gross ? "" : " (klein)"), w, hh).inWelt(o.ost(), o.nord(), h, 0.5, 1, true);
        k.ausweichen = true;
        k.rang = Orte.rang(o.art());
        k.weite = switch (o.art()) {
            case "Hallig", "Insel", "Sand" -> 30_000;
            case "Priel" -> 4_000;
            default -> 7_000;
        };
        return k;
    }

    private Font schrift(Orte.Ort o, boolean gross, Thema t) {
        boolean gebiet = o.art().equals("Hallig") || o.art().equals("Insel") || o.art().equals("Sand");
        float g = (float) (s * (gross ? (gebiet ? 19 : 15) : (gebiet ? 14 : 12)));
        Font f = t.titel(g);
        if (o.art().equals("Priel") || o.art().equals("Sand")) f = f.deriveFont(Font.ITALIC | Font.BOLD);
        return f;
    }

    int px(double v) { return (int) Math.round(v * s); }

    // ------------------------------------------------------------------ Steuerung

    private volatile int rand;

    /**
     * Seit W3: Abstand der Kacheln am Rand von oben und unten zusätzlich (Bildpunkte), etwa die Balken des Kinos. Darf
     * aus der Bildschleife kommen; die Lage gilt ab dem nächsten Bild.
     */
    public void rand(int px) {
        rand = Math.max(0, px);
        uhr.imBild(Kachel.Ecke.OBEN_LINKS, px(16), px(16) + rand);
        rose.imBild(Kachel.Ecke.OBEN_RECHTS, px(16), px(16) + rand);
        glas.imBild(Kachel.Ecke.OBEN_RECHTS, px(16), px(16) + rand);
        massK.imBild(Kachel.Ecke.UNTEN_LINKS, px(16), px(16) + rand);
        if (minikarte != null) minikarte.imBild(Kachel.Ecke.UNTEN_RECHTS, px(16), px(16) + rand);
    }

    private final List<Kachel> sondenK = new ArrayList<>();

    /**
     * Seit W5 (F209 A): die Marken der Sonden im Bild – je Ort {Ost, Nord, Höhe} eine Nadel mit Nummer in der Farbe der
     * Sonde, unabhängig von den Teilen (eine Sonde will man sehen). Ersetzt die bisherigen Marken.
     */
    public synchronized void sonden(List<double[]> orte, List<Color> farben) {
        for (Kachel k : sondenK) e.weg(k);
        sondenK.clear();
        for (int i = 0; i < orte.size(); i++) {
            double[] o = orte.get(i);
            Color c = farben.get(i % farben.size());
            String nr = String.valueOf(i + 1);
            int w = px(22), h = px(34);
            Kachel k = e.neu("Sonde " + nr, w, h).inWelt(o[0], o[1], o[2], 0.5, 1, false);
            k.male(g -> {
                g.setColor(new Color(0, 0, 0, 150));
                g.fillOval(px(2), px(2), w - px(2), w - px(2));
                g.setColor(c);
                g.fillOval(px(1), px(1), w - px(4), w - px(4));
                g.setStroke(new BasicStroke((float) Math.max(1.5, 2 * s)));
                g.drawLine(w / 2, w - px(3), w / 2, h - 1);
                g.setColor(Color.BLACK);
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, px(12)));
                FontMetrics fm = g.getFontMetrics();
                g.drawString(nr, (w - px(2) - fm.stringWidth(nr)) / 2f, (w - px(2) + fm.getAscent() - fm.getDescent()) / 2f);
            });
            k.sichtbar = true;
            sondenK.add(k);
        }
    }

    /** Welche Teile wirken (leer: alles aus, die Einblendung kostet nichts). */
    public void setze(Set<Teil> teile) {
        wirksam.clear();
        wirksam.addAll(teile);
        e.an = true;                                                        // seit W3: das Kino legt eigene Kacheln dazu
        uhrAlt = glasAlt = massAlt = legendeAlt = kegelAlt = null;
    }

    public Set<Teil> wirksam() { return Collections.unmodifiableSet(wirksam); }

    /** Die Kachel eines Teils (Kompass: die Rose; Ortsnamen: null), für Prüfungen. */
    public Kachel kachel(Teil t) {
        return switch (t) {
            case UHR -> uhr;
            case KOMPASS -> rose;
            case MASSSTAB -> massK;
            case MINIKARTE -> minikarte;
            case LEGENDE -> legende;
            case ORTE -> null;
        };
    }

    /** Zuletzt gezeigter Maßstab: {Länge (m), Bildpunkte, Zeile fy, Mitte fx}; null ohne. */
    public double[] massstab() { return massWert; }

    /** Anteil Wasser im Raster der Minikarte beim letzten Neuzeichnen des Wassers (NaN: noch keins). */
    public double wasserAnteil() { return wasserAnteil; }

    /** Rechteck der Minikarte (UTM; null ohne). */
    public double[] karte() { return karte == null ? null : karte.clone(); }

    /** Zahl der Ortsnamen. */
    public int orte() { return ortK.size(); }

    // ------------------------------------------------------------------ Klicks (W3, F197 A)

    /** Der Ort (UTM) unter einem Bildpunkt der Karte (px, py in Bildpunkten der Leinwand) in der Minikarte; null daneben. */
    public double[] karteOrt(double px, double py) {
        if (minikarte == null || !minikarte.sichtbar) return null;
        double[] z = minikarte.zuletzt();
        if (Double.isNaN(z[0])) return null;
        double r = px(7), fx = (px - z[0] - r) / px(kx), fy = (py - z[1] - r) / px(ky);
        if (fx < 0 || fy < 0 || fx > 1 || fy > 1) return null;
        return new double[] {karte[0] + fx * (karte[2] - karte[0]), karte[3] - fy * (karte[3] - karte[1])};
    }

    /** Umgekehrt: der Bildpunkt eines Orts (UTM) in der Minikarte, wie sie zuletzt lag (null: nicht gezeigt). */
    public double[] karteBildpunkt(double ost, double nord) {
        if (minikarte == null) return null;
        double[] z = minikarte.zuletzt();
        if (Double.isNaN(z[0])) return null;
        double r = px(7);
        return new double[] {z[0] + r + (ost - karte[0]) / (karte[2] - karte[0]) * px(kx), z[1] + r + (karte[3] - nord) / (karte[3] - karte[1]) * px(ky)};
    }

    /** Größe einer Zelle des Rasters der Minikarte (m). */
    public double zelle() { return karte == null ? Double.NaN : (karte[2] - karte[0]) / kx; }

    /** Der Ortsname unter einem Bildpunkt der Karte: {Ost, Nord, Höhe des Ankers} und der Name; null: keiner. */
    public Object[] ortBei(double px, double py) {
        for (OrtKachel o : ortK)
            for (Kachel k : new Kachel[] {o.gross, o.klein}) {
                double[] z = k.zuletzt();
                if (!k.sichtbar || Double.isNaN(z[0])) continue;
                if (px >= z[0] && px <= z[0] + k.breite && py >= z[1] && py <= z[1] + k.hoehe)
                    return new Object[] {new double[] {o.ort.ost(), o.ort.nord(), o.h}, o.ort.name()};
            }
        return null;
    }

    // ------------------------------------------------------------------ Takt

    /**
     * Seit W4: die Uhr (ms), nach der Maßstab und Wasser der Minikarte neu gerechnet werden; im Fenster die des Rechners,
     * im Video die Filmzeit (dieselbe Folge gibt dieselben Bilder).
     */
    public volatile java.util.function.LongSupplier uhrMs = System::currentTimeMillis;

    /** Je Takt der Oberfläche: malt neu, was sich geändert hat. */
    public void takt(Buehne.Zustand z) {
        Blick bl = z.blick;
        Thema t = Thema.aktuell();
        if (t != themaAlt) {
            themaAlt = t;
            uhrAlt = glasAlt = massAlt = legendeAlt = kegelAlt = null;
            roseMalen(t);
            for (OrtKachel o : ortK) {
                ortMalen(o.gross, o.ort, true, t);
                ortMalen(o.klein, o.ort, false, t);
            }
        }
        boolean an = !wirksam.isEmpty();
        uhr.sichtbar = an && wirksam.contains(Teil.UHR) && z.jetzt != null;
        rose.sichtbar = glas.sichtbar = an && wirksam.contains(Teil.KOMPASS) && bl != null;
        massK.sichtbar = an && wirksam.contains(Teil.MASSSTAB) && bl != null;
        if (minikarte != null) minikarte.sichtbar = an && wirksam.contains(Teil.MINIKARTE) && bl != null;
        boolean mitLegende = an && wirksam.contains(Teil.LEGENDE) && b.messblickAn();
        boolean mitOrten = an && wirksam.contains(Teil.ORTE) && bl != null;
        if (uhr.sichtbar) uhr(z, t);
        if (rose.sichtbar) glas(bl, t);
        if (massK.sichtbar) massstab(bl, t);
        else massWert = null;
        if (minikarte != null && minikarte.sichtbar) minikarte(bl, t);
        legende.sichtbar = mitLegende && legende(t);
        // die Legende steht über dem Maßstab, sonst an dessen Platz
        legende.imBild(Kachel.Ecke.UNTEN_LINKS, px(16), (massK.sichtbar ? px(16 + 50 + 10) : px(16)) + rand);
        for (OrtKachel o : ortK) {
            double d = bl == null ? 0 : Math.hypot(o.ort.ost() - bl.ost, o.ort.nord() - bl.nord);
            o.gross.sichtbar = mitOrten && d < NAH;
            o.klein.sichtbar = mitOrten && d >= NAH;
        }
    }

    // ------------------------------------------------------------------ Uhr und Pegel

    private void uhr(Buehne.Zustand z, Thema t) {
        Instant jetzt = z.jetzt;
        String zeit = UHRZEIT.withZone(zone).format(jetzt), tag = TAG.withZone(zone).format(jetzt);
        List<String> zeilen = new ArrayList<>();
        Pegelreihe pr = b.pegelreihe();
        String tendenz = "";
        String landunter = null;
        if (pegelName != null) {
            double p = z.pegel;
            if (pr != null) {
                double vor = pr.bei(z.t - 900);
                if (!Double.isNaN(p) && !Double.isNaN(vor)) tendenz = p - vor > 0.02 ? "steigt" : p - vor < -0.02 ? "fällt" : "steht";
            }
            zeilen.add("Pegel " + pegelName + "  " + (Double.isNaN(p) ? "trocken" : String.format(Locale.GERMAN, "%+.2f m NHN", p)));
            // die nächste Marke; gibt es keine mehr (Ende der Reihe), die letzte mit „war“
            Pegelreihe.Marke n = naechsteMarke(pr, z.t);
            boolean war = false;
            if (n == null && !marken.isEmpty() && marken.get(marken.size() - 1).t() <= z.t) {
                n = marken.get(marken.size() - 1);
                war = true;
            }
            zeilen.add(n == null ? "Keine Marke in der Reihe" : (n.art() == Pegelreihe.Art.HOCHWASSER ? "Hochwasser " : "Niedrigwasser ") + (war ? "war " : "")
                    + UHRZEIT.withZone(zone).format(z.start.plusMillis(Math.round(n.t() * 1000))) + String.format(Locale.GERMAN, " · %+.2f m", n.pegel()));
            if (pr != null && !Double.isNaN(marke) && !Double.isNaN(p) && p > marke)
                for (double[] u : pr.ueber(marke))
                    if (u[0] <= z.t && z.t <= u[1] + 1) landunter = markeName + " seit " + UHRZEIT.withZone(zone).format(z.start.plusMillis(Math.round(u[0] * 1000)));
        }
        double wr = (Math.toDegrees(Math.atan2(-z.windU, -z.windV)) + 360) % 360;
        String wind = z.wind < 0.3 ? "Wind still" : String.format(Locale.GERMAN, "Wind aus %s  %.1f m/s", STRICH[(int) Math.round(wr / 22.5) % 16], z.wind);
        zeilen.add(wind);
        String sig = zeit + tag + zeilen + tendenz + landunter + Math.round(wr / 5) + t;
        if (sig.equals(uhrAlt)) return;
        uhrAlt = sig;
        String lu = landunter, te = tendenz;
        double windZu = Math.toRadians((wr + 180) % 360);
        boolean mitWind = z.wind >= 0.3;
        uhr.male(g -> {
            tafel(g, t, uhr.breite, uhr.hoehe);
            int x = px(14), y = px(14);
            g.setFont(t.titel((float) (26 * s)));
            FontMetrics fm = g.getFontMetrics();
            g.setColor(t.text);
            g.drawString(zeit, x, y + fm.getAscent());
            int xz = x + fm.stringWidth(zeit) + px(10);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(12)));
            g.setColor(t.leise);
            g.drawString(tag, xz, y + fm.getAscent());
            y += fm.getHeight() + px(4);
            g.setColor(Thema.mitAlpha(t.primaer, 160));
            g.fillRect(x, y - px(2), px(40), Math.max(1, px(2)));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(13)));
            FontMetrics f2 = g.getFontMetrics();
            for (int i = 0; i < zeilen.size(); i++) {
                String zl = zeilen.get(i);
                y += f2.getHeight();
                g.setColor(i == 0 ? t.text : t.leise);
                g.drawString(zl, x, y);
                int ende = x + f2.stringWidth(zl) + px(8);
                if (i == 0 && !te.isEmpty()) {
                    pfeilTendenz(g, ende, y - f2.getAscent() / 2, te, t);
                    g.setColor(t.leise);
                    g.drawString(te, ende + px(14), y);
                }
                if (i == zeilen.size() - 1 && mitWind) windpfeil(g, ende + px(8), y - f2.getAscent() / 2 + px(1), windZu, t);
            }
            if (lu != null) {
                y += f2.getHeight() + px(2);
                g.setColor(t.akzent.brighter());
                g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, px(13)));
                g.drawString(lu, x, y);
            }
        });
    }

    private Pegelreihe.Marke naechsteMarke(Pegelreihe pr, double t) {
        if (pr == null) return null;
        if (pr != reiheAlt || pr.laenge() != reiheLaenge) {
            reiheAlt = pr;
            reiheLaenge = pr.laenge();
            marken = pr.marken();
        }
        for (Pegelreihe.Marke m : marken) if (m.t() > t) return m;
        return null;
    }

    private void pfeilTendenz(Graphics2D g, int x, int y, String te, Thema t) {
        int a = px(5);
        Path2D.Double p = new Path2D.Double();
        if (te.equals("steigt")) {
            p.moveTo(x, y + a);
            p.lineTo(x + 2 * a, y + a);
            p.lineTo(x + a, y - a);
        } else if (te.equals("fällt")) {
            p.moveTo(x, y - a);
            p.lineTo(x + 2 * a, y - a);
            p.lineTo(x + a, y + a);
        } else {
            p.moveTo(x, y - a / 2.0);
            p.lineTo(x + 2 * a, y - a / 2.0);
            p.lineTo(x + 2 * a, y + a / 2.0);
            p.lineTo(x, y + a / 2.0);
        }
        p.closePath();
        g.setColor(te.equals("steigt") ? t.kurve : te.equals("fällt") ? t.marke : t.leise);
        g.fill(p);
    }

    /** Ein Pfeil, wohin der Wind weht (Nord oben). */
    private void windpfeil(Graphics2D g, int cx, int cy, double zu, Thema t) {
        double l = 9 * s, dx = Math.sin(zu), dy = -Math.cos(zu);
        double x0 = cx - dx * l, y0 = cy - dy * l, x1 = cx + dx * l, y1 = cy + dy * l;
        g.setColor(t.pfeil);
        g.setStroke(new BasicStroke((float) (1.8 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(new Line2D.Double(x0, y0, x1, y1));
        Path2D.Double p = new Path2D.Double();
        double nx = -dy, ny = dx, k = 5 * s;
        p.moveTo(x1 + dx * 2 * s, y1 + dy * 2 * s);
        p.lineTo(x1 - dx * k + nx * k * 0.6, y1 - dy * k + ny * k * 0.6);
        p.lineTo(x1 - dx * k - nx * k * 0.6, y1 - dy * k - ny * k * 0.6);
        p.closePath();
        g.fill(p);
    }

    // ------------------------------------------------------------------ Kompass

    private void roseMalen(Thema t) {
        boolean nautik = t == Thema.NAUTIK;
        rose.male(g -> {
            double w = rose.breite, c = w / 2, r = c - 3 * s;
            g.setColor(Thema.mitAlpha(t.tief, 200));
            g.fill(new Ellipse2D.Double(c - r, c - r, 2 * r, 2 * r));
            if (nautik) {
                g.setPaint(new LinearGradientPaint((float) 0, (float) 0, (float) w, (float) w, new float[] {0f, 0.5f, 1f},
                        new Color[] {t.primaer.brighter(), t.primaer, t.primaer.darker()}));
                g.setStroke(new BasicStroke((float) (4.5 * s)));
            } else {
                g.setColor(t.primaer);
                g.setStroke(new BasicStroke((float) (1.8 * s)));
            }
            g.draw(new Ellipse2D.Double(c - r, c - r, 2 * r, 2 * r));
            for (int a = 0; a < 360; a += 10) {
                double ra = Math.toRadians(a), lang = a % 90 == 0 ? 12 : a % 30 == 0 ? 8 : 4;
                double r0 = r - 4 * s, r1 = r0 - lang * s;
                g.setColor(a % 30 == 0 ? t.text : t.leise);
                g.setStroke(new BasicStroke((float) ((a % 30 == 0 ? 1.4 : 1.0) * s)));
                g.draw(new Line2D.Double(c + Math.sin(ra) * r0, c - Math.cos(ra) * r0, c + Math.sin(ra) * r1, c - Math.cos(ra) * r1));
            }
            // Sternspitzen: Nord rot, die anderen im Ton des Themas
            for (int a = 0; a < 360; a += 90) {
                double ra = Math.toRadians(a), l = r - 22 * s, bb = 7 * s;
                Path2D.Double p = new Path2D.Double();
                p.moveTo(c + Math.sin(ra) * l, c - Math.cos(ra) * l);
                p.lineTo(c + Math.sin(ra + Math.PI / 2) * bb, c - Math.cos(ra + Math.PI / 2) * bb);
                p.lineTo(c, c);
                p.lineTo(c + Math.sin(ra - Math.PI / 2) * bb, c - Math.cos(ra - Math.PI / 2) * bb);
                p.closePath();
                g.setColor(a == 0 ? t.akzent : Thema.mitAlpha(nautik ? t.primaer : t.leise, 200));
                g.fill(p);
            }
            g.setFont(t.titel((float) (13 * s)));
            FontMetrics fm = g.getFontMetrics();
            String[] n = {"N", "O", "S", "W"};
            for (int i = 0; i < 4; i++) {
                double ra = Math.toRadians(90 * i), rr = r - 26 * s;
                double x = c + Math.sin(ra) * rr, y = c - Math.cos(ra) * rr;
                g.setColor(i == 0 ? t.akzent.brighter() : t.text);
                g.drawString(n[i], (float) (x - fm.stringWidth(n[i]) / 2.0), (float) (y + fm.getAscent() / 2.0 - 1));
            }
        });
    }

    private void glas(Blick bl, Thema t) {
        long r = Math.round(((bl.richtung % 360) + 360) % 360) % 360, n = Math.round(bl.neigung);
        String sig = r + "|" + n + "|" + t;
        if (sig.equals(glasAlt)) return;
        glasAlt = sig;
        glas.male(g -> {
            double w = glas.breite, c = w / 2;
            // Steuerstrich oben (zeigt die Blickrichtung)
            Path2D.Double p = new Path2D.Double();
            p.moveTo(c, 1 * s);
            p.lineTo(c - 6 * s, 11 * s);
            p.lineTo(c + 6 * s, 11 * s);
            p.closePath();
            g.setColor(t.primaer.brighter());
            g.fill(p);
            // Kurs in der Mitte, darunter die Neigung als Strich und Zahl
            g.setColor(Thema.mitAlpha(t.tief, 215));
            g.fill(new Ellipse2D.Double(c - 21 * s, c - 21 * s, 42 * s, 42 * s));
            g.setFont(t.titel((float) (14 * s)));
            FontMetrics fm = g.getFontMetrics();
            String k = r + "°";
            g.setColor(t.text);
            g.drawString(k, (float) (c - fm.stringWidth(k) / 2.0), (float) (c + fm.getAscent() / 2.0 - 3 * s));
            double dy = Math.max(-14, Math.min(14, -n * 0.7)) * s;
            g.setColor(t.kurve);
            g.setStroke(new BasicStroke((float) (2 * s), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(c - 14 * s, c + 11 * s + dy * 0.3, c + 14 * s, c + 11 * s + dy * 0.3));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(9)));
            String ne = String.format(Locale.GERMAN, "%+d°", n);
            g.setColor(t.leise);
            g.drawString(ne, (float) (c - g.getFontMetrics().stringWidth(ne) / 2.0), (float) (c + 26 * s));
        });
    }

    // ------------------------------------------------------------------ Maßstab

    /** Der Maßstab im unteren Drittel: zwei Strahlen um die Mitte, runde Länge (1, 2, 5 · 10^k m) für 70 … 200 Bildpunkte. */
    private void massstab(Blick bl, Thema t) {
        String bsig = String.format(Locale.ROOT, "%.1f|%.1f|%.1f|%.2f|%.2f|%.1f|%d|%d|%d", bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel,
                b.breite(), b.hoehe(), uhrMs.getAsLong() / 2000);
        if (bsig.equals(massAlt)) return;
        massAlt = bsig;
        double[] m = massstabRechnen(b, bl);
        massWert = m;
        if (m == null) {
            massK.male(g -> {
                tafel(g, t, massK.breite, massK.hoehe);
                g.setColor(t.leise);
                g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(12)));
                g.drawString("Maßstab: kein Boden im unteren Drittel", px(12), px(29));
            });
            return;
        }
        double laenge = m[0], pxBalken = m[1];
        String text = laenge >= 1000 ? String.format(Locale.GERMAN, "%.0f km", laenge / 1000) : laenge >= 1 ? String.format(Locale.GERMAN, "%.0f m", laenge)
                : laenge >= 0.1 ? String.format(Locale.GERMAN, "%.0f cm", laenge * 100) : String.format(Locale.GERMAN, "%.0f mm", laenge * 1000);
        String unter = bl.neigung <= -60 ? "von oben" : String.format(Locale.GERMAN, "am Boden in %s", m[4] >= 1000 ? String.format(Locale.GERMAN, "%.1f km", m[4] / 1000) : String.format(Locale.GERMAN, "%.0f m", m[4]));
        massK.male(g -> {
            tafel(g, t, massK.breite, massK.hoehe);
            int x = px(12), y = px(14), h = Math.max(4, px(6));
            int lp = (int) Math.round(Math.min(pxBalken, massK.breite - px(100)));
            int halb = lp / 2;
            g.setColor(t.text);
            g.fillRect(x, y, halb, h);
            g.setColor(t.tief);
            g.fillRect(x + halb, y, lp - halb, h);
            g.setColor(t.text);
            g.setStroke(new BasicStroke((float) Math.max(1, s)));
            g.drawRect(x, y, lp, h);
            g.setFont(t.titel((float) (14 * s)));
            g.drawString(text, x + lp + px(10), y + h + px(2));
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(11)));
            g.setColor(t.leise);
            g.drawString(unter, x, y + h + px(18));
        });
    }

    /**
     * Der Maßstab für einen Blick: {Länge (m), Bildpunkte, Zeile fy, Mitte fx, Abstand zum Boden in der Mitte (m)};
     * null, wenn einer der Strahlen nichts trifft.
     */
    public static double[] massstabRechnen(Buehne b, Blick bl) {
        double fy = MASS_ZEILE, d = 0.05;
        double[] a = b.ort(bl, 0.5 - d, fy), c = b.ort(bl, 0.5 + d, fy), mitte = b.ort(bl, 0.5, fy);
        if (a == null || c == null || mitte == null) return null;
        double meter = Math.hypot(c[0] - a[0], c[1] - a[1]), proPx = meter / (2 * d * b.breite());
        if (!(proPx > 0)) return null;
        double best = Double.NaN;
        for (double k = 1e-2; k <= 1e6 && Double.isNaN(best); k *= 10)
            for (double f : new double[] {1, 2, 5}) {
                double l = f * k, p = l / proPx;
                if (p >= 70 && p <= 200 && Double.isNaN(best)) best = l;
            }
        if (Double.isNaN(best)) best = Math.pow(10, Math.round(Math.log10(120 * proPx)));
        double abstand = Math.hypot(Math.hypot(mitte[0] - bl.ost, mitte[1] - bl.nord), mitte[2] - bl.hoehe);
        return new double[] {best, best / proPx, fy, 0.5, abstand};
    }

    // ------------------------------------------------------------------ Minikarte

    private void minikarte(Blick bl, Thema t) {
        long jetzt = uhrMs.getAsLong();
        boolean neuWasser = wasser == null || jetzt - wasserZeit >= WASSER_MS;
        if (neuWasser) {
            wasserZeit = jetzt;
            wasser = b.wasserRaster(karte[0], karte[1], karte[2], karte[3], kx, ky);
            kartenBild = kartenRaster(t);
        }
        double w = karte[2] - karte[0], h = karte[3] - karte[1];
        String sig = Math.round((bl.ost - karte[0]) / w * kx) + "|" + Math.round((bl.nord - karte[1]) / h * ky) + "|" + Math.round(bl.richtung / 2) + "|" + t;
        if (!neuWasser && sig.equals(kegelAlt)) return;
        kegelAlt = sig;
        BufferedImage bild = kartenBild;
        double horiz = 2 * Math.toDegrees(Math.atan(Math.tan(Math.toRadians(bl.bildwinkel / 2)) * b.breite() / Math.max(1, b.hoehe())));
        minikarte.male(g -> {
            int r = px(7);
            g.setColor(Thema.mitAlpha(t.tief, 210));
            g.fill(new RoundRectangle2D.Double(0, 0, minikarte.breite, minikarte.hoehe, px(10), px(10)));
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bild, r, r, px(kx), px(ky), null);
            // Orte als Punkte
            for (OrtKachel o : ortK) {
                double x = r + (o.ort.ost() - karte[0]) / w * px(kx), y = r + (karte[3] - o.ort.nord()) / h * px(ky);
                if (x < r || y < r || x > r + px(kx) || y > r + px(ky)) continue;
                g.setColor(t.marke);
                double d = o.ort.art().equals("Warft") || o.ort.art().equals("Kirche") ? 3 * s : 0;
                if (d > 0) g.fill(new Ellipse2D.Double(x - d / 2, y - d / 2, d, d));
            }
            // Auge und Blickkegel
            double ex = r + (bl.ost - karte[0]) / w * px(kx), ey = r + (karte[3] - bl.nord) / h * px(ky);
            if (ex >= 0 && ey >= 0 && ex <= minikarte.breite && ey <= minikarte.hoehe) {
                double l = 0.22 * px(kx), a = Math.toRadians(bl.richtung), hw = Math.toRadians(horiz / 2);
                Path2D.Double p = new Path2D.Double();
                p.moveTo(ex, ey);
                p.lineTo(ex + Math.sin(a - hw) * l, ey - Math.cos(a - hw) * l);
                p.lineTo(ex + Math.sin(a + hw) * l, ey - Math.cos(a + hw) * l);
                p.closePath();
                g.setColor(Thema.mitAlpha(t.primaer, 90));
                g.fill(p);
                g.setColor(t.primaer);
                g.setStroke(new BasicStroke((float) (1.2 * s)));
                g.draw(p);
                g.setColor(t.akzent.brighter());
                g.fill(new Ellipse2D.Double(ex - 3.5 * s, ey - 3.5 * s, 7 * s, 7 * s));
            }
            g.setColor(t == Thema.NAUTIK ? t.primaer : t.linie);
            g.setStroke(new BasicStroke((float) (t == Thema.NAUTIK ? 2.5 * s : 1.2 * s)));
            g.draw(new RoundRectangle2D.Double(s, s, minikarte.breite - 2 * s, minikarte.hoehe - 2 * s, px(10), px(10)));
        });
    }

    /** Land, Watt und Wasser je Zelle (Zeilen von Nord nach Süd); zählt dabei den Anteil Wasser. */
    private BufferedImage kartenRaster(Thema t) {
        boolean nautik = t == Thema.NAUTIK;
        // Seekarte: Land gelb, trockenfallend grün, flaches Wasser blau, tiefes hell; Labor: dunkel
        Color land = nautik ? new Color(0xD8C48E) : new Color(0x34483A), warft = nautik ? new Color(0xC2A86A) : new Color(0x4A6450),
                watt = nautik ? new Color(0x9DB083) : new Color(0x3B4137), flach = nautik ? new Color(0x8EC3D6) : new Color(0x1F6E8A),
                tief = nautik ? new Color(0xE4EEF2) : new Color(0x0D2B40), see = nautik ? new Color(0xF0F4F5) : new Color(0x0A2233);
        BufferedImage img = new BufferedImage(kx, ky, BufferedImage.TYPE_INT_RGB);
        long nass = 0, alle = 0;
        for (int j = 0; j < ky; j++)
            for (int i = 0; i < kx; i++) {
                float gl = grund[i + kx * j], et = wasser[i + kx * j];
                Color c;
                double tiefe = Float.isNaN(et) ? Double.NaN : Float.isNaN(gl) ? 10 : et - gl;
                alle++;
                if (!Double.isNaN(tiefe) && tiefe > 0.05) {
                    nass++;
                    c = Thema.mische(flach, tief, Math.min(1, tiefe / 4));
                } else if (Float.isNaN(gl)) c = see;
                else if (gl > 3.0) c = warft;
                else if (gl > 1.4) c = land;
                else c = watt;
                img.setRGB(i, ky - 1 - j, c.getRGB());
            }
        wasserAnteil = alle == 0 ? Double.NaN : (double) nass / alle;
        return img;
    }

    // ------------------------------------------------------------------ Legende

    private boolean legende(Thema t) {
        double v = b.messLeiter();
        int art = b.messArt();
        double[] st = b.messStufen();
        if (Double.isNaN(v)) return false;
        String sig = v + "|" + art + "|" + java.util.Arrays.toString(st) + "|" + t;
        if (sig.equals(legendeAlt)) return true;
        legendeAlt = sig;
        String[] namen = {"Wasserstand", "Tiefe", "Trübung"}, einheit = {"m NHN", "m", "g/m³"};
        String linien = art < 0 ? "" : st == null || st.length == 0 ? "Linien: " + namen[art] + " (" + einheit[art] + ")"
                : String.format(Locale.GERMAN, "Linien: %s %s … %s %s", namen[art], zahl(st[0]), zahl(st[st.length - 1]), einheit[art]);
        legende.male(g -> {
            tafel(g, t, legende.breite, legende.hoehe);
            int x = px(12), y = px(10), w = legende.breite - px(24), h = px(10);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(12)));
            g.setColor(t.text);
            g.drawString("Strömung (Partikel, LIC)", x, y + g.getFontMetrics().getAscent());
            y += px(20);
            for (int i = 0; i < w; i++) {
                g.setColor(leiter(i / (double) (w - 1)));
                g.fillRect(x + i, y, 1, h);
            }
            g.setColor(t.leise);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, px(11)));
            FontMetrics fm = g.getFontMetrics();
            String a = "0", e2 = String.format(Locale.GERMAN, "%.1f m/s", v), m2 = String.format(Locale.GERMAN, "%.2f", v / 2);
            g.drawString(a, x, y + h + fm.getAscent() + px(2));
            g.drawString(m2, x + w / 2 - fm.stringWidth(m2) / 2, y + h + fm.getAscent() + px(2));
            g.drawString(e2, x + w - fm.stringWidth(e2), y + h + fm.getAscent() + px(2));
            g.setColor(t.text);
            g.drawString(linien, x, legende.hoehe - px(10));
        });
        return true;
    }

    /** Die Farbleiter des Messblicks (linie.vert, linear) in sRGB. */
    static Color leiter(double s) {
        double[][] c = {{0.15, 0.20, 0.75}, {0.10, 0.65, 0.95}, {0.20, 0.85, 0.55}, {0.85, 0.90, 0.20}, {1.00, 0.60, 0.15}};
        s = Math.max(0, Math.min(1, s));
        int i = Math.min(3, (int) (s / 0.25));
        double f = (s - 0.25 * i) / 0.25;
        int[] r = new int[3];
        for (int k = 0; k < 3; k++) {
            double l = c[i][k] + f * (c[i + 1][k] - c[i][k]);
            double v = l <= 0.0031308 ? 12.92 * l : 1.055 * Math.pow(l, 1 / 2.4) - 0.055;
            r[k] = (int) Math.round(255 * Math.max(0, Math.min(1, v)));
        }
        return new Color(r[0], r[1], r[2]);
    }

    // ------------------------------------------------------------------ Ortsnamen

    private void ortMalen(Kachel k, Orte.Ort o, boolean gross, Thema t) {
        Font f = schrift(o, gross, t);
        k.male(g -> {
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            float x = (float) ((k.breite - fm.getStringBounds(o.name(), g).getWidth()) / 2), y = px(3) + fm.getAscent();
            // Schatten: vier Versätze, halb deckend
            g.setColor(new Color(0, 0, 0, 120));
            for (int[] v : new int[][] {{1, 1}, {-1, 1}, {1, -1}, {2, 2}}) g.drawString(o.name(), x + v[0] * (float) s, y + v[1] * (float) s);
            g.setColor(farbe(o, t));
            g.drawString(o.name(), x, y);
            // ein kleiner Strich hinunter zum Ort
            g.setColor(Thema.mitAlpha(farbe(o, t), 170));
            g.setStroke(new BasicStroke((float) Math.max(1, s)));
            g.draw(new Line2D.Double(k.breite / 2.0, y + fm.getDescent(), k.breite / 2.0, k.hoehe - 1));
        });
    }

    private static Color farbe(Orte.Ort o, Thema t) {
        return switch (o.art()) {
            case "Hallig", "Insel" -> t.text;
            case "Sand", "Priel" -> t.kurve;
            case "Kirche", "Anleger" -> t.marke;
            default -> Thema.mische(t.text, t.marke, 0.35);
        };
    }

    // ------------------------------------------------------------------ Hilfen

    /** Grund einer Tafel: abgerundet, halb deckend, Rand im Ton des Themas (Nautik: Messing). */
    private void tafel(Graphics2D g, Thema t, int w, int h) {
        g.setColor(Thema.mitAlpha(t.tief, 222));
        g.fill(new RoundRectangle2D.Double(0, 0, w, h, px(12), px(12)));
        g.setColor(t == Thema.NAUTIK ? Thema.mitAlpha(t.primaer, 200) : Thema.mitAlpha(t.linie, 230));
        g.setStroke(new BasicStroke((float) Math.max(1, (t == Thema.NAUTIK ? 1.6 : 1.0) * s)));
        g.draw(new RoundRectangle2D.Double(0.5 * s, 0.5 * s, w - s, h - s, px(12), px(12)));
    }

    private static String zahl(double v) { return Math.abs(v - Math.rint(v)) < 1e-9 ? String.format(Locale.GERMAN, "%.0f", v) : String.format(Locale.GERMAN, "%.1f", v); }

    private static double[] zahlen(String s) {
        if (s == null || s.isBlank()) return null;
        String[] t = s.trim().split("[\\s,;]+");
        double[] r = new double[t.length];
        try {
            for (int i = 0; i < t.length; i++) r[i] = Double.parseDouble(t[i]);
        } catch (NumberFormatException e) {
            return null;
        }
        return r;
    }

    /** Nimmt alle Kacheln aus der Einblendung (beim Schließen der App oder für einen Neubau). */
    public void weg() {
        for (Kachel k : List.of(uhr, rose, glas, massK, legende)) e.weg(k);
        if (minikarte != null) e.weg(minikarte);
        for (OrtKachel o : ortK) {
            e.weg(o.gross);
            e.weg(o.klein);
        }
    }
}
