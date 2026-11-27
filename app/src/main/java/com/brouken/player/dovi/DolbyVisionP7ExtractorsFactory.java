package com.brouken.player.dovi;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Purist: wraps the app's extractors so that Dolby Vision profile 7 video tracks are converted to profile 8.1
 * as they are read (see {@link DolbyVisionP7}). Every other track, and every non profile 7 file, passes
 * straight through to the player's own outputs.
 */
public final class DolbyVisionP7ExtractorsFactory implements ExtractorsFactory {

    private final ExtractorsFactory delegate;

    public DolbyVisionP7ExtractorsFactory(ExtractorsFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrap(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return wrap(delegate.createExtractors(uri, responseHeaders));
    }

    @Override
    public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
        delegate.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled);
        return this;
    }

    @Override
    public ExtractorsFactory setParseHagcMetadata(boolean parseHagcMetadata) {
        delegate.setParseHagcMetadata(parseHagcMetadata);
        return this;
    }

    private static Extractor[] wrap(Extractor[] extractors) {
        Extractor[] wrapped = new Extractor[extractors.length];
        for (int i = 0; i < extractors.length; i++) wrapped[i] = new WrappingExtractor(extractors[i]);
        return wrapped;
    }

    private static final class WrappingExtractor implements Extractor {
        private final Extractor inner;
        @Nullable private WrappingOutput output;

        WrappingExtractor(Extractor inner) {
            this.inner = inner;
        }

        @Override
        public boolean sniff(ExtractorInput input) throws IOException {
            return inner.sniff(input);
        }

        @Override
        public List<SniffFailure> getSniffFailureDetails() {
            return inner.getSniffFailureDetails();
        }

        @Override
        public void init(ExtractorOutput output) {
            this.output = new WrappingOutput(output);
            inner.init(this.output);
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            int result = inner.read(input, seekPosition);
            if (result == RESULT_END_OF_INPUT && output != null) output.releaseHeldFormats();
            return result;
        }

        @Override
        public void seek(long position, long timeUs) {
            if (output != null) output.discardPendingSamples();
            inner.seek(position, timeUs);
        }

        @Override
        public void release() {
            inner.release();
        }

        @Override
        public Extractor getUnderlyingImplementation() {
            return inner.getUnderlyingImplementation();
        }
    }

    private static final class WrappingOutput implements ExtractorOutput {
        private final ExtractorOutput delegate;
        private final List<ConvertingTrackOutput> videoTracks = new ArrayList<>();

        WrappingOutput(ExtractorOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput out = delegate.track(id, type);
            if (type != C.TRACK_TYPE_VIDEO) return out;
            ConvertingTrackOutput t = new ConvertingTrackOutput(out);
            videoTracks.add(t);
            return t;
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }

        void discardPendingSamples() {
            for (ConvertingTrackOutput t : videoTracks) t.pendingLength = 0;
        }

        void releaseHeldFormats() {
            for (ConvertingTrackOutput t : videoTracks) t.releaseHeldFormat();
        }
    }

    /**
     * Video track output. A track that may be profile 7 (declared as such, or plain HEVC HDR that could carry
     * profile 7 in the bitstream) is held back until its first frame: the format is forwarded only once the frame
     * shows what the stream really is. A real profile 7 stream is then converted frame by frame; anything else
     * (base layer only, dual track enhancement layer, ordinary HDR10) passes through exactly as before.
     */
    private static final class ConvertingTrackOutput implements TrackOutput {
        private static final int PASS = 0;
        private static final int PROBE = 1;
        private static final int CONVERT = 2;

        private final TrackOutput delegate;
        private int mode = PASS;
        @Nullable private Format heldFormat;
        private boolean declaredProfile7;
        @Nullable private DolbyVisionP7.Conversion conversion;
        @Nullable private DolbyVisionP7Rewriter rewriter;
        private byte[] pending = new byte[0];
        private byte[] combined = new byte[0];
        int pendingLength;

        ConvertingTrackOutput(TrackOutput delegate) {
            this.delegate = delegate;
        }

        @Override
        public void format(Format format) {
            boolean declared = DolbyVisionP7.isProfile7(format);
            if ((declared || DolbyVisionP7.mayCarryProfile7(format)) && DolbyVisionP7.deviceCanDecodeProfile8()) {
                if (mode == CONVERT && conversion != null) { // an updated format of a stream already converted
                    delegate.format(DolbyVisionP7.toProfile81(format, conversion));
                    return;
                }
                heldFormat = format;
                declaredProfile7 = declared;
                mode = PROBE;
                if (pending.length == 0) pending = new byte[1 << 20];
                return;
            }
            boolean hadPending = mode != PASS && pendingLength > 0;
            mode = PASS;
            heldFormat = null;
            delegate.format(format);
            if (hadPending) forwardRaw(0, pendingLength);
            pendingLength = 0;
        }

        /** The input ended before a first frame: hand over the held format as it is. */
        void releaseHeldFormat() {
            if (mode != PROBE || heldFormat == null) return;
            delegate.format(heldFormat);
            heldFormat = null;
            mode = PASS;
            if (pendingLength > 0) forwardRaw(0, pendingLength);
            pendingLength = 0;
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
                throws IOException {
            if (mode == PASS) return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            ensureCapacity(pendingLength + length);
            int read = input.read(pending, pendingLength, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new EOFException();
            }
            pendingLength += read;
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
            if (mode == PASS) {
                delegate.sampleData(data, length, sampleDataPart);
                return;
            }
            ensureCapacity(pendingLength + length);
            data.readBytes(pending, pendingLength, length);
            pendingLength += length;
        }

        @Override
        public void sampleMetadata(long timeUs, @C.BufferFlags int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            if (mode == PASS) {
                delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
                return;
            }
            int end = pendingLength - offset;
            int start = end - size;
            if (start < 0) {
                // should not happen; hand over what there is rather than stalling playback
                start = 0;
                end = pendingLength;
            }
            boolean supplemental = (flags & C.BUFFER_FLAG_HAS_SUPPLEMENTAL_DATA) != 0;
            int mainStart = start;
            int mainSize = end - start;
            if (supplemental) {
                // Media3 layout with supplemental data (block additions): main data size, main data, supplemental data
                mainSize = end - start >= 4 ? ((pending[start] & 0xFF) << 24) | ((pending[start + 1] & 0xFF) << 16)
                        | ((pending[start + 2] & 0xFF) << 8) | (pending[start + 3] & 0xFF) : -1;
                mainStart = start + 4;
                if (mainSize < 0 || mainStart + mainSize > end) mainSize = -1;
            }

            if (mode == PROBE) {
                Format original = heldFormat;
                heldFormat = null;
                int found = cryptoData == null && mainSize >= 0 && original != null
                        ? DolbyVisionP7Rewriter.probe(pending, mainStart, mainSize) : 0;
                boolean p7 = (found & DolbyVisionP7Rewriter.FOUND_PROFILE7_RPU) != 0;
                boolean el = (found & DolbyVisionP7Rewriter.FOUND_ENHANCEMENT_LAYER) != 0;
                // A declared profile 7 track converts when its frames carry profile 7 RPUs. A plain HEVC track
                // converts only when it is a complete single track profile 7 stream (RPU and enhancement layer).
                if (original != null && p7 && (declaredProfile7 || el)) {
                    conversion = new DolbyVisionP7.Conversion(original.codecs);
                    rewriter = new DolbyVisionP7Rewriter(conversion);
                    Format converted = DolbyVisionP7.toProfile81(original, conversion);
                    Log.i(DolbyVisionP7.TAG, "Dolby Vision profile 7 " + (declaredProfile7 ? "track" : "stream found in "
                            + "a plain HEVC track") + " (" + original.codecs + "): converting to profile 8.1 ("
                            + converted.codecs + "), enhancement layer dropped");
                    delegate.format(converted);
                    mode = CONVERT;
                } else {
                    if (declaredProfile7) {
                        Log.i(DolbyVisionP7.TAG, "track declared as Dolby Vision profile 7 (" + (original != null
                                ? original.codecs : null) + ") carries no profile 7 RPU in its frames (base layer "
                                + "only or dual track): left as it is");
                    }
                    if (original != null) delegate.format(original);
                    mode = PASS;
                    forwardRaw(start, end - start);
                    delegate.sampleMetadata(timeUs, flags, end - start, 0, cryptoData);
                    if (offset > 0) forwardRaw(end, offset); // bytes of the next sample
                    pendingLength = 0;
                    return;
                }
            }

            byte[] sample = pending;
            int sampleStart = start;
            int sampleSize = end - start;
            if (cryptoData == null && rewriter != null && mainSize >= 0 && rewriter.rewrite(pending, mainStart, mainSize)) {
                if (!supplemental) {
                    sample = rewriter.out;
                    sampleStart = 0;
                    sampleSize = rewriter.outLength;
                } else {
                    int supplementalSize = end - mainStart - mainSize;
                    int n = rewriter.outLength;
                    int total = 4 + n + supplementalSize;
                    if (combined.length < total) combined = new byte[Math.max(total, combined.length * 2)];
                    combined[0] = (byte) (n >>> 24);
                    combined[1] = (byte) (n >>> 16);
                    combined[2] = (byte) (n >>> 8);
                    combined[3] = (byte) n;
                    System.arraycopy(rewriter.out, 0, combined, 4, n);
                    System.arraycopy(pending, mainStart + mainSize, combined, 4 + n, supplementalSize);
                    sample = combined;
                    sampleStart = 0;
                    sampleSize = total;
                }
            }
            // the sample queue copies the bytes right away, so the buffers can be reused for the next sample
            ParsableByteArray data = new ParsableByteArray(sample, sampleStart + sampleSize);
            data.setPosition(sampleStart);
            delegate.sampleData(data, sampleSize, SAMPLE_DATA_PART_MAIN);
            delegate.sampleMetadata(timeUs, flags, sampleSize, 0, cryptoData);
            // keep the bytes that already belong to the next sample
            if (offset > 0) System.arraycopy(pending, end, pending, 0, offset);
            pendingLength = Math.max(0, offset);
        }

        private void forwardRaw(int from, int length) {
            if (length <= 0) return;
            ParsableByteArray data = new ParsableByteArray(pending, from + length);
            data.setPosition(from);
            delegate.sampleData(data, length, SAMPLE_DATA_PART_MAIN);
        }

        private void ensureCapacity(int capacity) {
            if (capacity > pending.length) pending = Arrays.copyOf(pending, Math.max(capacity, pending.length * 2));
        }
    }
}
