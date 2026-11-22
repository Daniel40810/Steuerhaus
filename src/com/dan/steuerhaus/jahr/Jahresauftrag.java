package com.dan.steuerhaus.jahr;

import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Was das Manifest über das Jahr sagt (seit Y3, Werkbuch HalligHooge 154):
 * <pre>
 * jahr.1        = morpho; Erstes Halbjahr      # Szenario (mit Archiv und Boden); Titel – der Reihe nach, der Boden geht weiter
 * jahr.2        = morpho2; Zweites Halbjahr
 * jahr.3        = morphosturm; Sturm
 * jahr.dauer    = 24 24 12                     # Teil der Spielzeit je Abschnitt (s; nur das Verhältnis zählt)
 * jahr.kasten   = 462000 6043000 468500 6051000   # Kasten des Sands (West, Süd, Ost, Nord; UTM)
 * jahr.schwelle = 1.0                          # Höhe (m NHN), über der der Sand zählt
 * jahr.sand     = Japsand                      # Name des Sands
 * jahr.profil   = 462500 6046000 471500 6046000   # Linie des Profils (Ost, Nord, Ost, Nord)
 * jahr.ansicht  = japsand_oben                 # „Im Bild zeigen“
 * jahr.hinweis  = Text unter den Zahlen
 * jahr.rand     = 10                           # Randstreifen der Läufe (Zellen), der wegbleibt
 * jahr.schritt  = 2                            # jeder wievielte Stand des Bodens ein Bild wird
 * jahr.karte.zelle = 10                        # m je Bildpunkt der Karte
 * jahr.orte     = Hallig, Sand, Insel          # Arten der Orte, deren Namen die Karte zeigt
 * ausgabe.jahr  = docs/jahr                    # Ordner der Mappe
 * </pre>
 */
public record Jahresauftrag(List<String[]> abschnitte, double[] dauer, double[] kasten, double schwelle, String sand, double[] profil, String ansicht, String hinweis,
                            int rand, int schritt, double karteZelle, List<String> orte, File ordner) {

    /** Liest die Schlüssel {@code jahr.*}; null, wenn kein Abschnitt steht. */
    public static Jahresauftrag lies(Manifest m) {
        List<String[]> ab = new ArrayList<>();
        for (String z : m.nummern("jahr.")) {
            String[] t = z.split(";", 2);
            String k = t[0].strip();
            if (k.isEmpty()) continue;
            ab.add(new String[] {k, t.length > 1 ? t[1].strip() : k});
        }
        if (ab.isEmpty()) return null;
        List<String> orte = new ArrayList<>();
        for (String o : m.text("jahr.orte", "Hallig, Sand, Insel").split(",")) if (!o.isBlank()) orte.add(o.strip());
        return new Jahresauftrag(Collections.unmodifiableList(ab), Jahresmappe.zahlen(m.text("jahr.dauer", "")), Jahresmappe.zahlen(m.text("jahr.kasten", "")),
                Jahresmappe.zahl(m.text("jahr.schwelle", "1.0"), 1.0), m.text("jahr.sand", "Sand"), Jahresmappe.zahlen(m.text("jahr.profil", "")),
                m.text("jahr.ansicht", ""), m.text("jahr.hinweis", ""), (int) Jahresmappe.zahl(m.text("jahr.rand", "10"), 10),
                Math.max(1, (int) Jahresmappe.zahl(m.text("jahr.schritt", "2"), 2)), Jahresmappe.zahl(m.text("jahr.karte.zelle", "10"), 10),
                Collections.unmodifiableList(orte), new File(m.text("ausgabe.jahr", "docs" + File.separator + "jahr")));
    }

    /** Die Archive der Abschnitte (null, wo ein Szenario fehlt oder live ist). */
    public List<File> archive(Szenen sz) {
        List<File> l = new ArrayList<>();
        for (String[] a : abschnitte) {
            Szenario s = sz.szenario(a[0]);
            l.add(s == null ? null : s.archiv);
        }
        return l;
    }

    /** Was in die Rechnung eingeht (ohne Profil, Dauer, Hinweis: die zeigt nur die Ansicht). */
    public String einstellungen() {
        StringBuilder s = new StringBuilder("jahr1");
        for (String[] a : abschnitte) s.append(';').append(a[0]);
        s.append(";rand=").append(rand).append(";schritt=").append(schritt).append(";zelle=").append(karteZelle).append(";schwelle=").append(schwelle);
        if (kasten != null) for (double v : kasten) s.append(String.format(Locale.ROOT, ";%.1f", v));
        s.append(";orte=").append(String.join(",", orte));
        return s.toString();
    }

    /** Die Kennung, die eine passende Mappe trägt; null, wenn ein Archiv fehlt. */
    public String kennung(Szenen sz) {
        List<File> a = archive(sz);
        if (a.contains(null)) return null;
        return Jahresmappe.kennung(einstellungen(), a);
    }

    /** Die gerechnete Mappe, wenn sie fertig ist und passt; sonst null (Grund in grund[0]). */
    public Jahresmappe mappe(Szenen sz, String[] grund) {
        try {
            if (!new File(ordner, Jahresmappe.DATEI).isFile()) {
                if (grund != null) grund[0] = "noch nicht gerechnet";
                return null;
            }
            Jahresmappe m = Jahresmappe.lies(ordner);
            String k = kennung(sz);
            if (!m.fertig || m.bilder.isEmpty()) {
                if (grund != null) grund[0] = "nicht fertig";
                return null;
            }
            if (k != null && !k.equals(m.kennung)) {
                if (grund != null) grund[0] = "passt nicht mehr zu den Läufen oder Einstellungen";
                return null;
            }
            return m;
        } catch (Exception e) {
            if (grund != null) grund[0] = e.getMessage();
            return null;
        }
    }
}
