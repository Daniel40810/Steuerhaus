package com.dan.steuerhaus.ausgabe;

import com.dan.core.astro.FAngle;
import com.dan.core.astro.Partialtide;
import com.dan.core.gitter.Gebiet;
import com.dan.core.pruef.Harmonik;
import com.dan.core.pruef.Pegel;
import com.dan.core.raum.Utm;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Vorhersage;

import java.io.File;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Rechnet die Konstanten des Tidenkalenders (seit Z1, Werkbuch HalligHooge 155; Aufruf {@code Ausgabe --gezeiten}).
 * <ol>
 * <li><b>Messung:</b> harmonische Analyse der Reihe von PEGELONLINE am Bezugspegel ({@code gezeiten.bezug}, aus
 *     {@code gebiet/pegel}), <b>ohne Zeiten mit Windstau</b>: der Rest gegen eine erste Analyse, gemittelt über 25 h, darf
 *     höchstens {@code gezeiten.stau} betragen (zweimal wiederholt). Was sich aus 30 Tagen nicht trennen lässt (K2, P1, NU2,
 *     2N2, und Q1, wenn es fehlt), folgt aus der Nachbartide im Verhältnis der Gleichgewichtstide, die Phase linear in der
 *     Frequenz.</li>
 * <li><b>Auf die Kennwerte gebracht [G]:</b> Die 30 Tage im Herbst haben einen kleineren Tidenhub als das Mittel über zehn
 *     Jahre (Wind hebt das Niedrigwasser; an Pellworm und Wittdün gleichermaßen rund 10 %). Darum alle Amplituden mal einem
 *     Faktor, bis die Vorhersage über ein Jahr den mittleren Tidenhub MThw − MTnw der Kennwerte trifft, und Z₀ so, dass
 *     sie MThw und MTnw im Mittel trifft. Die Zeiten bleiben.</li>
 * <li><b>Übertragung mit dem Modell:</b> In den Läufen {@code gezeiten.modell} (nach dem Einschwingen) M2, M4, M6 und K1
 *     am Ort und am Bezugspegel; je Art (halbtägig, vierteltägig, sechsteltägig) das Verhältnis der Amplituden und die
 *     Verspätung. Die Konstanten am Ort sind die des Bezugs, mal Verhältnis und um die Verspätung verschoben; eintägige
 *     wie halbtägige [G]; Z₀ um den Unterschied der Mittel im Modell.</li>
 * <li><b>Prüfung:</b> Vorhersage gegen gemessene Hoch- und Niedrigwasser ohne Windstau am Bezug; dieselbe Übertragung
 *     zum zweiten Pegel ({@code gezeiten.pruef}) gegen dessen Messung; Kennwerte; Marken des Modells am Ort.</li>
 * </ol>
 */
public final class Gezeitenrechnung {
    /** Kandidaten der Analyse (30 Tage, Rayleigh 1). */
    static final String[] KANDIDATEN = {"M2", "S2", "N2", "K1", "O1", "Q1", "M4", "MS4", "MN4", "M6"};
    /** Abgeleitete Tiden: Name, Bezug der Amplitude, zweite Tide für die Phase. */
    static final String[][] ABGELEITET = {{"K2", "S2", "M2"}, {"P1", "K1", "O1"}, {"NU2", "N2", "M2"}, {"2N2", "N2", "M2"}, {"Q1", "O1", "K1"}};
    /** Kleinster Unterschied zwischen benachbartem Hoch- und Niedrigwasser in gemessenen Reihen (m) [G]. */
    static final double ABSTAND_MESSUNG = 0.2;
    /** Halbe Breite des gleitenden Mittels gegen das Rauschen der Messung (min) [G]. */
    static final int GLAETTEN = 7;

    private Gezeitenrechnung() { }

    /** Ergebnis der Analyse einer gemessenen Reihe. */
    public record Analyse(Pegel pegel, Harmonik.Ergebnis ergebnis, int genutzt, int gesamt, List<String> abgeleitet, double[] t, double[] y, double[] stau) { }

    /** Konstanten eines Orts im Modell (M2, M4, M6, K1). */
    record Modellfit(Harmonik.Ergebnis ergebnis, int werte) { }

