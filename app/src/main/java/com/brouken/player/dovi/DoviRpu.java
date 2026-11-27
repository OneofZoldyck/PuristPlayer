package com.brouken.player.dovi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Purist: Dolby Vision RPU (HEVC UNSPEC62 NAL unit) parser, profile 7 to 8.1 converter and writer.
 *
 * A Java port of the parts of dovi_tool's dolby_vision crate (MIT, github.com/quietvoid/dovi_tool) that
 * conversion mode 2 uses, the same conversion Kodi applies on Android. Verified bit-exact against
 * dovi_tool's own test vectors (fel_orig to fel_to_81, mel_orig to mel_to_81).
 *
 * Mode 2 on a profile 7 RPU: removes the enhancement layer residual (NLQ) parameters, sets the profile 8.1
 * colour coefficients, and for FEL also resets the base layer mapping curves to identity. The display
 * management metadata (L1 to L11, the dynamic tone mapping) is carried over bit for bit.
 *
 * Ported from dovi_tool, which carries this notice:
 *
 * MIT License
 *
 * Copyright (c) 2026 quietvoid
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
public final class DoviRpu {

    public static final class DoviException extends Exception {
        DoviException(String message) {
            super(message);
        }
    }

    public enum ElType { MEL, FEL }

    private static final int FINAL_BYTE = 0x80;
    private static final int CRC32_TERMINATOR_BITS = 40;
    private static final int NUM_COMPONENTS = 3;
    private static final int RESHAPING_MAX_PIECES = 8;
    private static final int MMR_MAX_COEFFS = 7;
    private static final int NLQ_NUM_PIVOTS = 2;
    private static final int DM_DATA_PAYLOAD2_MIN_BITS = 56;
    private static final int[] CMV29_LEVELS = {1, 2, 4, 5, 6, 255};
    private static final int CMV29_MAX_BLOCKS = 13;
    private static final int[] CMV40_LEVELS = {3, 8, 9, 10, 11, 253, 254};
    private static final int CMV40_MAX_BLOCKS = 14;

    // ---- rpu_data_header ----
    int rpuType;
    int rpuFormat;
    int vdrRpuProfile;
    int vdrRpuLevel;
    boolean vdrSeqInfoPresentFlag;
    boolean chromaResamplingExplicitFilterFlag;
    int coefficientDataType;
    long coefficientLog2Denom;
    int coefficientLog2DenomLength;
    int vdrRpuNormalizedIdc;
    boolean blVideoFullRangeFlag;
    long blBitDepthMinus8;
    long elBitDepthMinus8;
    int extMappingIdc04;
    int extMappingIdc57;
    long vdrBitDepthMinus8;
    boolean spatialResamplingFilterFlag;
    int reservedZero3bits;
    boolean elSpatialResamplingFilterFlag;
    boolean disableResidualFlag;
    boolean vdrDmMetadataPresentFlag;
    boolean usePrevVdrRpuFlag;
    long prevVdrRpuId;

    Mapping mapping;
    DmData dm;
    boolean[] remaining;
    int trailingZeroes;

    int doviProfile;
    ElType elType;
    boolean modified;

    private DoviRpu() {
    }

    public int getDoviProfile() {
        return doviProfile;
    }

    public ElType getElType() {
        return elType;
    }

    // =====================================================================================================
    // Public conversion entry point
    // =====================================================================================================

    /**
     * @param nal  HEVC NAL unit starting at its 2-byte header (0x7C 0x01), without start code
     * @return the converted NAL unit (with header and emulation prevention), or null when the RPU is not
     *         profile 7 or cannot be parsed (the caller then keeps the original, as Kodi does)
     */
    public static byte[] convertProfile7To81(byte[] nal, int offset, int length, ElType[] elTypeOut) {
        try {
            DoviRpu rpu = parseUnspec62Nalu(nal, offset, length);
            if (rpu.doviProfile != 7) {
                return null;
            }
            if (elTypeOut != null) {
                elTypeOut[0] = rpu.elType;
            }
            rpu.convertToP81();
            return rpu.writeHevcUnspec62Nalu();
        } catch (DoviException | RuntimeException e) {
            return null;
        }
    }

    // =====================================================================================================
    // Parsing
    // =====================================================================================================

    /** HEVC UNSPEC62 NAL unit, with or without start code / NAL header; clears emulation prevention. */
    public static DoviRpu parseUnspec62Nalu(byte[] data, int offset, int length) throws DoviException {
        int start = validatedTrimmedStart(data, offset, length);
        byte[] bytes = clearEmulationPrevention(data, start, offset + length);
        return parse(bytes);
    }

