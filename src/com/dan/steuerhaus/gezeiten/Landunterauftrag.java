package com.dan.steuerhaus.gezeiten;

import com.dan.steuerhaus.Manifest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Was das Manifest über die Landunter-Uhr sagt (seit Z2, Werkbuch HalligHooge 156); sie rechnet mit den Konstanten des
 * Tidenkalenders (gezeiten.*):
 * <pre>
 * landunter.name   = Landunter-Uhr
 * landunter.krone  = 2.01; Krone des Sommerdeichs          # m NHN; Name – die niedrigste Stelle, hier läuft Wasser über
 * landunter.nass   = 2.50; Fennen nass                     # m NHN außen, ab der die Fennen an den Warften nass werden (Modell)
 * landunter.modell = seegang, xaver                        # Stürme im Modell, an denen LandunterCheck das prüft
 * landunter.hallig = Hooge; 2500                           # die Warften des Atlas im Umkreis (m) um diesen Ort
 * landunter.sturm  = an                                    # den Sturm des Atlas als Windstau zur Wahl stellen
 * landunter.wahl.1 = 1.50; Sturmflut, BSH-Klasse            # weitere Windstaus zur Wahl (m; Name)
 * landunter.hinweis = Text unter der Uhr                  # sonst gezeiten.hinweis
 * </pre>
 */
public record Landunterauftrag(String name, double krone, String kroneName, double nass, String nassName, List<String> modell, String hallig, double umkreis, boolean sturm,
                               List<Object[]> wahl, String hinweis) {

    /** Liest die Schlüssel {@code landunter.*}; null ohne Krone. */
    public static Landunterauftrag lies(Manifest m) {
        String[] k = Gezeitenauftrag.teile(m.text("landunter.krone", ""));
        if (k.length == 0 || k[0].isEmpty()) return null;
        double kr = Gezeitenauftrag.zahl(k[0], Double.NaN);
        if (Double.isNaN(kr)) return null;
        String[] h = Gezeitenauftrag.teile(m.text("landunter.hallig", ""));
        List<Object[]> w = new ArrayList<>();
        for (String s : m.nummern("landunter.wahl.")) {
            String[] t = Gezeitenauftrag.teile(s);
            double v = Gezeitenauftrag.zahl(t[0], Double.NaN);
            if (!Double.isNaN(v) && t.length > 1) w.add(new Object[] {t[1], v});
        }
        String[] n = Gezeitenauftrag.teile(m.text("landunter.nass", ""));
        double na = n.length > 0 && !n[0].isEmpty() ? Gezeitenauftrag.zahl(n[0], kr) : kr;
        List<String> mo = new ArrayList<>();
        for (String x : m.text("landunter.modell", "").split(",")) if (!x.isBlank()) mo.add(x.strip());
        return new Landunterauftrag(m.text("landunter.name", "Landunter-Uhr"), kr, k.length > 1 ? k[1] : "Krone", Math.max(kr, na), n.length > 1 ? n[1] : "Landunter",
                Collections.unmodifiableList(mo), h.length > 0 ? h[0] : "",
                h.length > 1 ? Gezeitenauftrag.zahl(h[1], 2500) : 2500, !m.text("landunter.sturm", "an").equals("aus"), Collections.unmodifiableList(w),
                m.text("landunter.hinweis", m.text("gezeiten.hinweis", "")));
    }
}
