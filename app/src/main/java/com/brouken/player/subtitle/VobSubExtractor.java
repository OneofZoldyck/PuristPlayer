package com.brouken.player.subtitle;

import android.util.SparseArray;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.IndexSeekMap;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.extractor.text.SubtitleTranscodingExtractorOutput;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Purist: standalone VobSub subtitles (.sub + .idx).
 *
 * The .sub is an MPEG program stream; subpicture units (SPUs) travel in private-stream-1 PES packets with
 * sub-stream id 0x20 + language index, split over several packs. This reassembles each SPU and emits it as a
 * sample, timed by the matching .idx entry (the .idx "filepos" points at the pack where the SPU starts), for
 * Media3's own VobsubParser (palette and size come from the .idx header). The .idx also gives the seek index.
 */
public final class VobSubExtractor implements Extractor {

    private static final int PACK_START = 0xBA;
    private static final int PRIVATE_STREAM_1 = 0xBD;

    private final Format baseFormat;
    private final File idxFile;
    @Nullable private final SubtitleParser.Factory transcodingFactory;

    private ExtractorOutput output;
    private boolean initialized;
    private final SparseArray<StreamState> streams = new SparseArray<>(); // by sub-stream index
    private final Map<Long, Long> timeByPackPosition = new HashMap<>();
    private final byte[] startCode = new byte[4];
    private final ParsableByteArray scratch = new ParsableByteArray(16);
    private long currentPackPosition = C.INDEX_UNSET;
    private byte[] pes = new byte[4096];

    private static final class StreamState {
        final TrackOutput output;
        byte[] spu = new byte[8192];
        int spuSize;      // total size announced by the SPU, 0 = none in progress
        int spuLength;    // bytes collected
        long spuTimeUs = C.TIME_UNSET;

        StreamState(TrackOutput output) {
            this.output = output;
        }
    }

    /**
     * @param baseFormat track format (id, label, flags) for the sidecar
     * @param idxFile    cached copy of the .idx (see {@link VobSubIndex#store})
     */
    public VobSubExtractor(Format baseFormat, File idxFile, @Nullable SubtitleParser.Factory transcodingFactory) {
        this.baseFormat = baseFormat;
        this.idxFile = idxFile;
        this.transcodingFactory = transcodingFactory;
    }

    @Override
    public boolean sniff(ExtractorInput input) throws IOException {
        byte[] b = new byte[4];
        return input.peekFully(b, 0, 4, true) && b[0] == 0 && b[1] == 0 && b[2] == 1 && (b[3] & 0xFF) == PACK_START;
    }

    @Override
    public void init(ExtractorOutput output) {
        this.output = transcodingFactory != null ? new SubtitleTranscodingExtractorOutput(output, transcodingFactory) : output;
    }

    private void initializeFromIndex() throws IOException {
        VobSubIndex index;
        try (InputStream in = new FileInputStream(idxFile)) {
            index = VobSubIndex.parse(VobSubIndex.readAll(in));
        }
        List<long[]> seekPoints = new ArrayList<>();
        boolean multiple = index.streams.size() > 1;
        for (int i = 0; i < index.streams.size(); i++) {
            VobSubIndex.Stream s = index.streams.get(i);
            Format.Builder f = baseFormat.buildUpon()
                    .setSampleMimeType(MimeTypes.APPLICATION_VOBSUB)
                    .setInitializationData(Collections.singletonList(index.header));
            if (!s.language.isEmpty()) f.setLanguage(s.language);
            if (multiple) {
                f.setId(baseFormat.id + "-" + s.index);
                if (baseFormat.label != null) f.setLabel(baseFormat.label + " (" + s.language + ")");
                f.setSelectionFlags(s.index == index.defaultStreamIndex ? baseFormat.selectionFlags : 0);
            }
            TrackOutput track = output.track(s.index, C.TRACK_TYPE_TEXT);
            track.format(f.build());
            streams.put(s.index, new StreamState(track));
            for (long[] e : s.entries) {
                timeByPackPosition.put(e[1], e[0]);
                seekPoints.add(e);
            }
        }
        output.endTracks();

        Collections.sort(seekPoints, (a, b) -> Long.compare(a[0], b[0]));
        long[] times = new long[seekPoints.size() + 1];
        long[] positions = new long[seekPoints.size() + 1];
        times[0] = 0;
        positions[0] = 0;
        for (int i = 0; i < seekPoints.size(); i++) {
            times[i + 1] = seekPoints.get(i)[0];
            positions[i + 1] = seekPoints.get(i)[1];
        }
        output.seekMap(new IndexSeekMap(positions, times, times[times.length - 1] + 10 * C.MICROS_PER_SECOND));
        initialized = true;
    }

