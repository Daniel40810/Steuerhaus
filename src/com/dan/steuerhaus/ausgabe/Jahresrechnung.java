package com.dan.steuerhaus.ausgabe;

import com.dan.core.archiv.Archiv;
import com.dan.core.feld.Feld;
import com.dan.core.feld.Katalog;
import com.dan.core.gitter.Gebiet;
import com.dan.core.gitter.Gitter;
import com.dan.core.gitter.Subgrid;
import com.dan.lauf.Archivquelle;
import com.dan.lauf.Karte;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.buehne.Orte;
import com.dan.steuerhaus.jahr.Jahresauftrag;
import com.dan.steuerhaus.jahr.Jahresbild;
import com.dan.steuerhaus.jahr.Jahresmappe;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rechnet die Mappe des Jahrs (seit Y3, Werkbuch HalligHooge 154; Aufruf {@code Ausgabe --jahr}): aus den Archiven der
 * Abschnitte ({@code jahr.N}) je Stand des Bodens ({@code bed.dz}, seit Beginn des Jahrs – jeder Lauf übernimmt den Boden des
 * vorigen) ein Bild, im Inneren des Sediment-Gitters ohne den Randstreifen ({@code jahr.rand}; dort setzt jeder Lauf den
 * Boden neu). Die Höhe zu Beginn ist die mittlere Höhe der Zelle aus der Packung von G3 (wie {@code MorphoFeldCheck}), die
 * Karte das Gelände von oben ({@link Karte}) ohne Wasser. Je Bild die Kennzahlen: Auftrag und Abtrag im Fenster, der Sand im
 * Kasten über der Schwelle (Fläche, Schwerpunkt, Volumen; wie P9c Prüfstein 13). Der Faktor des Bodens steht im Kopf des
 * Archivs ({@code antrieb.sediment … Faktor 60.0}).
 * <p>
 * Gelesen wird {@code bed.dz} der Läufe selbst ({@link Boden}), ohne den Anteil des Winds, den {@code Archivquelle} seit P10c
 * dazulegt, wo ein Archiv Flugsand hält: der liegt nur im zweiten Halbjahr, ohne Faktor, und geht nicht in den Sturm über
 * (sonst spränge der Boden am Übergang um bis zu 3 cm zurück).
 */
public final class Jahresrechnung {
    static final Pattern FAKTOR = Pattern.compile("Faktor\\s+([0-9]+(?:[.,][0-9]+)?)");

    private Jahresrechnung() { }

    /** Der Boden eines Laufs: {@code bed.dz} je Stand aus {@code boden_<Gitter>}, wie geschrieben (ohne Wind). */
    public static final class Boden implements Closeable {
        private final Archiv a;
        private final Feld f;
        public final double[] zeiten;
        /** Hält das Archiv Flugsand (P10c), der hier wegbleibt? */
        public final boolean wind;

        public Boden(File archiv) throws IOException {
            Path p = null;
            boolean w = false;
            try (var st = Files.list(archiv.toPath())) {
                for (Path q : st.sorted().toList()) {
                    String n = q.getFileName().toString();
                    if (!Files.isRegularFile(q.resolve("lauf.txt"))) continue;
                    if (n.startsWith("boden_") && p == null) p = q;
                    if (n.startsWith("aeolian_")) w = true;
                }
            }
            if (p == null) throw new IOException("Jahr: " + archiv + " hat keinen Boden (boden_…)");
            a = Archiv.oeffnen(p);
            f = a.feld("bed.dz", Katalog.grundstufe());
            zeiten = new double[a.staende()];
            for (int i = 0; i < zeiten.length; i++) zeiten[i] = a.zeit(i);
            wind = w;
        }

        public Gitter gitter() { return f.gitter; }

        /** Stand i in ziel (je Zelle des Gitters, m). */
        public void lies(int i, float[] ziel) throws IOException {
            a.lies("bed.dz", i, f);
            System.arraycopy(f.f, 0, ziel, 0, ziel.length);
        }

        @Override public void close() throws IOException { a.close(); }
    }

