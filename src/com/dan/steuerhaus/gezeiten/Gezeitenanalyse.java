package com.dan.steuerhaus.gezeiten;

import com.dan.core.astro.FAngle;
import com.dan.core.astro.Partialtide;
import com.dan.core.pruef.Harmonik;
import com.dan.core.pruef.Pegel;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.ToDoubleFunction;

/**
 * Die Analyse gemessener Pegel (seit DB5, Werkbuch HalligHooge 176; vorher in {@code ausgabe.Gezeitenrechnung}, Z1 bis
 * Z2b): die harmonische Analyse ohne Zeiten mit Windstau, die abgeleiteten Tiden, der Faktor auf die Kennwerte, der
 * Vergleich der Hoch- und Niedrigwasser mit der Messung, der Windstau aus der Messung – und seit DB5 die Regeln, nach
 * denen die Gezeit mit der Messung wächst: die Prüfzeit, die Annahme, das Mittelwasser ohne Windstau und der Zeitplan der
 * Tiden. Gebraucht von der Ausgabe ({@code --gezeiten}, mit dem Modell) und vom Sammler ({@code --gezeiten}, aus dem
 * Messarchiv).
 * <p>
 * Zeiten sind Epoch-Sekunden, die Reihen haben einen festen Schritt; fehlende Werte sind NaN.
 */
public final class Gezeitenanalyse {
    /** Kandidaten von Z1 (30 Tage, Rayleigh 1); die Ausgabe {@code --gezeiten} rechnet weiter mit ihnen. */
    public static final String[] KANDIDATEN_Z1 = {"M2", "S2", "N2", "K1", "O1", "Q1", "M4", "MS4", "MN4", "M6"};
    /**
     * Seit DB5 (F277 A): dazu die Tiden, die erst längere Reihen trennen (K2, P1, NU2, 2N2, MU2, L2, T2), und die
     * Flachwassertiden des Watts. Was die Dauer nicht trennt, lässt das Rayleigh-Kriterium aus.
     */
    public static final String[] KANDIDATEN = {"M2", "S2", "N2", "K1", "O1", "Q1", "M4", "MS4", "MN4", "M6", "K2", "P1", "NU2", "2N2", "MU2", "L2", "T2",
            "2SM2", "M3", "MK3", "MO3", "S4", "2MS6", "2MN6", "MSN6", "M8", "3MS8"};
    /** Seit DB5 (F279 A): Sa und Ssa, der Jahresgang des Mittelwassers, erst ab einem vollen Jahr. */
    public static final String[] JAHR = {"SA", "SSA"};
    public static final double JAHR_STUNDEN = 365.25 * 24;
    /** Abgeleitete Tiden: Name, Bezug der Amplitude, zweite Tide für die Phase. */
    static final String[][] ABGELEITET = {{"K2", "S2", "M2"}, {"P1", "K1", "O1"}, {"NU2", "N2", "M2"}, {"2N2", "N2", "M2"}, {"Q1", "O1", "K1"}};
    /** Kleinster Unterschied zwischen benachbartem Hoch- und Niedrigwasser in gemessenen Reihen (m) [G]. */
    public static final double ABSTAND_MESSUNG = 0.2;
    /** Halbe Breite des gleitenden Mittels gegen das Rauschen der Messung (min) [G]. */
    public static final int GLAETTEN = 7;
    /** Vorlauf (h), für den der Fehler des Scheitels mit gemessenem Windstau gerechnet wird [G]. */
    public static final double[] STAU_VORLAUF = {1, 2, 3, 4, 6};

    /** Seit DB5: die Prüfzeit – die jüngsten Tage, davor die Analyse (Tage) [G]; nicht final, damit Prüfungen sie verkürzen können. */
    public static double PRUEF_TAGE = 30, ANALYSE_MIN_TAGE = 30, PRUEF_MIN_TAGE = 7;
    /** Seit DB5: angenommen, wenn Höhe (m) oder Zeit (min) um so viel besser und keins um mehr schlechter wird [G]. */
    public static double BESSER_HOEHE = 0.005, BESSER_ZEIT = 1.0;
    /** Seit DB5: so viele Hoch- und Niedrigwasser ohne Windstau braucht ein Vergleich mindestens [G]. */
    public static int MIN_EXTREME = 10;

