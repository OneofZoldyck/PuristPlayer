package com.brouken.player;

import android.media.MediaFormat;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.FormatHolder;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Purist: a {@link MediaCodecVideoRenderer} that reports what the codec was actually configured with
 * (Dolby Vision vs. its HEVC/AVC base-layer fallback, HDR10+ metadata, …).
 *
 * <p>All overridden hooks run on the playback thread; results are posted to the main thread.
 */
public class InspectingVideoRenderer extends MediaCodecVideoRenderer {

    public interface Callback {
        /** Called on the main thread whenever the decoder snapshot changes. */
        void onVideoFormatInfo(VideoFormatInfo info);
    }

    /** Number of input buffers after (re)configuration to scan for HDR10+ / DV RPU markers. */
    private static final int SCAN_BUFFER_LIMIT = 120;
    private static final int SCAN_NONE = 0, SCAN_HEVC = 1, SCAN_AV1 = 2;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final boolean forcedBaseLayer;
    private volatile Callback callback;

    // Playback-thread state
    @Nullable private VideoFormatInfo current;
    private int scanMode = SCAN_NONE;
    private int buffersScanned;
    private boolean scanHdr10Plus;
    private boolean scanDvRpu;

    public InspectingVideoRenderer(Builder builder, Callback callback, boolean forcedBaseLayer) {
        super(builder);
        this.callback = callback;
        this.forcedBaseLayer = forcedBaseLayer;
    }

    /** Stop delivering callbacks (the activity rebuilds the player per video). */
    public void clearCallback() {
        callback = null;
    }

    @Override
    protected void onCodecInitialized(String name, MediaCodecAdapter.Configuration configuration,
                                      long initializedTimestampMs, long initializationDurationMs) {
        super.onCodecInitialized(name, configuration, initializedTimestampMs, initializationDurationMs);
        String codecMime = configuration.codecInfo.mimeType;
        MediaFormat mf = configuration.mediaFormat;
        if (mf != null && mf.containsKey(MediaFormat.KEY_MIME)) {
            String configured = mf.getString(MediaFormat.KEY_MIME);
            if (configured != null) codecMime = configured;
        }
        Format trackFormat = configuration.format;
        resetScan(trackFormat);
        current = new VideoFormatInfo(codecMime, name, configuration.codecInfo.hardwareAccelerated, trackFormat,
                VideoFormatInfo.readProfile(mf), false, false, forcedBaseLayer, true);
        publish(current);
    }

    @Nullable
    @Override
    protected DecoderReuseEvaluation onInputFormatChanged(FormatHolder formatHolder) throws ExoPlaybackException {
        DecoderReuseEvaluation evaluation = super.onInputFormatChanged(formatHolder);
        // Codec kept across a format change (e.g. adaptive switch): refresh the track format.
        if (current != null && current.codecActive && formatHolder.format != null && formatHolder.format != current.trackFormat) {
            resetScan(formatHolder.format);
            current = new VideoFormatInfo(current.codecMime, current.codecName, current.hardwareAccelerated,
                    formatHolder.format, current.configuredProfile, false, false, forcedBaseLayer, true);
            publish(current);
        }
        return evaluation;
    }

    @Override
    protected void onQueueInputBuffer(DecoderInputBuffer buffer) throws ExoPlaybackException {
        // Scan BEFORE super: the base class strips T.35 SEI from the bitstream on some devices.
        if (current != null && buffersScanned < SCAN_BUFFER_LIMIT && !buffer.isEndOfStream()) {
            buffersScanned++;
            try {
                scan(buffer);
            } catch (RuntimeException ignored) {
                // Never let inspection break playback.
            }
        }
        super.onQueueInputBuffer(buffer);
    }

    @Override
    protected void onOutputFormatChanged(Format format, @Nullable MediaFormat mediaFormat) {
        super.onOutputFormatChanged(format, mediaFormat);
        // Secondary signal: decoders that parse HDR10+ report it in the output format.
        if (mediaFormat != null && mediaFormat.containsKey(MediaFormat.KEY_HDR10_PLUS_INFO)) {
            markFlags(true, false);
        }
    }

