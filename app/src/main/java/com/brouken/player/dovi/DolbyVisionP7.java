package com.brouken.player.dovi;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: Dolby Vision profile 7 (Blu-ray, dual layer) to profile 8.1 playback.
 *
 * Phones decode single-layer Dolby Vision (profiles 5 and 8) but not profile 7, so such files used to fall
 * back to their HDR10 base layer. Whenever a profile 7 track is found (and the device has a profile 8 Dolby
 * Vision decoder) it is converted automatically while it is read: the enhancement layer is dropped and every
 * RPU is rewritten to profile 8.1 ({@link DoviRpu}, dovi_tool mode 2, as Kodi does), so the Dolby Vision
 * decoder plays the base layer with the full dynamic metadata.
 */
public final class DolbyVisionP7 {

    static final String TAG = "PuristDV7";

    private static final Pattern P7_CODECS = Pattern.compile("(?i)(dvhe|dvh1)\\.07\\.(\\d+)");

    /** Attached to converted tracks as {@link Format#customData}; read by Recheck. */
    public static final class Conversion {
        public final String originalCodecs;
        public volatile DoviRpu.ElType elType;
        public final AtomicLong convertedRpus = new AtomicLong();
        public final AtomicLong unconvertedRpus = new AtomicLong();
        public final AtomicLong droppedElNals = new AtomicLong();

        Conversion(String originalCodecs) {
            this.originalCodecs = originalCodecs;
        }
    }

    private static Boolean profile8Decoder;

    private DolbyVisionP7() {
    }

    @Nullable
    public static Conversion conversionOf(@Nullable Format format) {
        return format != null && format.customData instanceof Conversion ? (Conversion) format.customData : null;
    }

    static boolean isProfile7(Format f) {
        return MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType) && f.codecs != null
                && P7_CODECS.matcher(f.codecs.trim()).matches() && f.drmInitData == null;
    }

    /**
     * Plain HEVC that may carry profile 7 in its bitstream (files muxed without the Dolby Vision configuration):
     * HDR10 or unknown transfer, not encrypted. Whether it does is decided from its first frame.
     */
    static boolean mayCarryProfile7(Format f) {
        if (!MimeTypes.VIDEO_H265.equals(f.sampleMimeType) || f.drmInitData != null) return false;
        int transfer = f.colorInfo == null ? Format.NO_VALUE : f.colorInfo.colorTransfer;
        return transfer == C.COLOR_TRANSFER_ST2084 || transfer == Format.NO_VALUE;
    }

    /**
     * dvhe.07.LL becomes dvhe.08.LL (a plain HEVC track becomes dvhe.08 with the level of its size and frame
     * rate); enhancement layer NAL units are removed from the codec config.
     */
    static Format toProfile81(Format f, Conversion conversion) {
        Matcher m = f.codecs == null ? null : P7_CODECS.matcher(f.codecs.trim());
        String codecs = m != null && m.matches() ? m.group(1) + ".08." + m.group(2) : "dvhe.08." + level(f);
        List<byte[]> init = new ArrayList<>(f.initializationData.size());
        for (byte[] csd : f.initializationData) init.add(DolbyVisionP7Rewriter.withoutEnhancementLayer(csd));
        return f.buildUpon().setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION).setCodecs(codecs)
                .setInitializationData(init).setCustomData(conversion).build();
    }

    /** Dolby Vision level (two digits) for the picture size and frame rate: the smallest level that covers both. */
    static String level(Format f) {
        int width = f.width > 0 ? Math.max(f.width, f.height) : 3840;
        int height = f.height > 0 ? Math.min(f.width, f.height) : 2160;
        double fps = f.frameRate > 0 ? f.frameRate : 24;
        double pixelRate = (double) width * height * fps;
        long[] maxPixelRate = {22_118_400L, 27_648_000L, 49_766_400L, 62_208_000L, 124_416_000L, 199_065_600L,
                248_832_000L, 398_131_200L, 497_664_000L, 995_328_000L, 995_328_000L, 1_990_656_000L, 3_981_312_000L};
        int[] maxWidth = {1280, 1280, 1920, 1920, 1920, 3840, 3840, 3840, 3840, 3840, 7680, 7680, 7680};
        for (int i = 0; i < maxPixelRate.length; i++) {
            if (width <= maxWidth[i] && pixelRate <= maxPixelRate[i] * 1.001) return String.format(Locale.ROOT, "%02d", i + 1);
        }
        return "13";
    }

    /** True when a Dolby Vision decoder for profile 8 exists (otherwise the file is left as it is). */
    public static synchronized boolean deviceCanDecodeProfile8() {
        if (profile8Decoder == null) {
            boolean found = false;
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (info.isEncoder()) continue;
                    for (String type : info.getSupportedTypes()) {
                        if (!MimeTypes.VIDEO_DOLBY_VISION.equalsIgnoreCase(type)) continue;
                        for (MediaCodecInfo.CodecProfileLevel pl : info.getCapabilitiesForType(type).profileLevels) {
                            if (pl.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheSt) found = true;
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "codec query failed", e);
            }
            profile8Decoder = found;
            Log.i(TAG, "Dolby Vision profile 8 decoder: " + found);
        }
        return profile8Decoder;
    }
}
