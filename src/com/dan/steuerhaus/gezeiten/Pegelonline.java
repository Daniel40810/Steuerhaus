package com.dan.steuerhaus.gezeiten;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Der Wasserstand eines Pegels aus PEGELONLINE (seit Z2, Werkbuch HalligHooge 156): die Rohwerte W (cm über Pegelnull,
 * je Minute) der letzten Stunden über die REST-Schnittstelle der WSV, umgerechnet in m NHN. Dazu der Windstau: Messung
 * minus astronomische Vorhersage am selben Pegel. Lizenz der Daten: DL-DE Zero 2.0.
 */
public final class Pegelonline {
    public static final String BASIS = "https://www.pegelonline.wsv.de/webservices/rest-api/v2";
    static final Pattern WERT = Pattern.compile("\"timestamp\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"value\"\\s*:\\s*(-?[0-9.]+)");

    /** Ein Messwert (m NHN). */
    public record Messwert(Instant zeit, double hoehe) { }

    /** Der Windstau: Mittel von Messung minus Vorhersage über die letzte Stunde, Zeit des letzten Werts, Zahl der Werte. */
    public record Stau(double meter, Instant stand, int werte, List<Messwert> messung) { }

    private Pegelonline() { }

    /** Die Adresse der Messwerte eines Pegels (Nummer, UUID oder Kurzname) für die Dauer (ISO 8601, etwa P1D). */
    public static String adresse(String pegel, String dauer) {
        return BASIS + "/stations/" + URLEncoder.encode(pegel, StandardCharsets.UTF_8).replace("+", "%20") + "/W/measurements.json?start=" + dauer;
    }

    /** Liest die Werte der letzten Stunden (Wartezeit höchstens 10 s). */
    public static List<Messwert> lies(String pegel, String dauer, double pegelnull) throws IOException {
        HttpClient.Builder cb = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NORMAL);
        if (java.net.ProxySelector.getDefault() != null) cb.proxy(java.net.ProxySelector.getDefault());
        HttpClient c = cb.build();
        HttpRequest r = HttpRequest.newBuilder(URI.create(adresse(pegel, dauer))).timeout(Duration.ofSeconds(10)).header("Accept", "application/json")
                .header("User-Agent", "HalligHooge-Steuerhaus").GET().build();
        try {
            HttpResponse<String> a = c.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (a.statusCode() != 200) throw new IOException("PEGELONLINE antwortet " + a.statusCode());
            return deute(a.body(), pegelnull);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("PEGELONLINE: unterbrochen", e);
        }
    }

    /** Deutet die Antwort: je Eintrag {@code "timestamp": "…+01:00", "value": 549.0} (cm über Pegelnull). */
    public static List<Messwert> deute(String json, double pegelnull) {
        List<Messwert> l = new ArrayList<>();
        Matcher m = WERT.matcher(json);
        while (m.find()) l.add(new Messwert(OffsetDateTime.parse(m.group(1)).toInstant(), Double.parseDouble(m.group(2)) / 100 + pegelnull));
        return l;
    }

    /**
     * Der Windstau aus der Messung: Mittel von Messung minus Vorhersage über die letzte Stunde vor dem jüngsten Wert;
     * null ohne Werte.
     */
    public static Stau stau(List<Messwert> messung, Vorhersage v) {
        if (messung.isEmpty()) return null;
        Instant ende = messung.get(messung.size() - 1).zeit(), ab = ende.minusSeconds(3600);
        double s = 0;
        int n = 0;
        for (Messwert w : messung) {
            if (!w.zeit().isAfter(ab)) continue;
            s += w.hoehe() - v.wert(w.zeit());
            n++;
        }
        return n == 0 ? null : new Stau(s / n, ende, messung.size(), messung);
    }
}
