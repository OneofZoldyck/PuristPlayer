package com.brouken.player.dovi;

import java.util.Arrays;

/**
 * Purist: rewrites one HEVC access unit (Annex B, as Media3's Matroska and MP4 extractors deliver it):
 * enhancement layer NAL units (type 63) are dropped, Dolby Vision RPUs (type 62) are converted to profile
 * 8.1, everything else is copied. An RPU that cannot be converted is kept as it is (Kodi does the same).
 */
final class DolbyVisionP7Rewriter {

    private static final int NAL_UNSPEC62_RPU = 62;
    private static final int NAL_UNSPEC63_EL = 63;

    private final DolbyVisionP7.Conversion conversion;
    private final DoviRpu.ElType[] elType = new DoviRpu.ElType[1];
    byte[] out = new byte[1 << 20];
    int outLength;

    DolbyVisionP7Rewriter(DolbyVisionP7.Conversion conversion) {
        this.conversion = conversion;
    }

    /** @return false when no start code was found (the sample is then passed on untouched) */
    boolean rewrite(byte[] in, int offset, int length) {
        outLength = 0;
        int end = offset + length;
        int start = findStartCode(in, offset, end);
        if (start >= end) return false;
        int nalStart = start + 3;
        while (nalStart < end) {
            int next = findStartCode(in, nalStart, end);
            int nalEnd = next;
            while (nalEnd > nalStart && in[nalEnd - 1] == 0) nalEnd--; // trailing zeros / 4-byte start code
            if (nalEnd > nalStart) {
                int type = (in[nalStart] >> 1) & 0x3F;
                if (type == NAL_UNSPEC63_EL) {
                    conversion.droppedElNals.incrementAndGet();
                } else if (type == NAL_UNSPEC62_RPU) {
                    byte[] rpu = DoviRpu.convertProfile7To81(in, nalStart, nalEnd - nalStart, elType);
                    if (rpu != null) {
                        conversion.convertedRpus.incrementAndGet();
                        if (conversion.elType == null) conversion.elType = elType[0];
                        append(rpu, 0, rpu.length);
                    } else {
                        conversion.unconvertedRpus.incrementAndGet();
                        append(in, nalStart, nalEnd - nalStart);
                    }
                } else {
                    append(in, nalStart, nalEnd - nalStart);
                }
            }
            nalStart = next + 3;
        }
        return true;
    }

    private void append(byte[] src, int offset, int length) {
        int needed = outLength + 4 + length;
        if (needed > out.length) out = Arrays.copyOf(out, Math.max(needed, out.length * 2));
        out[outLength] = 0;
        out[outLength + 1] = 0;
        out[outLength + 2] = 0;
        out[outLength + 3] = 1;
        System.arraycopy(src, offset, out, outLength + 4, length);
        outLength = needed;
    }

    static final int FOUND_PROFILE7_RPU = 1;
    static final int FOUND_ENHANCEMENT_LAYER = 2;

    /** What one access unit carries: a profile 7 RPU and/or enhancement layer NAL units (flags above). */
    static int probe(byte[] in, int offset, int length) {
        int found = 0;
        int end = offset + length;
        int start = findStartCode(in, offset, end);
        int nalStart = start + 3;
        while (start < end && nalStart < end) {
            int next = findStartCode(in, nalStart, end);
            int nalEnd = next;
            while (nalEnd > nalStart && in[nalEnd - 1] == 0) nalEnd--;
            if (nalEnd > nalStart) {
                int type = (in[nalStart] >> 1) & 0x3F;
                if (type == NAL_UNSPEC63_EL) {
                    found |= FOUND_ENHANCEMENT_LAYER;
                } else if (type == NAL_UNSPEC62_RPU && (found & FOUND_PROFILE7_RPU) == 0) {
                    try {
                        if (DoviRpu.parseUnspec62Nalu(in, nalStart, nalEnd - nalStart).getDoviProfile() == 7) {
                            found |= FOUND_PROFILE7_RPU;
                        }
                    } catch (DoviRpu.DoviException | RuntimeException ignored) {
                        // not a valid RPU
                    }
                }
            }
            nalStart = next + 3;
        }
        return found;
    }

    /** Index of the next 00 00 01, or {@code end}. */
    static int findStartCode(byte[] d, int from, int end) {
        int i = from;
        while (i + 2 < end) {
            int b2 = d[i + 2] & 0xFF;
            if (b2 > 1) {
                i += 3;
            } else if (b2 == 1 && d[i + 1] == 0 && d[i] == 0) {
                return i;
            } else {
                i++;
            }
        }
        return end;
    }

    /** Codec config (Annex B NAL units) without enhancement layer NAL units; the same array if there are none. */
    static byte[] withoutEnhancementLayer(byte[] csd) {
        boolean hasEl = false;
        int s = findStartCode(csd, 0, csd.length);
        while (s < csd.length) {
            int n = s + 3;
            if (n < csd.length && ((csd[n] >> 1) & 0x3F) == NAL_UNSPEC63_EL) {
                hasEl = true;
                break;
            }
            s = findStartCode(csd, n, csd.length);
        }
        if (!hasEl) return csd;
        DolbyVisionP7Rewriter r = new DolbyVisionP7Rewriter(new DolbyVisionP7.Conversion(""));
        // RPUs never appear in codec config; this only drops type 63 and normalises start codes
        return r.rewrite(csd, 0, csd.length) ? Arrays.copyOf(r.out, r.outLength) : csd;
    }
}