    @Override
    protected void onCodecReleased(String name) {
        super.onCodecReleased(name);
        if (current != null) {
            current = current.withCodecReleased();
            publish(current);
        }
    }

    // ---- bitstream scanning ----

    private void resetScan(@Nullable Format f) {
        buffersScanned = 0;
        scanHdr10Plus = false;
        scanDvRpu = false;
        scanMode = SCAN_NONE;
        if (f == null) return;
        String mime = f.sampleMimeType;
        String codecs = f.codecs != null ? f.codecs.toLowerCase(Locale.US) : "";
        if (MimeTypes.VIDEO_H265.equals(mime)
                || (MimeTypes.VIDEO_DOLBY_VISION.equals(mime) && (codecs.contains("dvhe") || codecs.contains("dvh1")))) {
            scanMode = SCAN_HEVC;
        } else if (MimeTypes.VIDEO_AV1.equals(mime)
                || (MimeTypes.VIDEO_DOLBY_VISION.equals(mime) && codecs.contains("dav1"))) {
            scanMode = SCAN_AV1;
        }
    }

    private void scan(DecoderInputBuffer buffer) {
        boolean hdr10Plus = false, dvRpu = false;
        // VP9 (and others) carry HDR10+ as supplemental data (an ITU-T T.35 message).
        if (buffer.hasSupplementalData() && buffer.supplementalData != null) {
            ByteBuffer s = buffer.supplementalData;
            if (isHdr10PlusT35(s, s.position(), s.limit())) hdr10Plus = true;
        }
        ByteBuffer data = buffer.data;
        if (data != null) {
            if (scanMode == SCAN_HEVC) {
                int r = scanHevc(data);
                hdr10Plus |= (r & 1) != 0;
                dvRpu |= (r & 2) != 0;
            } else if (scanMode == SCAN_AV1) {
                hdr10Plus |= scanAv1(data);
            }
        }
        if ((hdr10Plus && !scanHdr10Plus) || (dvRpu && !scanDvRpu)) {
            markFlags(hdr10Plus, dvRpu);
        }
    }

    private void markFlags(boolean hdr10Plus, boolean dvRpu) {
        if (current == null) return;
        boolean changed = (hdr10Plus && !current.hdr10PlusSeen) || (dvRpu && !current.dvRpuSeen);
        scanHdr10Plus |= hdr10Plus;
        scanDvRpu |= dvRpu;
        if (changed) {
            current = current.withBitstreamFlags(hdr10Plus, dvRpu);
            publish(current);
        }
    }

    /**
     * Scans Annex-B HEVC data (start codes) for HDR10+ SEI (bit 1) and Dolby Vision RPU NAL units (bit 2).
     * Uses absolute reads only; the buffer position is not modified.
     */
    private static int scanHevc(ByteBuffer d) {
        int start = d.position(), limit = d.limit();
        // Prefix SEI (HDR10+) sits before the slice data at the head of an access unit; the Dolby Vision
        // RPU NAL is appended at its tail. Scanning both 8 KB windows avoids walking multi-MB 4K frames.
        final int window = 8 * 1024;
        if (limit - start <= 2 * window) {
            return scanHevcRange(d, start, limit);
        }
        return scanHevcRange(d, start, start + window) | scanHevcRange(d, limit - window, limit);
    }

    private static int scanHevcRange(ByteBuffer d, int start, int limit) {
        int result = 0;
        for (int i = start; i + 3 < limit; i++) {
            if (d.get(i) != 0 || d.get(i + 1) != 0 || d.get(i + 2) != 1) continue;
            int nal = i + 3;
            int type = (d.get(nal) >> 1) & 0x3F;
            if (type == 62) {
                result |= 2;
            } else if ((type == 39 || type == 40) && (result & 1) == 0) {
                if (seiHasHdr10Plus(d, nal + 2, limit)) result |= 1;
            }
            i = nal;
            if (result == 3) break;
        }
        return result;
    }

