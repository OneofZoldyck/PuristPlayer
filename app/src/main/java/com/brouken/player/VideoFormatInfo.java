package com.brouken.player;

import android.media.MediaCodecInfo.CodecProfileLevel;
import android.media.MediaFormat;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;

import java.util.Locale;

/**
 * Purist: immutable snapshot of what the video decoder was actually configured with, plus the
 * classification used to pick the format badge. Built by {@link InspectingVideoRenderer}.
 */
public final class VideoFormatInfo {

    public enum Kind { DOLBY_VISION, HDR10_PLUS, HDR10, HDR, SDR }

    /** MIME the codec was configured with, e.g. video/dolby-vision or video/hevc (fallback). */
    public final String codecMime;
    public final String codecName;
    public final boolean hardwareAccelerated;
    /** Input track format (container level). */
    @Nullable public final Format trackFormat;
    /** Profile the codec was configured with (MediaFormat.KEY_PROFILE), or -1. */
    public final int configuredProfile;
    /** HDR10+ dynamic metadata seen in the bitstream (SEI / OBU / supplemental data) or in the decoder output. */
    public final boolean hdr10PlusSeen;
    /** Dolby Vision RPU NAL units seen in the bitstream. */
    public final boolean dvRpuSeen;
    /** Debug-only switch "force DV base layer" was active for this codec. */
    public final boolean forcedBaseLayer;
    /** True while the codec is alive (false after it was released). */
    public final boolean codecActive;

    public final Kind kind;
    /** True when the track is Dolby Vision but a non-DV codec decodes its base layer. */
    public final boolean dolbyVisionFallback;
    /** Dolby Vision profile label, e.g. "8.1", "5", "7"; null when not Dolby Vision. */
    @Nullable public final String dvProfileLabel;

    VideoFormatInfo(String codecMime, String codecName, boolean hardwareAccelerated, @Nullable Format trackFormat,
                    int configuredProfile, boolean hdr10PlusSeen, boolean dvRpuSeen, boolean forcedBaseLayer,
                    boolean codecActive) {
        this.codecMime = codecMime;
        this.codecName = codecName;
        this.hardwareAccelerated = hardwareAccelerated;
        this.trackFormat = trackFormat;
        this.configuredProfile = configuredProfile;
        this.hdr10PlusSeen = hdr10PlusSeen;
        this.dvRpuSeen = dvRpuSeen;
        this.forcedBaseLayer = forcedBaseLayer;
        this.codecActive = codecActive;

        boolean trackIsDv = isDolbyVisionTrack(trackFormat);
        boolean codecIsDv = MimeTypes.VIDEO_DOLBY_VISION.equals(codecMime);
        int transfer = baseLayerTransfer(trackFormat);

        if (codecIsDv) {
            kind = Kind.DOLBY_VISION;
            dolbyVisionFallback = false;
        } else {
            dolbyVisionFallback = trackIsDv;
            if (transfer == C.COLOR_TRANSFER_ST2084 || isPqProfile(codecMime, configuredProfile)) {
                kind = hdr10PlusSeen || isHdr10PlusProfile(codecMime, configuredProfile) ? Kind.HDR10_PLUS : Kind.HDR10;
            } else if (transfer == C.COLOR_TRANSFER_HLG) {
                kind = Kind.HDR;
            } else {
                kind = Kind.SDR;
            }
        }
        dvProfileLabel = (codecIsDv || trackIsDv) ? dolbyVisionProfileLabel(trackFormat, configuredProfile, transfer) : null;
    }

    VideoFormatInfo withCodecReleased() {
        return new VideoFormatInfo(codecMime, codecName, hardwareAccelerated, trackFormat, configuredProfile,
                hdr10PlusSeen, dvRpuSeen, forcedBaseLayer, false);
    }

    VideoFormatInfo withBitstreamFlags(boolean hdr10Plus, boolean dvRpu) {
        return new VideoFormatInfo(codecMime, codecName, hardwareAccelerated, trackFormat, configuredProfile,
                hdr10PlusSeen || hdr10Plus, dvRpuSeen || dvRpu, forcedBaseLayer, codecActive);
    }

    public String kindLabel() {
        switch (kind) {
            case DOLBY_VISION: return "Dolby Vision";
            case HDR10_PLUS: return "HDR10+";
            case HDR10: return "HDR10";
            case HDR: return baseLayerTransfer(trackFormat) == C.COLOR_TRANSFER_HLG ? "HLG" : "HDR";
            default: return "SDR";
        }
    }