    private static int validatedTrimmedStart(byte[] d, int off, int len) throws DoviException {
        if (len < 25) throw new DoviException("Invalid RPU length: " + len);
        int b0 = d[off] & 0xFF, b1 = d[off + 1] & 0xFF, b2 = d[off + 2] & 0xFF, b3 = d[off + 3] & 0xFF,
                b4 = d[off + 4] & 0xFF;
        if (b0 == 0 && b1 == 0 && b2 == 0 && b3 == 1 && b4 == 25) return off + 4;
        if (b0 == 0 && b1 == 0 && b2 == 1 && b3 == 25 && b4 == 8) return off + 3;
        if ((b0 == 0 || b0 == 124) && b1 == 1 && b2 == 25 && b3 == 8 && b4 == 9) return off + 2;
        if (b0 == 1 && b1 == 25 && b2 == 8 && b3 == 9) return off + 1;
        if (b0 == 25 && b1 == 8 && b2 == 9) return off;
        throw new DoviException("Invalid RPU data start bytes");
    }

    static DoviRpu parse(byte[] data) throws DoviException {
        int trailingZeroes = 0;
        for (int i = data.length - 1; i >= 0 && data[i] == 0; i--) trailingZeroes++;
        int rpuEnd = data.length - trailingZeroes;
        if (rpuEnd < 7) throw new DoviException("RPU data too short");
        if ((data[rpuEnd - 1] & 0xFF) != FINAL_BYTE) throw new DoviException("Invalid RPU last byte");

        int crc32Start = rpuEnd - 5;
        int computed = crc32Mpeg2(data, 1, crc32Start - 1);
        int received = ((data[crc32Start] & 0xFF) << 24) | ((data[crc32Start + 1] & 0xFF) << 16)
                | ((data[crc32Start + 2] & 0xFF) << 8) | (data[crc32Start + 3] & 0xFF);
        if (received != computed) throw new DoviException("RPU CRC32 mismatch");

        DoviRpu rpu = new DoviRpu();
        rpu.readRpuData(data, rpuEnd);
        rpu.trailingZeroes = trailingZeroes;
        rpu.validate();
        return rpu;
    }

    private void readRpuData(byte[] bytes, int end) throws DoviException {
        BitReader r = new BitReader(bytes, end);
        if (r.read(8) != 25) throw new DoviException("rpu_nal_prefix should be 25");

        parseHeader(r);
        validateHeader(computeDoviProfile());

        if (!usePrevVdrRpuFlag) {
            mapping = parseMapping(r);
        }
        elType = mapping != null && mapping.nlq != null ? mapping.nlq.elType() : null;

        if (vdrDmMetadataPresentFlag) {
            dm = parseDm(r);
        }

        while (!r.byteAligned()) {
            if (r.readBit()) throw new DoviException("rpu_alignment_zero_bit != 0");
        }

        if (r.available() > CRC32_TERMINATOR_BITS) {
            boolean[] bits = new boolean[(int) (r.available() - CRC32_TERMINATOR_BITS)];
            for (int i = 0; i < bits.length; i++) bits[i] = r.readBit();
            remaining = bits;
        }
        if (r.available() != CRC32_TERMINATOR_BITS) throw new DoviException("unexpected RPU tail length");
        r.read(32); // crc32, already validated
        if (r.read(8) != FINAL_BYTE) throw new DoviException("last byte should be 0x80");

        doviProfile = computeDoviProfile();
    }

    private void parseHeader(BitReader r) throws DoviException {
        rpuType = (int) r.read(6);
        if (rpuType != 2) throw new DoviException("rpu_type should be 2");
        rpuFormat = (int) r.read(11);
        vdrRpuProfile = (int) r.read(4);
        vdrRpuLevel = (int) r.read(4);
        vdrSeqInfoPresentFlag = r.readBit();

        if (vdrSeqInfoPresentFlag) {
            chromaResamplingExplicitFilterFlag = r.readBit();
            coefficientDataType = (int) r.read(2);
            if (coefficientDataType == 0) {
                coefficientLog2Denom = r.readUe();
            }
            vdrRpuNormalizedIdc = (int) r.read(2);
            blVideoFullRangeFlag = r.readBit();

            if ((rpuFormat & 0x700) == 0) {
                blBitDepthMinus8 = r.readUe();
                long elBitDepth = r.readUe();
                elBitDepthMinus8 = elBitDepth & 0xFF;
                int extMappingIdc = (int) ((elBitDepth >>> 8) & 0xFF);
                extMappingIdc04 = extMappingIdc & 0x1F;
                extMappingIdc57 = extMappingIdc >> 5;
                vdrBitDepthMinus8 = r.readUe();
                spatialResamplingFilterFlag = r.readBit();
                reservedZero3bits = (int) r.read(3);
                elSpatialResamplingFilterFlag = r.readBit();
                disableResidualFlag = r.readBit();
            }

            if (coefficientDataType == 0) {
                coefficientLog2DenomLength = (int) coefficientLog2Denom;
            } else if (coefficientDataType == 1) {
                coefficientLog2DenomLength = 32;
            } else {
                throw new DoviException("Invalid coefficient_data_type");
            }
        }

        vdrDmMetadataPresentFlag = r.readBit();
        usePrevVdrRpuFlag = r.readBit();
        if (usePrevVdrRpuFlag) {
            prevVdrRpuId = r.readUe();
        }
    }