    /** Der Faktor des Bodens aus der Zeile {@code antrieb.sediment} des Archivs; 1 ohne Angabe. */
    static double faktor(String sediment) {
        if (sediment == null) return 1;
        Matcher x = FAKTOR.matcher(sediment);
        return x.find() ? Double.parseDouble(x.group(1).replace(',', '.')) : 1;
    }

    /** Rechnet die Mappe nach {@code ziel} (null: {@code ausgabe.jahr}); gibt sie gelesen zurück. */
    public static Jahresmappe erzeugen(Manifest m, Szenen sz, File ziel, Consumer<String> meldung) throws Exception {
        long n0 = System.nanoTime();
        Jahresauftrag ja = Jahresauftrag.lies(m);
        if (ja == null) throw new IllegalArgumentException("Jahr: keine Abschnitte (jahr.1 … im Manifest)");
        File ordner = ziel != null ? ziel : ja.ordner();
        List<File> archive = ja.archive(sz);
        for (int k = 0; k < archive.size(); k++)
            if (archive.get(k) == null) throw new IllegalArgumentException("Jahr: Szenario " + ja.abschnitte().get(k)[0] + " fehlt oder ist live");
        File gebiet = sz.gebiet();
        // ---- Gitter des Bodens und Höhe zu Beginn
        Gitter g;
        try (Archivquelle q = new Archivquelle(archive.get(0).toPath())) {
            g = q.sedimentGitter();
            if (g == null) throw new IllegalArgumentException("Jahr: " + archive.get(0) + " hat keinen Boden");
        }
        int r = ja.rand(), ni = g.nx - 2 * r, nj = g.ny - 2 * r;
        if (ni < 4 || nj < 4) throw new IllegalArgumentException("Jahr: Randstreifen " + r + " zu breit für " + g);
        double x0 = g.x0 + r * g.dx, y0 = g.y0 + r * g.dy;
        meldung.accept(String.format(Locale.GERMAN, "Jahr: Gitter %s, innen %d × %d ab O %.0f, N %.0f", g, ni, nj, x0, y0));
        Gebiet ge = Gebiet.lies(gebiet);
        Gitter g3 = ge.gitter("G3");
        int i0 = (int) Math.round((g.x0 - g3.x0) / g3.dx), j0 = (int) Math.round((g.y0 - g3.y0) / g3.dy);
        Subgrid.Packung p = ge.packung("G3").fenster(g3, i0, j0, g.nx, g.ny);
        double[] vor = new double[g.zellen()];
        for (int c = 0; c < vor.length; c++) {
            double oben = p.basis()[c] + 100;
            vor[c] = oben - p.volumen(c, oben) / p.zellflaeche();
        }
        float[] grund = new float[ni * nj];
        for (int j = 0; j < nj; j++) for (int i = 0; i < ni; i++) grund[i + ni * j] = (float) vor[(i + r) + g.nx * (j + r)];
        meldung.accept(String.format(Locale.GERMAN, "Jahr: Höhe zu Beginn aus der Packung von G3 (%.1f s)", (System.nanoTime() - n0) / 1e9));

        // ---- Zahl der Bilder vorab (für den Kopf von dz.bin)
        List<double[]> zeiten = new ArrayList<>();
        List<Double> faktoren = new ArrayList<>();
        List<Instant> starts = new ArrayList<>();
        int gesamt = 0;
        for (int k = 0; k < archive.size(); k++) {
            try (Archivquelle q = new Archivquelle(archive.get(k).toPath())) {
                if (q.sedimentGitter() == null || !q.sedimentGitter().gleich(g)) throw new IllegalArgumentException("Jahr: " + archive.get(k) + " hat ein anderes Gitter des Bodens");
                double[] z = q.bodenZeiten();
                if (z.length < 2) throw new IllegalArgumentException("Jahr: " + archive.get(k) + " hat " + z.length + " Stände des Bodens");
                zeiten.add(z);
                faktoren.add(faktor(q.kopf.antrieb.get("sediment")));
                starts.add(q.start);
                gesamt += auswahl(z.length, ja.schritt(), k > 0).size();
            }
        }
        Jahresmappe.Schreiber sw = new Jahresmappe.Schreiber(ordner, ni, nj, gesamt);
        sw.grund(grund);
        Map<String, String> kopf = new LinkedHashMap<>();
        kopf.put("name", m.text("jahr.name", "Ein Jahr in einer Minute"));
        kopf.put("kennung", Jahresmappe.kennung(ja.einstellungen(), archive));
        kopf.put("gitter", String.format(Locale.ROOT, "%.3f %.3f %.3f %.3f %d %d", x0, y0, g.dx, g.dy, ni, nj));
        kopf.put("gitter.lauf", g.toString() + " (Randstreifen " + r + " Zellen weggelassen)");
        if (ja.kasten() != null) kopf.put("kasten", zahlen(ja.kasten()));
        kopf.put("schwelle", String.format(Locale.ROOT, "%.3f", ja.schwelle()));
        if (ja.profil() != null) kopf.put("profil", zahlen(ja.profil()));
        kopf.put("karte.zelle", String.format(Locale.ROOT, "%.3f", ja.karteZelle()));
        kopf.put("grund", "mittlere Höhe der Zelle aus der Packung von G3 (" + gebiet + ")");

        float[] dz = new float[g.zellen()], letztes = null, innen = new float[ni * nj];
        double tag0 = 0;
        int nr = 0;
        for (int k = 0; k < archive.size(); k++) {
            String[] a = ja.abschnitte().get(k);
            double[] z = zeiten.get(k);
            double f = faktoren.get(k), t0 = z[0];
            Instant st = starts.get(k);
            double tag1 = tag0 + (z[z.length - 1] - t0) * f / 86_400;
            List<Integer> idx = auswahl(z.length, ja.schritt(), k > 0);
            int erstes = nr;
            try (Boden q = new Boden(archive.get(k))) {
                if (q.wind) kopf.put("wind." + (k + 1), "das Archiv hält Flugsand (P10c); der Anteil des Winds an bed.dz bleibt weg");
                if (q.zeiten.length != z.length || !q.gitter().gleich(g)) throw new IllegalArgumentException("Jahr: " + archive.get(k) + ": Boden passt nicht zu den Zeiten oder zum Gitter");
                if (k > 0) {
                    // Übergang: der erste Stand dieses Laufs gegen den letzten des vorigen (im Inneren gleich, am Rand neu)
                    q.lies(0, dz);
                    double innenMax = 0;
                    int randZellen = 0;
                    for (int c = 0; c < dz.length; c++) {
                        int i = c % g.nx, j = c / g.nx;
                        double d = Math.abs(dz[c] - letztes[c]);
                        if (Math.min(Math.min(i, g.nx - 1 - i), Math.min(j, g.ny - 1 - j)) < r) {
                            if (d > 0.01) randZellen++;
                        } else innenMax = Math.max(innenMax, d);
                    }
                    kopf.put("uebergang." + k, String.format(Locale.ROOT, "%.6f %d", innenMax, randZellen));
                    meldung.accept(String.format(Locale.GERMAN, "Jahr: Übergang %d → %d: im Inneren höchstens %.4f m, am Rand %d Zellen über 1 cm", k, k + 1, innenMax, randZellen));
                }
                for (int n = 0; n < idx.size(); n++) {
                    int s = idx.get(n);
                    q.lies(s, dz);
                    for (int j = 0; j < nj; j++) System.arraycopy(dz, (j + r) * g.nx + r, innen, j * ni, ni);
                    double tag = tag0 + (z[s] - t0) * f / 86_400;
                    double[] w = kennzahlen(innen, grund, ni, nj, x0, y0, g.dx, g.dy, ja.kasten(), ja.schwelle());
                    sw.bild(new Jahresmappe.Bild(nr, k, tag, st.plusMillis(Math.round(z[s] * 1000)), w[0], w[1], w[2], w[3], w[4], w[5], w[6], w[7], w[8]), innen);
                    nr++;
                    if (n % 20 == 0 || n == idx.size() - 1)
                        meldung.accept(String.format(Locale.GERMAN, "Jahr: %s – Bild %d von %d (Tag %.1f)", a[1], nr, gesamt, tag));
                }
                // der letzte Stand bleibt für den Übergang
                q.lies(z.length - 1, dz);
                letztes = dz.clone();
            }
            kopf.put("abschnitt." + (k + 1), String.join("; ", a[0], a[1], String.format(Locale.ROOT, "%.4f", f), st.plusMillis(Math.round(t0 * 1000)).toString(),
                    st.plusMillis(Math.round(z[z.length - 1] * 1000)).toString(), String.format(Locale.ROOT, "%.5f", tag0), String.format(Locale.ROOT, "%.5f", tag1),
                    String.valueOf(erstes), String.valueOf(nr - 1)));
            kopf.put("quelle." + (k + 1), archive.get(k).getPath() + " (" + z.length + " Stände, Faktor " + f + ")");
            tag0 = tag1;
        }
        // ---- wie MorphoFeldCheck auf dem ganzen Gitter (mit Randstreifen): der Sand vor und nach dem Jahr
        if (ja.kasten() != null && letztes != null) {
            double[] nach = new double[vor.length];
            for (int c = 0; c < vor.length; c++) nach[c] = vor[c] + letztes[c];
            double[] fv = schwerpunkt(g, vor, ja.kasten(), ja.schwelle(), false), fn = schwerpunkt(g, nach, ja.kasten(), ja.schwelle(), false),
                    vv = schwerpunkt(g, vor, ja.kasten(), ja.schwelle(), true), vn = schwerpunkt(g, nach, ja.kasten(), ja.schwelle(), true);
            kopf.put("pruef.sand", String.format(Locale.ROOT, "%.3f %.3f %.6f %.6f %.3f %.1f", fn[0] - fv[0], fn[1] - fv[1], fv[2] / 1e6, fn[2] / 1e6, vn[0] - vv[0], vn[3] - vv[3]));
            kopf.put("pruef.sand.text", "ganzes Gitter wie MorphoFeldCheck: Schwerpunkt Ost, Nord (m), Fläche vor, nach (km²), gewichtet Ost (m), Volumen (m³)");
        }
        // ---- Orte, Karte
        String[] om = new String[1];
        List<Orte.Ort> orte = Orte.lies(m, gebiet, om);
        int on = 0;
        double x1 = x0 + ni * g.dx, y1 = y0 + nj * g.dy;
        for (Orte.Ort o : orte) {
            if (!ja.orte().contains(o.art()) || o.ost() < x0 + 300 || o.ost() > x1 - 300 || o.nord() < y0 + 200 || o.nord() > y1 - 200) continue;
            kopf.put("ort." + (++on), String.format(Locale.ROOT, "%s; %s; %.1f; %.1f", o.art(), o.name(), o.ost(), o.nord()));
        }
        long n1 = System.nanoTime();
        Karte ka = Karte.aus(gebiet, new double[] {x0, y0, x1, y1}, ja.karteZelle());
        BufferedImage karte = ka.bild(null);
        schreibeJpeg(karte, new File(ordner, Jahresmappe.KARTE), 0.9f);
        meldung.accept(String.format(Locale.GERMAN, "Jahr: Karte %d × %d in %.1f s", karte.getWidth(), karte.getHeight(), (System.nanoTime() - n1) / 1e9));
        kopf.put("bilder", String.valueOf(nr));
        kopf.put("bytes", String.valueOf(sw.bytes()));
        kopf.put("rechenzeit", String.format(Locale.ROOT, "%.1f", (System.nanoTime() - n0) / 1e9));
        kopf.put("gerechnet", Instant.now().toString());
        sw.schliesse(kopf);
        Jahresmappe jm = Jahresmappe.lies(ordner);
        float[] ende = new float[jm.zellen()];
        jm.boden(jm.tage(), ende);
        schreibeJpeg(Jahresbild.vorschau(jm, karte, jm.grund(), ende, Jahresbild.SKALEN[Jahresbild.SKALA_VORGABE], 480, 270), jm.vorschau(), 0.9f);
        meldung.accept(String.format(Locale.GERMAN, "Jahr: %d Bilder, %.0f Tage, %.1f MB, fertig in %.1f s → %s", nr, jm.tage(), sw.bytes() / 1e6, (System.nanoTime() - n0) / 1e9, ordner));
        return jm;
    }