    private Gezeitenanalyse() { }

    /** Ergebnis der Analyse einer gemessenen Reihe (t in Epoch-Sekunden, fester Schritt in s). */
    public record Analyse(Harmonik.Ergebnis ergebnis, int genutzt, int gesamt, List<String> abgeleitet, double[] t, double[] y, double[] stau, int schritt) {
        public Vorhersage vorhersage() { return new Vorhersage(ergebnis.mittel(), konstanten(ergebnis)); }

        /** Tage mit Werten ohne Windstau. */
        public double tage() { return genutzt * (double) schritt / 86_400; }

        /** Die Zahl der analysierten (nicht abgeleiteten) Tiden. */
        public int analysiert() { return ergebnis.tiden().size() - abgeleitet.size(); }
    }

    public static List<Partialtide> tiden(String... namen) {
        List<Partialtide> l = new ArrayList<>();
        for (String n : namen) {
            Partialtide p = Partialtide.nach(n);
            if (p == null) throw new IllegalArgumentException("unbekannte Tide " + n);
            l.add(p);
        }
        return l;
    }

    /** Seit DB5: die Kandidaten für eine Reihe dieser Dauer – ab einem Jahr mit Sa und Ssa. */
    public static String[] kandidaten(double dauerStunden) {
        if (dauerStunden < JAHR_STUNDEN) return KANDIDATEN;
        String[] k = java.util.Arrays.copyOf(KANDIDATEN, KANDIDATEN.length + JAHR.length);
        System.arraycopy(JAHR, 0, k, KANDIDATEN.length, JAHR.length);
        return k;
    }

    // ------------------------------------------------------------------ Analyse

    /** Analyse ohne Zeiten mit Windstau (dreimal: alle Werte, dann zweimal ohne die Zeiten über der Schwelle), dann abgeleitet. */
    public static Analyse robust(double[] t, double[] y, int schritt, double stau, String[] kandidaten) {
        int n = y.length, gesamt = 0;
        for (double v : y) if (!Double.isNaN(v)) gesamt++;
        double[] yy = y.clone(), s;
        List<Partialtide> k = waehlen(tiden(kandidaten), dauer(t, y), null);
        Harmonik.Ergebnis e;
        for (int runde = 0; runde < 3; runde++) {
            e = Harmonik.analyse(t, yy, k, 1.0);
            s = stau(e, t, y, schritt);
            yy = y.clone();
            for (int i = 0; i < n; i++) if (!(Math.abs(s[i]) <= stau)) yy[i] = Double.NaN;
        }
        e = Harmonik.analyse(t, yy, k, 1.0);
        int genutzt = 0;
        for (double v : yy) if (!Double.isNaN(v)) genutzt++;
        List<String> abg = new ArrayList<>();
        Harmonik.Ergebnis voll = ableiten(e, abg);
        return new Analyse(voll, genutzt, gesamt, abg, t, y, stau(voll, t, y, schritt), schritt);
    }

    /** Dauer der Reihe vom ersten bis zum letzten Wert (h). */
    static double dauer(double[] t, double[] y) {
        int a = 0, b = y.length - 1;
        while (a < b && Double.isNaN(y[a])) a++;
        while (b > a && Double.isNaN(y[b])) b--;
        return (t[b] - t[a]) / 3600;
    }