    private Mapping parseMapping(BitReader r) throws DoviException {
        Mapping m = new Mapping();
        m.vdrRpuId = r.readUe();
        m.mappingColorSpace = r.readUe();
        m.mappingChromaFormatIdc = r.readUe();

        int blBitDepth = (int) (blBitDepthMinus8 + 8);
        for (Curve c : m.curves) {
            c.numPivotsMinus2 = r.readUe();
            if (c.numPivotsMinus2 >= RESHAPING_MAX_PIECES) throw new DoviException("too many pivots");
            c.pivots = new int[(int) c.numPivotsMinus2 + 2];
            for (int i = 0; i < c.pivots.length; i++) c.pivots[i] = (int) r.read(blBitDepth);
        }

        // profile 7 only
        if ((rpuFormat & 0x700) == 0 && !disableResidualFlag) {
            int nlqMethodIdc = (int) r.read(3);
            if (nlqMethodIdc != 0) throw new DoviException("Unsupported NLQ method");
            m.nlqMethodIdc = 0;
            m.nlqNumPivotsMinus2 = 0;
            m.nlqPredPivotValue = new int[NLQ_NUM_PIVOTS];
            for (int i = 0; i < NLQ_NUM_PIVOTS; i++) m.nlqPredPivotValue[i] = (int) r.read(blBitDepth);
        }

        m.numXPartitionsMinus1 = r.readUe();
        m.numYPartitionsMinus1 = r.readUe();

        for (Curve c : m.curves) {
            int numPieces = (int) c.numPivotsMinus2 + 1;
            for (int p = 0; p < numPieces; p++) {
                long mappingIdc = r.readUe();
                if (mappingIdc > 1) throw new DoviException("Invalid mapping_idc");
                c.mappingIdc = (int) mappingIdc;
                if (mappingIdc == 0) {
                    if (c.poly == null) c.poly = new Poly();
                    parsePoly(c.poly, r);
                } else {
                    if (c.mmr == null) c.mmr = new Mmr();
                    parseMmr(c.mmr, r);
                }
            }
        }

        if (m.nlqMethodIdc != null) {
            m.nlq = parseNlq(r, m);
        }
        return m;
    }

    private void parsePoly(Poly p, BitReader r) throws DoviException {
        long order = r.readUe();
        if (order > 1) throw new DoviException("poly_order_minus1 > 1");
        p.orderMinus1.add(order);
        boolean linearInterp = order == 0 && r.readBit();
        p.linearInterp.add(linearInterp);
        if (order == 0 && linearInterp) throw new DoviException("Polynomial interpolation unsupported");

        int count = (int) order + 2;
        long[] coefInt = new long[count];
        long[] coef = new long[count];
        for (int j = 0; j < count; j++) {
            if (coefficientDataType == 0) coefInt[j] = r.readSe();
            coef[j] = r.read(coefficientLog2DenomLength);
        }
        p.coefInt.add(coefInt);
        p.coef.add(coef);
    }

    private void parseMmr(Mmr m, BitReader r) throws DoviException {
        int order = (int) r.read(2);
        if (order > 2) throw new DoviException("mmr_order_minus1 > 2");
        m.orderMinus1.add(order);
        m.constantInt.add(coefficientDataType == 0 ? r.readSe() : 0L);
        m.constant.add(r.read(coefficientLog2DenomLength));
        long[][] coefInt = new long[order + 1][MMR_MAX_COEFFS];
        long[][] coef = new long[order + 1][MMR_MAX_COEFFS];
        for (int j = 0; j <= order; j++) {
            for (int k = 0; k < MMR_MAX_COEFFS; k++) {
                if (coefficientDataType == 0) coefInt[j][k] = r.readSe();
                coef[j][k] = r.read(coefficientLog2DenomLength);
            }
        }
        m.coefInt.add(coefInt);
        m.coef.add(coef);
    }

    private Nlq parseNlq(BitReader r, Mapping m) throws DoviException {
        if (m.nlqNumPivotsMinus2 + 1 != 1) throw new DoviException("NLQ should only have 1 significant pivot");
        Nlq n = new Nlq();
        int elBitDepth = (int) (elBitDepthMinus8 + 8);
        for (int c = 0; c < NUM_COMPONENTS; c++) {
            n.nlqOffset[c] = r.read(elBitDepth);
            if (coefficientDataType == 0) n.vdrInMaxInt[c] = r.readUe();
            n.vdrInMax[c] = r.read(coefficientLog2DenomLength);
            if (m.nlqMethodIdc == 0) {
                if (coefficientDataType == 0) n.slopeInt[c] = r.readUe();
                n.slope[c] = r.read(coefficientLog2DenomLength);
                if (coefficientDataType == 0) n.thresholdInt[c] = r.readUe();
                n.threshold[c] = r.read(coefficientLog2DenomLength);
            }
        }
        return n;
    }