    public int badgeDrawable() {
        switch (kind) {
            case DOLBY_VISION: return R.drawable.badge_dv;
            case HDR10_PLUS: return R.drawable.badge_hdr10plus;
            case HDR10: return R.drawable.badge_hdr10;
            case HDR: return R.drawable.badge_hdr;
            default: return R.drawable.badge_sdr;
        }
    }

    /**
     * One-line technical detail for the on-demand recheck, e.g.
     * "Dolby Vision · Profile 8.1 · c2.mtk.hevc.decoder · HDR active 2.25×".
     *
     * @param panelNits estimated current panel white luminance in nits, or -1 if unknown (then omitted)
     * @param hdrSdrRatio current display HDR/SDR ratio, or NaN if unavailable
     * @param framesAdvancing true/false when checked while playing, null when paused or unknown
     */
    public String detailLine(int panelNits, float hdrSdrRatio, @Nullable Boolean framesAdvancing) {
        StringBuilder sb = new StringBuilder(kindLabel());
        String dvProfileLabel = currentDvProfileLabel();
        if (kind == Kind.DOLBY_VISION) {
            if (dvProfileLabel != null) sb.append(" · Profile ").append(dvProfileLabel);
        } else if (dolbyVisionFallback) {
            String dv = dvProfileLabel != null ? "Dolby Vision P" + dvProfileLabel : "Dolby Vision";
            sb.append(forcedBaseLayer ? " · forced base layer (" + dv + ")" : " · fallback from " + dv);
        } else if (kind == Kind.SDR) {
            String space = colorSpaceLabel(trackFormat);
            if (space != null) sb.append(" · ").append(space);
        }
        if (panelNits > 0) {
            sb.append(" · ~").append(panelNits).append(" nits");
        }
        if (kind == Kind.DOLBY_VISION && hdr10PlusSeen) {
            sb.append(" · HDR10+ also present");
        }
        sb.append(" · ").append(codecName);
        if (!hardwareAccelerated) sb.append(" (software)");
        if (kind != Kind.SDR && !Float.isNaN(hdrSdrRatio)) {
            if (hdrSdrRatio > 1.01f) {
                sb.append(String.format(Locale.US, " · HDR active %.2f×", hdrSdrRatio));
            } else {
                sb.append(" · display HDR boost inactive");
            }
        }
        if (Boolean.FALSE.equals(framesAdvancing)) {
            sb.append(" · no frames rendering");
        }
        if (!codecActive) {
            sb.append(" · decoder released");
        }
        return sb.toString();
    }

    // ---- helpers ----

    static boolean isDolbyVisionTrack(@Nullable Format f) {
        if (f == null) return false;
        if (MimeTypes.VIDEO_DOLBY_VISION.equals(f.sampleMimeType)) return true;
        String codecs = f.codecs;
        if (codecs == null) return false;
        for (String c : codecs.split(",")) {
            String t = c.trim().toLowerCase(Locale.US);
            if (t.startsWith("dvhe") || t.startsWith("dvh1") || t.startsWith("dvav") || t.startsWith("dva1")
                    || t.startsWith("dav1")) {
                return true;
            }
        }
        return false;
    }

    static int baseLayerTransfer(@Nullable Format f) {
        if (f == null) return Format.NO_VALUE;
        ColorInfo ci = f.colorInfo;
        return ci != null ? ci.colorTransfer : Format.NO_VALUE;
    }

    @Nullable
    private static String colorSpaceLabel(@Nullable Format f) {
        if (f == null || f.colorInfo == null) return null;
        switch (f.colorInfo.colorSpace) {
            case C.COLOR_SPACE_BT709: return "BT.709";
            case C.COLOR_SPACE_BT601: return "BT.601";
            case C.COLOR_SPACE_BT2020: return "BT.2020";
            default: return null;
        }
    }

    private static boolean isPqProfile(String mime, int profile) {
        if (profile <= 0) return false;
        if (MimeTypes.VIDEO_H265.equals(mime)) {
            return profile == CodecProfileLevel.HEVCProfileMain10HDR10
                    || profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus;
        }
        if (MimeTypes.VIDEO_VP9.equals(mime)) {
            return profile == CodecProfileLevel.VP9Profile2HDR || profile == CodecProfileLevel.VP9Profile3HDR
                    || profile == CodecProfileLevel.VP9Profile2HDR10Plus || profile == CodecProfileLevel.VP9Profile3HDR10Plus;
        }
        if (MimeTypes.VIDEO_AV1.equals(mime)) {
            return profile == CodecProfileLevel.AV1ProfileMain10HDR10
                    || profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus;
        }
        return false;
    }