    /** Die Stände eines Abschnitts: jeder schritt-te und der letzte; ohne den ersten, wenn er der letzte des vorigen ist. */
    static List<Integer> auswahl(int n, int schritt, boolean ohneErsten) {
        List<Integer> l = new ArrayList<>();
        for (int i = ohneErsten ? schritt : 0; i < n; i += schritt) l.add(i);
        if (l.isEmpty() || l.get(l.size() - 1) != n - 1) l.add(n - 1);
        return l;
    }

    /**
     * Die Kennzahlen eines Bilds im Inneren: Auftrag, Abtrag (m³), Fläche des Sands (m²), Schwerpunkt Ost, Nord, Volumen über
     * der Schwelle (m³), Schwerpunkt mit der Höhe gewichtet Ost, Nord, größte Änderung (m).
     */
    public static double[] kennzahlen(float[] dz, float[] grund, int nx, int ny, double x0, double y0, double dx, double dy, double[] kasten, double schwelle) {
        double a = dx * dy, auf = 0, ab = 0, gr = 0, m = 0, mx = 0, my = 0, mv = 0, mvx = 0, mvy = 0, fl = 0, vol = 0;
        for (int j = 0; j < ny; j++) {
            double y = y0 + (j + 0.5) * dy;
            for (int i = 0; i < nx; i++) {
                int c = i + nx * j;
                float d = dz[c];
                if (Float.isNaN(d)) continue;
                if (d > 0) auf += d * a;
                else ab -= d * a;
                gr = Math.max(gr, Math.abs(d));
                if (kasten == null) continue;
                double x = x0 + (i + 0.5) * dx, h = grund[c] + d - schwelle;
                if (!(h > 0) || x < kasten[0] || x > kasten[2] || y < kasten[1] || y > kasten[3]) continue;
                m += 1;
                mx += x;
                my += y;
                mv += h;
                mvx += h * x;
                mvy += h * y;
                fl += a;
                vol += h * a;
            }
        }
        return new double[] {auf, ab, fl, m > 0 ? mx / m : Double.NaN, m > 0 ? my / m : Double.NaN, vol, mv > 0 ? mvx / mv : Double.NaN, mv > 0 ? mvy / mv : Double.NaN, gr};
    }

