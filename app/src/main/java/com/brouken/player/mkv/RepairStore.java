package com.brouken.player.mkv;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.zip.CRC32;

/**
 * Purist: keeps rebuilt indexes of incomplete Matroska files in the app cache, so a file that was checked once
 * opens straight away. An entry is used only while the file is unchanged: same length, same first 64 KiB, and the
 * bytes just past the downloaded data still as they were (a resumed download changes them).
 */
final class RepairStore {

    private static final int VERSION = 1;
    private static final int HEAD_BYTES = 64 * 1024;
    private static final int MAX_ENTRIES = 16;

    private RepairStore() {
    }

    /** CRC-32 of the first 64 KiB, or -1 when the file is not Matroska (EBML magic). */
    static long headCrc(MatroskaRepair.Source s) throws IOException {
        byte[] head = new byte[(int) Math.min(HEAD_BYTES, s.length())];
        int n = 0;
        while (n < head.length) {
            int r = s.read(n, head, n, head.length - n);
            if (r <= 0) break;
            n += r;
        }
        if (n < 4 || (head[0] & 0xFF) != 0x1A || (head[1] & 0xFF) != 0x45 || (head[2] & 0xFF) != 0xDF
                || (head[3] & 0xFF) != 0xA3) {
            return -1;
        }
        CRC32 crc = new CRC32();
        crc.update(head, 0, n);
        return crc.getValue();
    }

    @Nullable
    static MatroskaRepair.Result load(Context context, String key, MatroskaRepair.Source s, long headCrc) {
        File f = file(context, key);
        if (!f.isFile()) return null;
        try (DataInputStream in = new DataInputStream(new FileInputStream(f))) {
            if (in.readInt() != VERSION || in.readLong() != s.length() || in.readLong() != headCrc) return stale(f);
            long probePosition = in.readLong();
            byte[] probe = bytes(in);
            if (probePosition >= 0) {
                byte[] now = new byte[probe.length];
                int n = s.read(probePosition, now, 0, now.length);
                if (n != probe.length || !Arrays.equals(now, probe)) return stale(f);
            }
            long cuesPosition = in.readLong();
            byte[] cues = bytes(in);
            long dataEnd = in.readLong();
            long durationPosition = in.readLong();
            byte[] durationBytes = bytes(in);
            long originalMs = in.readLong();
            long playableMs = in.readLong();
            int cuePoints = in.readInt();
            f.setLastModified(System.currentTimeMillis());
            return new MatroskaRepair.Result(cuesPosition, cues, dataEnd, durationPosition,
                    durationBytes.length == 0 ? null : durationBytes, originalMs, playableMs, cuePoints, s.length(),
                    probePosition, probe);
        } catch (IOException e) {
            return stale(f);
        }
    }

    static void save(Context context, String key, MatroskaRepair.Result r, long headCrc) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) return;
        File f = file(context, key);
        File tmp = new File(dir, f.getName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new FileOutputStream(tmp))) {
            out.writeInt(VERSION);
            out.writeLong(r.fileLength);
            out.writeLong(headCrc);
            out.writeLong(r.probePosition);
            bytes(out, r.probe);
            out.writeLong(r.cuesPosition);
            bytes(out, r.cues);
            out.writeLong(r.dataEnd);
            out.writeLong(r.durationPosition);
            bytes(out, r.durationBytes);
            out.writeLong(r.originalDurationMs);
            out.writeLong(r.playableDurationMs);
            out.writeInt(r.cuePoints);
        } catch (IOException e) {
            tmp.delete();
            return;
        }
        if (!tmp.renameTo(f)) tmp.delete();
        File[] entries = dir.listFiles();
        if (entries != null && entries.length > MAX_ENTRIES) {
            Arrays.sort(entries, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (int i = MAX_ENTRIES; i < entries.length; i++) entries[i].delete();
        }
    }

    /** Drops the entry for {@code key} (the file is complete or changed). */
    static void remove(Context context, String key) {
        file(context, key).delete();
    }

    @Nullable
    private static MatroskaRepair.Result stale(File f) {
        f.delete();
        return null;
    }

    private static byte[] bytes(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > (64 << 20)) throw new IOException("bad entry");
        byte[] b = new byte[n];
        in.readFully(b);
        return b;
    }

    private static void bytes(DataOutputStream out, @Nullable byte[] b) throws IOException {
        out.writeInt(b == null ? 0 : b.length);
        if (b != null) out.write(b);
    }

    private static File dir(Context context) {
        return new File(context.getCacheDir(), "mkvrepair");
    }

    private static File file(Context context, String key) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return new File(dir(context), sb + ".idx");
        } catch (Exception e) {
            return new File(dir(context), Integer.toHexString(key.hashCode()) + ".idx");
        }
    }
}
