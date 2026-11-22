package com.dan.steuerhaus.buehne;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Die Ortsnamen einer Szene (seit W2, Werkbuch HalligHooge 113, F187 A): aus {@code <gebiet>/orte.txt} (gezogen von
 * {@code tools/orte/orte.py} aus OpenStreetMap), ausgewählt und umbenannt nach dem Manifest:
 * <pre>
 * orte.datei      = …/orte.txt                        # Vorgabe: orte.txt im Ordner des Gebiets
 * orte.arten      = Hallig, Insel, Warft, Sand, Kirche, Anleger
 * orte.ohne       = Name, Name                        # nicht zeigen
 * orte.umbenennen = Alter Name &gt; Neuer Name; …
 * orte.dazu.1     = Art; Name; Ost; Nord              # von Hand (UTM 32)
 * </pre>
 * Zeilen der Datei: {@code Art; Name; Ost; Nord; OSM}, {@code #} leitet einen Kommentar ein.
 */
public final class Orte {
    /** Ein Ort: Art, Name (nach dem Umbenennen), Lage (UTM, m), Herkunft. */
    public record Ort(String art, String name, double ost, double nord, String herkunft) { }

    /** Die Arten in der Folge ihres Rangs beim Ausweichen (was vorn steht, gewinnt). */
    static final List<String> RANG = List.of("Hallig", "Warft", "Kirche", "Anleger", "Insel", "Sand", "Priel", "Ort");

    private Orte() { }

    /** Die Orte nach dem Manifest; leer, wenn es die Datei nicht gibt. {@code meldung[0]} sagt, woher (darf null sein). */
    public static List<Ort> lies(Manifest m, File gebiet, String[] meldung) {
        String d = m.text("orte.datei", "");
        File datei = d.isEmpty() ? new File(gebiet, "orte.txt") : m.relativ(d);
        Set<String> arten = menge(m.text("orte.arten", "Hallig, Insel, Warft, Sand, Kirche, Anleger"));
        Set<String> ohne = menge(m.text("orte.ohne", ""));
        Map<String, String> neu = new LinkedHashMap<>();
        for (String paar : m.text("orte.umbenennen", "").split(";")) {
            int p = paar.indexOf('>');
            if (p > 0) neu.put(paar.substring(0, p).strip(), paar.substring(p + 1).strip());
        }
        List<Ort> l = new ArrayList<>();
        int gelesen = 0;
        if (datei.isFile()) {
            try {
                for (String z : Files.readAllLines(datei.toPath(), StandardCharsets.UTF_8)) {
                    Ort o = zeile(z, "OSM");
                    if (o == null) continue;
                    gelesen++;
                    if (arten.contains(o.art.toLowerCase(Locale.GERMAN)) && !ohne.contains(o.name.toLowerCase(Locale.GERMAN))) l.add(o);
                }
            } catch (IOException e) {
                if (meldung != null) meldung[0] = "Ortsnamen: " + datei + " nicht lesbar: " + e.getMessage();
            }
        }
        for (String z : m.nummern("orte.dazu.")) {
            Ort o = zeile(z, "Manifest");
            if (o != null) l.add(o);
        }
        List<Ort> r = new ArrayList<>();
        for (Ort o : l) r.add(neu.containsKey(o.name) ? new Ort(o.art, neu.get(o.name), o.ost, o.nord, o.herkunft) : o);
        if (meldung != null && meldung[0] == null)
            meldung[0] = datei.isFile() ? String.format(Locale.GERMAN, "Ortsnamen: %d von %d aus %s", r.size(), gelesen, datei) : "Ortsnamen: " + datei + " fehlt (tools/orte/orte.py)";
        return r;
    }

    /** Rang einer Art beim Ausweichen. */
    static int rang(String art) {
        int i = RANG.indexOf(art);
        return i < 0 ? RANG.size() : i;
    }

    public static Ort zeile(String z, String herkunft) {
        int k = z.indexOf('#');
        if (k >= 0) z = z.substring(0, k);
        String[] t = z.split(";");
        if (t.length < 4) return null;
        try {
            return new Ort(t[0].strip(), t[1].strip(), Double.parseDouble(t[2].strip()), Double.parseDouble(t[3].strip()), t.length > 4 ? t[4].strip() : herkunft);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Set<String> menge(String s) {
        Set<String> m = new LinkedHashSet<>();
        for (String x : s.split(",")) if (!x.isBlank()) m.add(x.strip().toLowerCase(Locale.GERMAN));
        return m;
    }
}