    public static Gezeitentafel erzeugen(Manifest m, Szenen sz, Consumer<String> meldung) throws Exception {
        long n0 = System.nanoTime();
        Gezeitenauftrag ga = Gezeitenauftrag.lies(m);
        if (ga == null) throw new IllegalArgumentException("Gezeiten: gezeiten.ort und gezeiten.bezug fehlen im Manifest");
        File gebiet = sz.gebiet();
        Map<String, Pegel> pg = Pegel.lies(gebiet);
        Pegel pb = pg.get(ga.bezug());
        if (pb == null) throw new IllegalArgumentException("Gezeiten: Pegel " + ga.bezug() + " nicht in " + gebiet + "/pegel; bekannt: " + pg.keySet());
        Pegel pw = ga.pruef() == null ? null : pg.get(ga.pruef());
        Analyse ab = robust(pb, ga.stau()), aw = pw == null ? null : robust(pw, ga.stau());
        meldung.accept(String.format(Locale.GERMAN, "Gezeiten: %s – %d von %d Werten ohne Windstau (%.1f Tage), Rest %.3f m; abgeleitet %s", pb.name, ab.genutzt, ab.gesamt,
                ab.genutzt / 1440.0, ab.ergebnis.restRms(), ab.abgeleitet));
        Map<String, String> kopf = new LinkedHashMap<>();
        // ---- auf die Kennwerte gebracht: Faktor auf die Amplituden (Tidenhub), dann Z₀ (Mittel von MThw und MTnw)
        double z0 = ab.ergebnis.mittel(), faktor = 1;
        List<Vorhersage.Konstante> k30 = konstanten(ab.ergebnis);
        double[] kb0 = kennwerte(new Vorhersage(0, k30), 2027), kb = kb0;
        double mthw = pb.kennwert("MThw"), mtnw = pb.kennwert("MTnw");
        if (!Double.isNaN(mthw) && !Double.isNaN(mtnw)) {
            for (int i = 0; i < 3; i++) {
                faktor *= (mthw - mtnw) / (kb[0] - kb[1]);
                kb = kennwerte(new Vorhersage(0, mal(k30, faktor)), 2027);
            }
            z0 = (mthw + mtnw - kb[0] - kb[1]) / 2;
        }
        meldung.accept(String.format(Locale.GERMAN, "Gezeiten: %s – Tidenhub der 30 Tage %.3f m gegen %.3f m der Kennwerte: Amplituden mal %.4f, Z₀ %.3f m", pb.name, kb0[0] - kb0[1],
                mthw - mtnw, faktor, z0));
        kopf.put("wert.kalibrierung", String.format(Locale.ROOT, "%.5f %.4f %.4f", faktor, kb0[0] - kb0[1], mthw - mtnw));
        kopf.put("bezug", String.format(Locale.ROOT, "%s (%s), PEGELONLINE %s bis %s, %d von %d Werten ohne Windstau über %.2f m; Rest %.3f m", ga.bezugName(), pb.nummer, pb.start,
                pb.ende(), ab.genutzt, ab.gesamt, ga.stau(), ab.ergebnis.restRms()));
        kopf.put("bezug.mittel", String.format(Locale.ROOT, "30 Tage %.3f m; aus MThw %.3f und MTnw %.3f (Kennwerte) %.3f m", ab.ergebnis.mittel(), mthw, mtnw, z0));
        kopf.put("bezug.hub", String.format(Locale.ROOT, "30 Tage %.3f m, Kennwerte %.3f m: Amplituden mal %.4f", kb0[0] - kb0[1], mthw - mtnw, faktor));
        kopf.put("bezug.abgeleitet", String.join(", ", ab.abgeleitet));
        kopf.put("wert.analyse.bezug", String.format(Locale.ROOT, "%d %d %.4f", ab.genutzt, ab.gesamt, ab.ergebnis.restRms()));
        List<Vorhersage.Konstante> kp = mal(k30, faktor);
        Vorhersage vb = new Vorhersage(z0, kp);

        // ---- Modell: Ort, Bezug, Prüfpegel
        Gebiet ge = Gebiet.lies(gebiet);
        double[] xb = Utm.vorwaerts(pb.laenge, pb.breite, 32), xw = pw == null ? null : Utm.vorwaerts(pw.laenge, pw.breite, 32);
        List<double[]> reiheO = new ArrayList<>(), reiheB = new ArrayList<>(), reiheW = new ArrayList<>();
        List<Gezeitentafel.Marke> marken = new ArrayList<>();
        Map<String, String> modelle = new LinkedHashMap<>();
        for (String k : ga.modell()) {
            Szenario s = sz.szenario(k);
            if (s == null || s.archiv == null) {
                meldung.accept("Gezeiten: Szenario " + k + " fehlt oder ist live – übergangen");
                continue;
            }
            try (Archivquelle q = new Archivquelle(s.archiv.toPath())) {
                double ab0 = ga.einschwingen() * 3600;
                Pegelreihe ro = Pegelreihe.aus(q, q.start, ge, ga.ort(), ga.ost(), ga.nord(), ga.umkreis());
                Pegelreihe rb = Pegelreihe.aus(q, q.start, ge, pb.name, xb[0], xb[1], ga.bezugUmkreis());
                Pegelreihe rw = xw == null ? null : Pegelreihe.aus(q, q.start, ge, pw.name, xw[0], xw[1], ga.pruefUmkreis());
                sammle(ro, q.start, ab0, reiheO);
                sammle(rb, q.start, ab0, reiheB);
                if (rw != null) sammle(rw, q.start, ab0, reiheW);
                int z = 0;
                for (Pegelreihe.Marke mk : ro.marken()) {
                    if (mk.t() < ab0) continue;
                    marken.add(new Gezeitentafel.Marke(k, mk.art() == Pegelreihe.Art.HOCHWASSER ? Vorhersage.Art.HOCHWASSER : Vorhersage.Art.NIEDRIGWASSER,
                            q.start.plusMillis(Math.round(mk.t() * 1000)), mk.pegel()));
                    z++;
                }
                modelle.put(k, s.name);
                meldung.accept(String.format(Locale.GERMAN, "Gezeiten: Modell %s – %d Stände am Ort (Gitter %s), %d Marken", k, ro.laenge(), ro.gitter.name, z));
            }
        }
        if (reiheO.size() < 500) throw new IllegalStateException("Gezeiten: zu wenig Stände im Modell (" + reiheO.size() + ")");
        Modellfit fo = fit(reiheO), fb = fit(reiheB), fw = reiheW.isEmpty() ? null : fit(reiheW);
        double[][] uo = uebertrag(fb.ergebnis, fo.ergebnis), uw = fw == null ? null : uebertrag(fb.ergebnis, fw.ergebnis);
        List<Vorhersage.Konstante> ko = uebertrage(kp, uo);
        double zo = z0 + fo.ergebnis.mittel() - fb.ergebnis.mittel();
        Vorhersage vo = new Vorhersage(zo, ko);
        String[] art = {"halbtägig (M2)", "vierteltägig (M4)", "sechsteltägig (M6)"};
        for (int a = 0; a < 3; a++)
            kopf.put("uebertrag." + (a + 1), String.format(Locale.ROOT, "%s: Verhältnis %.4f, Verspätung %+.1f min", art[a], uo[a][0], uo[a][1] / 60));
        kopf.put("uebertrag.mittel", String.format(Locale.ROOT, "Mittel im Modell: Ort %.3f, Bezug %.3f m; %d und %d Stände", fo.ergebnis.mittel(), fb.ergebnis.mittel(), fo.werte, fb.werte));

        // ---- Prüfung
        int pn = 0;
        double[] kh = kennwerte(vb, 2027);
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "kennwerte %s: MThw %.3f gegen %.3f, MTnw %.3f gegen %.3f, MThb %.3f gegen %.3f m (Vorhersage 2027 gegen Kennwerte)", pb.name, kh[0],
                mthw, kh[1], mtnw, kh[0] - kh[1], mthw - mtnw));
        kopf.put("wert.kennwerte.bezug", String.format(Locale.ROOT, "%.4f %.4f %.4f %.4f", kh[0], kh[1], mthw, mtnw));
        double[] hb = vergleich(vb, ab, ga.stau());
        kopf.put("wert.hindcast.bezug", werte(hb));
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "hindcast %s: %d Hoch- und Niedrigwasser ohne Windstau; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m", pb.name,
                (int) hb[0], hb[1], hb[2], hb[3], hb[4]));
        if (aw != null && uw != null) {
            Vorhersage vw = new Vorhersage(z0 + fw.ergebnis.mittel() - fb.ergebnis.mittel(), uebertrage(kp, uw));
            double[] hw = vergleich(vw, aw, ga.stau());
            kopf.put("wert.uebertrag.pruef", werte(hw));
            kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "uebertrag %s: %d Hoch- und Niedrigwasser ohne Windstau; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m", pw.name,
                    (int) hw[0], hw[1], hw[2], hw[3], hw[4]));
            double[] kw = kennwerte(vw, 2027);
            kopf.put("wert.kennwerte.pruef", String.format(Locale.ROOT, "%.4f %.4f %.4f %.4f", kw[0], kw[1], pw.kennwert("MThw"), pw.kennwert("MTnw")));
            kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "kennwerte %s: MThw %.3f gegen %.3f, MTnw %.3f gegen %.3f m (übertragen)", pw.name, kw[0], pw.kennwert("MThw"), kw[1],
                    pw.kennwert("MTnw")));
            Vorhersage veigen = new Vorhersage(aw.ergebnis.mittel(), konstanten(aw.ergebnis));
            double[] he = vergleich(veigen, aw, ga.stau());
            kopf.put("wert.eigen.pruef", werte(he));
            double[] ke = kennwerte(new Vorhersage(0, konstanten(aw.ergebnis)), 2027);
            kopf.put("pruef.hub." + pw.name, String.format(Locale.ROOT, "eigene Analyse der 30 Tage: Tidenhub %.3f m gegen %.3f m der Kennwerte (Faktor %.4f)", ke[0] - ke[1],
                    pw.kennwert("MThw") - pw.kennwert("MTnw"), (pw.kennwert("MThw") - pw.kennwert("MTnw")) / (ke[0] - ke[1])));
            kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "eigen %s: %d; Zeit %+.1f min, |%.1f| min; Höhe %+.3f m, |%.3f| m (eigene Analyse, zum Vergleich)", pw.name, (int) he[0], he[1],
                    he[2], he[3], he[4]));
            double am = uw[0][0] * amp(ab.ergebnis, "M2"), gm = FAngle.norm360(phase(ab.ergebnis, "M2") + uw[0][1] * Partialtide.M2.gradJeStunde() / 3600);
            kopf.put("pruef.uebertrag." + pw.name, String.format(Locale.ROOT, "M2 der 30 Tage übertragen %.4f m %.2f° gegen gemessen %.4f m %.2f°", am, gm, amp(aw.ergebnis, "M2"),
                    phase(aw.ergebnis, "M2")));
            kopf.put("wert.m2.pruef", String.format(Locale.ROOT, "%.5f %.3f %.5f %.3f", am, gm, amp(aw.ergebnis, "M2"), phase(aw.ergebnis, "M2")));
        }
        double[] ko2 = kennwerte(vo, 2027);
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "ort %s: MThw %.3f, MTnw %.3f, MThb %.3f m (Vorhersage 2027); Alter der Gezeit %.1f h", ga.ort(), ko2[0], ko2[1], ko2[0] - ko2[1],
                vo.alterStunden()));
        kopf.put("wert.ort", String.format(Locale.ROOT, "%.4f %.4f %.2f", ko2[0], ko2[1], vo.alterStunden()));
        double[] hm = modellVergleich(vo, marken);
        kopf.put("wert.modell", werte(hm));
        kopf.put("pruef." + (++pn), String.format(Locale.ROOT, "modell %s: %d Marken; Zeit %+.1f min im Mittel, |%.1f| min; Höhe %+.3f m, |%.3f| m (Modell mit Wind gegen Kalender)", ga.ort(),
                (int) hm[0], hm[1], hm[2], hm[3], hm[4]));
        for (int i = 1; i <= pn; i++) meldung.accept("Gezeiten: " + kopf.get("pruef." + i));
        kopf.put("quelle.1", "PEGELONLINE, Wasserstraßen- und Schifffahrtsverwaltung des Bundes (WSV), DL-DE Zero 2.0");
        // seit Z2: der Bezug selbst, damit die App den Windstau aus der Messung jetzt schätzen kann
        kopf.put("bezug.pegel", String.format(Locale.ROOT, "%s; %s; %.3f; %s", ga.bezug(), pb.nummer, pb.pegelnull, ga.bezugName()));
        kopf.put("bezug.z0", String.format(Locale.ROOT, "%.4f", z0));
        Map<String, List<Vorhersage.Konstante>> bt = new LinkedHashMap<>();
        bt.put("bezug", kp);
        List<Pegel> stauPegel = new ArrayList<>(List.of(pb));
        List<Vorhersage> stauV = new ArrayList<>(List.of(vb));
        // seit Z2b (F250 A): der Prüfpegel als zweiter Pegel für den Windstau – eigene Analyse, auf seine Kennwerte gebracht wie der Bezug
        if (aw != null) {
            Kalibrierung kw = kalibriert(aw, pw);
            kopf.put("bezug2.pegel", String.format(Locale.ROOT, "%s; %s; %.3f; %s", ga.pruef(), pw.nummer, pw.pegelnull, ga.pruefName()));
            kopf.put("bezug2.z0", String.format(Locale.ROOT, "%.4f", kw.z0()));
            kopf.put("bezug2.hub", String.format(Locale.ROOT, "30 Tage %.3f m, Kennwerte %.3f m: Amplituden mal %.4f", kw.hub30(), kw.hubKennwerte(), kw.faktor()));
            bt.put("bezug2", kw.konstanten());
            stauPegel.add(pw);
            stauV.add(new Vorhersage(kw.z0(), kw.konstanten()));
            meldung.accept(String.format(Locale.GERMAN, "Gezeiten: %s als zweiter Pegel für den Windstau – Amplituden mal %.4f, Z₀ %.3f m", pw.name, kw.faktor(), kw.z0()));
        }
        // seit Z2: wie gut der Scheitel aus Gezeit + gemessenem Windstau wird, je nach Vorlauf (für das Band der Uhr);
        // seit Z2b für das Mittel der Pegel, an jedem von ihnen gemessen
        double[][] sf = stauFehlerMittel(stauPegel, stauV, STAU_VORLAUF), se = stauFehler(pb, vb, STAU_VORLAUF);
        kopf.put("wert.stau.vorlauf", werte(STAU_VORLAUF));
        kopf.put("wert.stau.fehler", werte(sf[0]));
        kopf.put("wert.stau.mittel", werte(sf[1]));
        kopf.put("wert.stau.ohne", String.format(Locale.ROOT, "%.4f %.0f", sf[2][0], sf[2][1]));
        kopf.put("wert.stau.fehler.bezug", werte(se[0]));
        StringBuilder sfm = new StringBuilder();
        for (int i = 0; i < STAU_VORLAUF.length; i++) sfm.append(String.format(Locale.GERMAN, "%s%.0f h vorher %.3f m", i == 0 ? "" : ", ", STAU_VORLAUF[i], sf[0][i]));
        StringBuilder namen = new StringBuilder();
        for (Pegel p : stauPegel) namen.append(namen.length() > 0 ? " und " : "").append(p.name);
        meldung.accept(String.format(Locale.GERMAN, "Gezeiten: Windstau aus der Messung (Mittel %s) – Fehler des Scheitels (RMS) %s; ohne Wind %.3f m (%.0f Hochwasser)", namen, sfm,
                sf[2][0], sf[2][1]));
        kopf.put("gerechnet", Instant.now().toString());
        kopf.put("rechenzeit", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - n0) / 1e9));
        Gezeitentafel.schreibe(ga.datei(), ga.ort(), ga.ost(), ga.nord(), zo, ko, modelle, marken, kopf, bt);
        meldung.accept(String.format(Locale.GERMAN, "Gezeiten: %s geschrieben (%d Tiden, %d Marken) in %.1f s", ga.datei(), ko.size(), marken.size(), (System.nanoTime() - n0) / 1e9));
        return Gezeitentafel.lies(ga.datei());
    }

    // ------------------------------------------------------------------ Analyse der Messung

    static List<Partialtide> tiden(String... namen) {
        List<Partialtide> l = new ArrayList<>();
        for (String n : namen) l.add(Partialtide.nach(n));
        return l;
    }

    /** Analyse ohne Zeiten mit Windstau (dreimal: alle Werte, dann zweimal ohne die Zeiten über der Schwelle). */
    public static Analyse robust(Pegel p, double stau) {
        int n = p.werte.length;
        double[] t = new double[n], y = new double[n];
        long s0 = p.start.getEpochSecond();
        int gesamt = 0;
        for (int i = 0; i < n; i++) {
            t[i] = s0 + (double) i * p.schritt;
            y[i] = p.werte[i];
            if (!Double.isNaN(y[i])) gesamt++;
        }
        double[] yy = y.clone(), s = new double[n];
        Harmonik.Ergebnis e = null;
        for (int runde = 0; runde < 3; runde++) {
            e = Harmonik.analyse(t, yy, tiden(KANDIDATEN), 1.0);
            s = stau(e, t, y, p.schritt);
            yy = y.clone();
            for (int i = 0; i < n; i++) if (!(Math.abs(s[i]) <= stau)) yy[i] = Double.NaN;
        }
        e = Harmonik.analyse(t, yy, tiden(KANDIDATEN), 1.0);
        int genutzt = 0;
        for (double v : yy) if (!Double.isNaN(v)) genutzt++;
        List<String> abg = new ArrayList<>();
        Harmonik.Ergebnis voll = ableiten(e, abg);
        return new Analyse(p, voll, genutzt, gesamt, abg, t, y, stau(voll, t, y, p.schritt));
    }

    /** Windstau: Messung minus Vorhersage, gemittelt über 25 h (zentriert); NaN, wo die Messung fehlt. */
    static double[] stau(Harmonik.Ergebnis e, double[] t, double[] y, int schritt) {
        int n = y.length, h = (int) Math.round(12.5 * 3600 / schritt);
        double[] r = new double[n], s = new double[n];
        for (int i = 0; i < n; i++) r[i] = Double.isNaN(y[i]) ? Double.NaN : y[i] - e.wert(Instant.ofEpochSecond(Math.round(t[i])));
        double sum = 0;
        int cnt = 0;
        // gleitendes Mittel über [i − h, i + h]
        for (int i = 0; i <= Math.min(n - 1, h); i++) if (!Double.isNaN(r[i])) {
            sum += r[i];
            cnt++;
        }
        for (int i = 0; i < n; i++) {
            s[i] = Double.isNaN(y[i]) || cnt < h / 2 ? Double.NaN : sum / cnt;
            int rein = i + h + 1, raus = i - h;
            if (rein < n && !Double.isNaN(r[rein])) {
                sum += r[rein];
                cnt++;
            }
            if (raus >= 0 && !Double.isNaN(r[raus])) {
                sum -= r[raus];
                cnt--;
            }
        }
        return s;
    }

    /** Ergänzt die Tiden, die aus der Reihe nicht zu trennen waren, aus ihren Nachbarn (Gleichgewicht, Phase linear in ω). */
    static Harmonik.Ergebnis ableiten(Harmonik.Ergebnis e, List<String> abg) {
        List<Harmonik.Tide> l = new ArrayList<>(e.tiden());
        for (String[] r : ABGELEITET) {
            if (e.tide(r[0]) != null) continue;
            Harmonik.Tide a = e.tide(r[1]), b = e.tide(r[2]);
            if (a == null || b == null) continue;
            Partialtide x = Partialtide.nach(r[0]);
            double wx = x.gradJeStunde(), wa = a.tide().gradJeStunde(), wb = b.tide().gradJeStunde();
            double amp = a.amplitude() * x.gleichgewicht / a.tide().gleichgewicht;
            double dg = FAngle.norm180(b.phaseGrad() - a.phaseGrad());
            double g = FAngle.norm360(a.phaseGrad() + dg * (wx - wa) / (wb - wa));
            l.add(new Harmonik.Tide(x, amp, g));
            abg.add(String.format(Locale.ROOT, "%s %.4f m %.1f° aus %s", r[0], amp, g, r[1]));
        }
        return new Harmonik.Ergebnis(e.mittel(), l, List.of(), e.restRms(), e.werte(), e.dauerStunden());
    }

    static String werte(double[] v) {
        StringBuilder s = new StringBuilder();
        for (double x : v) s.append(s.length() > 0 ? " " : "").append(String.format(Locale.ROOT, "%.4f", x));
        return s.toString();
    }

    /** Alle Amplituden mal f. */
    static List<Vorhersage.Konstante> mal(List<Vorhersage.Konstante> l, double f) {
        List<Vorhersage.Konstante> r = new ArrayList<>();
        for (Vorhersage.Konstante k : l) r.add(new Vorhersage.Konstante(k.tide(), k.amplitude() * f, k.phaseGrad()));
        return r;
    }

    static List<Vorhersage.Konstante> konstanten(Harmonik.Ergebnis e) {
        List<Vorhersage.Konstante> l = new ArrayList<>();
        for (Harmonik.Tide t : e.tiden()) l.add(new Vorhersage.Konstante(t.tide(), t.amplitude(), t.phaseGrad()));
        return l;
    }

    static double amp(Harmonik.Ergebnis e, String n) {
        Harmonik.Tide t = e.tide(n);
        return t == null ? Double.NaN : t.amplitude();
    }

    static double phase(Harmonik.Ergebnis e, String n) {
        Harmonik.Tide t = e.tide(n);
        return t == null ? Double.NaN : t.phaseGrad();
    }

    /** Mittel von Hoch- und Niedrigwasser über das Jahr: {MThw, MTnw}. */
    static double[] kennwerte(Vorhersage v, int jahr) {
        Instant a = LocalDate.of(jahr, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant(), b = LocalDate.of(jahr + 1, 1, 1).atStartOfDay(ZoneOffset.UTC).toInstant();
        double sh = 0, sn = 0;
        int nh = 0, nn = 0;
        for (Vorhersage.Extrem e : v.extreme(a, b)) {
            if (e.art() == Vorhersage.Art.HOCHWASSER) {
                sh += e.hoehe();
                nh++;
            } else {
                sn += e.hoehe();
                nn++;
            }
        }
        return new double[] {sh / Math.max(1, nh), sn / Math.max(1, nn)};
    }

    // ------------------------------------------------------------------ Modell

    static void sammle(Pegelreihe r, Instant start, double ab, List<double[]> ziel) {
        double[] t = r.zeiten(), p = r.pegel();
        for (int i = 0; i < t.length; i++) if (t[i] >= ab && !Double.isNaN(p[i])) ziel.add(new double[] {start.getEpochSecond() + t[i], p[i]});
    }

    static Modellfit fit(List<double[]> reihe) {
        double[] t = new double[reihe.size()], y = new double[reihe.size()];
        for (int i = 0; i < t.length; i++) {
            t[i] = reihe.get(i)[0];
            y[i] = reihe.get(i)[1];
        }
        return new Modellfit(Harmonik.analyse(t, y, tiden("M2", "M4", "M6", "K1"), 0), t.length);
    }

    /** Je Art {Verhältnis, Verspätung in s} des Orts gegen den Bezug: halbtägig aus M2, vierteltägig aus M4, sechsteltägig aus M6. */
    static double[][] uebertrag(Harmonik.Ergebnis bezug, Harmonik.Ergebnis ort) {
        String[] r = {"M2", "M4", "M6"};
        double[][] u = new double[3][];
        for (int a = 0; a < 3; a++) {
            Harmonik.Tide b = bezug.tide(r[a]), o = ort.tide(r[a]);
            if (b == null || o == null || b.amplitude() < 1e-4) {
                u[a] = a == 0 ? new double[] {1, 0} : u[0].clone();
                continue;
            }
            double dg = FAngle.norm180(o.phaseGrad() - b.phaseGrad());
            u[a] = new double[] {o.amplitude() / b.amplitude(), dg / b.tide().gradJeStunde() * 3600};
        }
        return u;
    }

    /** Die Konstanten des Bezugs, übertragen: Amplitude mal Verhältnis der Art, Phase um ω · Verspätung (eintägige wie halbtägige). */
    static List<Vorhersage.Konstante> uebertrage(List<Vorhersage.Konstante> bezug, double[][] u) {
        List<Vorhersage.Konstante> l = new ArrayList<>();
        for (Vorhersage.Konstante k : bezug) {
            int art = k.tide().art();
            double[] f = art >= 6 ? u[2] : art >= 4 ? u[1] : u[0];
            l.add(new Vorhersage.Konstante(k.tide(), k.amplitude() * f[0], FAngle.norm360(k.phaseGrad() + k.tide().gradJeStunde() * f[1] / 3600)));
        }
        return l;
    }

    // ------------------------------------------------------------------ Vergleiche

    /**
     * Vorhersage gegen die gemessenen Hoch- und Niedrigwasser ohne Windstau: {Zahl, mittlere Zeit (min), mittlere |Zeit|,
     * mittlere Höhe (m), mittlere |Höhe|} (Vorhersage minus Messung).
     */
    /** Vorlauf (h), für den der Fehler des Scheitels mit gemessenem Windstau gerechnet wird [G]. */
    public static final double[] STAU_VORLAUF = {1, 2, 3, 4, 6};

    /** Seit Z2: wie {@link #stauFehlerMittel} mit einem Pegel. */
    public static double[][] stauFehler(Pegel p, Vorhersage v, double[] vorlauf) { return stauFehlerMittel(List.of(p), List.of(v), vorlauf); }

    /** Die gemessenen Hoch- und Niedrigwasser eines Pegels. */
    public static List<Vorhersage.Extrem> extreme(Pegel p) {
        int n = p.werte.length;
        double[] t = new double[n], y = new double[n];
        long s0 = p.start.getEpochSecond();
        for (int i = 0; i < n; i++) {
            t[i] = s0 + (double) i * p.schritt;
            y[i] = p.werte[i];
        }
        return extremeReihe(t, y, p.schritt);
    }

    /**
     * Seit Z2b: der Scheitel jedes gemessenen Hochwassers an jedem der Pegel, geschätzt wie die Landunter-Uhr – Gezeit
     * dieses Pegels plus das Mittel der Windstaus aller Pegel in der Stunde vor dem Zeitpunkt h Stunden vorher – gegen den
     * gemessenen Scheitel. Fehlt an einem Pegel die Messung, zählt das Hochwasser nicht. Gibt {RMS je Vorlauf}, {mittlerer
     * Fehler je Vorlauf}, {RMS ohne Wind, Zahl der Hochwasser}.
     */
    public static double[][] stauFehlerMittel(List<Pegel> pegel, List<Vorhersage> v, double[] vorlauf) {
        double[] rms = new double[vorlauf.length], mit = new double[vorlauf.length];
        double so = 0;
        int no = 0;
        List<List<Vorhersage.Extrem>> gem = new ArrayList<>();
        for (Pegel p : pegel) gem.add(extreme(p));
        for (int k = 0; k < vorlauf.length; k++) {
            double q = 0, m = 0;
            int c = 0;
            for (int i = 0; i < pegel.size(); i++)
                for (Vorhersage.Extrem e : gem.get(i)) {
                    if (e.art() != Vorhersage.Art.HOCHWASSER) continue;
                    Vorhersage.Extrem hv = naechstesHw(v.get(i), e.zeit());
                    Instant t0 = e.zeit().minusSeconds(Math.round(vorlauf[k] * 3600));
                    double st = 0;
                    for (int j = 0; j < pegel.size(); j++) st += stundenStau(pegel.get(j), v.get(j), t0) / pegel.size();
                    if (hv == null || Double.isNaN(st)) continue;
                    double f = hv.hoehe() + st - e.hoehe();
                    q += f * f;
                    m += f;
                    c++;
                    if (k == 0) {
                        so += (hv.hoehe() - e.hoehe()) * (hv.hoehe() - e.hoehe());
                        no++;
                    }
                }
            rms[k] = c == 0 ? Double.NaN : Math.sqrt(q / c);
            mit[k] = c == 0 ? Double.NaN : m / c;
        }
        return new double[][] {rms, mit, {no == 0 ? Double.NaN : Math.sqrt(so / no), no}};
    }

    /** Konstanten eines Pegels, auf seine Kennwerte gebracht: Amplituden mal Faktor (Tidenhub), Z₀ aus MThw und MTnw. */
    public record Kalibrierung(List<Vorhersage.Konstante> konstanten, double z0, double faktor, double hub30, double hubKennwerte) { }

    /** Seit Z2b: wie der Bezug (Faktor in drei Schritten auf den Tidenhub der Kennwerte 2027, Z₀ auf MThw/MTnw); ohne Kennwerte unverändert. */
    public static Kalibrierung kalibriert(Analyse a, Pegel p) {
        double z0 = a.ergebnis.mittel(), faktor = 1;
        List<Vorhersage.Konstante> k30 = konstanten(a.ergebnis);
        double[] kb0 = kennwerte(new Vorhersage(0, k30), 2027), kb = kb0;
        double mthw = p.kennwert("MThw"), mtnw = p.kennwert("MTnw");
        if (!Double.isNaN(mthw) && !Double.isNaN(mtnw)) {
            for (int i = 0; i < 3; i++) {
                faktor *= (mthw - mtnw) / (kb[0] - kb[1]);
                kb = kennwerte(new Vorhersage(0, mal(k30, faktor)), 2027);
            }
            z0 = (mthw + mtnw - kb[0] - kb[1]) / 2;
        }
        return new Kalibrierung(mal(k30, faktor), z0, faktor, kb0[0] - kb0[1], mthw - mtnw);
    }

    /** Windstau wie die App: Mittel von Messung minus Gezeit in der Stunde vor t0 (NaN mit weniger als 30 Werten). */
    public static double stundenStau(Pegel p, Vorhersage v, Instant t0) {
        double s = 0;
        int c = 0;
        long a = t0.getEpochSecond() - 3600, e = t0.getEpochSecond(), s0 = p.start.getEpochSecond();
        for (long i = Math.max(0, (a - s0) / p.schritt); i < p.werte.length; i++) {
            long ts = s0 + i * p.schritt;
            if (ts > e) break;
            if (ts <= a || Float.isNaN(p.werte[(int) i])) continue;
            s += p.werte[(int) i] - v.wert(Instant.ofEpochSecond(ts));
            c++;
        }
        return c < 30 ? Double.NaN : s / c;
    }

    /** Das Hochwasser der Vorhersage nächst t (höchstens 3 h weg), sonst null. */
    public static Vorhersage.Extrem naechstesHw(Vorhersage v, Instant t) {
        Vorhersage.Extrem b = null;
        for (Vorhersage.Extrem e : v.extreme(t.minusSeconds(4 * 3600), t.plusSeconds(4 * 3600)))
            if (e.art() == Vorhersage.Art.HOCHWASSER && (b == null || Math.abs(e.zeit().getEpochSecond() - t.getEpochSecond()) < Math.abs(b.zeit().getEpochSecond() - t.getEpochSecond()))) b = e;
        return b != null && Math.abs(b.zeit().getEpochSecond() - t.getEpochSecond()) <= 3 * 3600 ? b : null;
    }

    static double[] vergleich(Vorhersage v, Analyse a, double stau) {
        List<Vorhersage.Extrem> gemessen = extremeReihe(a.t, a.y, a.pegel.schritt);
        List<Vorhersage.Extrem> vorher = v.extreme(Instant.ofEpochSecond(Math.round(a.t[0])), Instant.ofEpochSecond(Math.round(a.t[a.t.length - 1])));
        double n = 0, st = 0, sta = 0, sh = 0, sha = 0;
        long s0 = Math.round(a.t[0]);
        for (Vorhersage.Extrem g : gemessen) {
            int i = (int) ((g.zeit().getEpochSecond() - s0) / a.pegel.schritt);
            if (i < 0 || i >= a.stau.length || !(Math.abs(a.stau[i]) <= stau)) continue;
            Vorhersage.Extrem b = naechstes(vorher, g, 3 * 3600);
            if (b == null) continue;
            double dt = (b.zeit().getEpochSecond() - g.zeit().getEpochSecond()) / 60.0, dh = b.hoehe() - g.hoehe();
            n++;
            st += dt;
            sta += Math.abs(dt);
            sh += dh;
            sha += Math.abs(dh);
        }
        return n == 0 ? new double[] {0, Double.NaN, Double.NaN, Double.NaN, Double.NaN} : new double[] {n, st / n, sta / n, sh / n, sha / n};
    }

    /** Die Marken des Modells am Ort gegen den Kalender (ohne Rücksicht auf den Wind im Lauf). */
    static double[] modellVergleich(Vorhersage v, List<Gezeitentafel.Marke> marken) {
        double n = 0, st = 0, sta = 0, sh = 0, sha = 0;
        for (Gezeitentafel.Marke m : marken) {
            Vorhersage.Extrem g = new Vorhersage.Extrem(m.art(), m.zeit(), m.hoehe());
            Vorhersage.Extrem b = naechstes(v.extreme(m.zeit().minusSeconds(4 * 3600), m.zeit().plusSeconds(4 * 3600)), g, 3 * 3600);
            if (b == null) continue;
            double dt = (b.zeit().getEpochSecond() - g.zeit().getEpochSecond()) / 60.0, dh = b.hoehe() - g.hoehe();
            n++;
            st += dt;
            sta += Math.abs(dt);
            sh += dh;
            sha += Math.abs(dh);
        }
        return n == 0 ? new double[] {0, Double.NaN, Double.NaN, Double.NaN, Double.NaN} : new double[] {n, st / n, sta / n, sh / n, sha / n};
    }

    static Vorhersage.Extrem naechstes(List<Vorhersage.Extrem> l, Vorhersage.Extrem g, long grenze) {
        Vorhersage.Extrem b = null;
        long best = Long.MAX_VALUE;
        for (Vorhersage.Extrem e : l) {
            if (e.art() != g.art()) continue;
            long d = Math.abs(e.zeit().getEpochSecond() - g.zeit().getEpochSecond());
            if (d < best) {
                best = d;
                b = e;
            }
        }
        return best <= grenze ? b : null;
    }

    /**
     * Hoch- und Niedrigwasser einer gemessenen Reihe: geglättet (gleitendes Mittel ± {@link #GLAETTEN} min), dann wie
     * {@code Pegelreihe.marken} mit dem Abstand {@link #ABSTAND_MESSUNG}; Lücken unterbrechen nicht.
     */
    public static List<Vorhersage.Extrem> extremeReihe(double[] t, double[] y, int schritt) {
        int n = y.length, h = Math.max(1, GLAETTEN * 60 / schritt);
        double[] g = new double[n];
        for (int i = 0; i < n; i++) {
            double s = 0;
            int c = 0;
            for (int j = Math.max(0, i - h); j <= Math.min(n - 1, i + h); j++) if (!Double.isNaN(y[j])) {
                s += y[j];
                c++;
            }
            g[i] = c > h ? s / c : Double.NaN;
        }
        List<Vorhersage.Extrem> m = new ArrayList<>();
        int iMax = -1, iMin = -1, lage = 0;
        for (int i = 0; i < n; i++) {
            if (Double.isNaN(g[i])) continue;
            if (iMax < 0 || g[i] > g[iMax]) iMax = i;
            if (iMin < 0 || g[i] < g[iMin]) iMin = i;
            if (lage >= 0 && g[i] < g[iMax] - ABSTAND_MESSUNG) {
                if (lage > 0) m.add(new Vorhersage.Extrem(Vorhersage.Art.HOCHWASSER, Instant.ofEpochSecond(Math.round(t[iMax])), g[iMax]));
                lage = -1;
                iMin = i;
            } else if (lage <= 0 && g[i] > g[iMin] + ABSTAND_MESSUNG) {
                if (lage < 0) m.add(new Vorhersage.Extrem(Vorhersage.Art.NIEDRIGWASSER, Instant.ofEpochSecond(Math.round(t[iMin])), g[iMin]));
                lage = 1;
                iMax = i;
            }
        }
        return m;
    }
}