    /**
     * Seit DB5: die Tiden, die eine Reihe der Dauer T (h) trennt – nach der Größe ihrer Gleichgewichtstide (gleich große in der
     * Reihenfolge der Kandidaten), und jede nur, wenn sie sich von allen größeren trennen lässt, auch von denen, die selbst
     * ausfielen (Rayleigh 1). Sonst fiele die Energie einer ausgelassenen Tide in ihre kleine Nachbarin: Ohne MU2 (aus 30 Tagen
     * nicht von N2 zu trennen) nähme 2N2 sie auf, und 2N2 käme an Pellworm auf 13 cm statt rund 3 cm.
     */
    public static List<Partialtide> waehlen(List<Partialtide> kandidaten, double dauerStunden, List<Partialtide> ausgelassen) {
        List<Partialtide> ordnung = new ArrayList<>(kandidaten), gut = new ArrayList<>(), vorher = new ArrayList<>();
        ordnung.sort(java.util.Comparator.comparingDouble((Partialtide p) -> -p.gleichgewicht));
        for (Partialtide p : ordnung) {
            boolean geht = p.gradJeStunde() * dauerStunden >= 360;
            for (Partialtide q : vorher) if (Math.abs(p.gradJeStunde() - q.gradJeStunde()) * dauerStunden < 360) geht = false;
            vorher.add(p);
            if (geht) gut.add(p);
            else if (ausgelassen != null) ausgelassen.add(p);
        }
        return gut;
    }

    /** Windstau: Messung minus Vorhersage, gemittelt über 25 h (zentriert); NaN, wo die Messung fehlt. */
    public static double[] stau(Harmonik.Ergebnis e, double[] t, double[] y, int schritt) { return stau(x -> e.wert(x), t, y, schritt); }

    /** Seit DB5: wie oben mit einer Vorhersage. */
    public static double[] stau(Vorhersage v, double[] t, double[] y, int schritt) { return stau(v::wert, t, y, schritt); }

