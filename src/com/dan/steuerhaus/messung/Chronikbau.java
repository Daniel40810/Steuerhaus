package com.dan.steuerhaus.messung;

import com.dan.steuerhaus.Chronik;

import java.io.File;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Chronik aus dem Messarchiv bauen (seit DB2, Werkbuch HalligHooge 169).
 * <ul>
 * <li><b>Am Ort:</b> aufeinander folgende geschätzte Hochwasser über der Krone (Abstand unter {@link #KETTE_H} Stunden)
 * sind ein Ereignis; reicht das höchste bis zur Höhe des Landunters ({@code nass}), ist es ein Landunter, sonst „über der
 * Krone“.</li>
 * <li><b>An den Pegeln:</b> die Sturmfluten nach BSH ({@code STURMFLUT_V}, je Pegel schon zu Ketten von Tiden gefasst),
 * die sich überschneiden oder höchstens {@link #KETTE_H} Stunden auseinander liegen, sind ein Ereignis; es nennt die Zahl der
 * Pegel und den mit dem höchsten Wasser über dem MThw.</li>
 * </ul>
 * Die Gruppen rechnen nur mit java.*; {@link #aus} liest dafür die Datenbank.
 */
public final class Chronikbau {
    private Chronikbau() { }

    /** Größter Abstand zweier Hochwasser in einer Kette (h) [G]: eine Tide fehlt, die Kette reißt. */
    public static final double KETTE_H = 18;

    /** Ein Hochwasser am Ort: Zeit (Epoch-Sekunden), Höhe (m NHN), Windstau (m). */
    public record Hochwasser(long zeit, double hoehe, double stau) { }

    /** Eine Sturmflut an einem Pegel (eine Zeile von STURMFLUT_V). */
    public record Pegelflut(String pegel, long ab, long bis, int tiden, double scheitel, double ueber) { }

    /** Die Ereignisse am Ort aus seinen Hochwassern (nach der Zeit). */
    public static List<Chronik.Ereignis> amOrt(List<Hochwasser> hw, double krone, double nass, String ort) {
        List<Chronik.Ereignis> l = new ArrayList<>();
        List<Hochwasser> kette = new ArrayList<>();
        for (Hochwasser h : hw) {
            boolean drueber = h.hoehe() >= krone;
            boolean anschluss = !kette.isEmpty() && h.zeit() - kette.get(kette.size() - 1).zeit() < KETTE_H * 3600;
            if (drueber && (kette.isEmpty() || anschluss)) {
                kette.add(h);
                continue;
            }
            if (!kette.isEmpty()) l.add(ereignis(kette, nass, ort));
            kette = new ArrayList<>();
            if (drueber) kette.add(h);
        }
        if (!kette.isEmpty()) l.add(ereignis(kette, nass, ort));
        return l;
    }

    static Chronik.Ereignis ereignis(List<Hochwasser> k, double nass, String ort) {
        Hochwasser max = k.get(0);
        double stau = Double.NEGATIVE_INFINITY;
        for (Hochwasser h : k) {
            if (h.hoehe() > max.hoehe()) max = h;
            if (!Double.isNaN(h.stau())) stau = Math.max(stau, h.stau());
        }
        boolean land = max.hoehe() >= nass;
        int n = k.size();
        String text = (land ? "Landunter" : "Wasser über der Krone") + (n == 1 ? " bei einer Tide" : " über " + (n == 2 ? "zwei" : n == 3 ? "drei" : String.valueOf(n)) + " Tiden")
                + ", geschätzt aus der Gezeit und dem Windstau an den Bezugspegeln";
        return new Chronik.Ereignis(land ? Chronik.Art.LANDUNTER : Chronik.Art.KRONE, Instant.ofEpochSecond(k.get(0).zeit()), Instant.ofEpochSecond(k.get(n - 1).zeit()),
                Instant.ofEpochSecond(max.zeit()), n, max.hoehe(), stau == Double.NEGATIVE_INFINITY ? Double.NaN : stau, ort, text);
    }

    /** Die Sturmfluten an den Pegeln, zu Ereignissen über alle Pegel gefasst (Eingabe nach Beginn). */
    public static List<Chronik.Ereignis> sturmfluten(List<Pegelflut> fl) {
        List<Pegelflut> s = new ArrayList<>(fl);
        s.sort((a, b) -> Long.compare(a.ab(), b.ab()));
        List<Chronik.Ereignis> l = new ArrayList<>();
        List<Pegelflut> g = new ArrayList<>();
        long bis = Long.MIN_VALUE;
        for (Pegelflut p : s) {
            if (!g.isEmpty() && p.ab() > bis + (long) (KETTE_H * 3600)) {
                l.add(flut(g));
                g = new ArrayList<>();
            }
            g.add(p);
            bis = g.size() == 1 ? p.bis() : Math.max(bis, p.bis());
        }
        if (!g.isEmpty()) l.add(flut(g));
        return l;
    }

    static Chronik.Ereignis flut(List<Pegelflut> g) {
        Pegelflut max = g.get(0);
        long ab = Long.MAX_VALUE, bis = Long.MIN_VALUE;
        int tiden = 0;
        List<String> pegel = new ArrayList<>();
        for (Pegelflut p : g) {
            if (p.ueber() > max.ueber()) max = p;
            ab = Math.min(ab, p.ab());
            bis = Math.max(bis, p.bis());
            tiden = Math.max(tiden, p.tiden());
            if (!pegel.contains(p.pegel())) pegel.add(p.pegel());
        }
        String klasse = max.ueber() >= 3.5 ? "sehr schwere Sturmflut" : max.ueber() >= 2.5 ? "schwere Sturmflut" : "Sturmflut";
        String text = String.format(Locale.GERMAN, "%s nach BSH an %d %s, am höchsten %s mit %.2f m über dem MThw", klasse.substring(0, 1).toUpperCase(Locale.GERMAN) + klasse.substring(1),
                pegel.size(), pegel.size() == 1 ? "Pegel" : "Pegeln", name(max.pegel()), max.ueber());
        return new Chronik.Ereignis(Chronik.Art.STURMFLUT, Instant.ofEpochSecond(ab), Instant.ofEpochSecond(bis), Instant.ofEpochSecond(max.ab()), tiden, max.scheitel(), max.ueber(),
                name(max.pegel()), text);
    }

    /** „EIDER-SPERRWERK_AP“ → „Eider-Sperrwerk AP“, „LIST_AUF_SYLT“ → „List auf Sylt“. */
    public static String name(String schluessel) {
        StringBuilder b = new StringBuilder();
        for (String w : schluessel.split("_")) {
            if (b.length() > 0) b.append(' ');
            if (w.length() <= 2) { b.append(w); continue; }
            if (w.equalsIgnoreCase("auf")) { b.append("auf"); continue; }
            StringBuilder t = new StringBuilder();
            for (String h : w.split("-", -1)) {
                if (t.length() > 0) t.append('-');
                t.append(h.isEmpty() ? "" : h.substring(0, 1) + h.substring(1).toLowerCase(Locale.GERMAN));
            }
            b.append(t);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ aus der Datenbank

    /** Baut die Chronik aus dem Archiv; null, wenn das Manifest keinen Ort nennt. */
    public static Chronik aus(Messarchiv ar, Messauftrag a) throws SQLException {
        if (a.ortSchluessel() == null) return null;
        Messarchiv.Station ort = ar.stationen("ORT").get(a.ortSchluessel());
        if (ort == null) return null;
        List<Hochwasser> hw = ar.hochwasser(ort.id(), Sammler.SCHAETZUNG);
        List<Chronik.Ereignis> e = new ArrayList<>(amOrt(hw, a.krone(), a.nass(), ort.name()));
        e.addAll(sturmfluten(ar.sturmfluten()));
        String quelle = "Messarchiv " + a.schema() + ": Pegel von PEGELONLINE (WSV, DL-DE Zero 2.0, ungeprüfte Rohdaten); am Ort geschätzt aus der Gezeit des Tidenkalenders"
                + " und dem Windstau an den Bezugspegeln; Sturmflut nach BSH über dem MThw der Kennwerte";
        return new Chronik(ort.name(), a.krone(), a.kroneText(), a.nass(), a.nassText(), hw.isEmpty() ? null : Instant.ofEpochSecond(hw.get(0).zeit()), quelle, e, Instant.now());
    }

    /** Schreibt die Chronik nach {@code chronik.datei} und in die Kopien, deren Ordner es gibt; gibt eine Zeile fürs Protokoll. */
    public static String schreiben(Chronik c, Messauftrag a) throws IOException {
        if (c == null || a.chronik() == null) return "keine Chronik (chronik.datei oder messung.ort fehlt)";
        boolean neu = c.schreibe(a.chronik());
        int kopien = 0;
        for (File k : a.chronikKopien()) if (k.getParentFile() != null && k.getParentFile().isDirectory() && c.schreibe(k)) kopien++;
        long land = c.ereignisse.stream().filter(x -> x.art() == Chronik.Art.LANDUNTER).count(), krone = c.ereignisse.stream().filter(x -> x.art() == Chronik.Art.KRONE).count(),
                flut = c.ereignisse.stream().filter(x -> x.art() == Chronik.Art.STURMFLUT).count();
        return String.format(Locale.GERMAN, "Chronik %s: %d Landunter, %d über der Krone, %d Sturmfluten%s", neu ? "neu geschrieben" : "unverändert", land, krone, flut,
                kopien > 0 ? ", " + kopien + (kopien == 1 ? " Kopie" : " Kopien") : "");
    }
}
