package com.dan.steuerhaus.messung;

import com.dan.core.pruef.Harmonik;
import com.dan.core.pruef.Pegel;
import com.dan.core.pruef.Windstation;
import com.dan.core.raum.Utm;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Vorhersage;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Den Bestand aus dem Gebiet ins Messarchiv übernehmen (seit DB1, Werkbuch HalligHooge 167): die Pegel als Maßstab
 * ({@code pegel/}: Stammdaten, Kennwert MThw, Minutenwerte, harmonische Analyse als Konstanten {@value #HERKUNFT_PEGEL}),
 * den Wind des DWD ({@code wind/}: 10-Minuten-Mittel, Zeit vom Ende auf die Mitte gerückt), die Ortsnamen aus OSM
 * ({@code orte.txt}) und den geschätzten Ort mit seinen Schwellen. Was schon da ist, bleibt: Werte werden nur nach dem
 * jüngsten vorhandenen eingefügt, ein zweiter Lauf fügt nichts ein.
 */
public final class Uebernahme {
    public static final String HERKUNFT_PEGEL = "PEGEL_30T", HERKUNFT_TAFEL = "TAFEL";
    /** Die Größen der Reihen. */
    public static final String W = "W", FF = "FF", DD = "DD";

    /** Was übernommen wurde. */
    public record Ergebnis(int pegel, long roh, int wind, long mittel, int orte, boolean ort) { }

    private Uebernahme() { }

    public static Ergebnis uebernehmen(Messarchiv a, Messauftrag m, File gebiet, Consumer<String> log) throws IOException, SQLException {
        int np = 0, nw = 0, no = 0;
        long nr = 0, nm = 0;
        // Pegel
        if (Pegel.da(gebiet)) {
            for (Pegel p : Pegel.lies(gebiet).values()) {
                double[] u = Utm.vorwaerts(p.laenge, p.breite, 32);
                int id = a.station("PEGEL", p.name, p.name, p.nummer, p.gewaesser, p.laenge, p.breite, u[0], u[1], p.pegelnull, Double.NaN, p.kennwert("MThw"), "PEGELONLINE");
                if (p.analyse != null) {
                    List<Vorhersage.Konstante> k = new ArrayList<>();
                    for (Harmonik.Tide t : p.analyse.tiden()) k.add(new Vorhersage.Konstante(t.tide(), t.amplitude(), t.phaseGrad()));
                    a.gezeit(id, HERKUNFT_PEGEL, p.analyse.mittel(), k, p.start, p.ende());
                }
                long[] t = new long[p.werte.length];
                for (int i = 0; i < t.length; i++) t[i] = p.start.getEpochSecond() + (long) i * p.schritt;
                Long nach = a.letzte("ROH", id, W);
                int n = a.einfuegen("ROH", id, W, t, p.werte, nach, null);
                nr += n;
                np++;
                log.accept(String.format("Pegel %-24s %6d Minutenwerte%s", p.name, n, nach == null ? "" : " (nach " + Messarchiv.zeit(nach) + ")"));
            }
        } else log.accept("Keine Pegel im Gebiet (" + new File(gebiet, Pegel.ORDNER) + ")");
        // Wind
        Map<String, Windstation> ws = Windstation.lies(gebiet);
        for (Windstation w : ws.values()) {
            double[] u = Utm.vorwaerts(w.laenge, w.breite, 32);
            int id = a.station("WIND", w.kennung, w.name, w.kennung, null, w.laenge, w.breite, u[0], u[1], w.hoehe, w.geber, Double.NaN, "DWD");
            long[] t = new long[w.zeiten.length];
            float[] ff = new float[t.length], dd = new float[t.length];
            for (int i = 0; i < t.length; i++) {
                t[i] = w.zeiten[i] - 300;                                                   // Ende → Mitte der 10 Minuten
                ff[i] = w.ff[i] >= 0 && w.ff[i] <= 80 ? w.ff[i] : Float.NaN;               // wie die Prüfregel von HH_MITTEL
                dd[i] = w.dd[i] >= 0 && w.dd[i] <= 360 ? w.dd[i] : Float.NaN;
            }
            Long nf = a.letzte("MITTEL", id, FF), nd = a.letzte("MITTEL", id, DD);
            int f = a.einfuegen("MITTEL", id, FF, t, ff, nf, null), d = a.einfuegen("MITTEL", id, DD, t, dd, nd, null);
            nm += f + d;
            nw++;
            log.accept(String.format("Wind  %-24s %6d Geschwindigkeiten, %6d Richtungen", w.name + " (" + w.kennung + ")", f, d));
        }
        // Orte aus OSM
        File of = new File(gebiet, "orte.txt");
        if (of.isFile()) {
            List<String[]> orte = new ArrayList<>();
            for (String z : Files.readAllLines(of.toPath(), StandardCharsets.UTF_8)) {
                if (z.isBlank() || z.startsWith("#")) continue;
                String[] s = z.split(";");
                if (s.length < 4) continue;
                for (int i = 0; i < s.length; i++) s[i] = s[i].strip();
                orte.add(s);
            }
            no = a.orteErsetzen("OSM", orte);
            log.accept("Orte aus OSM: " + no);
        }
        // der geschätzte Ort
        boolean ort = false;
        if (m.ortSchluessel() != null && m.gezeiten() != null && m.gezeiten().isFile()) {
            Gezeitentafel g = Gezeitentafel.lies(m.gezeiten());
            double[] ll = Utm.rueckwaerts(g.ost, g.nord, 32);
            int id = a.station("ORT", m.ortSchluessel(), m.ortName() == null ? g.ort : m.ortName(), null, null, ll[0], ll[1], g.ost, g.nord, Double.NaN, Double.NaN,
                    Double.NaN, "GEZEITEN");
            a.gezeit(id, HERKUNFT_TAFEL, g.mittel, g.tiden, null, null);
            if (!Double.isNaN(m.krone())) a.schwelle(id, "KRONE", m.krone(), m.kroneText());
            if (!Double.isNaN(m.nass())) a.schwelle(id, "NASS", m.nass(), m.nassText());
            ort = true;
            log.accept("Ort " + m.ortSchluessel() + ": Gezeit aus " + m.gezeiten().getName() + ", Schwellen " + m.krone() + " und " + m.nass() + " m NHN");
        }
        return new Ergebnis(np, nr, nw, nm, no, ort);
    }
}
