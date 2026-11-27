package com.brouken.player.subtitle;

import android.net.Uri;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.DataReader;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.mkv.EbmlProcessor;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SniffFailure;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.peerless2012.ass.media.AssHandler;

/**
 * Purist: libass support for Matroska, written against Media3 1.12 (the ass-media helpers target 1.11 and
 * break on 1.12's SubtitleParser API).
 *
 * The app's extractors are used unchanged except that the Matroska extractor is wrapped:
 * <pre>
 *   MatroskaExtractor (+ font attachments) → [ASS tap, text tracks only] → SubtitleTranscodingExtractorOutput → player
 * </pre>
 * which is the stock wiring (MatroskaExtractor normally adds the same SubtitleTranscodingExtractorOutput itself in
 * init()) with a tap that copies ASS dialogue lines to libass. Video and audio tracks are handed straight to the
 * player's own outputs, so Dolby Vision configuration and samples are untouched.
 */
public final class AssExtractorsFactory implements ExtractorsFactory {

    private static final String TAG = "PuristLibass";

    private final DefaultExtractorsFactory delegate;
    private final AssHandler assHandler;
    private final AtomicBoolean released;
    private SubtitleParser.Factory subtitleParserFactory = new DefaultSubtitleParserFactory();
    private boolean textTrackTranscodingEnabled = true;

    /** @param released set once the libass handler is released (late loader threads must not recreate it) */
    public AssExtractorsFactory(DefaultExtractorsFactory delegate, AssHandler assHandler, AtomicBoolean released) {
        this.delegate = delegate;
        this.assHandler = assHandler;
        this.released = released;
    }

