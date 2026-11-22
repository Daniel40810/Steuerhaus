package com.dan.steuerhaus.ausgabe;

import com.dan.core.raum.Blick;
import com.dan.lauf.Szenario;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.regie.Fahrt;
import com.dan.steuerhaus.regie.Lesezeichen;
import com.dan.steuerhaus.regie.Regiebuch;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Die Ausgabe ohne Fenster (seit W4, Werkbuch HalligHooge 121, F200 A): ein Video eines Regiebuchs oder einer Fahrt, Bild
 * für Bild, oder ein Standbild. Ein eigener Prozess; die App startet ihn über das Menü Datei und liest seinen
 * Fortschritt (Zeilen „Ausgabe: …“).
 *
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.ausgabe.Ausgabe --manifest src\szenen\Hooge.steuerhaus
 *      --regie "Landunter an der Hanswarft" [--groesse 1080p|4k|BxH] [--bilder-je-s 24] [--teilbilder 8]
 *      [--verschluss 180] [--korn 1 | --ohne-korn] [--ohne-einblendung] [--ziel datei.mp4] [--ffmpeg pfad] [--png]
 *      [--crf 18] [--bis n]
 * … --fahrt "Um die Hanswarft" --szenario sturm [--zeit 2026-10-25T21:30:00Z]
 * … --standbild (--lesezeichen "Name" | --blick "szenario;ISO-Zeit;Ost Nord Höhe Richtung Neigung Bildwinkel") [--groesse 4k]
 *      [--vergleich szenario [--ausrichten uhrzeit|hochwasser]]   (seit W5: zwei Bilder _A.png, _B.png)
 * Seit X5 hat ein Video Ton ({@link com.dan.steuerhaus.ton.Klang}, je Bild aus dem Zustand), als AAC im MP4 oder als
 * {@code <ziel>.wav} neben der PNG-Folge; {@code --ohne-ton} lässt ihn weg.
 * … --postkarte a6|4k|quadrat (--lesezeichen … | --blick …) [--vorlage Name|Nummer] [--zeilen "Titel|Zeile"]
 *      (seit X4: das Bild in der Bildfläche der Vorlage, ohne Einblendungen, mit Rahmen und Unterschrift; {@link Postkarte})
 * … --vergleichsfolge "Name" [--groesse 1080p] [--ziel ordner]   (seit Y1: ein Vergleich aus der Datei der Vergleiche)
 * … --vergleichsfolge jetzt --blick "…" --vergleich szenario [--ausrichten uhrzeit|hochwasser|„A | B“] [--bereich "-3 3"]
 *      [--schritt 5] [--sonde "Name; Ost; Nord"] … [--name "Titel"]   (seit Y1: der Blick jetzt gegen ein zweites Szenario)
 *      – beides schreibt eine Mappe ({@link com.dan.steuerhaus.vergleich.Mappe}, Vorgabe {@code ausgabe.vergleiche/<schluessel>}).
 * … --jahr [--ziel ordner]   (seit Y3: die Mappe des Jahrs aus den Archiven der Abschnitte jahr.N, {@link Jahresrechnung};
 *      Vorgabe {@code ausgabe.jahr}; ohne Bühne)
 * … --gezeiten   (seit Z1: die Konstanten des Tidenkalenders aus PEGELONLINE und dem Modell, {@link Gezeitenrechnung};
 *      nach gezeiten.datei; ohne Bühne)
 * … --watt   (seit Z3: der Weg ins Watt und die Schwellen des Watt-Fensters aus Gelände und Modell, {@link Wattrechnung};
 *      nach watt.datei und watt/; ohne Bühne; braucht gezeiten.datei)
 * </pre>
 * Neben die Datei schreibt sie {@code <ziel>.txt}: Bilder, Größe, Bildrate, Dauer, Rechenzeit, Speicher.
 */
