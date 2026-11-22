package com.dan.steuerhaus.ausgabe;

import com.dan.lauf.Buehne;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Orte;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Die Postkarte (seit X4, Werkbuch HalligHooge 150): der Blick jetzt als Bild mit Rahmen und Unterschrift. Drei Formate
 * ({@link Format}), drei Stile ({@link Stil}: Goldkante wie das Symbol der App, Weiß wie eine Postkarte, randlos mit
 * Band). Die Vorlagen stehen im Manifest; ihre Zeilen haben Platzhalter, die {@link #werte} aus dem Zustand der Bühne
 * füllt:
 * <pre>
 * postkarte.titel     = Hallig Hooge
 * postkarte.vorlage.1 = Goldkante; gold; {titel}; {ort} · {datum}, {uhr} · {pegelname} {pegel} · Wind {wind}
 * </pre>
 * Platzhalter: {@code {titel} {ort} {ansicht} {szenario} {datum} {uhr} {pegel} {pegelname} {wind}}. Die Ausgabe rechnet
 * das Bild genau in der Größe der Bildfläche ({@link #bildflaeche}), ohne Skalieren; A6 bekommt 300 dpi in der Datei.
 */
public final class Postkarte {
    /** Ein Format: Name, Breite, Höhe (Bildpunkte), Auflösung für den Druck (dpi; 0: keine). */
    public enum Format {
        A6("a6", "A6 quer, 300 dpi (148 × 105 mm)", 1748, 1240, 300),
        K4("4k", "4K (3840 × 2160)", 3840, 2160, 0),
        QUADRAT("quadrat", "Quadrat (2160 × 2160)", 2160, 2160, 0);

        public final String schluessel, name;
        public final int breite, hoehe, dpi;

        Format(String schluessel, String name, int breite, int hoehe, int dpi) {
            this.schluessel = schluessel;
            this.name = name;
            this.breite = breite;
            this.hoehe = hoehe;
            this.dpi = dpi;
        }

        public static Format zu(String s) {
            for (Format f : values()) if (f.schluessel.equalsIgnoreCase(s == null ? "" : s.strip())) return f;
            throw new IllegalArgumentException("Postkarte: Format „" + s + "“ (a6, 4k, quadrat)");
        }

        @Override public String toString() { return name; }
    }

    /** Ein Stil des Rahmens. */
    public enum Stil {
        GOLD("gold", "Goldkante"), WEISS("weiss", "Weiß"), OHNE("ohne", "Randlos");

        public final String schluessel, name;

        Stil(String schluessel, String name) {
            this.schluessel = schluessel;
            this.name = name;
        }

        public static Stil zu(String s) {
            String k = s == null ? "" : s.strip().toLowerCase(Locale.GERMAN).replace("ß", "ss");
            for (Stil x : values()) if (x.schluessel.equals(k)) return x;
            throw new IllegalArgumentException("Postkarte: Stil „" + s + "“ (gold, weiss, ohne)");
        }
    }

    /** Eine Vorlage aus dem Manifest: Name, Stil, Muster der Titelzeile und der Zeile darunter. */
    public record Vorlage(String name, Stil stil, String titel, String zeile) {
        @Override public String toString() { return name + " (" + stil.name + ")"; }
    }

    /** Die Platzhalter, die {@link #werte} kennt. */
    public static final List<String> PLATZHALTER = List.of("titel", "ort", "ansicht", "szenario", "datum", "uhr", "pegel", "pegelname", "wind");

    private Postkarte() { }

    /** Die Vorlagen des Manifests ({@code postkarte.vorlage.N}); ohne Eintrag eine Goldkante. Fehler in {@code fehler}. */
    public static List<Vorlage> vorlagen(Manifest m, List<String> fehler) {
        List<Vorlage> l = new ArrayList<>();
        for (String z : m.nummern("postkarte.vorlage.")) {
            String[] t = z.split(";", 4);
            try {
                if (t.length < 4) throw new IllegalArgumentException("Name; Stil; Titel; Zeile erwartet: " + z);
                l.add(new Vorlage(t[0].strip(), Stil.zu(t[1]), t[2].strip(), t[3].strip()));
            } catch (IllegalArgumentException x) {
                if (fehler != null) fehler.add(x.getMessage());
            }
        }
        if (l.isEmpty()) l.add(new Vorlage("Goldkante", Stil.GOLD, "{titel}", "{ort} · {datum}, {uhr}"));
        return l;
    }

    /** Eine Vorlage nach Name oder Nummer (1 …); unbekannt: die erste. */
    public static Vorlage vorlage(List<Vorlage> l, String s) {
        if (s != null) {
            for (Vorlage v : l) if (v.name().equalsIgnoreCase(s.strip())) return v;
            try {
                int n = Integer.parseInt(s.strip());
                if (n >= 1 && n <= l.size()) return l.get(n - 1);
            } catch (NumberFormatException e) {
                // unten
            }
        }
        return l.get(0);
    }

    /**
     * Setzt die Platzhalter {name} ein; unbekannte bleiben stehen. Ein Teil zwischen „·“, in dem ein Platzhalter leer
     * bleibt (etwa „Wind {wind}“ ohne Wind), fällt ganz weg.
     */
    public static String fuelle(String muster, Map<String, String> werte) {
        List<String> teile = new ArrayList<>();
        for (String teil : muster.split("·")) {
            Matcher leer = Pattern.compile("\\{([a-z]+)\\}").matcher(teil);
            boolean weg = false;
            while (leer.find()) {
                String v = werte.get(leer.group(1));
                if (v != null && v.isBlank()) weg = true;
            }
            if (!weg) teile.add(ersetze(teil, werte));
        }
        return String.join(" · ", teile.stream().map(String::strip).filter(t -> !t.isEmpty()).toList()).replaceAll("^[,\\s]+|[,\\s]+$", "")
                .replaceAll("\\s{2,}", " ").strip();
    }

    private static String ersetze(String muster, Map<String, String> werte) {
        Matcher mt = Pattern.compile("\\{([a-z]+)\\}").matcher(muster);
        StringBuilder b = new StringBuilder();
        while (mt.find()) {
            String v = werte.get(mt.group(1));
            mt.appendReplacement(b, Matcher.quoteReplacement(v == null ? mt.group() : v));
        }
        mt.appendTail(b);
        return b.toString();
    }

    /** Woher der Wind kommt, als Richtung der Windrose (16 Striche), aus dem Vektor „wohin er weht“. */
    public static String windRichtung(double u, double v) {
        if (Math.hypot(u, v) < 1e-6) return "";
        double von = (Math.toDegrees(Math.atan2(-u, -v)) + 360) % 360;
        String[] r = {"N", "NNO", "NO", "ONO", "O", "OSO", "SO", "SSO", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"};
        return r[(int) Math.round(von / 22.5) % 16];
    }

    /**
     * Die Werte für die Platzhalter aus dem Zustand der Bühne. {@code ort}: aus der Luft (Auge über 80 m NHN) „Über …“ mit
     * der nächsten Hallig, sonst der nächste Ort (Warft, Kirche, Anleger, Sand, Hallig) in 1,5 km; sonst die Ansicht.
     */
    public static Map<String, String> werte(Manifest m, String pegelName, Buehne.Zustand z, String ansicht, List<Orte.Ort> orte, ZoneId zone) {
        Map<String, String> w = new LinkedHashMap<>();
        w.put("titel", m.text("postkarte.titel", m.titel()));
        w.put("ansicht", ansicht == null ? "" : ansicht);
        w.put("szenario", z != null && z.szenario != null ? z.szenario.name : "");
        if (z != null && z.jetzt != null) {
            w.put("datum", DateTimeFormatter.ofPattern("d. MMMM yyyy", Locale.GERMAN).withZone(zone).format(z.jetzt));
            w.put("uhr", DateTimeFormatter.ofPattern("HH:mm", Locale.GERMAN).withZone(zone).format(z.jetzt) + " Uhr");
        } else {
            w.put("datum", "");
            w.put("uhr", "");
        }
        w.put("pegelname", pegelName == null ? "" : "Pegel " + pegelName);
        w.put("pegel", z == null || pegelName == null ? "" : Double.isNaN(z.pegel) ? "trocken" : String.format(Locale.GERMAN, "%+.2f m NHN", z.pegel));
        w.put("wind", z == null || Double.isNaN(z.wind) ? "" : (windRichtung(z.windU, z.windV) + " " + String.format(Locale.GERMAN, "%.0f m/s", z.wind)).strip());
        w.put("ort", ort(z, orte, ansicht));
        return w;
    }

    static String ort(Buehne.Zustand z, List<Orte.Ort> orte, String ansicht) {
        String rest = ansicht == null ? "" : ansicht;
        if (z == null || z.blick == null || orte == null || orte.isEmpty()) return rest;
        double o = z.blick.ost, n = z.blick.nord;
        if (z.blick.hoehe > 80) {
            Orte.Ort h = naechster(orte, o, n, List.of("Hallig", "Insel"), 8000);
            return h != null ? "Über " + h.name() : rest;
        }
        Orte.Ort x = naechster(orte, o, n, List.of("Warft", "Kirche", "Anleger", "Sand", "Hallig"), 1500);
        return x != null ? x.name() : rest;
    }

    private static Orte.Ort naechster(List<Orte.Ort> orte, double o, double n, List<String> arten, double hoechstens) {
        Orte.Ort best = null;
        double bd = hoechstens;
        for (Orte.Ort x : orte) {
            if (!arten.contains(x.art())) continue;
            double d = Math.hypot(x.ost() - o, x.nord() - n);
            if (d < bd) {
                bd = d;
                best = x;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ Gestalt

    /** Kürzere Seite, Rand und Unterschrift (Bildpunkte) eines Stils. */
    private static int[] masse(int W, int H, Stil s) {
        int k = Math.min(W, H);
        return switch (s) {
            case GOLD -> new int[] {k, (int) Math.round(0.045 * k), (int) Math.round(0.135 * k)};
            case WEISS -> new int[] {k, (int) Math.round(0.050 * k), (int) Math.round(0.145 * k)};
            case OHNE -> new int[] {k, 0, 0};
        };
    }

    /** Wo das Bild in einer Karte W × H liegt (das rechnet die Ausgabe genau so groß). */
    public static Rectangle bildflaeche(int W, int H, Stil s) {
        int[] m = masse(W, H, s);
        int r = m[1], u = m[2];
        if (s == Stil.OHNE) return new Rectangle(0, 0, W, H);
        return new Rectangle(r, r, W - 2 * r, H - r - u);
    }

    /**
     * Die Karte: {@code foto} in die Bildfläche (skaliert, wenn es nicht genau passt – nur für die Vorschau), Rahmen,
     * Titel und Zeile; {@code logo} (darf null sein) rechts in der Unterschrift. Zu lange Zeilen werden kleiner gesetzt.
     */
    public static BufferedImage gestalte(BufferedImage foto, int W, int H, Stil s, String titel, String zeile, BufferedImage logo) {
        BufferedImage k = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = k.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int[] m = masse(W, H, s);
        int kz = m[0], r = m[1], u = m[2];
        Rectangle b = bildflaeche(W, H, s);
        switch (s) {
            case GOLD -> {
                g.setPaint(new GradientPaint(0, 0, new Color(0x2a3d55), 0, H, new Color(0x121c29)));
                g.fillRect(0, 0, W, H);
            }
            case WEISS -> {
                g.setColor(new Color(0xf4efe4));
                g.fillRect(0, 0, W, H);
            }
            case OHNE -> { }
        }
        if (foto != null) g.drawImage(foto, b.x, b.y, b.width, b.height, null);
        else {
            g.setPaint(new GradientPaint(0, b.y, new Color(0x4a6a8c), 0, b.y + b.height, new Color(0x2f7f7c)));
            g.fill(b);
        }
        if (s == Stil.GOLD) {
            // die Goldkante: ein Band mit Verlauf wie am Symbol, darin eine dunkle Haarlinie
            float d = (float) (0.0065 * kz), a = (float) (0.010 * kz);
            g.setPaint(new GradientPaint(b.x, b.y, new Color(0xf1d58a), b.x + b.width, b.y + b.height, new Color(0xa8772a)));
            g.setStroke(new BasicStroke(d));
            g.draw(new RoundRectangle2D.Double(b.x - a, b.y - a, b.width + 2 * a, b.height + 2 * a, 2 * a, 2 * a));
            g.setColor(new Color(0, 0, 0, 110));
            g.setStroke(new BasicStroke(Math.max(1f, d / 4)));
            g.draw(new Rectangle(b.x, b.y, b.width - 1, b.height - 1));
        } else if (s == Stil.WEISS) {
            g.setColor(new Color(0x2b2b2b));
            g.setStroke(new BasicStroke(Math.max(1f, (float) (0.0012 * kz))));
            g.draw(new Rectangle(b.x, b.y, b.width - 1, b.height - 1));
        }
        // Unterschrift
        int x0, x1, yT, yZ;
        Color cT, cZ;
        Font fT = new Font(Font.SERIF, Font.BOLD, 10), fZ = new Font(Font.SANS_SERIF, Font.PLAIN, 10);
        float sT, sZ;
        if (s == Stil.OHNE) {
            int band = (int) Math.round(0.22 * kz);
            g.setPaint(new GradientPaint(0, H - band, new Color(0, 0, 0, 0), 0, H, new Color(0, 0, 0, 170)));
            g.fillRect(0, H - band, W, band);
            x0 = (int) Math.round(0.04 * kz);
            x1 = W - x0;
            sT = (float) (0.055 * kz);
            sZ = (float) (0.026 * kz);
            yZ = H - (int) Math.round(0.04 * kz);
            yT = yZ - (int) Math.round(sZ * 1.6);
            cT = Color.WHITE;
            cZ = new Color(0xe6e2d6);
            g.setPaint(new GradientPaint(x0, 0, new Color(0xf1d58a), x0 + (int) (0.25 * kz), 0, new Color(0xa8772a)));
            g.fillRect(x0, yT - (int) Math.round(sT * 1.05), (int) Math.round(0.12 * kz), Math.max(2, (int) Math.round(0.004 * kz)));
        } else {
            x0 = b.x;
            x1 = b.x + b.width;
            sT = (float) ((s == Stil.GOLD ? 0.050 : 0.052) * kz);
            sZ = (float) (0.025 * kz);
            int mitte = b.y + b.height + (u - r) / 2 + r / 2;
            yT = mitte - (int) Math.round(0.006 * kz);
            yZ = yT + (int) Math.round(sZ * 1.55);
            cT = s == Stil.GOLD ? new Color(0xe8c873) : new Color(0x23303d);
            cZ = s == Stil.GOLD ? new Color(0xefe6cf) : new Color(0x55585c);
        }
        int logoB = 0;
        if (logo != null && s != Stil.WEISS) {
            int lh = (int) Math.round((s == Stil.OHNE ? 0.10 : 0.085) * kz);
            int lx = x1 - lh, ly = (s == Stil.OHNE ? yZ : yZ) - lh + (int) Math.round(0.004 * kz);
            g.drawImage(logo, lx, ly, lh, lh, null);
            logoB = lh + (int) Math.round(0.02 * kz);
        }
        int breit = x1 - x0 - logoB;
        g.setFont(passend(g, fT.deriveFont(sT), titel, breit));
        g.setColor(cT);
        g.drawString(titel == null ? "" : titel, x0, yT);
        g.setFont(passend(g, fZ.deriveFont(sZ), zeile, breit));
        g.setColor(cZ);
        g.drawString(zeile == null ? "" : zeile, x0, yZ);
        g.dispose();
        return k;
    }

    /** Die Schrift, kleiner gesetzt, bis der Text in {@code breit} passt (höchstens auf 60 %). */
    static Font passend(Graphics2D g, Font f, String text, int breit) {
        if (text == null || text.isEmpty()) return f;
        Font x = f;
        for (int i = 0; i < 12; i++) {
            FontMetrics fm = g.getFontMetrics(x);
            if (fm.stringWidth(text) <= breit) return x;
            x = x.deriveFont(x.getSize2D() * 0.95f);
            if (x.getSize2D() < f.getSize2D() * 0.6f) break;
        }
        return x;
    }

    /** Passt die Zeile in die Karte, ohne unter 60 % der Schrift zu gehen? (Prüfung) */
    public static boolean passt(int W, int H, Stil s, String titel, String zeile) {
        BufferedImage p = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = p.createGraphics();
        int[] m = masse(W, H, s);
        int kz = m[0];
        Rectangle b = bildflaeche(W, H, s);
        int breit = (s == Stil.OHNE ? W - 2 * (int) Math.round(0.04 * kz) : b.width) - (s == Stil.WEISS ? 0 : (int) Math.round(0.11 * kz));
        boolean ok = g.getFontMetrics(passend(g, new Font(Font.SERIF, Font.BOLD, 10).deriveFont((float) (0.05 * kz)), titel, breit)).stringWidth(titel) <= breit
                && g.getFontMetrics(passend(g, new Font(Font.SANS_SERIF, Font.PLAIN, 10).deriveFont((float) (0.025 * kz)), zeile, breit)).stringWidth(zeile) <= breit;
        g.dispose();
        return ok;
    }

    /** Schreibt PNG, mit Auflösung (dpi &gt; 0: Angabe pHYs für den Druck). */
    public static void schreibe(BufferedImage im, int dpi, File ziel) throws IOException {
        if (ziel.getParentFile() != null) ziel.getParentFile().mkdirs();
        ImageWriter w = ImageIO.getImageWritersByFormatName("png").next();
        IIOMetadata md = w.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(im), w.getDefaultWriteParam());
        if (dpi > 0) {
            IIOMetadataNode wurzel = new IIOMetadataNode("javax_imageio_png_1.0");
            IIOMetadataNode phys = new IIOMetadataNode("pHYs");
            String ppm = String.valueOf(Math.round(dpi / 0.0254));
            phys.setAttribute("pixelsPerUnitXAxis", ppm);
            phys.setAttribute("pixelsPerUnitYAxis", ppm);
            phys.setAttribute("unitSpecifier", "meter");
            wurzel.appendChild(phys);
            md.mergeTree("javax_imageio_png_1.0", wurzel);
        }
        try (ImageOutputStream os = ImageIO.createImageOutputStream(ziel)) {
            w.setOutput(os);
            w.write(null, new IIOImage(im, null, md), w.getDefaultWriteParam());
        } finally {
            w.dispose();
        }
    }

    /** Die Auflösung aus einer PNG-Datei (dpi; 0: keine Angabe). Für die Prüfung. */
    public static int dpi(File f) throws IOException {
        try (var in = ImageIO.createImageInputStream(f)) {
            var r = ImageIO.getImageReaders(in).next();
            r.setInput(in);
            IIOMetadata md = r.getImageMetadata(0);
            IIOMetadataNode t = (IIOMetadataNode) md.getAsTree("javax_imageio_png_1.0");
            var l = t.getElementsByTagName("pHYs");
            if (l.getLength() > 0) {
                String x = ((IIOMetadataNode) l.item(0)).getAttribute("pixelsPerUnitXAxis");
                return (int) Math.round(Long.parseLong(x) * 0.0254);
            }
            return 0;
        }
    }
}
