package com.dan.steuerhaus.demo;

import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Chronik;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.chronik.Abdeckung;
import com.dan.steuerhaus.chronik.Chronikansicht;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Landunteruhr;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * DB2 mit den echten Dateien (Werkbuch HalligHooge 169): Prüfstein 1 die Chronik, die der Sammler geschrieben hat
 * ({@code chronik.datei}); 2 die Abdeckung der Szenarien aus dem Archiv und ob das höchste Landunter eines hat; 3 die
 * Ansicht ohne Fenster in beiden Themen ({@code DB2_Chronik_<Thema>.png}) mit einem Knopf je Ereignis mit Szenario;
 * 4 die Landunter-Uhr mit der Zeile der Chronik und dem gemessenen Windstau zur Wahl ({@code DB2_Landunter.png}); 5 die Kopie
 * im Abbild, wenn es eins gibt, gleich der Datei; 6 der Eintrag im Menü.
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.ChronikCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class ChronikCheck {
    private ChronikCheck() { }

    static int steine, verstoesse;

    static void pruefe(boolean ok, String was, String ist) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + ist);
    }

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null, datei = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                case "--chronik" -> datei = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        if (manifest == null) throw new IllegalArgumentException("--manifest fehlt");
        Manifest m = Manifest.lies(manifest);
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        System.out.println("ChronikCheck (DB2): " + manifest);
        if (datei == null) datei = m.relativ(m.text("chronik.datei", "Hooge.chronik"));
        // 1 Chronik
        List<String> f1 = new ArrayList<>();
        Chronik c = null;
        if (!datei.isFile()) f1.add(datei + " fehlt (Sammler --chronik)");
        else {
            c = Chronik.lies(datei);
            if (c.ereignisse.isEmpty()) f1.add("keine Ereignisse");
            Instant vor = Instant.MIN;
            for (Chronik.Ereignis e : c.ereignisse) {
                if (e.ab().isBefore(vor)) f1.add("Folge bei " + e.ab());
                vor = e.ab();
                if (e.bis().isBefore(e.ab()) || e.hoechst().isBefore(e.ab()) || e.hoechst().isAfter(e.bis())) f1.add("Zeiten " + e);
                if (e.art() == Chronik.Art.LANDUNTER && e.hoehe() < c.nass) f1.add("Landunter unter NASS: " + e);
                if (e.art() == Chronik.Art.KRONE && (e.hoehe() < c.krone || e.hoehe() >= c.nass)) f1.add("Krone außerhalb: " + e);
                if (e.art() == Chronik.Art.STURMFLUT && e.stau() < 1.5) f1.add("Sturmflut unter 1,5 m über MThw: " + e);
            }
        }
        pruefe(f1.isEmpty(), "Prüfstein 1: Chronik lesbar, nach dem Beginn, Zeiten in sich, Landunter ab NASS, Krone darunter, Sturmflut ab 1,5 m über MThw",
                f1.isEmpty() ? c.ereignisse.size() + " Ereignisse, " + c.zuletzt(zone) + ", Stand " + c.erstellt : f1.toString());
        if (c == null) {
            ende();
            return;
        }
        // 2 Abdeckung
        Szenen sz = Szenen.lies(m.szenen());
        List<Chronikansicht.Szene> szenen = new ArrayList<>();
        List<String> ohne = new ArrayList<>();
        for (Szenario s : sz.szenarien()) {
            if (s.live() || s.archiv == null) continue;
            Instant[] ab = Abdeckung.lies(s.archiv);
            if (ab == null) ohne.add(s.schluessel);
            else szenen.add(new Chronikansicht.Szene(s.schluessel, s.name, ab[0], ab[1]));
        }
        Chronik.Ereignis hoch = null;
        for (Chronik.Ereignis e : c.ereignisse) if (e.art() == Chronik.Art.LANDUNTER && (hoch == null || e.hoehe() > hoch.hoehe())) hoch = e;
        Chronikansicht.Szene hs = null;
        if (hoch != null) for (Chronikansicht.Szene s : szenen) if (s.deckt(hoch.hoechst())) { hs = s; break; }
        StringBuilder z2 = new StringBuilder();
        for (Chronikansicht.Szene s : szenen) z2.append(z2.length() > 0 ? "; " : "").append(s.schluessel()).append(' ').append(s.von()).append(" bis ").append(s.bis());
        pruefe(ohne.isEmpty() && !szenen.isEmpty() && (hoch == null || hs != null), "Prüfstein 2: Abdeckung der Szenarien aus dem Archiv, das höchste Landunter hat ein Szenario",
                szenen.size() + " Szenarien" + (ohne.isEmpty() ? "" : ", ohne Lauf: " + ohne) + (hoch == null ? "" : "; Landunter " + hoch.hoechst() + " → " + (hs == null ? "keins" : hs.schluessel()))
                        + " [" + z2 + "]");
        // 3 Ansicht in beiden Themen
        List<String> f3 = new ArrayList<>();
        final Chronik cc = c;
        long soll = 0;
        for (Chronik.Ereignis e : c.ereignisse) for (Chronikansicht.Szene s : szenen) if (s.deckt(e.hoechst())) { soll++; break; }
        int[] knoepfe = new int[1];
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    Chronikansicht a = new Chronikansicht(cc, szenen, zone, m.text("chronik.hinweis", ""), (s, e) -> { });
                    knoepfe[0] = a.knoepfe().size();
                    bi[0] = a.alsBild(1180, 860);
                } catch (Exception x) {
                    f3.add(th.name + ": " + x);
                }
            });
            if (bi[0] == null) f3.add(th.name + ": kein Bild");
            else if (bilder != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "DB2_Chronik_" + th.name + ".png"));
            }
            if (knoepfe[0] != soll) f3.add(th.name + ": " + knoepfe[0] + " Knöpfe statt " + soll);
        }
        Thema.LABOR.anwenden();
        pruefe(f3.isEmpty(), "Prüfstein 3: Ansicht ohne Fenster in beiden Themen, ein Knopf je Ereignis mit Szenario", f3.isEmpty() ? soll + " Knöpfe, DB2_Chronik_*.png" : f3.toString());
        // 4 Landunter-Uhr
        List<String> f4 = new ArrayList<>();
        File gz = m.relativ(m.text("gezeiten.datei", "Hooge.gezeiten"));
        if (gz.isFile()) {
            Gezeitentafel t = Gezeitentafel.lies(gz);
            Chronik.Ereignis lu = c.letztes(Chronik.Art.LANDUNTER);
            List<Object[]> wahl = new ArrayList<>();
            if (lu != null) wahl.add(new Object[] {"Landunter " + java.time.format.DateTimeFormatter.ofPattern("d.M.yyyy").format(lu.hoechst().atZone(zone)) + ", gemessen",
                    Math.round(lu.stau() / 0.05) * 0.05});
            String[] kr = m.text("landunter.krone", "2.01; Krone").split(";"), na = m.text("landunter.nass", "2.50; Landunter").split(";");
            File tmp = Files.createTempDirectory("chronik").toFile();
            BufferedImage[] bi = new BufferedImage[1];
            String[] zeile = new String[1];
            List<String> quellen = new ArrayList<>();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    var u = new Landunteruhr(t, Double.parseDouble(kr[0].strip()), kr.length > 1 ? kr[1].strip() : "", Double.parseDouble(na[0].strip()),
                            na.length > 1 ? na[1].strip() : "", List.of(), wahl, m.text("landunter.hinweis", ""), zone, tmp);
                    u.messung = new ArrayList<>();
                    u.chronikZeile = cc.zuletzt(zone) + " · Messarchiv";
                    zeile[0] = u.chronikZeile;
                    quellen.addAll(u.quellen());
                    int i = 0;
                    for (String q : u.quellen()) {
                        if (q.contains("gemessen)") || q.contains(", gemessen")) u.quelle(i);
                        i++;
                    }
                    bi[0] = u.alsBild(1500, 1000);
                } catch (Exception x) {
                    f4.add(String.valueOf(x));
                }
            });
            if (lu != null && quellen.stream().noneMatch(q -> q.contains(", gemessen"))) f4.add("Wahl ohne den gemessenen Windstau: " + quellen);
            if (bi[0] == null) f4.add("kein Bild");
            else if (bilder != null) ImageIO.write(bi[0], "png", new File(bilder, "DB2_Landunter.png"));
            pruefe(f4.isEmpty(), "Prüfstein 4: Landunter-Uhr mit der Zeile der Chronik und dem gemessenen Windstau zur Wahl", f4.isEmpty() ? "„" + zeile[0] + "“, DB2_Landunter.png" : f4.toString());
        } else pruefe(false, "Prüfstein 4: Landunter-Uhr", gz + " fehlt");
        // 6 Menü
        var bf = com.dan.steuerhaus.buehne.Szenenapp.befehleOhneFenster(m, sz).finde("labor.chronik");
        pruefe(bf != null && "ctrl shift H".equals(bf.taste), "Prüfstein 6: Menü Labor › Chronik der Hochwasser (Strg Umschalt H)", bf == null ? "fehlt" : bf.name + ", " + bf.taste);
        // 5 Kopie im Abbild
        String k = m.text("chronik.kopie", "");
        if (!k.isBlank()) {
            File kf = m.relativ(k.split(";")[0].strip());
            if (kf.getParentFile() != null && kf.getParentFile().isDirectory())
                pruefe(kf.isFile() && Chronik.lies(kf).inhalt().equals(c.inhalt()), "Prüfstein 5: Kopie der Chronik im Abbild gleich der Datei", kf.isFile() ? kf.toString() : kf + " fehlt");
            else System.out.println("  info   Prüfstein 5: kein Abbild (" + kf.getParentFile() + ")");
        }
        ende();
    }

    static void ende() {
        System.out.println(verstoesse == 0 ? String.format(Locale.GERMAN, "ChronikCheck: %d Prüfsteine, alles in Ordnung", steine)
                : String.format(Locale.GERMAN, "ChronikCheck: %d von %d Prüfsteinen verletzt", verstoesse, steine));
        System.exit(verstoesse == 0 ? 0 : 1);
    }
}