public final class Ausgabe {
    public static void main(String[] args) throws Exception {
        File manifest = null, ziel = null;
        String regie = null, fahrt = null, szenario = null, zeit = null, lz = null, blick = null, ffmpeg = null, vergleich = null, ausrichten = "uhrzeit";
        int breite = 1920, hoehe = 1080, teil = 8, crf = 18;
        double bjs = 24, verschluss = 180, korn = Double.NaN;
        boolean einbl = true, png = false, standbild = false, mitTon = true, jahr = false, gezeiten = false, watt = false;
        String postkarte = null, vorlage = null, zeilen = null;
        String folge = null, bereich = null, schritt = null, vname = null;
        List<String> vsonden = new ArrayList<>();
        long bis = Long.MAX_VALUE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--regie" -> regie = args[++i];
                case "--fahrt" -> fahrt = args[++i];
                case "--szenario" -> szenario = args[++i];
                case "--zeit" -> zeit = args[++i];
                case "--standbild" -> standbild = true;
                case "--lesezeichen" -> lz = args[++i];
                case "--blick" -> blick = args[++i];
                case "--vergleich" -> vergleich = args[++i];
                case "--ausrichten" -> ausrichten = args[++i];
                case "--groesse" -> {
                    String g = args[++i].toLowerCase(Locale.ROOT);
                    if (g.equals("4k") || g.equals("2160p")) { breite = 3840; hoehe = 2160; }
                    else if (g.equals("1080p")) { breite = 1920; hoehe = 1080; }
                    else if (g.equals("1440p")) { breite = 2560; hoehe = 1440; }
                    else {
                        String[] t = g.split("x");
                        breite = Integer.parseInt(t[0]);
                        hoehe = Integer.parseInt(t[1]);
                    }
                }
                case "--bilder-je-s" -> bjs = Double.parseDouble(args[++i]);
                case "--teilbilder" -> teil = Integer.parseInt(args[++i]);
                case "--verschluss" -> verschluss = Double.parseDouble(args[++i]);
                case "--korn" -> korn = Double.parseDouble(args[++i]);
                case "--ohne-korn" -> korn = 0;
                case "--ohne-einblendung" -> einbl = false;
                case "--ohne-ton" -> mitTon = false;
                case "--ziel" -> ziel = new File(args[++i]);
                case "--ffmpeg" -> ffmpeg = args[++i];
                case "--png" -> png = true;
                case "--crf" -> crf = Integer.parseInt(args[++i]);
                case "--bis" -> bis = Long.parseLong(args[++i]);
                case "--postkarte" -> postkarte = args[++i];
                case "--vorlage" -> vorlage = args[++i];
                case "--zeilen" -> zeilen = args[++i];
                case "--vergleichsfolge" -> folge = args[++i];
                case "--bereich" -> bereich = args[++i];
                case "--schritt" -> schritt = args[++i];
                case "--sonde" -> vsonden.add(args[++i]);
                case "--name" -> vname = args[++i];
                case "--jahr" -> jahr = true;
                case "--gezeiten" -> gezeiten = true;
                case "--watt" -> watt = true;
                default -> throw new IllegalArgumentException("Ausgabe: unbekannt: " + args[i]);
            }
        }
        if (manifest == null) throw new IllegalArgumentException("Ausgabe: --manifest fehlt");
        Manifest m = Manifest.lies(manifest);
        if (watt) {
            // seit Z3: ohne Bühne – Gelände, Archive, Kalender
            long j0 = System.nanoTime();
            Wattrechnung.erzeugen(m, com.dan.lauf.Szenen.lies(m.szenen()), x -> System.out.println("Ausgabe: " + x));
            System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - j0) / 1e9);
            System.exit(0);
            return;
        }
        if (gezeiten) {
            // seit Z1: ohne Bühne – Pegel, Gebiet, Archive
            long j0 = System.nanoTime();
            Gezeitenrechnung.erzeugen(m, com.dan.lauf.Szenen.lies(m.szenen()), x -> System.out.println("Ausgabe: " + x));
            System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - j0) / 1e9);
            System.exit(0);
            return;
        }
        if (jahr) {
            // seit Y3: ohne Bühne, nur Archive, Gebiet und Karte
            long j0 = System.nanoTime();
            Jahresrechnung.erzeugen(m, com.dan.lauf.Szenen.lies(m.szenen()), ziel, x -> System.out.println("Ausgabe: " + x));
            System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - j0) / 1e9);
            System.exit(0);
            return;
        }
        // seit Y1: die Bildfolge eines Vergleichs ohne Korn (ein Standbild hat stehendes Korn; über die Folge läge es fest im Bild)
        if (Double.isNaN(korn)) korn = folge != null ? 0 : m.korn();
        Postkarte.Format pf = null;
        Postkarte.Vorlage pv = null;
        if (postkarte != null) {
            // seit X4: die Bühne rechnet genau die Bildfläche der Karte, ohne Einblendungen
            pf = Postkarte.Format.zu(postkarte);
            List<String> vf = new ArrayList<>();
            pv = Postkarte.vorlage(Postkarte.vorlagen(m, vf), vorlage);
            for (String x : vf) System.out.println("Ausgabe: Postkarte: " + x);
            java.awt.Rectangle r = Postkarte.bildflaeche(pf.breite, pf.hoehe, pv.stil());
            breite = r.width;
            hoehe = r.height;
            einbl = false;
            standbild = true;
        }
        Aufnahme.Optionen o = new Aufnahme.Optionen(breite, hoehe, bjs, teil, verschluss, korn, einbl, 1958);
        String stempel = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"));
        long n0 = System.nanoTime();
        try (Aufnahme a = new Aufnahme(m, o, s -> System.out.println("Ausgabe: " + s))) {
            for (String f : a.fehler) System.out.println("Ausgabe: Regie: " + f);
            if (pf != null) {
                File z = ziel != null ? ziel : new File(m.fotos(), "Postkarte_" + stempel + "_" + pf.schluessel + ".png");
                postkarte(a, lz, blick, pf, pv, zeilen, z);
                System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - n0) / 1e9);
                a.close();
                System.exit(0);
            }
            if (folge != null) {
                vergleichsfolge(a, m, folge, blick, lz, vergleich, ausrichten, bereich, schritt, vsonden, vname, ziel);
                System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - n0) / 1e9);
                a.close();
                System.exit(0);
            }
            if (standbild) {
                File z = ziel != null ? ziel : new File(m.fotos(), (vergleich != null ? "Vergleich_" : "Standbild_") + stempel + "_" + breite + "x" + hoehe + ".png");
                if (vergleich == null) standbild(a, lz, blick, z);
                else vergleich(a, lz, blick, vergleich, ausrichten.startsWith("hoch"), z);
                System.out.printf(Locale.GERMAN, "Ausgabe: fertig in %.1f s%n", (System.nanoTime() - n0) / 1e9);
                a.close();
                System.exit(0);
            }
            String titel;
            if (regie != null) {
                Regiebuch rb = a.buch(regie);
                if (rb == null) throw new IllegalArgumentException("Ausgabe: Regiebuch „" + regie + "“ gibt es nicht; bekannt: " + a.buecher.keySet());
                titel = rb.name;
                a.spiele(rb);
            } else if (fahrt != null) {
                Fahrt f = a.fahrten.get(fahrt);
                if (f == null) throw new IllegalArgumentException("Ausgabe: Fahrt „" + fahrt + "“ gibt es nicht; bekannt: " + a.fahrten.keySet());
                Szenario s = szenario != null ? a.sz.szenario(szenario) : a.sz.szenarien().get(0);
                if (s == null || s.archiv == null) throw new IllegalArgumentException("Ausgabe: Szenario " + szenario + " fehlt oder ist live (F205 A)");
                a.oeffne(s);
                if (zeit != null) a.b.zeit((Instant.parse(zeit).toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0);
                titel = f.name;
                a.fahre(f);
            } else throw new IllegalArgumentException("Ausgabe: --regie, --fahrt oder --standbild fehlt");
            if (ziel == null) ziel = new File(m.videos(), datei(titel) + "_" + stempel + "_" + (hoehe == 2160 ? "4K" : hoehe + "p") + ".mp4");
            video(a, o, ziel, ffmpeg, png, crf, bis, titel, n0, mitTon);
        }
        System.exit(0);
    }

    /**
     * Seit Y1: die Bildfolge eines Vergleichs – aus der Datei der Vergleiche (Name oder Schlüssel) oder „jetzt“: der Blick
     * (oder das Lesezeichen) gegen das Szenario {@code vergleich}, gleiche Uhrzeit oder das Hochwasser nächst der Zeit.
     */
    static com.dan.steuerhaus.vergleich.Mappe vergleichsfolge(Aufnahme a, Manifest m, String folge, String blick, String lz, String vergleich, String ausrichten, String bereich,
                                                             String schritt, List<String> sonden, String name, File ziel) throws Exception {
        com.dan.steuerhaus.vergleich.Vergleich v;
        if (!folge.equalsIgnoreCase("jetzt")) {
            List<String> fehler = new ArrayList<>();
            List<com.dan.steuerhaus.vergleich.Vergleich> alle = com.dan.steuerhaus.vergleich.Vergleich.lies(m, fehler);
            for (String x : fehler) System.out.println("Ausgabe: Vergleiche: " + x);
            v = alle.stream().filter(x -> x.name().equals(folge) || x.schluessel().equals(folge)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Ausgabe: Vergleich „" + folge + "“ gibt es nicht; bekannt: " + alle.stream().map(com.dan.steuerhaus.vergleich.Vergleich::name).toList()));
        } else {
            if (vergleich == null) throw new IllegalArgumentException("Ausgabe: --vergleichsfolge jetzt braucht --vergleich szenario");
            Object[] o = ort(a, lz, blick);
            Szenario sa = (Szenario) o[0];
            Instant t = (Instant) o[1];
            Blick bl = (Blick) o[2];
            Szenario sb = a.sz.szenario(vergleich);
            if (sb == null) throw new IllegalArgumentException("Ausgabe: Szenario „" + vergleich + "“ gibt es nicht");
            String au = ausrichten.contains(" ") || ausrichten.contains("|") ? ausrichten
                    : ausrichten.startsWith("hoch") ? "hochwasser " + t + " | hochwasser" : "zeit " + t + " | uhrzeit";
            double[] be = bereich != null ? zahlen2(bereich) : new double[] {-3, 3};
            double sm = schritt != null ? Double.parseDouble(schritt.replace(',', '.')) : 5;
            List<String> so = new ArrayList<>(sonden);
            if (so.isEmpty()) {
                so.add("pegel");
                so.add("mitte");
            }
            String n = name != null ? name : sa.name + " und " + sb.name;
            v = new com.dan.steuerhaus.vergleich.Vergleich("jetzt_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")), n, "", sa.schluessel, sb.schluessel,
                    null, new double[] {bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel}, com.dan.steuerhaus.vergleich.Vergleich.Ausrichtung.lies(au),
                    be[0] * 3600, be[1] * 3600, sm * 60, List.copyOf(so.subList(0, Math.min(so.size(), com.dan.steuerhaus.vergleich.Vergleich.SONDEN))));
            String w = v.pruefe();
            if (w != null) throw new IllegalArgumentException("Ausgabe: Vergleich: " + w);
        }
        File ordner = ziel != null ? ziel : new File(m.vergleiche(), v.schluessel());
        return Vergleichsfolge.rechne(a, v, ordner, s -> System.out.println("Ausgabe: " + s)).mappe();
    }

    private static double[] zahlen2(String s) {
        String[] t = s.strip().split("[\\s;]+");
        if (t.length != 2) throw new IllegalArgumentException("Ausgabe: --bereich \"von bis\" (Stunden)");
        return new double[] {Double.parseDouble(t[0].replace(',', '.')), Double.parseDouble(t[1].replace(',', '.'))};
    }

    static String datei(String titel) { return titel.replaceAll("[^\\p{L}\\p{N}]+", "_").replaceAll("^_|_$", ""); }

    static void video(Aufnahme a, Aufnahme.Optionen o, File ziel, String ffmpeg, boolean png, int crf, long bis, String titel, long n0, boolean mitTon) throws Exception {
        String ff = png ? null : Kodierer.ffmpeg(ffmpeg);
        if (!png && ff == null) {
            System.out.println("Ausgabe: ffmpeg nicht gefunden – PNG-Folge statt MP4 (einmalig: winget install Gyan.FFmpeg)");
            ziel = new File(ziel.getParentFile(), ziel.getName().replaceFirst("\\.mp4$", ""));
        }
        // seit X5: der Ton, Bild für Bild aus dem Zustand gerechnet, in eine WAV-Datei; am Ende ins MP4
        int rate = com.dan.steuerhaus.ton.Ausgang.RATE;
        File wav = mitTon ? new File(ziel.getPath() + (ff != null ? ".ton.wav" : ".wav")) : null;
        com.dan.steuerhaus.ton.Klang klang = mitTon ? new com.dan.steuerhaus.ton.Klang(1958, rate) : null;
        com.dan.steuerhaus.ton.WavDatei wd = mitTon ? new com.dan.steuerhaus.ton.WavDatei(wav, rate) : null;
        float[] tl = new float[rate], tr = new float[rate];
        long[] spitze = {0};
        double[] langsamst = {0};
        try (Kodierer k = ff != null ? new Kodierer.Ffmpeg(ff, ziel, o.breite(), o.hoehe(), o.bilderJeS(), crf) : new Kodierer.PngFolge(ziel, o.breite(), o.hoehe())) {
            System.out.printf(Locale.GERMAN, "Ausgabe: „%s“, %d × %d, %.0f B/s, %d Teilbilder, Verschluss %.0f°, Korn %.2f, Einblendungen %s → %s (%s)%n",
                    titel, o.breite(), o.hoehe(), o.bilderJeS(), o.teilbilder(), o.verschluss(), o.korn(), o.einblendung() ? "wie im Regiebuch" : "aus", ziel, k.art());
            long nStart = System.nanoTime();
            while (a.laeuft() && a.bilder() < bis) {
                long nb = System.nanoTime();
                byte[] rgb = a.bild();
                k.bild(rgb);
                if (klang != null) {
                    long nb0 = a.bilder() - 1;
                    int m = (int) (Math.round((nb0 + 1) * rate / o.bilderJeS()) - Math.round(nb0 * rate / o.bilderJeS()));
                    klang.setze(com.dan.steuerhaus.ton.Tonwerte.aus(a.b, a.b.zustand()));
                    klang.rechne(tl, tr, 0, m);
                    wd.schreibe(tl, tr, 0, m);
                }
                double s = (System.nanoTime() - nb) / 1e9;
                langsamst[0] = Math.max(langsamst[0], s);
                if (s > 2) System.out.printf(Locale.GERMAN, "Ausgabe: langes Bild %d: %.1f s%n", a.bilder(), s);   // seit W4b (A53)
                spitze[0] = Math.max(spitze[0], a.b.speicher()[1]);
                long n = a.bilder();
                if (n % 24 == 0 || n == 1) {
                    double je = (System.nanoTime() - nStart) / 1e9 / n;
                    System.out.printf(Locale.GERMAN, "Ausgabe: Bild %d · Film %s · %.2f s je Bild%s%n", n, mmss(n / o.bilderJeS()), je,
                            a.r.regie() != null ? " · Regie " + mmss(a.r.regie().uhr()) : a.r.imKino() ? " · Abspann" : "");
                }
            }
        }
        if (wd != null) {
            wd.close();
            System.out.printf(Locale.GERMAN, "Ausgabe: Ton %.1f s, %d Möwenrufe → %s%n", wd.frames() / (double) rate, klang.rufe(), wav.getName());
            if (ff != null) ton(ff, ziel, wav);
        }
        long n = a.bilder();
        double rechen = (System.nanoTime() - n0) / 1e9;
        String text = String.format(Locale.ROOT, """
                titel = %s
                datei = %s
                bilder = %d
                breite = %d
                hoehe = %d
                bilder_je_s = %s
                dauer_s = %.3f
                teilbilder = %d
                verschluss = %s
                korn = %s
                einblendung = %s
                rechenzeit_s = %.1f
                je_bild_s = %.3f
                langsamstes_bild_s = %.3f
                speicher_spitze_mb = %.0f
                """, titel, ziel.getName(), n, o.breite(), o.hoehe(), fmt(o.bilderJeS()), n / o.bilderJeS(), o.teilbilder(), fmt(o.verschluss()), fmt(o.korn()), o.einblendung(),
                rechen, n > 0 ? rechen / n : 0, langsamst[0], spitze[0] / 1048576.0);
        Files.writeString(new File(ziel.getPath() + ".txt").toPath(), text, StandardCharsets.UTF_8);
        System.out.printf(Locale.GERMAN, "Ausgabe: fertig – %d Bilder (%s Film) in %s, %.2f s je Bild → %s%n", n, mmss(n / o.bilderJeS()), mmss(rechen), n > 0 ? rechen / n : 0, ziel);
    }

    /** Seit X5: legt den Ton ins MP4 (Video kopiert, Ton AAC 192 kb/s); gelingt es, fällt die WAV-Datei weg. */
    static void ton(String ff, File video, File wav) throws Exception {
        File tmp = new File(video.getPath() + ".mitton.mp4");
        Process p = new ProcessBuilder(ff, "-y", "-loglevel", "error", "-i", video.getPath(), "-i", wav.getPath(), "-c:v", "copy", "-c:a", "aac", "-b:a", "192k", "-shortest",
                tmp.getPath()).redirectErrorStream(true).start();
        String aus = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int e = p.waitFor();
        if (e == 0 && tmp.isFile() && tmp.length() > 0) {
            Files.move(tmp.toPath(), video.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.deleteIfExists(wav.toPath());
            System.out.println("Ausgabe: Ton im Video (AAC)");
        } else System.out.println("Ausgabe: Ton nicht ins Video gelegt (ffmpeg " + e + ": " + aus.strip() + ") – er liegt daneben: " + wav.getName());
    }

    static String fmt(double x) { return x == Math.rint(x) ? String.valueOf((long) x) : String.valueOf(x); }

    static String mmss(double s) { return String.format(Locale.ROOT, "%d:%02d", (int) s / 60, (int) s % 60); }

    /**
     * Seit W5 (F210 A): das Vergleichsbild – derselbe Blick im Szenario jetzt (A) und im Vergleich (B), zur gleichen Uhrzeit
     * (ganze Tage verschoben) oder zum gleichen Hochwasser (das erste der Pegelreihe beider); schreibt {@code ziel_A.png} und
     * {@code ziel_B.png}.
     */
    /** Ergebnis von {@link #vergleich}: die beiden Dateien, Szenarien und Szenenzeiten (s seit Start der jeweiligen Quelle). */
    public record Vergleich(File a, File b, Szenario sa, double tsa, Szenario sb, double tsb, Blick blick) { }

    public static Vergleich vergleich(Aufnahme a, String lz, String blick, String vergleich, boolean hochwasser, File ziel) throws Exception {
        Object[] o = ort(a, lz, blick);
        Szenario sa = (Szenario) o[0], sb = a.sz.szenario(vergleich);
        if (sb == null || sb.archiv == null) throw new IllegalArgumentException("Ausgabe: Vergleich „" + vergleich + "“ fehlt oder ist live");
        Instant ta = (Instant) o[1];
        Blick bl = (Blick) o[2];
        String stamm = ziel.getPath().replaceFirst("\\.png$", "");
        File fa = new File(stamm + "_A.png"), fb = new File(stamm + "_B.png");
        // seit W5b (F217 A): jede Hälfte in einer frischen Bühne, so gleicht sie einem einzelnen Standbild
        if (a.benutzt()) a.frischeBuehne();
        a.oeffne(sa);
        com.dan.lauf.Pegelreihe pa = a.b.pegelreihe();
        double tsa = (ta.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0;
        byte[] ra = a.standbild(bl, tsa);
        schreibePng(ra, a.o.breite(), a.o.hoehe(), fa);
        Instant hwA = hochwasser(pa);
        a.frischeBuehne();
        a.oeffne(sb);
        com.dan.lauf.Pegelreihe pb = a.b.pegelreihe();
        Instant tb;
        Instant hwB = hochwasser(pb);
        if (hochwasser && hwA != null && hwB != null) tb = ta.minusMillis(hwA.toEpochMilli() - hwB.toEpochMilli());
        else {
            java.time.ZoneId z = a.zone;
            long tage = java.time.LocalDate.ofInstant(ta, z).toEpochDay() - java.time.LocalDate.ofInstant(a.b.uhr().bei(pb != null && pb.laenge() > 0 ? pb.zeiten()[0] : 0), z).toEpochDay();
            tb = ta.minus(java.time.Duration.ofDays(tage));
        }
        double tsb = (tb.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0;
        byte[] rb = a.standbild(bl, tsb);
        schreibePng(rb, a.o.breite(), a.o.hoehe(), fb);
        System.out.printf(Locale.GERMAN, "Ausgabe: Vergleich %s (%s) | %s (%s) → %s, %s%n", sa.name, ta, sb.name, tb, fa.getName(), fb.getName());
        return new Vergleich(fa, fb, sa, tsa, sb, tsb, bl);
    }

    private static Instant hochwasser(com.dan.lauf.Pegelreihe p) {
        if (p == null) return null;
        for (com.dan.lauf.Pegelreihe.Marke mk : p.marken()) if (mk.art() == com.dan.lauf.Pegelreihe.Art.HOCHWASSER) return p.start.plusMillis(Math.round(mk.t() * 1000));
        return null;
    }

    /** Szenario, Zeit und Blick aus Lesezeichen oder --blick: {Szenario, Instant, Blick}. */
    static Object[] ort(Aufnahme a, String lz, String blick) {
        String sk;
        Instant t;
        double[] v;
        if (lz != null) {
            Lesezeichen z = a.lesezeichen.stream().filter(x -> x.name().equals(lz)).findFirst().orElseThrow(() -> new IllegalArgumentException("Ausgabe: Lesezeichen „" + lz + "“ gibt es nicht"));
            sk = z.szenario();
            t = z.zeit();
            v = z.blick();
        } else if (blick != null) {
            String[] p = blick.split(";");
            sk = p[0].strip();
            t = Instant.parse(p[1].strip());
            String[] w = p[2].strip().split("\\s+");
            v = new double[6];
            for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(w[i].replace(',', '.'));
        } else throw new IllegalArgumentException("Ausgabe: --standbild braucht --lesezeichen oder --blick");
        Szenario s = a.sz.szenario(sk);
        if (s == null) throw new IllegalArgumentException("Ausgabe: Szenario „" + sk + "“ gibt es nicht");
        Blick bl = new Blick();
        bl.ost = v[0];
        bl.nord = v[1];
        bl.hoehe = v[2];
        bl.richtung = v[3];
        bl.neigung = v[4];
        bl.bildwinkel = v[5];
        bl.groesse(a.o.breite(), a.o.hoehe());
        return new Object[] {s, t, bl};
    }

    static void standbild(Aufnahme a, String lz, String blick, File ziel) throws Exception {
        String sk;
        Instant t;
        double[] v;
        if (lz != null) {
            Lesezeichen z = a.lesezeichen.stream().filter(x -> x.name().equals(lz)).findFirst().orElseThrow(() -> new IllegalArgumentException("Ausgabe: Lesezeichen „" + lz + "“ gibt es nicht"));
            sk = z.szenario();
            t = z.zeit();
            v = z.blick();
        } else if (blick != null) {
            String[] p = blick.split(";");
            sk = p[0].strip();
            t = Instant.parse(p[1].strip());
            String[] w = p[2].strip().split("\\s+");
            v = new double[6];
            for (int i = 0; i < 6; i++) v[i] = Double.parseDouble(w[i].replace(',', '.'));
        } else throw new IllegalArgumentException("Ausgabe: --standbild braucht --lesezeichen oder --blick");
        Szenario s = a.sz.szenario(sk);
        if (s == null) throw new IllegalArgumentException("Ausgabe: Szenario „" + sk + "“ gibt es nicht");
        a.oeffne(s);
        Blick bl = new Blick();
        bl.ost = v[0];
        bl.nord = v[1];
        bl.hoehe = v[2];
        bl.richtung = v[3];
        bl.neigung = v[4];
        bl.bildwinkel = v[5];
        bl.groesse(a.o.breite(), a.o.hoehe());
        double ts = (t.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0;
        System.out.printf(Locale.GERMAN, "Ausgabe: Standbild %d × %d, %s, %s, Korn %.2f → %s%n", a.o.breite(), a.o.hoehe(), s.name, t, a.o.korn(), ziel);
        byte[] rgb = a.standbild(bl, ts);
        schreibePng(rgb, a.o.breite(), a.o.hoehe(), ziel);
        long[] sp = a.b.speicher();
        System.out.printf(Locale.GERMAN, "Ausgabe: Speicher auf der Karte: Spitze %.0f MB%n", sp[1] / 1048576.0);
    }

    /**
     * Seit X4: eine Postkarte – der Blick in der Bildfläche, Rahmen und Unterschrift der Vorlage. Ohne {@code zeilen}
     * füllt die Ausgabe die Vorlage selbst (Pegel aus der Pegelreihe; Wind kennt sie im Standbild nicht, der Teil fällt
     * weg). Daneben {@code <ziel>.txt} mit Zeilen und Blick.
     */
    static void postkarte(Aufnahme a, String lz, String blick, Postkarte.Format f, Postkarte.Vorlage v, String zeilen, File ziel) throws Exception {
        Object[] o = ort(a, lz, blick);
        Szenario s = (Szenario) o[0];
        Instant t = (Instant) o[1];
        Blick bl = (Blick) o[2];
        a.oeffne(s);
        double ts = (t.toEpochMilli() - a.b.uhr().start.toEpochMilli()) / 1000.0;
        System.out.printf(Locale.GERMAN, "Ausgabe: Postkarte %s, %s, Bild %d × %d, %s, %s → %s%n", f.name, v, a.o.breite(), a.o.hoehe(), s.name, t, ziel);
        byte[] rgb = a.standbild(bl, ts);
        BufferedImage foto = new BufferedImage(a.o.breite(), a.o.hoehe(), BufferedImage.TYPE_INT_RGB);
        int[] px = new int[a.o.breite() * a.o.hoehe()];
        for (int i = 0; i < px.length; i++) px[i] = (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | rgb[3 * i + 2] & 0xFF;
        foto.setRGB(0, 0, a.o.breite(), a.o.hoehe(), px, 0, a.o.breite());
        String titel, zeile;
        if (zeilen != null) {
            String[] z = zeilen.split("\\|", 2);
            titel = z[0].strip();
            zeile = z.length > 1 ? z[1].strip() : "";
        } else {
            com.dan.lauf.Buehne.Zustand z = new com.dan.lauf.Buehne.Zustand();
            z.szenario = s;
            z.jetzt = t;
            z.blick = bl;
            z.wind = Double.NaN;
            com.dan.lauf.Pegelreihe pr = a.b.pegelreihe();
            z.pegel = pr != null ? pr.bei(ts) : Double.NaN;
            String pn = a.sz.zahlen("pegel.ort") != null ? a.sz.text("pegel.name", "Pegel") : null;
            var w = Postkarte.werte(a.m, pn, z, null, com.dan.steuerhaus.buehne.Orte.lies(a.m, a.sz.gebiet(), null), a.zone);
            titel = Postkarte.fuelle(v.titel(), w);
            zeile = Postkarte.fuelle(v.zeile(), w);
        }
        BufferedImage logo = null;
        String sy = a.m.text("app.icon", "");
        if (!sy.isEmpty()) {
            try {
                logo = ImageIO.read(a.m.relativ(sy));
            } catch (IOException x) {
                System.out.println("Ausgabe: Postkarte ohne Symbol: " + x.getMessage());
            }
        }
        BufferedImage karte = Postkarte.gestalte(foto, f.breite, f.hoehe, v.stil(), titel, zeile, logo);
        Postkarte.schreibe(karte, f.dpi, ziel);
        Files.writeString(new File(ziel.getPath() + ".txt").toPath(), String.format(Locale.ROOT, """
                format = %s
                vorlage = %s
                titel = %s
                zeile = %s
                szenario = %s
                zeit = %s
                blick = %.3f %.3f %.3f %.4f %.4f %.4f
                """, f.schluessel, v.name(), titel, zeile, s.schluessel, t, bl.ost, bl.nord, bl.hoehe, bl.richtung, bl.neigung, bl.bildwinkel), StandardCharsets.UTF_8);
        System.out.println("Ausgabe: Postkarte „" + titel + "“ · " + zeile + " → " + ziel.getName());
    }

    public static void schreibePng(byte[] rgb, int w, int h, File ziel) throws IOException {
        if (ziel.getParentFile() != null) ziel.getParentFile().mkdirs();
        BufferedImage im = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        int[] p = new int[w * h];
        for (int i = 0; i < p.length; i++) p[i] = (rgb[3 * i] & 0xFF) << 16 | (rgb[3 * i + 1] & 0xFF) << 8 | rgb[3 * i + 2] & 0xFF;
        im.setRGB(0, 0, w, h, p, 0, w);
        ImageIO.write(im, "png", ziel);
    }
}