    @Override
    public Extractor[] createExtractors() {
        return wrapMatroska(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
        return wrapMatroska(delegate.createExtractors(uri, responseHeaders));
    }

    @Override
    public ExtractorsFactory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
        this.subtitleParserFactory = subtitleParserFactory;
        delegate.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public ExtractorsFactory experimentalSetTextTrackTranscodingEnabled(boolean enabled) {
        this.textTrackTranscodingEnabled = enabled;
        delegate.experimentalSetTextTrackTranscodingEnabled(enabled);
        return this;
    }

    @Override
    public ExtractorsFactory setParseHagcMetadata(boolean parseHagcMetadata) {
        delegate.setParseHagcMetadata(parseHagcMetadata);
        return this;
    }

    private Extractor[] wrapMatroska(Extractor[] extractors) {
        for (int i = 0; i < extractors.length; i++) {
            if (extractors[i] instanceof MatroskaExtractor) {
                // Same position: the order decides which extractor sniffs first.
                extractors[i] = new AssMatroskaExtractor(assHandler, textTrackTranscodingEnabled
                        ? new EmbeddedRouting(subtitleParserFactory, assHandler, released) : null);
            }
        }
        return extractors;
    }

    // ---- Extractor wrapper ----

    private static final class AssMatroskaExtractor implements Extractor {
        private final FontAwareMatroskaExtractor inner;
        private final AssHandler assHandler;
        @Nullable private final SubtitleParser.Factory transcodingFactory;

        AssMatroskaExtractor(AssHandler assHandler, @Nullable SubtitleParser.Factory transcodingFactory) {
            // Raw subtitle data from the inner extractor: the transcoding output is added below, after the ASS tap.
            this.inner = new FontAwareMatroskaExtractor(transcodingFactory != null ? transcodingFactory
                    : new DefaultSubtitleParserFactory(), MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, assHandler);
            this.assHandler = assHandler;
            this.transcodingFactory = transcodingFactory;
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
            ExtractorOutput out = transcodingFactory != null
                    ? new SubtitleTranscodingExtractorOutput(output, transcodingFactory) : output;
            inner.init(new AssTapExtractorOutput(out, assHandler));
        }

        @Override
        public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
            return inner.read(input, seekPosition);
        }

        @Override
        public void seek(long position, long timeUs) {
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

    /** Matroska extractor that also hands attached fonts (and the video size) to libass. */
    private static final class FontAwareMatroskaExtractor extends MatroskaExtractor {
        private static final int ID_VIDEO = 0xE0;
        private static final int ID_ATTACHMENTS = 0x1941A469;
        private static final int ID_ATTACHED_FILE = 0x61A7;
        private static final int ID_FILE_NAME = 0x466E;
        private static final int ID_FILE_MIME_TYPE = 0x4660;
        private static final int ID_FILE_DATA = 0x465C;
        private static final int MAX_FONT_BYTES = 64 * 1024 * 1024;
        private static final List<String> FONT_MIME_TYPES = Arrays.asList("font/collection", "font/ttf", "font/otf",
                "font/sfnt", "font/woff", "font/woff2", "application/font-sfnt", "application/font-woff",
                "application/x-truetype-font", "application/vnd.ms-opentype", "application/x-font",
                "application/x-font-ttf", "application/x-font-otf");
        private static final List<String> FONT_EXTENSIONS = Arrays.asList(".ttf", ".otf", ".ttc", ".otc");

        private final AssHandler assHandler;
        @Nullable private String attachmentName;
        @Nullable private String attachmentMime;
        @Nullable private byte[] attachmentData;

        FontAwareMatroskaExtractor(SubtitleParser.Factory subtitleParserFactory, int flags, AssHandler assHandler) {
            super(subtitleParserFactory, flags);
            this.assHandler = assHandler;
        }

        @Override
        protected int getElementType(int id) {
            switch (id) {
                case ID_ATTACHMENTS:
                case ID_ATTACHED_FILE:
                    return EbmlProcessor.ELEMENT_TYPE_MASTER;
                case ID_FILE_NAME:
                case ID_FILE_MIME_TYPE:
                    return EbmlProcessor.ELEMENT_TYPE_STRING;
                case ID_FILE_DATA:
                    return EbmlProcessor.ELEMENT_TYPE_BINARY;
                default:
                    return super.getElementType(id);
            }
        }

        @Override
        protected boolean isLevel1Element(int id) {
            return super.isLevel1Element(id) || id == ID_ATTACHMENTS;
        }

        @Override
        protected void startMasterElement(int id, long contentPosition, long contentSize) throws ParserException {
            if (id == ID_ATTACHED_FILE) {
                clearAttachment();
            } else {
                super.startMasterElement(id, contentPosition, contentSize);
            }
        }

        @Override
        protected void endMasterElement(int id) throws ParserException {
            if (id == ID_ATTACHED_FILE) {
                if (attachmentName != null && attachmentData != null && isFont(attachmentName, attachmentMime)) {
                    try {
                        assHandler.addFont(attachmentName, attachmentData);
                    } catch (Throwable t) {
                        Log.w(TAG, "addFont " + attachmentName, t);
                    }
                }
                clearAttachment();
                return;
            }
            if (id == ID_VIDEO) {
                Track track = getCurrentTrack(id);
                assHandler.setVideoSize(track.width, track.height); // early, so typeset scales right from the start
            }
            super.endMasterElement(id);
        }

        @Override
        protected void stringElement(int id, String value) throws ParserException {
            if (id == ID_FILE_NAME) {
                attachmentName = value;
            } else if (id == ID_FILE_MIME_TYPE) {
                attachmentMime = value;
            } else {
                super.stringElement(id, value);
            }
        }

        @Override
        protected void binaryElement(int id, int contentSize, ExtractorInput input) throws IOException {
            if (id == ID_FILE_DATA) {
                if (contentSize <= MAX_FONT_BYTES && isFont(attachmentName, attachmentMime)) {
                    attachmentData = new byte[contentSize];
                    input.readFully(attachmentData, 0, contentSize);
                } else {
                    input.skipFully(contentSize); // not a font (cover art etc.): don't hold it in memory
                }
            } else {
                super.binaryElement(id, contentSize, input);
            }
        }

        private static boolean isFont(@Nullable String name, @Nullable String mime) {
            // https://github.com/ietf-wg-cellar/matroska-specification attachments.md "Font files"
            if (mime != null && FONT_MIME_TYPES.contains(mime.toLowerCase(Locale.US))) return true;
            if (name == null) return false;
            String lower = name.toLowerCase(Locale.US);
            for (String ext : FONT_EXTENSIONS) {
                if (lower.endsWith(ext)) return true;
            }
            return false;
        }

        private void clearAttachment() {
            attachmentName = null;
            attachmentMime = null;
            attachmentData = null;
        }
    }

    // ---- ASS tap: text tracks only ----

    private static final class AssTapExtractorOutput implements ExtractorOutput {
        private final ExtractorOutput delegate;
        private final AssHandler assHandler;

        AssTapExtractorOutput(ExtractorOutput delegate, AssHandler assHandler) {
            this.delegate = delegate;
            this.assHandler = assHandler;
        }

        @Override
        public TrackOutput track(int id, int type) {
            TrackOutput out = delegate.track(id, type);
            return type == C.TRACK_TYPE_TEXT ? new AssTapTrackOutput(out, assHandler) : out;
        }

        @Override
        public void endTracks() {
            delegate.endTracks();
        }

        @Override
        public void seekMap(SeekMap seekMap) {
            delegate.seekMap(seekMap);
        }
    }

    /**
     * Forwards everything; for ASS tracks it also copies each sample (MatroskaExtractor writes
     * "Dialogue: 0:00:00:00,&lt;duration&gt;,&lt;ReadOrder,Layer,Style,...,Text&gt;") into libass.
     */
    private static final class AssTapTrackOutput implements TrackOutput {
        private static final Pattern SSA_TIMECODE = Pattern.compile("(?:(\\d+):)?(\\d+):(\\d+)[:.](\\d+)");

        private final TrackOutput delegate;
        private final AssHandler assHandler;
        private boolean isAss;
        @Nullable private String trackId;
        private byte[] pending = new byte[0];
        private int pendingLength;

        AssTapTrackOutput(TrackOutput delegate, AssHandler assHandler) {
            this.delegate = delegate;
            this.assHandler = assHandler;
        }

        @Override
        public void format(Format format) {
            isAss = MimeTypes.TEXT_SSA.equals(format.sampleMimeType) || MimeTypes.TEXT_SSA.equals(format.codecs);
            trackId = format.id;
            delegate.format(format);
        }

        @Override
        public void durationUs(long durationUs) {
            delegate.durationUs(durationUs);
        }

        @Override
        public int sampleData(DataReader input, int length, boolean allowEndOfInput, @SampleDataPart int sampleDataPart)
                throws IOException {
            if (!isAss) {
                return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart);
            }
            byte[] chunk = new byte[length];
            int read = input.read(chunk, 0, length);
            if (read == C.RESULT_END_OF_INPUT) {
                if (allowEndOfInput) return C.RESULT_END_OF_INPUT;
                throw new java.io.EOFException();
            }
            append(chunk, 0, read);
            delegate.sampleData(new ParsableByteArray(chunk, read), read, sampleDataPart);
            return read;
        }

        @Override
        public void sampleData(ParsableByteArray data, int length, @SampleDataPart int sampleDataPart) {
            if (isAss) {
                append(data.getData(), data.getPosition(), length);
            }
            delegate.sampleData(data, length, sampleDataPart);
        }

        @Override
        public void sampleMetadata(long timeUs, @C.BufferFlags int flags, int size, int offset,
                                   @Nullable CryptoData cryptoData) {
            if (isAss) {
                int end = pendingLength - offset;
                int start = end - size;
                if (timeUs != C.TIME_UNSET && start >= 0 && end <= pendingLength) {
                    feedDialogue(timeUs, pending, start, end);
                }
                // keep the bytes that belong to the next sample
                if (offset > 0 && end >= 0) {
                    System.arraycopy(pending, end, pending, 0, offset);
                    pendingLength = offset;
                } else {
                    pendingLength = 0;
                }
            }
            delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData);
        }

        private void feedDialogue(long timeUs, byte[] data, int start, int end) {
            int firstComma = indexOf(data, start, end, (byte) ',');
            if (firstComma < 0) return;
            int secondComma = indexOf(data, firstComma + 1, end, (byte) ',');
            if (secondComma < 0) return;
            long durationUs = parseTimecodeUs(new String(data, firstComma + 1, secondComma - firstComma - 1,
                    StandardCharsets.UTF_8));
            if (durationUs == C.TIME_UNSET) return;
            int textStart = secondComma + 1;
            try {
                assHandler.readTrackDialogue(trackId, timeUs / 1000, durationUs / 1000, data, textStart, end - textStart);
            } catch (Throwable t) {
                Log.w(TAG, "readTrackDialogue", t);
            }
        }

        private void append(byte[] src, int srcOffset, int length) {
            if (pendingLength + length > pending.length) {
                pending = Arrays.copyOf(pending, Math.max(pending.length * 2, pendingLength + length));
            }
            System.arraycopy(src, srcOffset, pending, pendingLength, length);
            pendingLength += length;
        }

        private static int indexOf(byte[] data, int from, int to, byte value) {
            for (int i = from; i < to; i++) {
                if (data[i] == value) return i;
            }
            return -1;
        }

        private static long parseTimecodeUs(String s) {
            Matcher m = SSA_TIMECODE.matcher(s.trim());
            if (!m.matches()) return C.TIME_UNSET;
            long us = m.group(1) != null ? Long.parseLong(m.group(1)) * 3_600_000_000L : 0;
            us += Long.parseLong(m.group(2)) * 60_000_000L;
            us += Long.parseLong(m.group(3)) * 1_000_000L;
            us += Long.parseLong(m.group(4)) * 10_000L; // centiseconds
            return us;
        }
    }

