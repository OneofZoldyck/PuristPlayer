package com.brouken.player.subtitle;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.ByteArrayOutputStream;
import java.util.function.Consumer;
import java.util.zip.Inflater;

/**
 * Purist: DVD subpicture timing fix.
 *
 * A VobSub SPU's display control sequences carry their start/stop delays in units of 1024/90000 s (≈11.38 ms;
 * DVD spec, as read by FFmpeg and VLC). Media3's VobsubParser multiplies them by 10 ms, so every DVD subtitle
 * disappears ≈12 % too early (a 3 s line ends 0.36 s early). This rescales the delays to the 10 ms unit Media3
 * expects before handing the SPU to it, for embedded (MKV) and standalone (.idx/.sub) VobSub alike.
 */
public final class VobSubTiming {

    private VobSubTiming() {
    }

    /** Wraps a parser factory so that VobSub parsers get spec-correct timing. */
    public static SubtitleParser.Factory wrap(SubtitleParser.Factory factory) {
        return new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return factory.supportsFormat(format);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return factory.getCueReplacementBehavior(format);
            }

            @Override
            public SubtitleParser create(Format format) {
                SubtitleParser parser = factory.create(format);
                return MimeTypes.APPLICATION_VOBSUB.equals(format.sampleMimeType) ? new Corrected(parser) : parser;
            }
        };
    }

    public static SubtitleParser wrapParser(SubtitleParser parser) {
        return new Corrected(parser);
    }

    private static final class Corrected implements SubtitleParser {
        private final SubtitleParser delegate;

        Corrected(SubtitleParser delegate) {
            this.delegate = delegate;
        }

        @Override
        public void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
            byte[] spu = corrected(data, offset, length);
            if (spu != null) {
                delegate.parse(spu, 0, spu.length, outputOptions, output);
            } else {
                delegate.parse(data, offset, length, outputOptions, output);
            }
        }

        @Override
        public int getCueReplacementBehavior() {
            return delegate.getCueReplacementBehavior();
        }

        @Override
        public void reset() {
            delegate.reset();
        }
    }

    /** @return a copy with rescaled control-sequence delays, or null to leave the sample untouched */
    static byte[] corrected(byte[] data, int offset, int length) {
        byte[] spu = null;
        if (length >= 2 && (data[offset] & 0xFF) == 0x78) {
            spu = inflate(data, offset, length); // zlib-compressed SPU (Matroska content compression)
        }
        if (spu == null) {
            spu = new byte[length];
            System.arraycopy(data, offset, spu, 0, length);
        }
        if (spu.length < 8 || readShort(spu, 0) != spu.length) return null;
        int sequence = readShort(spu, 2);
        for (int guard = 0; guard < 64; guard++) {
            if (sequence < 4 || sequence + 4 > spu.length) return null;
            long delay = readShort(spu, sequence);
            long tenMs = Math.min(0xFFFF, Math.round(delay * 1024 / 900.0));
            spu[sequence] = (byte) (tenMs >> 8);
            spu[sequence + 1] = (byte) tenMs;
            int next = readShort(spu, sequence + 2);
            if (next == sequence) return spu;
            sequence = next;
        }
        return null;
    }

    private static int readShort(byte[] b, int i) {
        return ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static byte[] inflate(byte[] data, int offset, int length) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data, offset, length);
            ByteArrayOutputStream out = new ByteArrayOutputStream(length * 2);
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                out.write(buf, 0, n);
            }
            return inflater.finished() ? out.toByteArray() : null;
        } catch (Exception e) {
            return null;
        } finally {
            inflater.end();
        }
    }
}
