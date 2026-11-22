package com.dan.steuerhaus.ton;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/** Seit X5: eine WAV-Datei, 16 Bit, Stereo; die Größen im Kopf schreibt {@link #close}. */
public final class WavDatei implements AutoCloseable {
    private final RandomAccessFile f;
    private final int rate;
    private long frames;

    public WavDatei(File datei, int rate) throws IOException {
        if (datei.getParentFile() != null) datei.getParentFile().mkdirs();
        f = new RandomAccessFile(datei, "rw");
        f.setLength(0);
        this.rate = rate;
        f.write(new byte[44]);
    }

    /** Schreibt n Abtastwerte je Kanal ab {@code ab}. */
    public void schreibe(float[] l, float[] r, int ab, int n) throws IOException {
        byte[] b = new byte[4 * n];
        for (int i = 0; i < n; i++) {
            int a = Math.round(Math.max(-1, Math.min(1, l[ab + i])) * 32767), c = Math.round(Math.max(-1, Math.min(1, r[ab + i])) * 32767);
            b[4 * i] = (byte) a;
            b[4 * i + 1] = (byte) (a >> 8);
            b[4 * i + 2] = (byte) c;
            b[4 * i + 3] = (byte) (c >> 8);
        }
        f.write(b);
        frames += n;
    }

    public long frames() { return frames; }

    @Override public void close() throws IOException {
        long daten = frames * 4;
        f.seek(0);
        f.writeBytes("RIFF");
        le32(36 + daten);
        f.writeBytes("WAVEfmt ");
        le32(16);
        le16(1);
        le16(2);
        le32(rate);
        le32(rate * 4L);
        le16(4);
        le16(16);
        f.writeBytes("data");
        le32(daten);
        f.close();
    }

    private void le32(long v) throws IOException {
        f.write((int) v & 0xFF);
        f.write((int) (v >> 8) & 0xFF);
        f.write((int) (v >> 16) & 0xFF);
        f.write((int) (v >> 24) & 0xFF);
    }

    private void le16(int v) throws IOException {
        f.write(v & 0xFF);
        f.write((v >> 8) & 0xFF);
    }
}