    static double[] stau(ToDoubleFunction<Instant> gezeit, double[] t, double[] y, int schritt) {
        int n = y.length, h = (int) Math.round(12.5 * 3600 / schritt);
        double[] r = new double[n], s = new double[n];
        for (int i = 0; i < n; i++) r[i] = Double.isNaN(y[i]) ? Double.NaN : y[i] - gezeit.applyAsDouble(Instant.ofEpochSecond(Math.round(t[i])));
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
    public static Harmonik.Ergebnis ableiten(Harmonik.Ergebnis e, List<String> abg) {
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

    public static List<Vorhersage.Konstante> konstanten(Harmonik.Ergebnis e) {
        List<Vorhersage.Konstante> l = new ArrayList<>();
        for (Harmonik.Tide t : e.tiden()) l.add(new Vorhersage.Konstante(t.tide(), t.amplitude(), t.phaseGrad()));
        return l;
    }

    /** Alle Amplituden mal f. */
    public static List<Vorhersage.Konstante> mal(List<Vorhersage.Konstante> l, double f) {
        List<Vorhersage.Konstante> r = new ArrayList<>();
        for (Vorhersage.Konstante k : l) r.add(new Vorhersage.Konstante(k.tide(), k.amplitude() * f, k.phaseGrad()));
        return r;
    }

    /** Mittel von Hoch- und Niedrigwasser über das Jahr: {MThw, MTnw}. */
    public static double[] kennwerte(Vorhersage v, int jahr) {
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

    /** Konstanten, auf die Kennwerte gebracht: Amplituden mal Faktor (Tidenhub), Z₀ aus MThw und MTnw. */
    public record Kalibrierung(List<Vorhersage.Konstante> konstanten, double z0, double faktor, double hubAnalyse, double hubKennwerte) {
        public Vorhersage vorhersage() { return new Vorhersage(z0, konstanten); }
    }

    /** Faktor in drei Schritten auf den Tidenhub der Kennwerte 2027, Z₀ auf MThw/MTnw; ohne Kennwerte unverändert. */
    public static Kalibrierung kalibriert(List<Vorhersage.Konstante> k, double z0, double mthw, double mtnw) {
        double faktor = 1;
        double[] kb0 = kennwerte(new Vorhersage(0, k), 2027), kb = kb0;
        if (!Double.isNaN(mthw) && !Double.isNaN(mtnw)) {
            for (int i = 0; i < 3; i++) {
                faktor *= (mthw - mtnw) / (kb[0] - kb[1]);
                kb = kennwerte(new Vorhersage(0, mal(k, faktor)), 2027);
            }
            z0 = (mthw + mtnw - kb[0] - kb[1]) / 2;
        }
        return new Kalibrierung(mal(k, faktor), z0, faktor, kb0[0] - kb0[1], mthw - mtnw);
    }

    /**
     * Die Konstanten des Bezugs, übertragen: Amplitude mal Verhältnis der Art, Phase um ω · Verspätung. {@code u} je Art
     * {Verhältnis, Verspätung in s}: halbtägig (M2), vierteltägig (M4), sechsteltägig (M6). Eintägige und langperiodische wie
     * halbtägige [G]; seit DB5 drittelstägige mit dem geometrischen Mittel der Verhältnisse und dem Mittel der Verspätungen
     * von halb- und vierteltägig, achteltägige wie sechsteltägige [G].
     */
    public static List<Vorhersage.Konstante> uebertrage(List<Vorhersage.Konstante> bezug, double[][] u) {
        List<Vorhersage.Konstante> l = new ArrayList<>();
        for (Vorhersage.Konstante k : bezug) {
            double[] f = art(u, k.tide().art());
            l.add(new Vorhersage.Konstante(k.tide(), k.amplitude() * f[0], FAngle.norm360(k.phaseGrad() + k.tide().gradJeStunde() * f[1] / 3600)));
        }
        return l;
    }

    static double[] art(double[][] u, int art) {
        if (art >= 6) return u[2];
        if (art >= 4) return u[1];
        if (art == 3) return new double[] {Math.sqrt(u[0][0] * u[1][0]), (u[0][1] + u[1][1]) / 2};
        return u[0];
    }

    // ------------------------------------------------------------------ Vergleich mit der Messung

    /**
     * Vorhersage gegen die gemessenen Hoch- und Niedrigwasser ohne Windstau ({@code |stau| ≤ grenze}), deren Zeit in
     * [von, bis] liegt: {Zahl, mittlere Zeit (min), mittlere |Zeit|, mittlere Höhe (m), mittlere |Höhe|} (Vorhersage minus
     * Messung).
     */
    public static double[] vergleich(Vorhersage v, double[] t, double[] y, double[] stau, int schritt, double grenze, long von, long bis) {
        List<Vorhersage.Extrem> gemessen = extremeReihe(t, y, schritt);
        List<Vorhersage.Extrem> vorher = v.extreme(Instant.ofEpochSecond(von - 4 * 3600), Instant.ofEpochSecond(bis + 4 * 3600));
        double n = 0, st = 0, sta = 0, sh = 0, sha = 0;
        long s0 = Math.round(t[0]);
        for (Vorhersage.Extrem g : gemessen) {
            long tg = g.zeit().getEpochSecond();
            if (tg < von || tg > bis) continue;
            int i = (int) ((tg - s0) / schritt);
            if (i < 0 || i >= stau.length || !(Math.abs(stau[i]) <= grenze)) continue;
            Vorhersage.Extrem b = naechstes(vorher, g, 3 * 3600);
            if (b == null) continue;
            double dt = (b.zeit().getEpochSecond() - tg) / 60.0, dh = b.hoehe() - g.hoehe();
            n++;
            st += dt;
            sta += Math.abs(dt);
            sh += dh;
            sha += Math.abs(dh);
        }
        return n == 0 ? new double[] {0, Double.NaN, Double.NaN, Double.NaN, Double.NaN} : new double[] {n, st / n, sta / n, sh / n, sha / n};
    }

    /** Wie oben über die ganze Reihe einer Analyse (seit Z1). */
    public static double[] vergleich(Vorhersage v, Analyse a, double grenze) {
        return vergleich(v, a.t, a.y, a.stau, a.schritt, grenze, Math.round(a.t[0]), Math.round(a.t[a.t.length - 1]));
    }

    public static Vorhersage.Extrem naechstes(List<Vorhersage.Extrem> l, Vorhersage.Extrem g, long grenze) {
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

    // ------------------------------------------------------------------ Windstau aus der Messung (seit Z2, Z2b)

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

    /** Wie {@link #stauFehlerMittel} mit einem Pegel. */
    public static double[][] stauFehler(Pegel p, Vorhersage v, double[] vorlauf) { return stauFehlerMittel(List.of(p), List.of(v), vorlauf); }

    /**
     * Der Scheitel jedes gemessenen Hochwassers an jedem der Pegel, geschätzt wie die Landunter-Uhr – Gezeit dieses Pegels
     * plus das Mittel der Windstaus aller Pegel in der Stunde vor dem Zeitpunkt h Stunden vorher – gegen den gemessenen
     * Scheitel. Fehlt an einem Pegel die Messung, zählt das Hochwasser nicht. Gibt {RMS je Vorlauf}, {mittlerer Fehler je
     * Vorlauf}, {RMS ohne Wind, Zahl der Hochwasser}.
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

    // ------------------------------------------------------------------ seit DB5: wachsen

    /**
     * Die Prüfzeit [von, ende]: die jüngsten {@link #PRUEF_TAGE} Tage, aber nicht vor dem Ende der Analyse der geltenden
     * Konstanten ({@code geltendBis}, Long.MIN_VALUE: ohne) und so, dass davor mindestens {@link #ANALYSE_MIN_TAGE} Tage zur
     * Analyse bleiben; null, wenn sie kürzer als {@link #PRUEF_MIN_TAGE} Tage würde.
     */
    public static long[] pruefzeit(long beginn, long ende, long geltendBis) {
        long von = Math.max(ende - Math.round(PRUEF_TAGE * 86_400), Math.max(geltendBis, beginn + Math.round(ANALYSE_MIN_TAGE * 86_400)));
        return ende - von < PRUEF_MIN_TAGE * 86_400 ? null : new long[] {von, ende};
    }

    /**
     * Ist {@code neu} besser als {@code alt} (je {Zahl, mittlere Zeit, mittlere |Zeit| (min), mittlere Höhe, mittlere |Höhe|
     * (m)} aus {@link #vergleich})? Ja, wenn die Höhe um mindestens {@link #BESSER_HOEHE} oder die Zeit um mindestens
     * {@link #BESSER_ZEIT} besser wird und keins von beiden um mehr als das schlechter – an mindestens
     * {@link #MIN_EXTREME} Hoch- und Niedrigwassern.
     */
    public static boolean besser(double[] neu, double[] alt) {
        if (neu == null || neu[0] < MIN_EXTREME || Double.isNaN(neu[4]) || Double.isNaN(neu[2])) return false;
        if (alt == null || alt[0] < MIN_EXTREME || Double.isNaN(alt[4]) || Double.isNaN(alt[2])) return true;
        double dh = alt[4] - neu[4], dz = alt[2] - neu[2];
        return (dh >= BESSER_HOEHE - 1e-12 || dz >= BESSER_ZEIT - 1e-12) && dh >= -BESSER_HOEHE - 1e-12 && dz >= -BESSER_ZEIT - 1e-12;
    }

    /**
     * Das Mittelwasser ohne Windstau in [von, bis): Z₀ der Vorhersage plus das Mittel von Messung minus Gezeit über die Zeiten,
     * deren Windstau (über 25 h gemittelt, aus derselben Reihe mit ihrem Rand) höchstens {@code grenze} beträgt. Dazu das Mittel
     * aller Werte (mit den Sturmtagen). Gibt {Mittel ohne Windstau, Mittel aller, genutzt, gesamt}; NaN ohne Werte.
     */
    public static double[] mittelOhneStau(double[] t, double[] y, int schritt, Vorhersage v, double grenze, long von, long bis) {
        double[] s = stau(v, t, y, schritt);
        double sr = 0, sa = 0;
        int nr = 0, na = 0;
        for (int i = 0; i < y.length; i++) {
            if (t[i] < von || t[i] >= bis || Double.isNaN(y[i])) continue;
            sa += y[i];
            na++;
            if (!(Math.abs(s[i]) <= grenze)) continue;
            sr += y[i] - v.wert(Instant.ofEpochSecond(Math.round(t[i])));
            nr++;
        }
        return new double[] {nr == 0 ? Double.NaN : v.mittel + sr / nr, na == 0 ? Double.NaN : sa / na, nr, na};
    }

    /** Ein Monat (UTC): Beginn (Epoch-Sekunden), Mittel ohne Windstau, Mittel aller Werte, genutzt, gesamt. */
    public record Monat(long beginn, double mittel, double mittelAlle, int genutzt, int gesamt) { }

    /**
     * Seit DB5 (F279 A): das Mittelwasser je Kalendermonat (UTC) wie {@link #mittelOhneStau}, mit einem Durchgang über die
     * ganze Reihe (die Gezeit nur einmal je Wert gerechnet). Monate ohne Werte fehlen.
     */
    public static List<Monat> monatsmittel(double[] t, double[] y, int schritt, Vorhersage v, double grenze) {
        int n = y.length;
        double[] r = new double[n];
        for (int i = 0; i < n; i++) r[i] = Double.isNaN(y[i]) ? Double.NaN : y[i] - v.wert(Instant.ofEpochSecond(Math.round(t[i])));
        double[] s = stau(x -> 0.0, t, r, schritt);                                     // r ist schon Messung minus Gezeit
        List<Monat> l = new ArrayList<>();
        long monat = Long.MIN_VALUE;
        double sr = 0, sa = 0;
        int nr = 0, na = 0;
        for (int i = 0; i <= n; i++) {
            long m = i == n ? Long.MAX_VALUE : monatsbeginn(Math.round(t[i]));
            if (m != monat) {
                if (monat != Long.MIN_VALUE && na > 0) l.add(new Monat(monat, nr == 0 ? Double.NaN : v.mittel + sr / nr, sa / na, nr, na));
                monat = m;
                sr = sa = 0;
                nr = na = 0;
            }
            if (i == n || Double.isNaN(y[i])) continue;
            sa += y[i];
            na++;
            if (Math.abs(s[i]) <= grenze) {
                sr += r[i];
                nr++;
            }
        }
        return l;
    }

    /** Beginn des Kalendermonats (UTC) von t, in Epoch-Sekunden. */
    public static long monatsbeginn(long t) {
        LocalDate d = Instant.ofEpochSecond(t).atZone(ZoneOffset.UTC).toLocalDate().withDayOfMonth(1);
        return d.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
    }

    /** Die Marken eines Modells am Ort gegen eine Vorhersage (ohne Rücksicht auf den Wind im Lauf): wie {@link #vergleich}. */
    public static double[] markenVergleich(Vorhersage v, List<Gezeitentafel.Marke> marken) {
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

    /** Seit DB5: ab wann eine Reihe Tiden trennt, die heute abgeleitet oder ausgelassen sind (Rayleigh 1). */
    public record Schwelle(Instant ab, double tage, String text) { }

    /** Die Schwellen für eine Messung ab {@code beginn}: je Gruppe das erste Paar bestimmt die Dauer. */
    public static List<Schwelle> zeitplan(Instant beginn) {
        String[][] gruppen = {
                {"L2", "S2", "L2 von S2, MU2 von N2"},
                {"K2", "S2", "K2 von S2, P1 von K1 (bis dahin abgeleitet)"},
                {"NU2", "N2", "NU2 von N2, 2N2 von MU2 (bis dahin abgeleitet)"},
                {"T2", "S2", "T2 von S2; Sa und Ssa, der Jahresgang des Mittelwassers"}};
        List<Schwelle> l = new ArrayList<>();
        for (String[] g : gruppen) {
            double dw = Math.abs(Partialtide.nach(g[0]).gradJeStunde() - Partialtide.nach(g[1]).gradJeStunde());
            double stunden = 360 / dw;
            if (g[0].equals("T2")) stunden = Math.max(stunden, JAHR_STUNDEN);
            l.add(new Schwelle(beginn.plusSeconds(Math.round(stunden * 3600)), stunden / 24, g[2]));
        }
        return l;
    }
}