    private static boolean isHdr10PlusProfile(String mime, int profile) {
        if (profile <= 0) return false;
        if (MimeTypes.VIDEO_H265.equals(mime)) return profile == CodecProfileLevel.HEVCProfileMain10HDR10Plus;
        if (MimeTypes.VIDEO_VP9.equals(mime)) {
            return profile == CodecProfileLevel.VP9Profile2HDR10Plus || profile == CodecProfileLevel.VP9Profile3HDR10Plus;
        }
        if (MimeTypes.VIDEO_AV1.equals(mime)) return profile == CodecProfileLevel.AV1ProfileMain10HDR10Plus;
        return false;
    }

    /**
     * The profile label as it is now: a profile 7 conversion only learns whether the enhancement layer was FEL or
     * MEL once the first frames have been read, which is usually after the codec started.
     */
    @Nullable
    private String currentDvProfileLabel() {
        if (dvProfileLabel == null || com.brouken.player.dovi.DolbyVisionP7.conversionOf(trackFormat) == null) {
            return dvProfileLabel;
        }
        return dolbyVisionProfileLabel(trackFormat, configuredProfile, baseLayerTransfer(trackFormat));
    }

    /** "8.1" etc. Profile from the codecs string (dvhe.08.06), else from the configured MediaFormat profile. */
    @Nullable
    private static String dolbyVisionProfileLabel(@Nullable Format f, int configuredProfile, int transfer) {
        // Purist: profile 7 converted to 8.1 while reading (enhancement layer dropped)
        com.brouken.player.dovi.DolbyVisionP7.Conversion p7 = com.brouken.player.dovi.DolbyVisionP7.conversionOf(f);
        if (p7 != null) {
            String target = transfer == C.COLOR_TRANSFER_ST2084 || transfer == Format.NO_VALUE ? "8.1" : "8";
            return "7" + (p7.elType != null ? " " + p7.elType : "") + " → " + target;
        }
        int profile = -1;
        if (f != null && f.codecs != null) {
            for (String c : f.codecs.split(",")) {
                String[] parts = c.trim().split("\\.");
                String fourcc = parts[0].toLowerCase(Locale.US);
                if (parts.length >= 2 && (fourcc.startsWith("dv") || fourcc.startsWith("dav1"))) {
                    try {
                        profile = Integer.parseInt(parts[1]);
                        break;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        if (profile < 0) profile = dvProfileFromMediaCodecProfile(configuredProfile);
        if (profile < 0) return null;
        if (profile == 8) {
            if (transfer == C.COLOR_TRANSFER_ST2084) return "8.1";
            if (transfer == C.COLOR_TRANSFER_HLG) return "8.4";
            if (transfer == C.COLOR_TRANSFER_SDR || transfer == C.COLOR_TRANSFER_GAMMA_2_2) return "8.2";
            return "8";
        }
        return String.valueOf(profile);
    }

    private static int dvProfileFromMediaCodecProfile(int p) {
        switch (p) {
            case CodecProfileLevel.DolbyVisionProfileDvavPer: return 0;
            case CodecProfileLevel.DolbyVisionProfileDvavPen: return 1;
            case CodecProfileLevel.DolbyVisionProfileDvheDer: return 2;
            case CodecProfileLevel.DolbyVisionProfileDvheDen: return 3;
            case CodecProfileLevel.DolbyVisionProfileDvheDtr: return 4;
            case CodecProfileLevel.DolbyVisionProfileDvheStn: return 5;
            case CodecProfileLevel.DolbyVisionProfileDvheDth: return 6;
            case CodecProfileLevel.DolbyVisionProfileDvheDtb: return 7;
            case CodecProfileLevel.DolbyVisionProfileDvheSt: return 8;
            case CodecProfileLevel.DolbyVisionProfileDvavSe: return 9;
            case CodecProfileLevel.DolbyVisionProfileDvav110: return 10;
            default: return -1;
        }
    }

    static int readProfile(@Nullable MediaFormat mf) {
        if (mf == null || !mf.containsKey(MediaFormat.KEY_PROFILE)) return -1;
        try {
            return mf.getInteger(MediaFormat.KEY_PROFILE);
        } catch (Exception e) {
            return -1;
        }
    }
}
