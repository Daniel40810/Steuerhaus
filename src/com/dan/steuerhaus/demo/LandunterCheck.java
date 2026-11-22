package com.dan.steuerhaus.demo;

import com.dan.core.gitter.Gebiet;
import com.dan.core.pruef.Pegel;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Pegelreihe;
import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.ausgabe.Gezeitenrechnung;
import com.dan.steuerhaus.buehne.Szenenapp;
import com.dan.steuerhaus.gezeiten.Gezeitenauftrag;
import com.dan.steuerhaus.gezeiten.Gezeitentafel;
import com.dan.steuerhaus.gezeiten.Landunter;
import com.dan.steuerhaus.gezeiten.Landunterauftrag;
import com.dan.steuerhaus.gezeiten.Landunteruhr;
import com.dan.steuerhaus.gezeiten.Pegelonline;
import com.dan.steuerhaus.gezeiten.Vorhersage;
import com.dan.steuerhaus.thema.Thema;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Z2 mit Pegeln, Atlas und Archiven (Werkbuch HalligHooge 156): prüft die Landunter-Uhr mit echten Daten, nach
 * {@code Ausgabe --gezeiten}. Prüfstein 6 der Bezug in der Tafel (Nummer und Pegelnull wie im Gebiet, Konstanten),
 * 7 der gemessene Windstau am Bezug: der Scheitel jedes Hochwassers aus Gezeit plus Windstau der Stunde 1–6 h vorher
 * gegen den gemessenen Scheitel, mit und ohne Wind, 8 der Windstau gemessen an Pellworm und Wittdün (Hooge liegt
 * dazwischen), 9 die Badewanne in den Stürmen des Modells ({@code landunter.modell}): die Fennen an den Warften werden erst
 * ab der Höhe des Landunters nass, beim Scheitel steht das Wasser dort so hoch wie außen. Auskunft: PEGELONLINE jetzt
 * (wenn erreichbar). Bilder: die Uhr drei Stunden vor dem höchsten gemessenen Sturmhochwasser mit der Messung bis dahin
 * ({@code Z2_Landunter_Sturm.png}) und jetzt ohne Wind ({@code Z2_Landunter_Hooge.png}).
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.LandunterCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class LandunterCheck {
    /** Grenzen [G]: Windstau an Bezug und Prüfpegel gleich (m), Wasser beim Scheitel innen gegen außen (m). */
    static final double GLEICH = 0.30, WANNE = 0.10;
    /** Umkreis um die Mitte einer Warft für die tiefste Zelle am Fuß (m) [G]. */
    static final double FUSS_UMKREIS = 150;
    static int steine, verstoesse;

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                default -> throw new IllegalArgumentException("unbekannt: " + args[i]);
            }
        }
        System.out.println("LandunterCheck – Z2 (Werkbuch HalligHooge 156)");
        Manifest m = Manifest.lies(manifest);
        Gezeitenauftrag ga = Gezeitenauftrag.lies(m);
        Landunterauftrag la = Landunterauftrag.lies(m);
        Szenen sz = Szenen.lies(m.szenen());
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        Gezeitentafel t;
        try {
            t = Gezeitentafel.lies(ga.datei());
        } catch (Exception x) {
            pruefe(false, "Prüfstein 6: Tafel vorhanden", x.getMessage(), "vorhanden");
            ende();
            return;
        }
        File gebiet = sz.gebiet();
        Map<String, Pegel> pg = Pegel.lies(gebiet);
        Pegel pb = pg.get(ga.bezug());
        Vorhersage vb = t.bezugVorhersage();

        // ---- Prüfstein 6: die Pegel des Windstaus in der Tafel (seit Z2b: Bezug und Prüfpegel)
        List<String> f6 = new ArrayList<>();
        if (vb == null) f6.add("keine Konstanten des Bezugs (vor Z2 gerechnet?)");
        if (pb == null) f6.add("Pegel " + ga.bezug() + " fehlt im Gebiet");
        List<Pegel> stauPegel = new ArrayList<>();
        List<Vorhersage> stauV = new ArrayList<>();
        StringBuilder w6 = new StringBuilder();
        for (Gezeitentafel.Bezug b : t.bezuege) {
            Pegel p = pg.get(b.kennung());
            if (p == null) {
                f6.add("Pegel " + b.kennung() + " fehlt im Gebiet");
                continue;
            }
            if (!p.nummer.equals(b.nummer())) f6.add(b.kurz() + ": Nummer " + b.nummer() + " statt " + p.nummer);
            if (!(Math.abs(p.pegelnull - b.pegelnull()) < 1e-3)) f6.add(b.kurz() + ": Pegelnull " + b.pegelnull() + " statt " + p.pegelnull);
            if (b.tiden().size() < 12) f6.add(b.kurz() + ": " + b.tiden().size() + " Tiden");
            stauPegel.add(p);
            stauV.add(b.vorhersage());
            w6.append(String.format(Locale.GERMAN, "%s (%s), Pegelnull %+.3f m NHN, %d Tiden, Z₀ %+.3f m; ", b.anzeige(), b.nummer(), b.pegelnull(), b.tiden().size(), b.z0()));
        }
        if (ga.pruef() != null && t.bezuege.size() < 2) f6.add("Prüfpegel " + ga.pruef() + " nicht als zweiter Pegel in der Tafel");
        if (t.bezuege.isEmpty() || !t.bezuege.get(0).kennung().equals(ga.bezug())) f6.add("erster Pegel nicht " + ga.bezug());
        pruefe(f6.isEmpty(), "Prüfstein 6: Pegel des Windstaus in der Tafel – Bezug und Prüfpegel, Nummer bei PEGELONLINE und Pegelnull wie im Gebiet, Konstanten",
                f6.isEmpty() ? w6.toString() : f6.toString(), "0 Fehler");
        if (vb == null || pb == null || stauPegel.isEmpty()) {
            ende();
            return;
        }

        // ---- Prüfstein 7: der gemessene Windstau (seit Z2b das Mittel der Pegel) – Scheitel aus Gezeit + Windstau gegen die Messung, an jedem Pegel
        double[] vl = Gezeitenrechnung.STAU_VORLAUF;
        double[][] sf = Gezeitenrechnung.stauFehlerMittel(stauPegel, stauV, vl), sfe = Gezeitenrechnung.stauFehler(pb, vb, vl);
        double[] gespeichert = w(t, "wert.stau.fehler");
        List<String> f7 = new ArrayList<>();
        if (gespeichert == null || gespeichert.length != vl.length) f7.add("wert.stau.fehler fehlt in der Tafel");
        else for (int i = 0; i < vl.length; i++) if (Math.abs(gespeichert[i] - sf[0][i]) > 1e-3) f7.add("gespeichert " + gespeichert[i] + " statt " + sf[0][i]);
        int i1 = 0, i3 = 2;
        if (!(sf[0][i1] < sf[2][0]) || !(sf[0][i3] < sf[2][0])) f7.add("1 h oder 3 h vorher nicht besser als ohne Wind");
        // der Sturm: Hochwasser mehr als 1 m über der Gezeit, an jedem Pegel; geschätzt mit dem Mittel der Windstaus
        int n = pb.werte.length;
        double sturmOhne = 0, sturmMit = 0;
        int sturmN = 0;
        Vorhersage.Extrem sturmHw = null;
        StringBuilder sturmText = new StringBuilder();
        for (int i = 0; i < stauPegel.size(); i++)
            for (Vorhersage.Extrem e : Gezeitenrechnung.extreme(stauPegel.get(i))) {
                if (e.art() != Vorhersage.Art.HOCHWASSER) continue;
                Vorhersage.Extrem hv = Gezeitenrechnung.naechstesHw(stauV.get(i), e.zeit());
                if (hv == null || e.hoehe() - hv.hoehe() <= 1.0) continue;
                if (i == 0 && (sturmHw == null || e.hoehe() > sturmHw.hoehe())) sturmHw = e;
                sturmText.append(String.format(Locale.GERMAN, "%s %s gemessen %+.2f m, Gezeit %+.2f m; geschätzt", t.bezuege.get(i).kurz(),
                        e.zeit().atZone(zone).toLocalDateTime().toString().replace('T', ' '), e.hoehe(), hv.hoehe()));
                for (double h : vl) {
                    double st = mittelStau(stauPegel, stauV, e.zeit().minusSeconds(Math.round(h * 3600)));
                    sturmText.append(String.format(Locale.GERMAN, " %.0f h vorher %+.2f m,", h, hv.hoehe() + st));
                    if (h == 3 && !Double.isNaN(st)) {
                        sturmN++;
                        sturmOhne += Math.abs(hv.hoehe() - e.hoehe());
                        sturmMit += Math.abs(hv.hoehe() + st - e.hoehe());
                    }
                }
                sturmText.append("; ");
            }
        if (sturmN > 0 && sturmMit > 0.5 * sturmOhne) f7.add("im Sturm 3 h vorher nicht halb so viel Fehler");
        StringBuilder sb = new StringBuilder(), sbe = new StringBuilder();
        for (int i = 0; i < vl.length; i++) {
            sb.append(String.format(Locale.GERMAN, "%.0f h: %.3f m (Mittel %+.3f); ", vl[i], sf[0][i], sf[1][i]));
            sbe.append(String.format(Locale.GERMAN, "%.0f h: %.3f m; ", vl[i], sfe[0][i]));
        }
        pruefe(f7.isEmpty(), "Prüfstein 7: Windstau aus der Messung (Mittel " + stauPegel.size() + " Pegel) – Scheitel jedes Hochwassers an jedem Pegel aus Gezeit + Windstau der Stunde h vorher "
                + "gegen den gemessenen Scheitel", "RMS " + sb + String.format(Locale.GERMAN, "ohne Wind %.3f m (%.0f HW)", sf[2][0], sf[2][1]) + (sturmN > 0 ? String.format(Locale.GERMAN,
                        "; Sturm 3 h vorher |Fehler| %.2f m mit, %.2f m ohne (%d HW)", sturmMit / sturmN, sturmOhne / sturmN, sturmN) : "; kein Sturm in der Reihe") + (f7.isEmpty() ? "" : " – " + f7),
                "1 h und 3 h: RMS mit < ohne; Sturm: |Fehler| mit ≤ halb so groß; wie in der Tafel ± 1 mm");
        if (sturmText.length() > 0) info("Sturm", sturmText.toString());
        info("zum Vergleich " + ga.bezugName() + " allein (wie Z2)", sbe.toString());

        // ---- Prüfstein 8: das Mittel gegen jeden der Pegel (Hooge liegt dazwischen), ± 6 h um den größten Windstau
        if (stauPegel.size() > 1) {
            long a0 = Long.MIN_VALUE, a1 = Long.MAX_VALUE;
            for (Pegel p : stauPegel) {
                a0 = Math.max(a0, p.start.getEpochSecond() + 3600);
                a1 = Math.min(a1, p.ende().getEpochSecond());
            }
            double smax = -9, tmax = Double.NaN;
            for (long ts = a0; ts < a1; ts += 600) {
                double m0 = mittelStau(stauPegel, stauV, Instant.ofEpochSecond(ts));
                if (m0 > smax) {
                    smax = m0;
                    tmax = ts;
                }
            }
            double dmax = 0, dpw = 0;
            double[] beim = new double[stauPegel.size()];
            int c = 0;
            for (long ts = (long) tmax - 6 * 3600; ts <= tmax + 6 * 3600; ts += 600) {
                Instant ti = Instant.ofEpochSecond(ts);
                double m0 = mittelStau(stauPegel, stauV, ti);
                if (Double.isNaN(m0)) continue;
                double lo = 9, hi = -9;
                for (int j = 0; j < stauPegel.size(); j++) {
                    double sj = Gezeitenrechnung.stundenStau(stauPegel.get(j), stauV.get(j), ti);
                    dmax = Math.max(dmax, Math.abs(sj - m0));
                    lo = Math.min(lo, sj);
                    hi = Math.max(hi, sj);
                    if (ts == (long) tmax) beim[j] = sj;
                }
                dpw = Math.max(dpw, hi - lo);
                c++;
            }
            StringBuilder w8 = new StringBuilder();
            for (int j = 0; j < stauPegel.size(); j++) w8.append(String.format(Locale.GERMAN, "%s %+.2f m, ", t.bezuege.get(j).kurz(), beim[j]));
            pruefe(c > 40 && dmax <= GLEICH, "Prüfstein 8: Windstau als Mittel – höchstens so weit von jedem gemessenen Pegel (Hooge liegt dazwischen), ± 6 h um den größten",
                    String.format(Locale.GERMAN, "größtes Mittel %+.2f m (%s): %s; ± 6 h die Pegel bis %.2f m auseinander, das Mittel von jedem höchstens %.2f m (%d Werte)", smax,
                            Instant.ofEpochSecond((long) tmax).atZone(zone).toLocalDateTime(), w8, dpw, dmax, c), "≤ 0,30 m");
        } else info("Prüfstein 8", "übergangen: nur ein Pegel in der Tafel");

        // ---- Prüfstein 9: Badewanne in den Stürmen des Modells
        var atlas = com.dan.steuerhaus.atlas.Atlas.lies(m, sz.datei);
        List<Landunter.Warft> wf = Szenenapp.warften(atlas, la);
        Map<String, double[]> lage = new LinkedHashMap<>();
        for (var o : atlas.orte) lage.put(o.name(), new double[] {o.ost(), o.nord()});
        double[] po = com.dan.steuerhaus.atlas.Atlas.zahlenAus(sz.text("pegel.ort", ""));
        double pu = sz.zahl("pegel.umkreis", 300);
        Gebiet ge = Gebiet.lies(gebiet);
        // am ruhigen Tag nasse Zellen sind Priele mit Verbindung nach außen: die zählen nicht
        Map<String, Double> ruhigNass = new LinkedHashMap<>();
        Szenario sr = sz.szenario(m.text("atlas.ruhig", "ruhig"));
        long n0 = System.nanoTime();
        if (sr != null && sr.archiv != null) try (Archivquelle q = new Archivquelle(sr.archiv.toPath())) {
            for (Landunter.Warft w : wf) {
                double[] p = lage.get(w.name());
                ruhigNass.put(w.name(), anteilNass(Pegelreihe.aus(q, q.start, ge, w.name(), p[0], p[1], FUSS_UMKREIS)));
            }
        }
        List<String> f9 = new ArrayList<>();
        List<String> stuerme = new ArrayList<>(la.modell());
        String atlasSturm = m.text("atlas.sturm", "");
        if (!atlasSturm.isEmpty() && !stuerme.contains(atlasSturm)) stuerme.add(atlasSturm);
        double frueh = 99, dWanne = 0;
        int geprueft = 0;
        for (String k : stuerme) {
            boolean zaehlt = la.modell().contains(k);
            Szenario ss = sz.szenario(k);
            if (ss == null || ss.archiv == null) {
                if (zaehlt) f9.add("Sturm " + k + " ohne Archiv");
                continue;
            }
            StringBuilder w9 = new StringBuilder();
            try (Archivquelle q = new Archivquelle(ss.archiv.toPath())) {
                Pegelreihe aus = Pegelreihe.aus(q, q.start, ge, "außen", po[0], po[1], pu);
                Pegelreihe.Marke sch = aus.hoechster();
                double vorFrueh = 99, vorWanne = 0;
                for (Landunter.Warft w : wf) {
                    if (ruhigNass.getOrDefault(w.name(), 0.0) > 0.05) continue;
                    double[] p = lage.get(w.name());
                    Pegelreihe pr = Pegelreihe.aus(q, q.start, ge, w.name(), p[0], p[1], FUSS_UMKREIS);
                    double[] pe = pr.pegel(), ze = pr.zeiten();
                    double erst = Double.NaN;
                    for (int i = 0; i < pe.length; i++) if (!Double.isNaN(pe[i]) && pe[i] - pr.grund > 0.02) {
                        erst = ze[i];
                        break;
                    }
                    double innen = pr.bei(sch.t()), ausErst = Double.isNaN(erst) ? Double.NaN : aus.bei(erst);
                    w9.append(String.format(Locale.GERMAN, "%s nass ab %+.2f, beim Scheitel %+.2f; ", w.name(), ausErst, innen));
                    if (sch.pegel() > la.nass() + 0.1 && (Double.isNaN(erst) || Double.isNaN(innen))) {
                        if (zaehlt) f9.add(k + ": " + w.name() + " bleibt trocken");
                        continue;
                    }
                    if (Double.isNaN(erst)) continue;
                    vorFrueh = Math.min(vorFrueh, ausErst);
                    vorWanne = Math.max(vorWanne, Math.abs(innen - sch.pegel()));
                    if (zaehlt) geprueft++;
                }
                if (zaehlt) {
                    frueh = Math.min(frueh, vorFrueh);
                    dWanne = Math.max(dWanne, vorWanne);
                    if (vorFrueh < la.nass()) f9.add(String.format(Locale.GERMAN, "%s: nass schon ab %+.2f m außen", k, vorFrueh));
                    if (vorWanne > WANNE) f9.add(String.format(Locale.GERMAN, "%s: beim Scheitel bis %.2f m unter außen", k, vorWanne));
                }
                info((zaehlt ? "Sturm " : "Zur Auskunft (älterer Stand) ") + ss.name, String.format(Locale.GERMAN, "außen höchstens %+.2f m (%s); nass frühestens ab %+.2f m außen; beim Scheitel bis %.2f m Unterschied; %s",
                        sch.pegel(), q.start.plusMillis(Math.round(sch.t() * 1000)), vorFrueh, vorWanne, w9));
            }
        }
        if (geprueft < 10) f9.add("nur " + geprueft + " Warften und Stürme mit trockenem Fuß am ruhigen Tag");
        pruefe(f9.isEmpty(), "Prüfstein 9: Badewanne in den Stürmen " + la.modell() + " – Fennen an den Warften erst nass ab " + LandunterCheck.zahl(la.nass()) + " m außen; beim Scheitel innen so hoch wie außen",
                f9.isEmpty() ? String.format(Locale.GERMAN, "%d Warften × Stürme; nass frühestens ab %+.2f m außen; beim Scheitel höchstens %.2f m Unterschied; gelesen in %.0f s", geprueft, frueh,
                        dWanne, (System.nanoTime() - n0) / 1e9) : f9.toString(), "nass ab ≥ Landunter-Höhe; ± 0,10 m beim Scheitel");

        // ---- Auskunft: PEGELONLINE jetzt
        double summe = 0;
        int erreicht = 0;
        for (Gezeitentafel.Bezug b : t.bezuege)
            try {
                List<Pegelonline.Messwert> jetzt = Pegelonline.lies(b.nummer(), "PT3H", b.pegelnull());
                Pegelonline.Stau st = Pegelonline.stau(jetzt, b.vorhersage());
                if (st != null) {
                    summe += st.meter();
                    erreicht++;
                }
                info("PEGELONLINE jetzt", st == null ? b.anzeige() + ": keine Werte" : String.format(Locale.GERMAN,
                        "%s (%s): %d Werte bis %s, zuletzt %+.2f m NHN, Gezeit %+.2f m, Windstau der letzten Stunde %+.2f m", b.anzeige(), b.nummer(), st.werte(),
                        st.stand().atZone(zone).toLocalDateTime(), jetzt.get(jetzt.size() - 1).hoehe(), b.vorhersage().wert(st.stand()), st.meter()));
            } catch (Exception x) {
                info("PEGELONLINE jetzt", b.anzeige() + " nicht erreichbar: " + x.getMessage());
            }
        if (erreicht > 0) info("PEGELONLINE jetzt", String.format(Locale.GERMAN, "Windstau für Hooge (Mittel von %d) %+.2f m", erreicht, summe / erreicht));

        // ---- Bilder: die Uhr im Sturm (Messung bis 3 h vor dem gemessenen Scheitel) und jetzt ohne Wind
        if (bilder != null) {
            bilder.mkdirs();
            Thema.zu(m.thema()).anwenden();
            List<Object[]> wahl = new ArrayList<>(la.wahl());
            BufferedImage[] bi = new BufferedImage[2];
            String[] auskunft = new String[1];
            Instant damals = sturmHw == null ? null : sturmHw.zeit().minusSeconds(3 * 3600);
            // je Pegel die Messung der 26 h bis dahin
            List<java.util.concurrent.Callable<List<Pegelonline.Messwert>>> bis = new ArrayList<>();
            int werte = 0;
            if (damals != null)
                for (Pegel p : stauPegel) {
                    List<Pegelonline.Messwert> l = new ArrayList<>();
                    for (int i = 0; i < p.werte.length; i++) {
                        Instant ti = p.zeit(i);
                        if (!ti.isAfter(damals) && ti.isAfter(damals.minusSeconds(26 * 3600)) && !Float.isNaN(p.werte[i])) l.add(new Pegelonline.Messwert(ti, p.werte[i]));
                    }
                    werte += l.size();
                    bis.add(() -> l);
                }
            int werteBis = werte;
            Vorhersage.Extrem gemessen = sturmHw;
            SwingUtilities.invokeAndWait(() -> {
                File tmp = new File(System.getProperty("java.io.tmpdir"));
                if (damals != null) {
                    var u = new Landunteruhr(t, la.krone(), la.kroneName(), la.nass(), la.nassName(), wf, wahl, la.hinweis(), zone, tmp);
                    u.jetzt = () -> damals;
                    u.messung = bis;
                    u.ladenJetzt();
                    u.quelle(0);
                    var hw = u.lu.naechstes(damals, u.stau());
                    StringBuilder je = new StringBuilder();
                    for (int j = 0; j < u.gemessenJe().size(); j++)
                        je.append(String.format(Locale.GERMAN, "%s %+.2f, ", t.bezuege.get(j).kurz(), u.gemessenJe().get(j) == null ? Double.NaN : u.gemessenJe().get(j).meter()));
                    if (hw != null) auskunft[0] = String.format(Locale.GERMAN, "Windstau gemessen %s Mittel %+.2f m (%d Werte); nächstes HW %s: Gezeit %+.2f + Stau = %+.2f m ± %.2f m; "
                            + "gemessen in %s %+.2f m", je, u.stau(), werteBis, hw.zeit().atZone(zone).toLocalDateTime(), hw.gezeit(), hw.scheitel(), u.band(3), t.bezugAnzeige, gemessen.hoehe());
                    bi[0] = u.alsBild(1460, 990);
                }
                // jetzt: mit der Messung, wenn PEGELONLINE erreichbar ist (sonst Windstau 0)
                var v = new Landunteruhr(t, la.krone(), la.kroneName(), la.nass(), la.nassName(), wf, wahl, la.hinweis(), zone, tmp);
                v.ladenJetzt();
                v.quelle(0);
                bi[1] = v.alsBild(1460, 990);
            });
            if (bi[0] != null) ImageIO.write(bi[0], "png", new File(bilder, "Z2_Landunter_Sturm.png"));
            ImageIO.write(bi[1], "png", new File(bilder, "Z2_Landunter_Hooge.png"));
            if (auskunft[0] != null) info("Uhr am " + damals.atZone(zone).toLocalDateTime() + " (3 h vor dem Scheitel am Bezug)", auskunft[0]);
            info("Bilder", (bi[0] != null ? "Z2_Landunter_Sturm.png, " : "") + "Z2_Landunter_Hooge.png (jetzt, mit der Messung, wenn erreichbar)");
        }
        ende();
    }

    static String zahl(double v) { return String.format(Locale.GERMAN, "%+.2f", v); }

    /** Das Mittel der Windstaus der Pegel in der Stunde vor t0 (wie die Uhr); NaN, wenn einer fehlt. */
    static double mittelStau(List<Pegel> p, List<Vorhersage> v, Instant t0) {
        double s = 0;
        for (int j = 0; j < p.size(); j++) s += Gezeitenrechnung.stundenStau(p.get(j), v.get(j), t0) / p.size();
        return s;
    }

    static List<Vorhersage.Konstante> konst(com.dan.core.pruef.Harmonik.Ergebnis e) {
        List<Vorhersage.Konstante> l = new ArrayList<>();
        for (var x : e.tiden()) l.add(new Vorhersage.Konstante(x.tide(), x.amplitude(), x.phaseGrad()));
        return l;
    }

    static double anteilNass(Pegelreihe r) {
        double[] p = r.pegel();
        int n = 0;
        for (double v : p) if (!Double.isNaN(v) && v - r.grund > 0.02) n++;
        return p.length == 0 ? 0 : (double) n / p.length;
    }

    /** Die erste Zahl in einem Text wie „30 Tage 0.074 m; …“. */
    static double zahlVor(String s, double vorgabe) {
        java.util.regex.Matcher mt = java.util.regex.Pattern.compile("(-?\\d+\\.\\d+)").matcher(s);
        return mt.find() ? Double.parseDouble(mt.group(1)) : vorgabe;
    }

    static double[] w(Gezeitentafel t, String k) {
        String s = t.kopf.get(k);
        if (s == null) return null;
        String[] x = s.strip().split("\\s+");
        double[] r = new double[x.length];
        for (int i = 0; i < x.length; i++) r[i] = Double.parseDouble(x[i]);
        return r;
    }

    static void ende() {
        System.out.println();
        System.out.println(steine + " Prüfsteine, " + verstoesse + " Verstöße.");
        System.exit(verstoesse == 0 ? 0 : 1);
    }

    static void info(String was, String wert) { System.out.println("  info   " + was + ": " + wert); }

    static void pruefe(boolean ok, String was, String wert, String grenze) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + wert + "  [" + grenze + "]");
    }
}