    // ---- parsers ----

    /** For embedded tracks: libass gets the dialogue from the tap, so the parser outputs nothing. */
    private static final class EmbeddedRouting implements SubtitleParser.Factory {
        private final SubtitleParser.Factory base;
        private final AssHandler assHandler;
        private final AtomicBoolean released;

        EmbeddedRouting(SubtitleParser.Factory base, AssHandler assHandler, AtomicBoolean released) {
            this.base = base;
            this.assHandler = assHandler;
            this.released = released;
        }

        @Override
        public boolean supportsFormat(Format format) {
            return MimeTypes.TEXT_SSA.equals(format.sampleMimeType) || base.supportsFormat(format);
        }

        @Override
        public int getCueReplacementBehavior(Format format) {
            return MimeTypes.TEXT_SSA.equals(format.sampleMimeType)
                    ? Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE : base.getCueReplacementBehavior(format);
        }

        @Override
        public SubtitleParser create(Format format) {
            if (MimeTypes.TEXT_SSA.equals(format.sampleMimeType)) {
                if (released.get()) return new NoOutputParser();
                try {
                    assHandler.createTrack(format);
                    return new NoOutputParser();
                } catch (Throwable t) {
                    Log.w(TAG, "libass unavailable, using simple SSA rendering", t);
                    return new DefaultSubtitleParserFactory().create(format);
                }
            }
            return base.create(format);
        }
    }

    /** For external ASS/SSA files: the whole file goes into the libass track; drawing is done by the overlay. */
    public static SubtitleParser.Factory externalFileFactory(AssHandler assHandler, AtomicBoolean released) {
        return new SubtitleParser.Factory() {
            @Override
            public boolean supportsFormat(Format format) {
                return MimeTypes.TEXT_SSA.equals(format.sampleMimeType);
            }

            @Override
            public int getCueReplacementBehavior(Format format) {
                return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;
            }

            @Override
            public SubtitleParser create(Format format) {
                if (released.get()) return new NoOutputParser();
                final io.github.peerless2012.ass.AssTrack track = assHandler.createTrack(format);
                return new NoOutputParser() {
                    @Override
                    public void parse(byte[] data, int offset, int length, OutputOptions outputOptions,
                                      Consumer<CuesWithTiming> output) {
                        track.readBuffer(data, offset, length);
                    }
                };
            }
        };
    }

    private static class NoOutputParser implements SubtitleParser {
        @Override
        public void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
        }

        @Override
        public int getCueReplacementBehavior() {
            return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE;
        }
    }
}
