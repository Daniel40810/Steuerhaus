package com.dan.steuerhaus.demo;

import com.dan.lauf.Szenario;
import com.dan.lauf.Szenen;
import com.dan.steuerhaus.Guete;
import com.dan.steuerhaus.Manifest;
import com.dan.steuerhaus.guete.Gueteansicht;
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
 * DB3 mit den echten Dateien (Werkbuch HalligHooge 171): Prüfstein 1 die Datei Modell gegen Messung ({@code guete.datei}):
 * jeder Lauf mit allen Pegeln aus {@code guete.pegel}, Reihen des Modells zu mindestens 90 % belegt, die Zelle im Umkreis;
 * 2 jedes Szenario mit Archiv hat seinen Lauf; 3 die Läufe im Oktober 2026 mit Messung an mindestens 6 von 7 Pegeln und
 * Kennzahlen in ihrem Wertebereich (Skill 0 … 1, |Bias| und RMSE unter 1 m – eine Plausibilität, keine Güte; die Güte
 * selbst ist Auskunft); 4 Xaver mit dem HThw an allen Pegeln und dem Hochwasser des Modells an dem Tag; 5 die Ansicht ohne
 * Fenster in beiden Themen ({@code DB3_Guete_<Thema>.png}, dazu {@code DB3_Guete_Xaver.png}) mit dem Knopf ins Szenario;
 * 6 der Eintrag im Menü; 7 die Kopie im Abbild gleich der Datei.
 * <pre>
 * java -cp dist\HalligHooge.jar com.dan.steuerhaus.demo.GueteCheck --manifest src\szenen\Hooge.steuerhaus --bilder docs\status
 * </pre>
 */
public final class GueteCheck {
    private GueteCheck() { }

    static int steine, verstoesse;

    static void pruefe(boolean ok, String was, String ist) {
        steine++;
        if (!ok) verstoesse++;
        System.out.println((ok ? "  ok     " : "  FEHLT  ") + was + ": " + ist);
    }

    static void info(String was, String ist) { System.out.println("  info   " + was + ": " + ist); }

    static String f(String s, Object... a) { return String.format(Locale.GERMAN, s, a); }