    private DmData parseDm(BitReader r) throws DoviException {
        DmData d = new DmData();
        d.compressed = reservedZero3bits == 1;
        d.affectedDmMetadataId = r.readUe();
        d.currentDmMetadataId = r.readUe();
        d.sceneRefreshFlag = r.readUe();
        if (!d.compressed) {
            for (int i = 0; i < 9; i++) d.yccToRgbCoef[i] = r.read(16);
            for (int i = 0; i < 3; i++) d.yccToRgbOffset[i] = r.read(32);
            for (int i = 0; i < 9; i++) d.rgbToLmsCoef[i] = r.read(16);
            d.signalEotf = r.read(16);
            d.signalEotfParam0 = r.read(16);
            d.signalEotfParam1 = r.read(16);
            d.signalEotfParam2 = r.read(32);
            d.signalBitDepth = r.read(5);
            d.signalColorSpace = r.read(2);
            d.signalChromaFormat = r.read(2);
            d.signalFullRangeFlag = r.read(2);
            d.sourceMinPq = r.read(12);
            d.sourceMaxPq = r.read(12);
            d.sourceDiagonal = r.read(10);
        }
        d.cmv29 = parseExtBlocks(r, CMV29_LEVELS, CMV29_MAX_BLOCKS);
        if (r.available() >= DM_DATA_PAYLOAD2_MIN_BITS) {
            d.cmv40 = parseExtBlocks(r, CMV40_LEVELS, CMV40_MAX_BLOCKS);
        }
        return d;
    }

    /** Extension metadata blocks: structure parsed, block payloads (whole bytes) carried over untouched. */
    private static ExtBlocks parseExtBlocks(BitReader r, int[] allowedLevels, int maxBlocks) throws DoviException {
        ExtBlocks e = new ExtBlocks();
        e.numExtBlocks = r.readUe();
        if (e.numExtBlocks > maxBlocks) throw new DoviException("too many extension metadata blocks");
        while (!r.byteAligned()) {
            if (r.readBit()) throw new DoviException("dm_alignment_zero_bit != 0");
        }
        for (int i = 0; i < e.numExtBlocks; i++) {
            long length = r.readUe();
            int level = (int) r.read(8);
            boolean allowed = false;
            for (int l : allowedLevels) allowed |= l == level;
            if (!allowed) throw new DoviException("disallowed extension metadata level " + level);
            if (length * 8 > r.available()) throw new DoviException("extension block overflows RPU");
            byte[] payload = new byte[(int) length];
            for (int b = 0; b < payload.length; b++) payload[b] = (byte) r.read(8);
            e.blocks.add(new ExtBlock(length, level, payload));
        }
        return e;
    }

    // =====================================================================================================
    // Conversion (dovi_tool mode 2 for profile 7 and 8)
    // =====================================================================================================

    public void convertToP81() throws DoviException {
        convertToP81(true);
    }

    /** @param removeFelMapping true = dovi_tool mode 2 (current); false = "mapping preserved" (old mode 2) */
    void convertToP81(boolean removeFelMapping) throws DoviException {
        if (doviProfile != 7 && doviProfile != 8) throw new DoviException("Invalid profile for 8.1 conversion");
        modified = true;

        // convert_to_p81
        elSpatialResamplingFilterFlag = false;
        disableResidualFlag = true;
        if (mapping != null) {
            mapping.nlqMethodIdc = null;
            mapping.nlqNumPivotsMinus2 = null;
            mapping.nlqPredPivotValue = null;
            mapping.numXPartitionsMinus1 = 0;
            mapping.numYPartitionsMinus1 = 0;
            mapping.nlq = null;
        }
        if (dm != null) {
            dm.setP81Coeffs();
        }

        // convert_to_p81_remove_mapping: FEL mapping is reset to identity
        if (removeFelMapping && elType == ElType.FEL && mapping != null) {
            mapping.setEmptyP81Mapping();
        }

        doviProfile = computeDoviProfile();
        elType = mapping != null && mapping.nlq != null ? mapping.nlq.elType() : null;
    }

    // =====================================================================================================
    // Writing
    // =====================================================================================================

    /** NAL unit with the 0x7C 0x01 header, emulation prevention applied. */
    public byte[] writeHevcUnspec62Nalu() throws DoviException {
        byte[] escaped = addEmulationPrevention(writeRpuData());
        byte[] nal = new byte[escaped.length + 2];
        nal[0] = 0x7C;
        nal[1] = 0x01;
        System.arraycopy(escaped, 0, nal, 2, escaped.length);
        return nal;
    }

