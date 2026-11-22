package com.dan.steuerhaus.gezeiten;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Was das Manifest über das Watt-Fenster sagt (seit Z3, Werkbuch HalligHooge 158); es rechnet mit der Gezeit des
 * Tidenkalenders (gezeiten.*):
 * <pre>
 * watt.name    = Watt-Fenster
 * watt.datei   = Hooge.watt                          # Weg und Schwellen (relativ zum Manifest); Bilder im Ordner watt daneben
 * watt.start   = Westspitze; 468290 6047690          # Name; Ost Nord (UTM 32)
 * watt.ziel    = Japsand; 465987 6046653; 300        # Name; Ost Nord; die höchste Stelle im Umkreis (m)
 * watt.karte   = 463000 6044000 470000 6050000; 5    # Rechteck der Karte; Zelle (m)
 * watt.priel   = 120                                 # Umkreis (m) um den Sattel: der Priel, an dem das Modell das Wasser misst
 * watt.modell  = morpho, morpho2, ruhig              # Läufe, aus denen die Schwellen kommen
 * watt.gehen   = 3                                   # km/h zu Fuß im Watt
 * watt.hinweis = Text
 * </pre>
 */
public record Wattauftrag(String name, File datei, String start, double startOst, double startNord, String ziel, double zielOst, double zielNord, double zielUmkreis,
                          double[] karte, double zelle, double priel, List<String> modell, double gehen, String hinweis) {

    /** Liest {@code watt.*}; null ohne Start oder Ziel. */
    public static Wattauftrag lies(Manifest m) {
        String[] s = Gezeitenauftrag.teile(m.text("watt.start", "")), z = Gezeitenauftrag.teile(m.text("watt.ziel", "")), k = Gezeitenauftrag.teile(m.text("watt.karte", ""));
        if (s.length < 2 || z.length < 2) return null;
        double[] sp = Gezeitenauftrag.zahlen(s[1]), zp = Gezeitenauftrag.zahlen(z[1]), kr = Gezeitenauftrag.zahlen(k.length > 0 ? k[0] : "");
        if (sp.length < 2 || zp.length < 2) return null;
        List<String> mo = new ArrayList<>();
        for (String x : m.text("watt.modell", "").split(",")) if (!x.isBlank()) mo.add(x.strip());
        return new Wattauftrag(m.text("watt.name", "Watt-Fenster"), m.relativ(m.text("watt.datei", "Hooge.watt")), s[0], sp[0], sp[1], z[0], zp[0], zp[1],
                z.length > 2 ? Gezeitenauftrag.zahl(z[2], 300) : 300, kr.length == 4 ? kr : null, k.length > 1 ? Gezeitenauftrag.zahl(k[1], 5) : 5,
                Gezeitenauftrag.zahl(m.text("watt.priel", "120"), 120), Collections.unmodifiableList(mo), Gezeitenauftrag.zahl(m.text("watt.gehen", "3"), 3),
                m.text("watt.hinweis", ""));
    }

    /** Der Ordner der Bilder: {@code watt} neben der Datei. */
    public File ordner() { return new File(datei.getAbsoluteFile().getParentFile(), "watt"); }
}