    /** Parses SEI messages (emulation-prevention bytes removed) looking for an HDR10+ T.35 payload. */
    private static boolean seiHasHdr10Plus(ByteBuffer d, int from, int limit) {
        byte[] rbsp = new byte[512];
        int n = 0, zeros = 0;
        for (int i = from; i < limit && n < rbsp.length; i++) {
            int b = d.get(i) & 0xFF;
            if (zeros >= 2 && b == 0x03) { // emulation prevention byte
                zeros = 0;
                continue;
            }
            if (zeros >= 2 && b <= 0x01) break; // next start code
            zeros = (b == 0) ? zeros + 1 : 0;
            rbsp[n++] = (byte) b;
        }
        int p = 0;
        while (p < n) {
            int payloadType = 0, payloadSize = 0;
            while (p < n && (rbsp[p] & 0xFF) == 0xFF) { payloadType += 255; p++; }
            if (p >= n) return false;
            payloadType += rbsp[p++] & 0xFF;
            while (p < n && (rbsp[p] & 0xFF) == 0xFF) { payloadSize += 255; p++; }
            if (p >= n) return false;
            payloadSize += rbsp[p++] & 0xFF;
            if (payloadType == 4 && p + 6 <= n && isHdr10PlusT35(rbsp, p)) return true;
            if (payloadType == 0x80 && payloadSize == 0) return false; // rbsp trailing bits
            p += payloadSize;
        }
        return false;
    }

    /** Scans AV1 low-overhead OBUs for an ITU-T T.35 metadata OBU carrying HDR10+. */
    private static boolean scanAv1(ByteBuffer d) {
        int p = d.position(), limit = d.limit();
        while (p < limit) {
            int header = d.get(p) & 0xFF;
            int obuType = (header >> 3) & 0x0F;
            boolean ext = ((header >> 2) & 1) != 0;
            boolean hasSize = ((header >> 1) & 1) != 0;
            p += ext ? 2 : 1;
            long size;
            if (hasSize) {
                long[] leb = leb128(d, p, limit);
                if (leb == null) return false;
                size = leb[0];
                p = (int) leb[1];
            } else {
                size = limit - p;
            }
            if (obuType == 5 && size > 0) { // OBU_METADATA
                long[] mt = leb128(d, p, limit);
                if (mt != null && mt[0] == 4 && isHdr10PlusT35(d, (int) mt[1], limit)) return true; // METADATA_TYPE_ITUT_T35
            }
            if (size < 0 || p + size > limit) return false;
            p += (int) size;
        }
        return false;
    }

    @Nullable
    private static long[] leb128(ByteBuffer d, int p, int limit) {
        long value = 0;
        for (int i = 0; i < 8 && p < limit; i++) {
            int b = d.get(p++) & 0xFF;
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) return new long[]{value, p};
        }
        return null;
    }

    /** ITU-T T.35 header of an HDR10+ (SMPTE ST 2094-40) message: B5 00 3C 00 01 04. */
    private static boolean isHdr10PlusT35(ByteBuffer d, int p, int limit) {
        if (p + 6 > limit) return false;
        return (d.get(p) & 0xFF) == 0xB5 && d.get(p + 1) == 0x00 && (d.get(p + 2) & 0xFF) == 0x3C
                && d.get(p + 3) == 0x00 && d.get(p + 4) == 0x01 && d.get(p + 5) == 0x04;
    }

    private static boolean isHdr10PlusT35(byte[] b, int p) {
        return (b[p] & 0xFF) == 0xB5 && b[p + 1] == 0x00 && (b[p + 2] & 0xFF) == 0x3C
                && b[p + 3] == 0x00 && b[p + 4] == 0x01 && b[p + 5] == 0x04;
    }

    private void publish(VideoFormatInfo info) {
        final Callback cb = callback;
        if (cb == null) return;
        mainHandler.post(() -> {
            Callback c = callback;
            if (c != null) c.onVideoFormatInfo(info);
        });
    }
}