    @Override
    public int read(ExtractorInput input, PositionHolder seekPosition) throws IOException {
        if (!initialized) {
            initializeFromIndex();
            return RESULT_CONTINUE;
        }
        long position = input.getPosition();
        if (!input.peekFully(startCode, 0, 4, true)) {
            return RESULT_END_OF_INPUT;
        }
        input.resetPeekPosition();
        if (startCode[0] != 0 || startCode[1] != 0 || startCode[2] != 1) {
            input.skipFully(1); // not on a start code: resync byte by byte
            return RESULT_CONTINUE;
        }
        input.skipFully(4);
        int code = startCode[3] & 0xFF;
        if (code == PACK_START) {
            currentPackPosition = position;
            skipPackHeader(input);
        } else if (code >= 0xBB) {
            int length = readUnsignedShort(input);
            if (code == PRIVATE_STREAM_1) {
                readPrivateStream(input, length);
            } else {
                input.skipFully(length); // system header, padding, other streams
            }
        }
        // anything else (program end, stray codes): carry on with the next bytes
        return RESULT_CONTINUE;
    }

    private void skipPackHeader(ExtractorInput input) throws IOException {
        byte[] b = scratch.getData();
        input.readFully(b, 0, 1);
        if ((b[0] & 0xC0) == 0x40) {
            // MPEG-2: 10 bytes after the start code, last 3 bits of the 10th = stuffing length
            input.readFully(b, 1, 9);
            input.skipFully(b[9] & 0x07);
        } else {
            // MPEG-1: 8 bytes after the start code
            input.readFully(b, 1, 7);
        }
    }

    private void readPrivateStream(ExtractorInput input, int length) throws IOException {
        if (length > pes.length) pes = new byte[length];
        input.readFully(pes, 0, length);
        int payload;
        if ((pes[0] & 0xC0) == 0x80) {
            payload = 3 + (pes[2] & 0xFF); // MPEG-2 PES header
        } else {
            // MPEG-1 PES header: stuffing, optional STD buffer, PTS/DTS
            int p = 0;
            while (p < length && (pes[p] & 0xFF) == 0xFF) p++;
            if (p < length && (pes[p] & 0xC0) == 0x40) p += 2;
            if (p < length && (pes[p] & 0xF0) == 0x20) p += 5;
            else if (p < length && (pes[p] & 0xF0) == 0x30) p += 10;
            else p += 1;
            payload = p;
        }
        if (payload >= length) return;
        int subStream = pes[payload] & 0xFF;
        if (subStream < 0x20 || subStream > 0x3F) return; // not a subpicture
        StreamState stream = streams.get(subStream - 0x20);
        if (stream == null) return;
        int dataStart = payload + 1;
        int dataLength = length - dataStart;
        if (dataLength <= 0) return;

        if (stream.spuSize == 0) {
            // start of a new SPU: needs its time from the index
            Long timeUs = timeByPackPosition.get(currentPackPosition);
            if (timeUs == null || dataLength < 2) return; // mid-SPU after a seek, or unknown: skip
            stream.spuSize = ((pes[dataStart] & 0xFF) << 8) | (pes[dataStart + 1] & 0xFF);
            stream.spuLength = 0;
            stream.spuTimeUs = timeUs;
            if (stream.spuSize < 4) {
                stream.spuSize = 0;
                return;
            }
            if (stream.spu.length < stream.spuSize) stream.spu = new byte[stream.spuSize];
        }
        int copy = Math.min(dataLength, stream.spuSize - stream.spuLength);
        System.arraycopy(pes, dataStart, stream.spu, stream.spuLength, copy);
        stream.spuLength += copy;
        if (stream.spuLength >= stream.spuSize) {
            stream.output.sampleData(new ParsableByteArray(stream.spu, stream.spuSize), stream.spuSize);
            stream.output.sampleMetadata(stream.spuTimeUs, C.BUFFER_FLAG_KEY_FRAME, stream.spuSize, 0, null);
            stream.spuSize = 0;
            stream.spuLength = 0;
            stream.spuTimeUs = C.TIME_UNSET;
        }
    }

    private int readUnsignedShort(ExtractorInput input) throws IOException {
        byte[] b = scratch.getData();
        input.readFully(b, 0, 2);
        return ((b[0] & 0xFF) << 8) | (b[1] & 0xFF);
    }

    @Override
    public void seek(long position, long timeUs) {
        for (int i = 0; i < streams.size(); i++) {
            StreamState s = streams.valueAt(i);
            s.spuSize = 0;
            s.spuLength = 0;
            s.spuTimeUs = C.TIME_UNSET;
        }
        currentPackPosition = C.INDEX_UNSET;
    }

    @Override
    public void release() {
    }

}