    public static void main(String[] args) throws Exception {
        File manifest = null, bilder = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--manifest" -> manifest = new File(args[++i]);
                case "--bilder" -> bilder = new File(args[++i]);
                default -> throw new IllegalArgumentException(args[i]);
            }
        }
        if (manifest == null) throw new IllegalArgumentException("--manifest fehlt");
        Manifest m = Manifest.lies(manifest);
        ZoneId zone = ZoneId.of(m.text("app.zone", "Europe/Berlin"));
        System.out.println("GueteCheck (DB3): " + manifest);
        Guete.Auftrag ga = Guete.Auftrag.lies(m);
        if (ga == null) {
            pruefe(false, "Prüfstein 1: Schlüssel guete.*", "guete.datei fehlt");
            ende();
            return;
        }
        // 1 Datei
        List<String> f1 = new ArrayList<>();
        Guete g = null;
        if (!ga.datei().isFile()) f1.add(ga.datei() + " fehlt (Ausgabe --guete, Sammler --guete)");
        else {
            g = Guete.lies(ga.datei());
            if (g.laeufe.isEmpty()) f1.add("keine Läufe");
            for (Guete.Lauf l : g.laeufe) {
                if (!l.kennung().matches("[0-9a-f]{16}")) f1.add(l.schluessel() + ": Kennung " + l.kennung());
                for (String p : ga.pegel()) {
                    Guete.Eintrag e = g.eintrag(l.schluessel(), p);
                    if (e == null) { f1.add(l.schluessel() + " ohne " + p); continue; }
                    if (e.modell().n() == 0 || e.modell().belegt() < 0.9 * e.modell().n()) f1.add(l.schluessel() + "/" + p + ": Modell " + e.modell().belegt() + " von " + e.modell().n());
                    if (!(e.abstand() <= ga.umkreis() + 150)) f1.add(l.schluessel() + "/" + p + f(": Zelle %.0f m vom Pegel", e.abstand()));
                }
            }
        }
        pruefe(f1.isEmpty(), "Prüfstein 1: Datei lesbar, je Lauf alle Pegel, Modell zu mindestens 90 % belegt, Zelle im Umkreis",
                f1.isEmpty() ? g.laeufe.size() + " Läufe, " + g.eintraege.size() + " Reihen, Stand " + g.erstellt : f1.subList(0, Math.min(8, f1.size())).toString());
        if (g == null) {
            ende();
            return;
        }
        // 2 Szenarien
        Szenen sz = Szenen.lies(m.szenen());
        List<String> f2 = new ArrayList<>();
        Map<String, String> namen = new LinkedHashMap<>();
        int mitArchiv = 0;
        for (Szenario s : sz.szenarien()) {
            namen.put(s.schluessel, s.name);
            if (s.live() || s.archiv == null || !s.archiv.isDirectory()) continue;
            mitArchiv++;
            if (g.laufVon(s.schluessel) == null) f2.add(s.schluessel + " ohne Lauf");
        }
        for (Guete.Lauf l : g.laeufe) for (String s : l.szenarien()) if (sz.szenario(s) == null) f2.add("Lauf " + l.schluessel() + ": Szenario " + s + " fehlt");
        pruefe(f2.isEmpty(), "Prüfstein 2: Jedes Szenario mit Archiv hat seinen Lauf", f2.isEmpty() ? mitArchiv + " Szenarien in " + g.laeufe.size() + " Läufen" : f2.toString());
        // 3 Messung und Kennzahlen (Oktober 2026)
        List<String> f3 = new ArrayList<>();
        StringBuilder z3 = new StringBuilder();
        int okt = 0;
        for (Guete.Lauf l : g.laeufe) {
            if (l.von().atZone(zone).getYear() < 2026) continue;
            okt++;
            int mit = 0;
            double rmse = 0, bias = 0, skill = 0, dt = 0, dh = 0, stau = 0;
            int hw = 0, ns = 0;
            for (Guete.Eintrag e : g.eintraege(l.schluessel())) {
                Guete.Kennzahlen k = e.kennzahlen();
                if (k.werte() == 0) continue;
                mit++;
                if (!(k.skill() >= 0 && k.skill() <= 1) || !(Math.abs(k.bias()) < 1) || !(k.rmse() < 1)) f3.add(l.schluessel() + "/" + e.pegel() + f(": RMSE %.2f, Bias %.2f, Skill %.3f", k.rmse(), k.bias(), k.skill()));
                rmse += k.rmse();
                bias += k.bias();
                skill += k.skill();
                if (k.hw() > 0) { dt += k.hwZeit() * k.hw(); dh += k.hwHoehe() * k.hw(); hw += k.hw(); }
                if (!Double.isNaN(k.stauAnteil())) { stau += k.stauAnteil(); ns++; }
            }
            if (mit < 6) f3.add(l.schluessel() + ": Messung an " + mit + " von " + ga.pegel().size() + " Pegeln");
            if (mit > 0) {
                String z = f("%s: RMSE %.2f m, Bias %+.0f cm, Skill %.3f, %d HW %+.0f min %+.0f cm%s", l.schluessel(), rmse / mit, 100 * bias / mit, skill / mit, hw, hw > 0 ? dt / hw : Double.NaN,
                        hw > 0 ? 100 * dh / hw : Double.NaN, ns > 0 ? f(", Windstau im Modell %.0f %%", 100 * stau / ns) : "");
                info("Güte " + l.schluessel() + " (Mittel über " + mit + " Pegel)", z);
                z3.append(z3.length() > 0 ? "; " : "").append(l.schluessel()).append(f(" %.3f", skill / mit));
            }
        }
        pruefe(f3.isEmpty() && okt > 0, "Prüfstein 3: Läufe im Oktober 2026 mit Messung an mindestens 6 von 7 Pegeln, Kennzahlen im Wertebereich (Plausibilität)",
                f3.isEmpty() ? okt + " Läufe, Skill im Mittel: " + z3 : f3.subList(0, Math.min(8, f3.size())).toString());
        // 4 Xaver
        List<String> f4 = new ArrayList<>();
        StringBuilder z4 = new StringBuilder();
        Guete.Lauf xl = g.laufVon("xaver");
        if (xl == null) f4.add("kein Lauf für xaver");
        else {
            for (Guete.Eintrag e : g.eintraege(xl.schluessel())) {
                Guete.Kennzahlen k = e.kennzahlen();
                if (!k.mitKennwert()) { f4.add(e.pegel() + " ohne HThw am Tag"); continue; }
                z4.append(z4.length() > 0 ? ", " : "").append(e.name()).append(f(" %+.0f cm", 100 * (k.amTag() - k.kennwert())));
            }
            if (g.eintraege(xl.schluessel()).size() != ga.pegel().size()) f4.add(g.eintraege(xl.schluessel()).size() + " Pegel");
        }
        pruefe(f4.isEmpty(), "Prüfstein 4: Xaver mit dem HThw an allen Pegeln und dem Hochwasser des Modells an dem Tag (Modell − HThw)", f4.isEmpty() ? z4.toString() : f4.toString());
        // 5 Ansicht in beiden Themen
        List<String> f5 = new ArrayList<>();
        final Guete gg = g;
        String erst = null;
        for (Guete.Lauf l : g.laeufe) if (l.schluessel().equals("sturm")) erst = l.schluessel();
        if (erst == null) erst = g.laeufe.get(0).schluessel();
        final String lauf = erst, pegel = ga.pegel().get(0);
        Instant[] sprung = new Instant[1];
        for (Thema th : Thema.values()) {
            BufferedImage[] bi = new BufferedImage[2];
            String[] knopf = new String[1];
            SwingUtilities.invokeAndWait(() -> {
                try {
                    th.anwenden();
                    Gueteansicht a = new Gueteansicht(gg, namen, zone, ga.hinweis(), (s, t) -> sprung[0] = t);
                    if (!a.waehle(lauf, pegel)) f5.add(th.name + ": " + lauf + "/" + pegel + " nicht wählbar");
                    bi[0] = a.alsBild(1320, 940);
                    a.knopf().doClick(0);
                    knopf[0] = a.knopf().getText();
                    if (xl != null && th == Thema.NAUTIK && a.waehle(xl.schluessel(), pegel)) bi[1] = a.alsBild(1320, 940);
                } catch (Exception x) {
                    f5.add(th.name + ": " + x);
                }
            });
            if (bi[0] == null) f5.add(th.name + ": kein Bild");
            else if (bilder != null) {
                bilder.mkdirs();
                ImageIO.write(bi[0], "png", new File(bilder, "DB3_Guete_" + th.name + ".png"));
                if (bi[1] != null) ImageIO.write(bi[1], "png", new File(bilder, "DB3_Guete_Xaver.png"));
            }
            Guete.Lauf l = g.lauf(lauf);
            if (sprung[0] == null || sprung[0].isBefore(l.von()) || sprung[0].isAfter(l.bis())) f5.add(th.name + ": Sprung " + sprung[0] + " (" + knopf[0] + ")");
        }
        Thema.LABOR.anwenden();
        pruefe(f5.isEmpty(), "Prüfstein 5: Ansicht ohne Fenster in beiden Themen, der Knopf springt in die Zeit des Laufs", f5.isEmpty() ? lauf + "/" + pegel + " → " + sprung[0]
                + ", DB3_Guete_*.png" : f5.toString());
        // 6 Menü
        var bf = com.dan.steuerhaus.buehne.Szenenapp.befehleOhneFenster(m, sz).finde("labor.guete");
        pruefe(bf != null && "ctrl shift M".equals(bf.taste), "Prüfstein 6: Menü Labor › Modell gegen Messung (Strg Umschalt M)", bf == null ? "fehlt" : bf.name + ", " + bf.taste);
        // 7 Kopie im Abbild
        if (!ga.kopien().isEmpty()) {
            File kf = ga.kopien().get(0);
            if (kf.getParentFile() != null && kf.getParentFile().isDirectory())
                pruefe(kf.isFile() && Guete.lies(kf).inhalt().equals(g.inhalt()), "Prüfstein 7: Kopie im Abbild gleich der Datei", kf.isFile() ? kf.toString() : kf + " fehlt");
            else info("Prüfstein 7", "kein Abbild (" + kf.getParentFile() + ")");
        }
        ende();
    }

    static void ende() {
        System.out.println(verstoesse == 0 ? String.format(Locale.GERMAN, "GueteCheck: %d Prüfsteine, alles in Ordnung", steine)
                : String.format(Locale.GERMAN, "GueteCheck: %d von %d Prüfsteinen verletzt", verstoesse, steine));
        System.exit(verstoesse == 0 ? 0 : 1);
    }
}
