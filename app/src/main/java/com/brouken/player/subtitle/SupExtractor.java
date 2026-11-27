package com.brouken.player.subtitle;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;

import java.io.IOException;
import java.util.Arrays;

/**
 * Purist: standalone Blu-ray PGS subtitle files (.sup).
 *
 * A .sup file is a list of segments, each with a 13-byte header ("PG", PTS, DTS, type, size). Segments from a
 * presentation composition (PCS) up to the END segment form one display set. Each display set becomes one
 * sample, in the form Matroska/TS carry PGS (segments without the "PG" header), timed by the PCS PTS, and is
 * decoded by Media3's own PgsParser. The file is indexed first (headers only) so the player can seek.
 */
public final class SupExtractor implements Extractor {

    private static final int HEADER_SIZE = 13;
    private static final int SEGMENT_PCS = 0x16;
    private static final int SEGMENT_END = 0x80;
    private static final int SCAN_SEGMENTS_PER_READ = 4096;

    private final Format format;
    @Nullable private final SubtitleParser.Factory transcodingFactory;
    private final ParsableByteArray header = new ParsableByteArray(HEADER_SIZE);

    private ExtractorOutput output;
    private TrackOutput trackOutput;

    // index (first pass)
    private boolean indexed;
    private long[] indexPositions = new long[256];
    private long[] indexTimesUs = new long[256];
    private int indexSize;

    // display set being assembled (second pass)
    private byte[] displaySet = new byte[32 * 1024];
    private int displaySetLength;
    private long displaySetTimeUs = C.TIME_UNSET;

    /**
     * @param format             track format (id, label, language, flags); the sample MIME type is set here
     * @param transcodingFactory parser factory used to turn display sets into cues during extraction
     */
    public SupExtractor(Format format, @Nullable SubtitleParser.Factory transcodingFactory) {
        this.format = format.buildUpon().setSampleMimeType(androidx.media3.common.MimeTypes.APPLICATION_PGS).build();
        this.transcodingFactory = transcodingFactory;
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        byte[] b = new byte[HEADER_SIZE];
        if (!input.peekFully(b, 0, HEADER_SIZE, true)) return false;
        int type = b[10] & 0xFF;
        return b[0] == 'P' && b[1] == 'G' && (type == SEGMENT_PCS || type == 0x14 || type == 0x15 || type == 0x17
                || type == SEGMENT_END);
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = transcodingFactory != null ? new SubtitleTranscodingExtractorOutput(output, transcodingFactory) : output;
        trackOutput = this.output.track(0, C.TRACK_TYPE_TEXT);
        trackOutput.format(format);
        this.output.endTracks();
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        if (!indexed) {
            return scan(input, seekPosition);
        }
        if (!input.readFully(header.getData(), 0, HEADER_SIZE, true)) {
            return RESULT_END_OF_INPUT;
        }
        header.setPosition(0);
        if (header.readUnsignedByte() != 'P' || header.readUnsignedByte() != 'G') {
            throw ParserException.createForMalformedContainer("PGS: missing segment header", null);
        }
        long ptsUs = header.readUnsignedInt() * 100 / 9; // 90 kHz
        header.skipBytes(4); // DTS
        int type = header.readUnsignedByte();
        int size = header.readUnsignedShort();

        if (type == SEGMENT_PCS) {
            displaySetLength = 0;
            displaySetTimeUs = ptsUs;
        }
        ensureCapacity(displaySetLength + 3 + size);
        displaySet[displaySetLength++] = (byte) type;
        displaySet[displaySetLength++] = (byte) (size >> 8);
        displaySet[displaySetLength++] = (byte) size;
        input.readFully(displaySet, displaySetLength, size);
        displaySetLength += size;

        if (type == SEGMENT_END) {
            if (displaySetTimeUs != C.TIME_UNSET) {
                trackOutput.sampleData(new ParsableByteArray(displaySet, displaySetLength), displaySetLength);
                trackOutput.sampleMetadata(displaySetTimeUs, C.BUFFER_FLAG_KEY_FRAME, displaySetLength, 0, null);
            }
            displaySetLength = 0;
            displaySetTimeUs = C.TIME_UNSET;
        }
        return RESULT_CONTINUE;
    }

    /** First pass: record where each display set starts, then rewind for the real read. */
    private int scan(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        for (int i = 0; i < SCAN_SEGMENTS_PER_READ; i++) {
            long position = input.getPosition();
            if (!input.readFully(header.getData(), 0, HEADER_SIZE, true)) {
                finishIndex(seekPosition);
                return RESULT_SEEK;
            }
            header.setPosition(0);
            if (header.readUnsignedByte() != 'P' || header.readUnsignedByte() != 'G') {
                // trailing garbage: index what we have
                finishIndex(seekPosition);
                return RESULT_SEEK;
            }
            long ptsUs = header.readUnsignedInt() * 100 / 9;
            header.skipBytes(4);
            int type = header.readUnsignedByte();
            int size = header.readUnsignedShort();
            if (type == SEGMENT_PCS && (indexSize == 0 || ptsUs >= indexTimesUs[indexSize - 1])) {
                if (indexSize == indexPositions.length) {
                    indexPositions = Arrays.copyOf(indexPositions, indexSize * 2);
                    indexTimesUs = Arrays.copyOf(indexTimesUs, indexSize * 2);
                }
                indexPositions[indexSize] = position;
                indexTimesUs[indexSize] = ptsUs;
                indexSize++;
            }
            if (!input.skipFully(size, true)) {
                finishIndex(seekPosition);
                return RESULT_SEEK;
            }
        }
        return RESULT_CONTINUE;
    }

    private void finishIndex(PositionHolder seekPosition) {
        long[] positions;
        long[] times;
        if (indexSize == 0) {
            positions = new long[]{0};
            times = new long[]{0};
        } else if (indexTimesUs[0] > 0) {
            // allow seeking to the very start
            positions = new long[indexSize + 1];
            times = new long[indexSize + 1];
            positions[0] = indexPositions[0];
            System.arraycopy(indexPositions, 0, positions, 1, indexSize);
            System.arraycopy(indexTimesUs, 0, times, 1, indexSize);
        } else {
            positions = Arrays.copyOf(indexPositions, indexSize);
            times = Arrays.copyOf(indexTimesUs, indexSize);
        }
        long durationUs = times[times.length - 1] + 10 * C.MICROS_PER_SECOND;
        output.seekMap(new IndexSeekMap(positions, times, durationUs));
        indexed = true;
        indexPositions = null;
        indexTimesUs = null;
        seekPosition.position = 0;
    }

    private void ensureCapacity(int capacity) {
        if (capacity > displaySet.length) {
            displaySet = Arrays.copyOf(displaySet, Math.max(capacity, displaySet.length * 2));
        }
    }

    @Override
    public void seek(long position, long timeUs) {
        displaySetLength = 0;
        displaySetTimeUs = C.TIME_UNSET;
    }

    @Override
    public void release() {
    }
}
