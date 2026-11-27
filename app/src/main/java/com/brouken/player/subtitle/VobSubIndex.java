package com.brouken.player.subtitle;

import android.content.Context;
import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: a VobSub .idx file (palette, frame size, and per language a list of "timestamp, filepos" pointing into
 * the .sub file). The .idx of the loaded subtitle is copied into app storage next to a key derived from the .sub
 * URI, so the .sub can be played later without needing access to the .idx again.
 */
public final class VobSubIndex {

    private static final int MAX_IDX_BYTES = 8 * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("^id:\\s*([A-Za-z-]*)\\s*,\\s*index:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIMESTAMP = Pattern.compile(
            "^timestamp:\\s*(-?)(\\d+):(\\d+):(\\d+)[:.](\\d+)\\s*,\\s*filepos:\\s*([0-9A-Fa-f]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELAY = Pattern.compile("^delay:\\s*(-?)(\\d+):(\\d+):(\\d+)[:.](\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TIME_OFFSET = Pattern.compile("^time offset:\\s*(-?\\d+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LANGIDX = Pattern.compile("^langidx:\\s*(\\d+)", Pattern.CASE_INSENSITIVE);

    public static final class Stream {
        public final String language;
        public final int index; // sub-stream 0x20 + index
        public final List<long[]> entries = new ArrayList<>(); // {timeUs, filepos}

        Stream(String language, int index) {
            this.language = language;
            this.index = index;
        }
    }

    /** Header lines (size, palette, ...): VobsubParser's initialization data. */
    public final byte[] header;
    public final List<Stream> streams;
    public final int defaultStreamIndex;

    private VobSubIndex(byte[] header, List<Stream> streams, int defaultStreamIndex) {
        this.header = header;
        this.streams = streams;
        this.defaultStreamIndex = defaultStreamIndex;
    }

    public static VobSubIndex parse(byte[] data) throws IOException {
        String text = new String(data, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') text = text.substring(1);
        StringBuilder header = new StringBuilder();
        List<Stream> streams = new ArrayList<>();
        Stream current = null;
        long timeOffsetUs = 0;
        long delayUs = 0;
        int langIdx = 0;
        boolean sawPalette = false;
        for (String raw : text.split("\r?\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m;
            if ((m = ID.matcher(line)).find()) {
                current = new Stream(m.group(1).toLowerCase(Locale.US), Integer.parseInt(m.group(2)));
                streams.add(current);
                delayUs = 0;
            } else if ((m = TIMESTAMP.matcher(line)).find()) {
                if (current == null) continue;
                long t = timeUs(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5)) + timeOffsetUs + delayUs;
                current.entries.add(new long[]{Math.max(0, t), Long.parseLong(m.group(6), 16)});
            } else if ((m = DELAY.matcher(line)).find()) {
                delayUs += timeUs(m.group(1), m.group(2), m.group(3), m.group(4), m.group(5));
            } else if (current == null) {
                if ((m = TIME_OFFSET.matcher(line)).find()) {
                    timeOffsetUs = Long.parseLong(m.group(1)) * 1000;
                } else if ((m = LANGIDX.matcher(line)).find()) {
                    langIdx = Integer.parseInt(m.group(1));
                }
                if (line.toLowerCase(Locale.US).startsWith("palette:")) sawPalette = true;
                header.append(line).append('\n');
            }
        }
        // drop languages without subpictures
        List<Stream> nonEmpty = new ArrayList<>();
        for (Stream s : streams) {
            if (!s.entries.isEmpty()) nonEmpty.add(s);
        }
        if (!sawPalette || nonEmpty.isEmpty()) {
            throw new IOException("Not a VobSub index (no palette or no timestamps)");
        }
        return new VobSubIndex(header.toString().getBytes(StandardCharsets.UTF_8), nonEmpty, langIdx);
    }

    private static long timeUs(String sign, String h, String m, String s, String ms) {
        long v = ((Long.parseLong(h) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1_000_000L
                + Long.parseLong(ms) * 1000L;
        return "-".equals(sign) ? -v : v;
    }

    // ---- cached copy of the .idx, keyed by the .sub URI ----

    public static File cacheFile(Context context, Uri subUri) {
        return new File(new File(context.getFilesDir(), "vobsub"), sha1(subUri.toString()) + ".idx");
    }

    /** Copies (and validates) the .idx for the given .sub; older copies are removed. */
    public static void store(Context context, Uri idxUri, Uri subUri) throws IOException {
        byte[] data = readAll(context, idxUri);
        parse(data); // throws if invalid
        File file = cacheFile(context, subUri);
        File dir = file.getParentFile();
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(data);
        }
    }

    public static byte[] readAll(Context context, Uri uri) throws IOException {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("Cannot open " + uri);
            return readAll(in);
        }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int r;
        while ((r = in.read(buf)) > 0) {
            out.write(buf, 0, r);
            if (out.size() > MAX_IDX_BYTES) throw new IOException("VobSub index too large");
        }
        return out.toByteArray();
    }

    @Nullable
    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format(Locale.US, "%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }
}
