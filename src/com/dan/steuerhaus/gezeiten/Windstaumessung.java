package com.dan.steuerhaus.gezeiten;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Der gemessene Windstau ohne Oberfläche (seit Z3, Werkbuch HalligHooge 158; wie in der {@link Landunteruhr}): je Pegel der
 * Tafel die Messung von PEGELONLINE, Windstau = Mittel von Messung minus Gezeit über die letzte Stunde, für den Ort das
 * Mittel der erreichten Pegel.
 */
public final class Windstaumessung {
    public final List<Gezeitentafel.Bezug> bezuege;
    /** Je Bezug, woher die Messung kommt (für Prüfungen ersetzbar). */
    public List<Callable<List<Pegelonline.Messwert>>> messung = new ArrayList<>();
    private volatile List<Pegelonline.Stau> staus = List.of();
    private volatile List<String> fehler = List.of();

    public Windstaumessung(Gezeitentafel t) {
        this.bezuege = t.bezuege;
        for (Gezeitentafel.Bezug b : bezuege) messung.add(() -> Pegelonline.lies(b.nummer(), "P1D", b.pegelnull()));
    }

    /** Holt die Messung aller Pegel (in diesem Faden; je Pegel höchstens rund 10 s). */
    public void holen() {
        List<Pegelonline.Stau> neu = new ArrayList<>();
        List<String> f = new ArrayList<>();
        for (int i = 0; i < bezuege.size(); i++) {
            Callable<List<Pegelonline.Messwert>> c = i < messung.size() ? messung.get(i) : null;
            try {
                Pegelonline.Stau s = c == null ? null : Pegelonline.stau(c.call(), bezuege.get(i).vorhersage());
                neu.add(s);
                f.add(s == null ? "keine Werte" : null);
            } catch (Exception x) {
                neu.add(null);
                f.add(x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage());
            }
        }
        staus = neu;
        fehler = f;
    }

    /** Das Mittel der erreichten Pegel; null, wenn keiner erreicht ist (oder noch nicht geholt). */
    public Pegelonline.Stau gemessen() {
        double sum = 0;
        int n = 0, werte = 0;
        Instant stand = null;
        List<Pegelonline.Messwert> erste = null;
        for (Pegelonline.Stau x : staus) {
            if (x == null) continue;
            sum += x.meter();
            n++;
            werte += x.werte();
            if (stand == null || x.stand().isBefore(stand)) stand = x.stand();
            if (erste == null) erste = x.messung();
        }
        return n == 0 ? null : new Pegelonline.Stau(sum / n, stand, werte, erste);
    }

    public List<Pegelonline.Stau> je() { return staus; }

    /** Was fehlschlug („Wittdün: …“); null, wenn alle erreicht sind. */
    public String fehler() {
        StringBuilder b = new StringBuilder();
        List<String> f = fehler;
        for (int i = 0; i < f.size(); i++) if (f.get(i) != null) b.append(b.length() > 0 ? "; " : "").append(bezuege.get(i).kurz()).append(": ").append(f.get(i));
        return b.length() == 0 ? null : b.toString();
    }

    /** „Pellworm, Wittdün“. */
    public String namen() {
        StringBuilder b = new StringBuilder();
        for (Gezeitentafel.Bezug x : bezuege) b.append(b.length() > 0 ? ", " : "").append(x.kurz());
        return b.toString();
    }
}