    byte[] writeRpuData() throws DoviException {
        validate();
        BitWriter w = new BitWriter();
        w.write(8, 0x19);
        writeHeader(w);
        if (rpuType == 2) {
            if (!usePrevVdrRpuFlag && mapping != null) writeMapping(w);
            if (vdrDmMetadataPresentFlag && dm != null) writeDm(w);
        }
        if (remaining != null) {
            for (boolean b : remaining) w.writeBit(b);
        }
        w.byteAlign();
        byte[] body = w.toByteArray();
        int crc = crc32Mpeg2(body, 1, body.length - 1);
        w.write(32, crc & 0xFFFFFFFFL);
        w.write(8, FINAL_BYTE);
        for (int i = 0; i < trailingZeroes; i++) w.write(8, 0);
        return w.toByteArray();
    }

    private void writeHeader(BitWriter w) {
        w.write(6, rpuType);
        w.write(11, rpuFormat);
        w.write(4, vdrRpuProfile);
        w.write(4, vdrRpuLevel);
        w.writeBit(vdrSeqInfoPresentFlag);
        if (vdrSeqInfoPresentFlag) {
            w.writeBit(chromaResamplingExplicitFilterFlag);
            w.write(2, coefficientDataType);
            if (coefficientDataType == 0) w.writeUe(coefficientLog2Denom);
            w.write(2, vdrRpuNormalizedIdc);
            w.writeBit(blVideoFullRangeFlag);
            if ((rpuFormat & 0x700) == 0) {
                w.writeUe(blBitDepthMinus8);
                long extMappingIdc = ((long) (extMappingIdc57 << 5) | extMappingIdc04);
                w.writeUe((extMappingIdc << 8) | elBitDepthMinus8);
                w.writeUe(vdrBitDepthMinus8);
                w.writeBit(spatialResamplingFilterFlag);
                w.write(3, reservedZero3bits);
                w.writeBit(elSpatialResamplingFilterFlag);
                w.writeBit(disableResidualFlag);
            }
        }
        w.writeBit(vdrDmMetadataPresentFlag);
        w.writeBit(usePrevVdrRpuFlag);
        if (usePrevVdrRpuFlag) w.writeUe(prevVdrRpuId);
    }

    private void writeMapping(BitWriter w) throws DoviException {
        Mapping m = mapping;
        int denomLen = coefficientLog2DenomLength;
        int blBitDepth = (int) (blBitDepthMinus8 + 8);

        w.writeUe(m.vdrRpuId);
        w.writeUe(m.mappingColorSpace);
        w.writeUe(m.mappingChromaFormatIdc);
        for (Curve c : m.curves) {
            w.writeUe(c.numPivotsMinus2);
            for (int p : c.pivots) w.write(blBitDepth, p);
        }
        if ((rpuFormat & 0x700) == 0 && !disableResidualFlag) {
            if (m.nlqMethodIdc != null) w.write(3, m.nlqMethodIdc);
            if (m.nlqPredPivotValue != null) {
                for (int pv : m.nlqPredPivotValue) w.write(blBitDepth, pv);
            }
        }
        w.writeUe(m.numXPartitionsMinus1);
        w.writeUe(m.numYPartitionsMinus1);

        for (Curve c : m.curves) {
            int numPieces = (int) c.numPivotsMinus2 + 1;
            for (int i = 0; i < numPieces; i++) {
                w.writeUe(c.mappingIdc);
                if (c.poly != null) {
                    long order = c.poly.orderMinus1.get(i);
                    w.writeUe(order);
                    boolean linearInterp = c.poly.linearInterp.get(i);
                    if (order == 0) w.writeBit(linearInterp);
                    if (order == 0 && linearInterp) throw new DoviException("Polynomial interpolation unsupported");
                    long[] coefInt = c.poly.coefInt.get(i);
                    long[] coef = c.poly.coef.get(i);
                    for (int j = 0; j <= order + 1; j++) {
                        if (coefficientDataType == 0) w.writeSe(coefInt[j]);
                        w.write(denomLen, coef[j]);
                    }
                } else if (c.mmr != null) {
                    int order = c.mmr.orderMinus1.get(i);
                    w.write(2, order);
                    if (coefficientDataType == 0) w.writeSe(c.mmr.constantInt.get(i));
                    w.write(denomLen, c.mmr.constant.get(i));
                    long[][] coefInt = c.mmr.coefInt.get(i);
                    long[][] coef = c.mmr.coef.get(i);
                    for (int j = 0; j <= order; j++) {
                        for (int k = 0; k < MMR_MAX_COEFFS; k++) {
                            if (coefficientDataType == 0) w.writeSe(coefInt[j][k]);
                            w.write(denomLen, coef[j][k]);
                        }
                    }
                } else {
                    throw new DoviException("Missing mapping method");
                }
            }
        }

        if (m.nlq != null) {
            Nlq n = m.nlq;
            int elBitDepth = (int) (elBitDepthMinus8 + 8);
            for (int c = 0; c < NUM_COMPONENTS; c++) {
                w.write(elBitDepth, n.nlqOffset[c]);
                if (coefficientDataType == 0) w.writeUe(n.vdrInMaxInt[c]);
                w.write(denomLen, n.vdrInMax[c]);
                if (m.nlqMethodIdc != null && m.nlqMethodIdc == 0) {
                    if (coefficientDataType == 0) w.writeUe(n.slopeInt[c]);
                    w.write(denomLen, n.slope[c]);
                    if (coefficientDataType == 0) w.writeUe(n.thresholdInt[c]);
                    w.write(denomLen, n.threshold[c]);
                }
            }
        }
    }

