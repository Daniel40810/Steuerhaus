package com.dan.steuerhaus.chronik;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Arrays;

/**
 * Welche Zeit ein Archiv abdeckt (seit DB2, Werkbuch HalligHooge 169): Beginn aus {@code lauf.txt} ({@code start = …}),
 * erster und letzter Stand aus {@code zeiten.f64} (Sekunden seit dem Beginn, float64 Little Endian). Ein Lauf liegt im
 * Ordner des Archivs selbst oder, je Gitter, in einem Unterordner (G2, G3 …); genommen wird der erste. Liest nur zwei Zahlen
 * und eine Zeile, öffnet keine Felder. Nur java.*.
 */
public final class Abdeckung {
    private Abdeckung() { }

    /** Erster und letzter Stand eines Archivs oder null, wenn es keinen Lauf findet. */
    public static Instant[] lies(File archiv) {
        if (archiv == null || !archiv.isDirectory()) return null;
        File lauf = lauf(archiv);
        if (lauf == null) {
            File[] u = archiv.listFiles(File::isDirectory);
            if (u == null) return null;
            Arrays.sort(u);
            for (File d : u) if ((lauf = lauf(d)) != null) break;
        }
        if (lauf == null) return null;
        try {
            Instant start = null;
            for (String z : Files.readAllLines(new File(lauf, "lauf.txt").toPath(), StandardCharsets.UTF_8)) {
                int p = z.indexOf(" = ");
                if (p > 0 && z.substring(0, p).strip().equals("start")) start = Instant.parse(z.substring(p + 3).strip());
            }
            if (start == null) return null;
            try (RandomAccessFile f = new RandomAccessFile(new File(lauf, "zeiten.f64"), "r")) {
                long n = f.length() / 8;
                if (n == 0) return null;
                byte[] b = new byte[8];
                f.readFully(b);
                double erst = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getDouble();
                f.seek((n - 1) * 8);
                f.readFully(b);
                double letzt = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getDouble();
                return new Instant[] {start.plusMillis(Math.round(erst * 1000)), start.plusMillis(Math.round(letzt * 1000))};
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static File lauf(File d) {
        return new File(d, "lauf.txt").isFile() && new File(d, "zeiten.f64").isFile() ? d : null;
    }
}
