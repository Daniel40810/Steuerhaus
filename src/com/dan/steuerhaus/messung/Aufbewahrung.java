package com.dan.steuerhaus.messung;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Was die Pflege wegnimmt (seit DB1, Werkbuch HalligHooge 167) – die Wachstumsgrenze des Messarchivs. Die Minutenwerte
 * ({@code HH_ROH}) liegen in Monatsfächern, die 10-Minuten-Mittel ({@code HH_MITTEL}) in Jahresfächern (Partitionen
 * nach Intervall). Ein Fach fällt als Ganzes weg ({@code DROP PARTITION}): Das gibt den Platz sofort frei, ohne Undo und
 * ohne einzelne Zeilen zu löschen. Ein Trigger je Zeile wäre dafür das falsche Werkzeug: Er feuerte bei jedem der rund
 * 32 000 neuen Werte am Tag und dürfte die eigene Tabelle nicht ändern.
 * <ol>
 * <li><b>Aufbewahrung:</b> Ein Monatsfach der Minutenwerte, das ganz älter ist als {@code rohMonate}, fällt weg. Seine
 * Werte stehen dann schon als Mittel in {@code HH_MITTEL} (die Pflege verdichtet vorher noch einmal).</li>
 * <li><b>Grenze:</b> Liegt der Platz danach noch über {@code grenzeMb}, fallen weitere Monatsfächer der Minutenwerte
 * (nie der laufende und der vorige Monat), dann Jahresfächer der Mittel (nie das laufende Jahr), das älteste zuerst, bis
 * 90 % der Grenze erreicht sind.</li>
 * <li>Reicht auch das nicht, ist der Plan {@link Plan#ueber}: Der Sammler fügt nichts mehr ein und meldet es.</li>
 * </ol>
 * Scheitel, Ereignisse, Stationen und Konstanten bleiben immer. Das Fach unter 2000 (Anker der Intervalle) ist leer und
 * bleibt. Nur java.*.
 */
public final class Aufbewahrung {
    private Aufbewahrung() { }

    /** Ein Fach: Tabelle, Partition, Zeitraum [ab, bis) (ab null: Anker), Größe in MB (Tabelle und lokaler Index). */
    public record Fach(String tabelle, String partition, LocalDate ab, LocalDate bis, double mb) {
        public boolean anker() { return ab == null; }

        public String kurz() {
            return String.format(Locale.ROOT, "%s %s (%s bis %s, %.1f MB)", tabelle, partition, ab, bis, mb);
        }
    }

    /** Der Plan: was wegfällt (mit Grund), Platz vorher und nachher (MB), ob es über der Grenze bleibt. */
    public record Plan(List<Fach> weg, List<String> gruende, double vorher, double nachher, boolean ueber) {
        public boolean leer() { return weg.isEmpty(); }
    }

    public static Plan plan(List<Fach> roh, List<Fach> mittel, LocalDate heute, int rohMonate, double gesamtMb, double grenzeMb) {
        List<Fach> weg = new ArrayList<>();
        List<String> gruende = new ArrayList<>();
        LocalDate schnitt = heute.minusMonths(Math.max(0, rohMonate));
        List<Fach> r = new ArrayList<>(roh.stream().filter(f -> !f.anker()).sorted(Comparator.comparing(Fach::ab)).toList());
        List<Fach> m = new ArrayList<>(mittel.stream().filter(f -> !f.anker()).sorted(Comparator.comparing(Fach::ab)).toList());
        double rest = gesamtMb;
        // 1 Aufbewahrung
        for (Fach f : List.copyOf(r)) {
            if (!f.bis().isAfter(schnitt)) {
                weg.add(f);
                gruende.add("älter als " + rohMonate + " Monate: " + f.kurz());
                rest -= f.mb();
                r.remove(f);
            }
        }
        // 2 Grenze
        if (grenzeMb > 0 && rest > grenzeMb) {
            double ziel = 0.9 * grenzeMb;
            LocalDate monat = heute.withDayOfMonth(1).minusMonths(1), jahr = heute.withDayOfYear(1);
            for (Fach f : List.copyOf(r)) {
                if (rest <= ziel) break;
                if (!f.ab().isBefore(monat)) break;
                weg.add(f);
                gruende.add(String.format(Locale.ROOT, "über der Grenze %.0f MB: %s", grenzeMb, f.kurz()));
                rest -= f.mb();
                r.remove(f);
            }
            for (Fach f : List.copyOf(m)) {
                if (rest <= ziel) break;
                if (!f.ab().isBefore(jahr)) break;
                weg.add(f);
                gruende.add(String.format(Locale.ROOT, "über der Grenze %.0f MB: %s", grenzeMb, f.kurz()));
                rest -= f.mb();
                m.remove(f);
            }
        }
        boolean ueber = grenzeMb > 0 && rest > grenzeMb;
        if (ueber) gruende.add(String.format(Locale.ROOT, "bleibt über der Grenze: %.1f von %.0f MB", rest, grenzeMb));
        return new Plan(List.copyOf(weg), List.copyOf(gruende), gesamtMb, rest, ueber);
    }
}
