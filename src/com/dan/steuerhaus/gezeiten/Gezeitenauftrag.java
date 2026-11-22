package com.dan.steuerhaus.gezeiten;

import com.dan.steuerhaus.Manifest;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Was das Manifest über den Tidenkalender sagt (seit Z1, Werkbuch HalligHooge 155):
 * <pre>
 * gezeiten.name     = Tidenkalender
 * gezeiten.datei    = Hooge.gezeiten                       # die Konstanten (relativ zum Manifest)
 * gezeiten.ort      = Anleger Hooge; 471381 6047983; 300   # Name; Ost Nord (UTM 32); Umkreis (m) für den Pegel im Modell
 * gezeiten.bezug    = PELLWORM_ANLEGER; Pellworm Anleger; 500   # gemessener Pegel (gebiet/pegel), dessen Konstanten gelten
 * gezeiten.pruef    = WITTDÜN; Wittdün (Amrum); 500        # zweiter Pegel, an dem die Übertragung geprüft wird
 * gezeiten.modell   = morpho, morpho2, ruhig               # Szenarien mit Archiv für die Übertragung und den Vergleich
 * gezeiten.einschwingen = 24                               # h nach dem Start eines Archivs, die nicht zählen
 * gezeiten.stau     = 0.25                                 # m: Zeiten mit mehr Windstau bleiben aus der Analyse
 * gezeiten.marke    = 2.01; Landunter                      # eine Höhe im Tagesbild (m NHN; Name)
 * gezeiten.hinweis  = Text unter dem Kalender
 * </pre>
 */
public record Gezeitenauftrag(String name, File datei, String ort, double ost, double nord, double umkreis, String bezug, String bezugName, double bezugUmkreis,
                              String pruef, String pruefName, double pruefUmkreis, List<String> modell, double einschwingen, double stau, double marke, String markeName,
                              String hinweis) {

    /** Liest die Schlüssel {@code gezeiten.*}; null ohne Ort oder Bezug. */
    public static Gezeitenauftrag lies(Manifest m) {
        String o = m.text("gezeiten.ort", ""), b = m.text("gezeiten.bezug", "");
        if (o.isBlank() || b.isBlank()) return null;
        String[] to = teile(o), tb = teile(b), tp = teile(m.text("gezeiten.pruef", "")), tm = teile(m.text("gezeiten.marke", ""));
        double[] xy = zahlen(to.length > 1 ? to[1] : "");
        List<String> mo = new ArrayList<>();
        for (String s : m.text("gezeiten.modell", "").split(",")) if (!s.isBlank()) mo.add(s.strip());
        return new Gezeitenauftrag(m.text("gezeiten.name", "Tidenkalender"), m.relativ(m.text("gezeiten.datei", "Hooge.gezeiten")), to[0],
                xy.length > 0 ? xy[0] : Double.NaN, xy.length > 1 ? xy[1] : Double.NaN, to.length > 2 ? zahl(to[2], 300) : 300,
                tb[0], tb.length > 1 ? tb[1] : tb[0], tb.length > 2 ? zahl(tb[2], 500) : 500,
                tp.length > 0 && !tp[0].isEmpty() ? tp[0] : null, tp.length > 1 ? tp[1] : tp[0], tp.length > 2 ? zahl(tp[2], 500) : 500,
                Collections.unmodifiableList(mo), zahl(m.text("gezeiten.einschwingen", "24"), 24), zahl(m.text("gezeiten.stau", "0.25"), 0.25),
                tm.length > 0 && !tm[0].isEmpty() ? zahl(tm[0], Double.NaN) : Double.NaN, tm.length > 1 ? tm[1] : "", m.text("gezeiten.hinweis", ""));
    }

    static String[] teile(String s) {
        String[] t = s.split(";");
        for (int i = 0; i < t.length; i++) t[i] = t[i].strip();
        return t;
    }

    static double[] zahlen(String s) {
        if (s.isBlank()) return new double[0];
        String[] t = s.strip().split("\\s+");
        double[] r = new double[t.length];
        for (int i = 0; i < t.length; i++) r[i] = zahl(t[i], Double.NaN);
        return r;
    }

    static double zahl(String s, double vorgabe) {
        try {
            return Double.parseDouble(s.strip().replace(',', '.'));
        } catch (RuntimeException e) {
            return vorgabe;
        }
    }
}