    /** Wie {@code MorphoFeldCheck.schwerpunkt}: {Ost, Nord, Fläche m², Volumen m³} über der Schwelle im Kasten. */
    static double[] schwerpunkt(Gitter g, double[] z, double[] kasten, double schwelle, boolean nachVolumen) {
        double m = 0, mx = 0, my = 0, fl = 0, vol = 0, a = g.dx * g.dy;
        for (int j = 0; j < g.ny; j++) for (int i = 0; i < g.nx; i++) {
            double x = g.x(i), y = g.y(j), h = z[i + g.nx * j] - schwelle;
            if (!(h > 0) || x < kasten[0] || x > kasten[2] || y < kasten[1] || y > kasten[3]) continue;
            double w = nachVolumen ? h : 1;
            m += w;
            mx += w * x;
            my += w * y;
            fl += a;
            vol += h * a;
        }
        return new double[] {m > 0 ? mx / m : Double.NaN, m > 0 ? my / m : Double.NaN, fl, vol};
    }

    static String zahlen(double[] v) {
        StringBuilder s = new StringBuilder();
        for (double x : v) s.append(s.length() > 0 ? " " : "").append(String.format(Locale.ROOT, "%.1f", x));
        return s.toString();
    }

    static void schreibeJpeg(BufferedImage img, File datei, float guete) throws IOException {
        ImageWriter w = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(guete);
        try (ImageOutputStream o = ImageIO.createImageOutputStream(datei)) {
            if (o == null) throw new IOException("Jahr: nicht schreibbar: " + datei);
            w.setOutput(o);
            w.write(null, new IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
    }
}
