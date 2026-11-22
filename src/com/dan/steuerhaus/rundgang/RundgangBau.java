package com.dan.steuerhaus.rundgang;

import com.dan.aufbereitung.Osm;
import com.dan.aufbereitung.Wegenetz;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.atlas.Atlas;
import com.dan.steuerhaus.buehne.Orte;
import com.dan.steuerhaus.regie.Regiebuch;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rechnet die Wege der Rundgänge (seit X3, Werkbuch HalligHooge 148; Aufruf {@code --wege}, wie {@code --atlas}): je
 * Rundgang aus dem Manifest ({@code rundgang.N}) die Strecken von Halt zu Halt über das Wegenetz aus OSM
 * ({@link Wegenetz}), das Profil des Bodens alle {@link #SCHRITT} m, und daraus ein Regiebuch im Format der Kapitel:
 * <ul>
 * <li>Abschnitt 1: Szenario, Zeit, Raffer, Einblendung; die Kamera steht am ersten Halt; die Tafel; {@code warte klick}.</li>
 * <li>Abschnitt k: die Fahrt zum Halt k mit einer Zwischentafel ({@code unterwegs}); am Halt die Tafel und
 * {@code warte klick}. „Zurück“ geht den Weg zum Halt davor noch einmal.</li>
 * </ul>
 * Die Augenhöhe ist der Boden (über ±{@link #GLAETTEN} m gemittelt) plus {@code auge}, mindestens {@code wasser} plus
 * {@code boot}: im Landunter schwimmt das Auge über den Fennen. Die Richtung schaut 14 m voraus. Das Tempo steigt auf den
 * ersten 25 m an und fällt auf den letzten 25 m ab (mindestens ein Fünftel).
 * <p>
 * OSM: {@code rundgang.osm} im Manifest oder {@code <Daten>/vektor/osm_szene/osm_szene.json} (dieselbe Datei wie die
 * Wege im Bild, Gestaltkarte).
 */
public final class RundgangBau {
    /** Abstand der Punkte im Profil, Mittel des Bodens, Abstand der Schlüsselbilder (m) [G]. */
    public static final double SCHRITT = 2, GLAETTEN = 6, SCHLUESSEL = 8;
    /** Voraus und zurück für die Richtung (m), Anlauf und Auslauf (m), kleinstes Tempo (Anteil) [G]. */
    static final double VORAUS = 14, ZURUECK = 4, ANLAUF = 25, MINDEST = 0.2;
    /** Abstand der Sonde vor dem Halt (m) [G]. */
    static final double SONDE = 100;

    private RundgangBau() { }

    /** Rechnet alle Rundgänge; gibt die Zahl der geschriebenen Wege zurück. */
    public static int erzeugen(Manifest m, Szenen szenen) throws Exception {
        long n0 = System.nanoTime();
        List<String> dateien = m.nummern("rundgang.");
        if (dateien.isEmpty()) {
            System.out.println("Wege: kein rundgang.N im Manifest");
            return 0;
        }
        File gebiet = szenen.gebiet();
        String op = m.text("rundgang.osm", "");
        File osmDatei = !op.isEmpty() ? m.relativ(op) : new File(new File(new File(gebiet.getParentFile(), "vektor"), "osm_szene"), "osm_szene.json");
        double[] r = Atlas.zahlenAus(m.text("atlas.karte", m.text("minikarte", "")));
        if (r == null || r.length < 4) throw new IllegalArgumentException("Wege: atlas.karte (oder minikarte) fehlt im Manifest");
        Wegenetz netz;
        if (osmDatei.isFile()) {
            Osm osm = Osm.lies(Integer.parseInt(m.text("rundgang.utm", "32").strip()), osmDatei.toPath());
            netz = Wegenetz.aus(osm, r, gebiet);
            System.out.printf(Locale.GERMAN, "Wege: Netz aus %s: %d Wege, %d Knoten; Gelände %s (%.1f s)%n", osmDatei, netz.wege, netz.knoten,
                    netz.mitGelaende() ? "da" : "FEHLT", (System.nanoTime() - n0) / 1e9);
        } else {
            netz = Wegenetz.aus(new Osm(), r, gebiet);
            System.out.println("Wege: FEHLT  OSM " + osmDatei + " – alle Strecken gerade");
        }
        String[] meldung = new String[1];
        List<Orte.Ort> orte = Orte.lies(m, gebiet, meldung);
        System.out.println("Wege: " + meldung[0]);
        int n = 0;
        for (String d : dateien) {
            File q = m.relativ(d);
            try {
                Rundgang g = Rundgang.lies(q);
                if (!g.fehler.isEmpty()) {
                    System.out.println("  FEHLT  " + q.getName() + ": " + g.fehler);
                    continue;
                }
                String text = regiebuch(g, orte, netz);
                File w = Rundgang.wegDatei(q);
                Files.writeString(w.toPath(), text, StandardCharsets.UTF_8);
                Regiebuch rb = Regiebuch.lies(text, w.toString());
                System.out.printf(Locale.GERMAN, "  %s → %s: %s%s%n", q.getName(), w.getName(), rb, rb.fehler.isEmpty() ? "" : "  FEHLER " + rb.fehler);
                if (rb.fehler.isEmpty()) n++;
            } catch (Exception x) {
                System.out.println("  FEHLT  " + q.getName() + ": " + x);
                x.printStackTrace(System.out);
            }
        }
        System.out.printf(Locale.GERMAN, "Wege: %d von %d Rundgängen in %.1f s%n", n, dateien.size(), (System.nanoTime() - n0) / 1e9);
        return n;
    }

    /** Sucht den Ort eines Halts: zuerst gleich, dann ohne Groß- und Kleinschreibung, dann als Anfang. */
    static Rundgang.Halt verorte(Rundgang.Halt h, List<Orte.Ort> orte) {
        if (h.hatLage()) return h;
        for (Orte.Ort o : orte) if (o.name().equals(h.ort())) return h.an(o.ost(), o.nord());
        for (Orte.Ort o : orte) if (o.name().equalsIgnoreCase(h.ort())) return h.an(o.ost(), o.nord());
        for (Orte.Ort o : orte) if (o.name().toLowerCase(Locale.GERMAN).startsWith(h.ort().toLowerCase(Locale.GERMAN))) return h.an(o.ost(), o.nord());
        throw new IllegalArgumentException("Ort „" + h.ort() + "“ steht nicht in den Ortsnamen");
    }

    /** Das Regiebuch eines Rundgangs als Text (auch für die Prüfung, ohne Daten). */
    public static String regiebuch(Rundgang g, List<Orte.Ort> orte, Wegenetz netz) {
        List<Rundgang.Halt> h = new ArrayList<>();
        for (Rundgang.Halt x : g.halte) h.add(verorte(x, orte));
        double tempo = g.zahl("tempo", 5), auge = g.zahl("auge", 1.8), wasser = g.zahl("wasser", Double.NaN), boot = g.zahl("boot", 1.2);
        double neigung = g.zahl("neigung", -3), bildwinkel = g.zahl("bildwinkel", 60);
        String name = g.name;

        // die Strecken und ihre Profile
        List<Wegenetz.Strecke> st = new ArrayList<>();
        List<Wegenetz.Profil> pr = new ArrayList<>();
        for (int k = 1; k < h.size(); k++) {
            Wegenetz.Strecke s = netz.weg(h.get(k - 1).ost(), h.get(k - 1).nord(), h.get(k).ost(), h.get(k).nord());
            st.add(s);
            pr.add(anheben(netz.profil(s, SCHRITT, GLAETTEN), h));
        }

        StringBuilder kopf = new StringBuilder(), z = new StringBuilder(), f = new StringBuilder();
        kopf.append("# Rundgang (X3, Werkbuch HalligHooge 148): erzeugt von com.dan.steuerhaus.rundgang.RundgangBau (--wege) aus ")
                .append(g.quelle == null ? "?" : g.quelle.getName()).append(" am ").append(Instant.now().truncatedTo(ChronoUnit.SECONDS))
                .append(". Nicht von Hand ändern: die Quelle ändern und --wege neu rechnen.\n");
        kopf.append("# rundgang.tempo = ").append(f(tempo, 2)).append("; auge = ").append(f(auge, 2)).append("; wasser = ")
                .append(Double.isNaN(wasser) ? "-" : f(wasser, 2)).append("; boot = ").append(f(boot, 2)).append('\n');
        kopf.append("# strecke.k = von; nach; Weg m; Luftlinie m; auf Wegen (Anteil); verbunden; Dauer s; Auge über Boden kleinst m; größter Abstand der Schlüsselbilder m; größtes Tempo m/s\n");
        kopf.append("Regiebuch: ").append(name).append('\n');
        kopf.append("Beschreibung: ").append(g.beschreibung).append('\n');

        double t = 0;
        // ---- Abschnitt 1: am ersten Halt
        Rundgang.Halt h0 = h.get(0);
        double r0 = !Double.isNaN(h0.blick()) ? h0.blick() : st.isEmpty() ? 0 : richtung(pr.get(0), 0);
        double z0 = auge(pr.isEmpty() ? Double.NaN : pr.get(0).boden()[0], auge, wasser, boot);
        String fahrt0 = name + " · 1";
        f.append("\nFahrt: ").append(fahrt0).append('\n');
        f.append(schluessel(0, h0.ost(), h0.nord(), z0, r0, Double.isNaN(h0.neigung()) ? neigung : h0.neigung(), bildwinkel)).append("Ende\n");
        zeile(z, t, "weiter", "");
        zeile(z, t, "szenario " + g.text("szenario", "ruhig"), "");
        String zeit = g.text("zeit", "");
        if (!zeit.isEmpty()) zeile(z, t, "zeit " + zeit, "");
        zeile(z, t, "raffer " + f(g.zahl("raffer", 20), 1), "");
        zeile(z, t, "einblendung " + g.text("einblendung", "uhr,minikarte"), "");
        // seit X3b: erst wenn das Szenario offen ist – sonst setzt es danach seine eigene Ansicht über den ersten Halt
        zeile(z, t, "warte szene", "");
        zeile(z, t, "fahrt " + fahrt0, "");
        zeile(z, t, "tafel " + h0.titel(), h0.text());
        sonde(z, t, h0, pr.isEmpty() ? null : pr.get(0), false);
        t += 2;
        zeile(z, t, "warte klick", "");
        double blickVorher = r0;

        // ---- Abschnitte 2 …: Strecke, dann Halt
        for (int k = 1; k < h.size(); k++) {
            Rundgang.Halt hk = h.get(k);
            Wegenetz.Strecke s = st.get(k - 1);
            Wegenetz.Profil p = pr.get(k - 1);
            String fn = name + " · " + (k + 1);
            List<double[]> keys = new ArrayList<>();                       // t, ost, nord, z, richtung, neigung
            double nk = Double.isNaN(hk.neigung()) ? neigung : hk.neigung();
            // am Anfang schaut die Kamera noch wie am Halt davor und dreht sich beim Anfahren in den Weg (kein Schlüsselbild
            // auf der Stelle: die Kurve würde sonst ein Stück zurück und wieder vor schwingen)
            keys.add(new double[] {0, p.ost()[0], p.nord()[0], auge(p.boden()[0], auge, wasser, boot), blickVorher, neigung});
            double tk = 0;
            double L = p.laenge(), letzteS = 0, kleinstUeber = Double.POSITIVE_INFINITY, groesstAbstand = 0, groesstTempo = 0;
            int letzte = 0;
            for (int i = 0; i < p.n(); i++) {
                double si = p.s()[i];
                boolean ende = i == p.n() - 1;
                if (!ende && i > 0 && si - letzteS < SCHLUESSEL) continue;
                if (i == 0) continue;
                // Zeit von letzteS nach si mit dem Tempo dazwischen (Simpson über drei Punkte)
                double dt = 0;
                for (int q = 0; q < 4; q++) {
                    double a = letzteS + (si - letzteS) * q / 4.0, b = letzteS + (si - letzteS) * (q + 1) / 4.0;
                    dt += (b - a) / tempo(tempo, (a + b) / 2, L);
                }
                tk += dt;
                double zi = auge(p.boden()[i], auge, wasser, boot);
                if (!Double.isNaN(p.boden()[i])) kleinstUeber = Math.min(kleinstUeber, zi - p.boden()[i]);
                groesstAbstand = Math.max(groesstAbstand, Math.hypot(p.ost()[i] - p.ost()[letzte], p.nord()[i] - p.nord()[letzte]));
                groesstTempo = Math.max(groesstTempo, (si - letzteS) / dt);
                keys.add(new double[] {tk, p.ost()[i], p.nord()[i], zi, richtung(p, i), neigung});
                letzteS = si;
                letzte = i;
            }
            double ankunft = tk;
            // am Ende dreht die Kamera beim Auslaufen zum Blick des Halts
            double[] last = keys.get(keys.size() - 1);
            double rEnde = !Double.isNaN(hk.blick()) ? hk.blick() : last[4];
            last[4] = rEnde;
            last[5] = nk;
            double dauer = tk;
            f.append("\nFahrt: ").append(fn).append('\n');
            double tAlt = -1;
            for (double[] kk : keys) {
                double tt = Math.round(kk[0] * 10) / 10.0;
                if (tt <= tAlt) tt = tAlt + 0.1;
                tAlt = tt;
                f.append(schluessel(tt, kk[1], kk[2], kk[3], kk[4], kk[5], bildwinkel));
            }
            f.append("Ende\n");
            zeile(z, t, "fahrt " + fn, "");
            String nach = hk.titel();
            zeile(z, t, "unterwegs " + zur(nach), String.format(Locale.GERMAN, "%,.0f m auf %s, rund %d min. Mit der Maus umsehen (ziehen).", s.laenge(),
                    s.verbunden() ? "den Wegen der Hallig" : "gerader Linie", Math.max(1, Math.round(ankunft / 60))));
            double tA = Math.round((t + ankunft) * 10) / 10.0;
            zeile(z, tA, "tafel " + hk.titel(), hk.text());
            sonde(z, tA, hk, p, true);
            t = Math.round((t + dauer) * 10) / 10.0 + 0.5;
            zeile(z, t, "warte klick", "");
            blickVorher = rEnde;
            kopf.append("# strecke.").append(k).append(" = ").append(h.get(k - 1).titel()).append("; ").append(hk.titel()).append("; ").append(f(s.laenge(), 0))
                    .append("; ").append(f(s.luftlinie(), 0)).append("; ").append(f(Wegenetz.anteilAufWegen(s), 3)).append("; ").append(s.verbunden() ? "ja" : "nein")
                    .append("; ").append(f(dauer, 1)).append("; ").append(Double.isInfinite(kleinstUeber) ? "-" : f(kleinstUeber, 2)).append("; ")
                    .append(f(groesstAbstand, 1)).append("; ").append(f(groesstTempo, 2)).append('\n');
        }
        zeile(z, t, "einblendung aus", "");
        kopf.append("Länge: ").append(Regiebuch.zeit(t + 1)).append('\n');
        return kopf + z.toString() + f;
    }

    /** Seit X3b: der Boden im Umkreis eines Halts mit {@code boden} mindestens so hoch (Anleger, Steg); verändert {@code p}. */
    static Wegenetz.Profil anheben(Wegenetz.Profil p, List<Rundgang.Halt> halte) {
        for (Rundgang.Halt h : halte) {
            if (Double.isNaN(h.bodenMin()) || h.bodenUmkreis() <= 0) continue;
            for (int i = 0; i < p.n(); i++)
                if (Math.hypot(p.ost()[i] - h.ost(), p.nord()[i] - h.nord()) <= h.bodenUmkreis() && !(p.boden()[i] >= h.bodenMin())) p.boden()[i] = h.bodenMin();
        }
        return p;
    }

    /** Tempo an der Stelle s einer Strecke der Länge L: Anlauf und Auslauf über {@link #ANLAUF} m. */
    static double tempo(double v, double s, double L) {
        double a = Math.min(Math.sqrt(Math.max(0, s) / ANLAUF), Math.sqrt(Math.max(0, L - s) / ANLAUF));
        return v * Math.max(MINDEST, Math.min(1, a));
    }

    /** Augenhöhe (m NHN): Boden plus Auge, mindestens Wasser plus Boot; Boden unbekannt: Wasser plus Boot oder das Auge. */
    static double auge(double boden, double auge, double wasser, double boot) {
        double a = Double.isNaN(boden) ? Double.NaN : boden + auge, b = Double.isNaN(wasser) ? Double.NaN : wasser + boot;
        if (Double.isNaN(a)) return Double.isNaN(b) ? auge : b;
        return Double.isNaN(b) ? a : Math.max(a, b);
    }

    /** Richtung (Grad, 0 = Nord) am Punkt i: vom Punkt {@link #ZURUECK} m zurück zum Punkt {@link #VORAUS} m voraus. */
    static double richtung(Wegenetz.Profil p, int i) {
        double s = p.s()[i];
        int a = i, b = i;
        while (a > 0 && s - p.s()[a] < ZURUECK) a--;
        while (b < p.n() - 1 && p.s()[b] - s < VORAUS) b++;
        if (a == b) return 0;
        double dx = p.ost()[b] - p.ost()[a], dy = p.nord()[b] - p.nord()[a];
        return (Math.toDegrees(Math.atan2(dx, dy)) + 360) % 360;
    }

    /** Eine Sonde am Halt: wo angegeben, sonst in der Fenne {@link #SONDE} m vor dem Halt (auf der Strecke dorthin). */
    static void sonde(StringBuilder z, double t, Rundgang.Halt h, Wegenetz.Profil p, boolean amEnde) {
        if (!h.sonde()) return;
        double o, n;
        if (!Double.isNaN(h.sondeOst())) {
            o = h.sondeOst();
            n = h.sondeNord();
        } else if (p != null) {
            double ziel = amEnde ? Math.max(0, p.laenge() - SONDE) : Math.min(p.laenge(), SONDE);
            int i = 0;
            while (i < p.n() - 1 && p.s()[i] < ziel) i++;
            o = p.ost()[i];
            n = p.nord()[i];
        } else return;
        // Name: mit eigener Lage der Titel des Halts, sonst der Weg an der Warft
        String ort = h.hatLage() && h.ort().matches("[-0-9.,\\s]+") ? h.titel() : h.ort();
        String name = !Double.isNaN(h.sondeOst()) ? h.titel() : (ort.toLowerCase(Locale.GERMAN).endsWith("warft") ? "Weg an der " : "Weg bei ") + ort;
        zeile(z, t, String.format(Locale.ROOT, "sonde %.0f %.0f %s", o, n, name.replaceAll("[|]", "")), "");
    }

    /** Titel der Zwischentafel: „Zur Backenswarft“, sonst „Weiter: …“. */
    static String zur(String titel) {
        String t = titel.toLowerCase(Locale.GERMAN);
        boolean warft = (t.endsWith("warft") || t.matches(".*warft und .*")) && !t.startsWith("am ") && !t.startsWith("auf ");
        return warft ? "Zur " + titel : "Weiter: " + titel;
    }

    static String schluessel(double t, double o, double n, double z, double r, double neig, double bw) {
        return String.format(Locale.ROOT, "%s %.2f %.2f %.2f nhn %.2f %.2f %.2f%n", Regiebuch.zeit(t), o, n, z, ((r % 360) + 360) % 360, neig, bw);
    }

    static void zeile(StringBuilder z, double t, String befehl, String text) {
        z.append(Regiebuch.zeit(t)).append(' ').append(befehl);
        if (!text.isEmpty()) z.append(" | ").append(text);
        z.append('\n');
    }

    static String f(double v, int st) { return String.format(Locale.ROOT, "%." + st + "f", v); }
}