    private void writeDm(BitWriter w) {
        DmData d = dm;
        w.writeUe(d.affectedDmMetadataId);
        w.writeUe(d.currentDmMetadataId);
        w.writeUe(d.sceneRefreshFlag);
        if (!d.compressed) {
            for (int i = 0; i < 9; i++) w.write(16, d.yccToRgbCoef[i]);
            for (int i = 0; i < 3; i++) w.write(32, d.yccToRgbOffset[i]);
            for (int i = 0; i < 9; i++) w.write(16, d.rgbToLmsCoef[i]);
            w.write(16, d.signalEotf);
            w.write(16, d.signalEotfParam0);
            w.write(16, d.signalEotfParam1);
            w.write(32, d.signalEotfParam2);
            w.write(5, d.signalBitDepth);
            w.write(2, d.signalColorSpace);
            w.write(2, d.signalChromaFormat);
            w.write(2, d.signalFullRangeFlag);
            w.write(12, d.sourceMinPq);
            w.write(12, d.sourceMaxPq);
            w.write(10, d.sourceDiagonal);
        }
        if (d.cmv29 != null) writeExtBlocks(w, d.cmv29);
        if (d.cmv40 != null) writeExtBlocks(w, d.cmv40);
    }

    private static void writeExtBlocks(BitWriter w, ExtBlocks e) {
        w.writeUe(e.numExtBlocks);
        w.byteAlign(); // dm_alignment_zero_bit
        for (ExtBlock b : e.blocks) {
            w.writeUe(b.length);
            w.write(8, b.level);
            for (byte v : b.payload) w.write(8, v & 0xFF);
        }
    }

    // =====================================================================================================
    // Validation and profile
    // =====================================================================================================

    private int computeDoviProfile() {
        if (vdrRpuProfile == 0) return blVideoFullRangeFlag ? 5 : 0;
        if (vdrRpuProfile == 1) {
            if (elSpatialResamplingFilterFlag && !disableResidualFlag) {
                return vdrBitDepthMinus8 == 4 ? 7 : 4;
            }
            return 8;
        }
        return 0;
    }

    private void validate() throws DoviException {
        validateHeader(doviProfile);
        if (mapping != null) validateMapping(doviProfile);
        if (dm != null) dm.validate();
    }

    private void validateHeader(int profile) throws DoviException {
        if (profile == 5 && (vdrRpuProfile != 0 || !blVideoFullRangeFlag)) throw new DoviException("profile 5 header");
        if ((profile == 7 || profile == 8) && vdrRpuProfile != 1) throw new DoviException("profile 7/8 header");
        if (vdrRpuLevel != 0) throw new DoviException("vdr_rpu_level should be 0");
        if (blBitDepthMinus8 != 2) throw new DoviException("bl_bit_depth_minus8 should be 2");
        if (elBitDepthMinus8 != 2) throw new DoviException("el_bit_depth_minus8 should be 2");
        if (vdrBitDepthMinus8 > 6) throw new DoviException("vdr_bit_depth_minus8 should be <= 6");
        if (coefficientLog2Denom > 23) throw new DoviException("coefficient_log2_denom should be <= 23");
    }

    private void validateMapping(int profile) throws DoviException {
        Mapping m = mapping;
        for (Curve c : m.curves) {
            if (c.numPivotsMinus2 >= RESHAPING_MAX_PIECES || c.pivots.length != c.numPivotsMinus2 + 2) {
                throw new DoviException("invalid curve");
            }
        }
        boolean nlqUnset = m.nlqMethodIdc == null && m.nlqNumPivotsMinus2 == null && m.nlqPredPivotValue == null;
        if ((profile == 5 || profile == 8) && !nlqUnset) throw new DoviException("NLQ should be undefined");
        if (profile == 7) {
            if (m.nlqPredPivotValue == null) throw new DoviException("profile 7: nlq_pred_pivot_value missing");
            int sum = 0;
            for (int v : m.nlqPredPivotValue) sum += v;
            if (sum != 1023) throw new DoviException("profile 7: nlq_pred_pivot_value sum");
        }
        if (m.mappingColorSpace != 0) throw new DoviException("mapping_color_space should be 0");
        if (m.mappingChromaFormatIdc != 0) throw new DoviException("mapping_chroma_format_idc should be 0");
    }

