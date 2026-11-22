package com.dan.steuerhaus.messung;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Die Abrufe des Sammlers (seit DB1, Werkbuch HalligHooge 167): Wasserstand von PEGELONLINE (WSV, DL-DE Zero 2.0; die
 * REST-Schnittstelle gibt 30 Tage rückwirkend her, {@code start} als Zeitpunkt nach ISO 8601) und die 10-Minuten-Werte
 * des Winds aus dem Climate Data Center des DWD (CC BY 4.0; Ordner {@code recent}, täglich neu, je Station ein ZIP mit
 * {@code produkt_zehn_min_ff_*.txt}). Gedeutet wird die Antwort von PEGELONLINE mit {@code gezeiten.Pegelonline.deute}.
 * Nur java.*.
 */
public final class Abruf {
    private Abruf() { }

    /** Wartezeit je Abruf in Sekunden. */
    public static int wartenS = 30;
    public static final String KENNUNG = "HalligHooge-Messarchiv";
    static final DateTimeFormatter ISO_MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm'+00:00'");

    /** Die Adresse der Messwerte W eines Pegels (Nummer) ab einem Zeitpunkt (auf die Minute, UTC). */
    public static String pegelAdresse(String basis, String nummer, Instant ab) {
        String t = LocalDateTime.ofInstant(ab.truncatedTo(ChronoUnit.MINUTES), ZoneOffset.UTC).format(ISO_MINUTE);
        return basis + "/stations/" + URLEncoder.encode(nummer, StandardCharsets.UTF_8).replace("+", "%20") + "/W/measurements.json?start="
                + URLEncoder.encode(t, StandardCharsets.UTF_8);
    }

    /** Die Adresse des ZIP mit den 10-Minuten-Werten einer Station des DWD (Kennung fünfstellig). */
    public static String dwdAdresse(String basis, String kennung) {
        return basis + "/10minutenwerte_wind_" + kennung + "_akt.zip";
    }

    private static HttpClient client() {
        HttpClient.Builder cb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL);
        if (java.net.ProxySelector.getDefault() != null) cb.proxy(java.net.ProxySelector.getDefault());
        return cb.build();
    }

    /** Holt eine Adresse als Bytes; wirft bei einer Antwort außer 200. */
    public static byte[] hole(String url, String accept) throws IOException {
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(wartenS)).header("Accept", accept)
                .header("User-Agent", KENNUNG).GET().build();
        try {
            HttpResponse<byte[]> a = client().send(r, HttpResponse.BodyHandlers.ofByteArray());
            if (a.statusCode() != 200) {
                String t = new String(a.body(), StandardCharsets.UTF_8).strip().replaceAll("\\s+", " ");
                throw new IOException("Antwort " + a.statusCode() + " von " + url + (t.isEmpty() ? "" : ": " + t.substring(0, Math.min(200, t.length()))));
            }
            return a.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("unterbrochen: " + url, e);
        }
    }

    public static String holeText(String url) throws IOException {
        return new String(hole(url, "application/json"), StandardCharsets.UTF_8);
    }

    /** Ein Wert des DWD: Ende des 10-Minuten-Zeitraums (Epoch-Sekunden UTC), Geschwindigkeit (m/s), Richtung (Grad). */
    public record Wind(long ende, float ff, float dd) { }

    /**
     * Liest {@code produkt_zehn_min_ff_*.txt} aus einem ZIP des DWD: Spalten über ihre Namen ({@code MESS_DATUM},
     * {@code FF_10}, {@code DD_10}), Zeit {@code JJJJMMTTHHmm} in UTC am Ende der 10 Minuten, −999 fehlt (NaN).
     */
    public static List<Wind> dwd(byte[] zip) throws IOException {
        List<Wind> l = new ArrayList<>();
        boolean gefunden = false;
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                if (!e.getName().toLowerCase(Locale.ROOT).startsWith("produkt_zehn_min_ff")) continue;
                gefunden = true;
                BufferedReader r = new BufferedReader(new InputStreamReader(z, StandardCharsets.ISO_8859_1));
                int iz = -1, iff = -1, idd = -1;
                for (String s; (s = r.readLine()) != null; ) {
                    if (s.isBlank()) continue;
                    String[] f = s.split(";", -1);
                    for (int i = 0; i < f.length; i++) f[i] = f[i].strip();
                    if (iz < 0) {
                        for (int i = 0; i < f.length; i++) {
                            String k = f[i].toLowerCase(Locale.ROOT);
                            if (k.equals("mess_datum")) iz = i;
                            else if (k.equals("ff_10")) iff = i;
                            else if (k.equals("dd_10")) idd = i;
                        }
                        if (iz < 0 || iff < 0) throw new IOException("DWD: Spalten MESS_DATUM, FF_10 fehlen im Kopf: " + s);
                        continue;
                    }
                    if (f.length <= Math.max(iz, Math.max(iff, idd))) continue;
                    long t = LocalDateTime.parse(f[iz], DateTimeFormatter.ofPattern("yyyyMMddHHmm")).toEpochSecond(ZoneOffset.UTC);
                    l.add(new Wind(t, wert(f[iff]), idd >= 0 ? wert(f[idd]) : Float.NaN));
                }
            }
        }
        if (!gefunden) throw new IOException("DWD: keine Datei produkt_zehn_min_ff_*.txt im ZIP");
        return l;
    }

    /** Seit DB2: eine historische Datei des DWD: Name, erster und letzter Tag (einschließlich). */
    public record Historisch(String name, java.time.LocalDate von, java.time.LocalDate bis) { }

    /**
     * Seit DB2: die historischen ZIP einer Station aus der Liste des Ordners {@code historical} des DWD
     * ({@code 10minutenwerte_wind_<Kennung>_<JJJJMMTT>_<JJJJMMTT>_hist.zip}), jede nur einmal, nach dem Beginn.
     */
    public static List<Historisch> dwdHistorisch(String verzeichnis, String kennung) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("10minutenwerte_wind_" + java.util.regex.Pattern.quote(kennung) + "_(\\d{8})_(\\d{8})_hist\\.zip")
                .matcher(verzeichnis);
        java.util.Map<String, Historisch> l = new java.util.TreeMap<>();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("yyyyMMdd");
        while (m.find()) l.putIfAbsent(m.group(), new Historisch(m.group(), java.time.LocalDate.parse(m.group(1), f), java.time.LocalDate.parse(m.group(2), f)));
        List<Historisch> r = new ArrayList<>(l.values());
        r.sort(java.util.Comparator.comparing(Historisch::von));
        return r;
    }

    static float wert(String s) {
        if (s.isEmpty()) return Float.NaN;
        float v = Float.parseFloat(s);
        return v <= -999 ? Float.NaN : v;
    }
}