    // =====================================================================================================
    // Data structures
    // =====================================================================================================

    static final class Mapping {
        long vdrRpuId;
        long mappingColorSpace;
        long mappingChromaFormatIdc;
        long numXPartitionsMinus1;
        long numYPartitionsMinus1;
        final Curve[] curves = {new Curve(), new Curve(), new Curve()};
        Integer nlqMethodIdc;
        Integer nlqNumPivotsMinus2;
        int[] nlqPredPivotValue;
        Nlq nlq;

        void setEmptyP81Mapping() {
            for (Curve c : curves) {
                c.numPivotsMinus2 = 0;
                c.pivots = new int[]{0, 1023};
                c.mappingIdc = 0;
                c.mmr = null;
                if (c.poly == null) c.poly = new Poly();
                c.poly.setP81Params();
            }
        }
    }

    static final class Curve {
        long numPivotsMinus2;
        int[] pivots;
        int mappingIdc = 255;
        Poly poly;
        Mmr mmr;
    }

    static final class Poly {
        final List<Long> orderMinus1 = new ArrayList<>();
        final List<Boolean> linearInterp = new ArrayList<>();
        final List<long[]> coefInt = new ArrayList<>();
        final List<long[]> coef = new ArrayList<>();

        void setP81Params() {
            orderMinus1.clear();
            orderMinus1.add(0L);
            linearInterp.clear();
            linearInterp.add(false);
            coefInt.clear();
            coefInt.add(new long[]{0, 1});
            coef.clear();
            coef.add(new long[]{0, 0});
        }
    }

    static final class Mmr {
        final List<Integer> orderMinus1 = new ArrayList<>();
        final List<Long> constantInt = new ArrayList<>();
        final List<Long> constant = new ArrayList<>();
        final List<long[][]> coefInt = new ArrayList<>();
        final List<long[][]> coef = new ArrayList<>();
    }

    static final class Nlq {
        final long[] nlqOffset = new long[NUM_COMPONENTS];
        final long[] vdrInMaxInt = new long[NUM_COMPONENTS];
        final long[] vdrInMax = new long[NUM_COMPONENTS];
        final long[] slopeInt = new long[NUM_COMPONENTS];
        final long[] slope = new long[NUM_COMPONENTS];
        final long[] thresholdInt = new long[NUM_COMPONENTS];
        final long[] threshold = new long[NUM_COMPONENTS];

        ElType elType() {
            for (int c = 0; c < NUM_COMPONENTS; c++) {
                if (nlqOffset[c] != 0 || vdrInMaxInt[c] != 1 || vdrInMax[c] != 0 || slopeInt[c] != 0
                        || slope[c] != 0 || thresholdInt[c] != 0 || threshold[c] != 0) {
                    return ElType.FEL;
                }
            }
            return ElType.MEL;
        }
    }

    static final class DmData {
        boolean compressed;
        long affectedDmMetadataId;
        long currentDmMetadataId;
        long sceneRefreshFlag;
        final long[] yccToRgbCoef = new long[9];
        final long[] yccToRgbOffset = new long[3];
        final long[] rgbToLmsCoef = new long[9];
        long signalEotf;
        long signalEotfParam0;
        long signalEotfParam1;
        long signalEotfParam2;
        long signalBitDepth;
        long signalColorSpace;
        long signalChromaFormat;
        long signalFullRangeFlag;
        long sourceMinPq;
        long sourceMaxPq;
        long sourceDiagonal;
        ExtBlocks cmv29;
        ExtBlocks cmv40;

        void setP81Coeffs() {
            long[] ycc = {9574, 0, 13802, 9574, -1540, -5348, 9574, 17610, 0};
            for (int i = 0; i < 9; i++) yccToRgbCoef[i] = ycc[i] & 0xFFFF;
            yccToRgbOffset[0] = 16777216L;
            yccToRgbOffset[1] = 134217728L;
            yccToRgbOffset[2] = 134217728L;
            long[] lms = {7222, 8771, 390, 2654, 12430, 1300, 0, 422, 15962};
            for (int i = 0; i < 9; i++) rgbToLmsCoef[i] = lms[i] & 0xFFFF;
            signalColorSpace = 0;
        }

        void validate() throws DoviException {
            if (affectedDmMetadataId > 15) throw new DoviException("affected_dm_metadata_id should be <= 15");
            if (!compressed) {
                if (signalBitDepth < 8 || signalBitDepth > 16) throw new DoviException("signal_bit_depth");
                if (signalEotfParam0 == 0 && signalEotfParam1 == 0 && signalEotfParam2 == 0 && signalEotf != 65535) {
                    throw new DoviException("signal_eotf should be 65535");
                }
            }
        }
    }

    static final class ExtBlocks {
        long numExtBlocks;
        final List<ExtBlock> blocks = new ArrayList<>();
    }

    static final class ExtBlock {
        final long length;
        final int level;
        final byte[] payload;

        ExtBlock(long length, int level, byte[] payload) {
            this.length = length;
            this.level = level;
            this.payload = payload;
        }
    }

    // =====================================================================================================
    // Bitstream helpers
    // =====================================================================================================

    static final class BitReader {
        private final byte[] data;
        private final long endBits;
        private long pos;

        BitReader(byte[] data, int endBytes) {
            this.data = data;
            this.endBits = (long) endBytes * 8;
        }

        long available() {
            return endBits - pos;
        }

        boolean byteAligned() {
            return (pos & 7) == 0;
        }

        boolean readBit() throws DoviException {
            if (pos >= endBits) throw new DoviException("read past end of RPU");
            int bit = (data[(int) (pos >>> 3)] >> (7 - (int) (pos & 7))) & 1;
            pos++;
            return bit != 0;
        }

        long read(int n) throws DoviException {
            if (n > 64 || n < 0) throw new DoviException("bad read size " + n);
            if (available() < n) throw new DoviException("read past end of RPU");
            long v = 0;
            for (int i = 0; i < n; i++) v = (v << 1) | (readBit() ? 1 : 0);
            return v;
        }

        long readUe() throws DoviException {
            int leadingZeros = 0;
            while (!readBit()) {
                if (++leadingZeros > 62) throw new DoviException("invalid exp-golomb code");
            }
            if (leadingZeros == 0) return 0;
            return ((1L << leadingZeros) - 1) + read(leadingZeros);
        }

        long readSe() throws DoviException {
            long k = readUe();
            return (k & 1) != 0 ? (k + 1) / 2 : -(k / 2);
        }
    }

    static final class BitWriter {
        private byte[] buf = new byte[512];
        private long pos;

        void writeBit(boolean bit) {
            int index = (int) (pos >>> 3);
            if (index >= buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
            if (bit) buf[index] |= (byte) (0x80 >>> (int) (pos & 7));
            pos++;
        }

        void write(int n, long value) {
            for (int i = n - 1; i >= 0; i--) writeBit(((value >>> i) & 1) != 0);
        }

        void writeUe(long value) {
            long x = value + 1;
            int bits = 64 - Long.numberOfLeadingZeros(x);
            for (int i = 0; i < bits - 1; i++) writeBit(false);
            write(bits, x);
        }

        void writeSe(long value) {
            writeUe(value > 0 ? 2 * value - 1 : -2 * value);
        }

        void byteAlign() {
            while ((pos & 7) != 0) writeBit(false);
        }

        byte[] toByteArray() {
            return Arrays.copyOf(buf, (int) ((pos + 7) >>> 3));
        }
    }

    /** dovi_tool's unescape: drops 0x03 bytes preceded by 0x00 0x00 in the original data. */
    static byte[] clearEmulationPrevention(byte[] d, int start, int end) {
        int len = end - start;
        if (len <= 2) return Arrays.copyOfRange(d, start, end);
        byte[] out = new byte[len];
        out[0] = d[start];
        out[1] = d[start + 1];
        int n = 2;
        for (int i = start + 2; i < end; i++) {
            if (!(d[i - 2] == 0 && d[i - 1] == 0 && d[i] == 3)) out[n++] = d[i];
        }
        return Arrays.copyOf(out, n);
    }

    /** dovi_tool's escape: inserts 0x03 before a byte <= 3 that follows 0x00 0x00 (from index 3 on). */
    static byte[] addEmulationPrevention(byte[] data) {
        byte[] out = new byte[data.length + data.length / 2 + 4];
        int n = 0;
        for (byte b : data) {
            if (n + 2 >= out.length) out = Arrays.copyOf(out, out.length * 2);
            if (n > 2 && out[n - 2] == 0 && out[n - 1] == 0 && (b & 0xFF) <= 3) {
                out[n++] = 3;
            }
            out[n++] = b;
        }
        return Arrays.copyOf(out, n);
    }

    private static final int[] CRC_TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int c = i << 24;
            for (int k = 0; k < 8; k++) c = (c & 0x80000000) != 0 ? (c << 1) ^ 0x04C11DB7 : c << 1;
            CRC_TABLE[i] = c;
        }
    }

    /** CRC-32/MPEG-2 (poly 0x04C11DB7, init 0xFFFFFFFF, no reflection, no final xor). */
    static int crc32Mpeg2(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = (crc << 8) ^ CRC_TABLE[((crc >>> 24) ^ data[i]) & 0xFF];
        }
        return crc;
    }
}
