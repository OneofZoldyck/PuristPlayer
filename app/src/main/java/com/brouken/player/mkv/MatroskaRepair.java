package com.brouken.player.mkv;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Purist: makes incomplete Matroska downloads playable.
 *
 * A Matroska file keeps its seek index (Cues) at the end. When a download stops early the index is missing
 * (or, for downloaders that reserve the full size up front, the end of the file is all zeros) and Media3 never
 * starts playback, because it reads the index before anything else. This class checks whether the index the
 * file points to is really there. If it is not, it rebuilds an index from the cluster headers of the part that
 * was downloaded, finds where the downloaded data ends, and works out how long that part plays.
 * {@link IncompleteMatroskaDataSource} then serves the rebuilt index in place of the missing one and ends the
 * file where the data ends. Complete files cost two small reads and are left alone.
 */
public final class MatroskaRepair {

    /** Random access to the file. */
    interface Source extends java.io.Closeable {
        long length();

        /** Reads up to {@code length} bytes at {@code position}; returns the number read (0 at the end). */
        int read(long position, byte[] buffer, int offset, int length) throws IOException;

        /** An independent handle on the same file for a parallel reader (closed by the caller). */
        default Source openAnother() throws IOException {
            return this;
        }

        @Override
        default void close() throws IOException {
        }
    }

    /** What was found and how the file is served. */
    public static final class Result {
        /** Where the file says the index is; the rebuilt index is served from here. */
        public final long cuesPosition;
        final byte[] cues;
        /** Downloaded data that plays, from 0; reads stop here. */
        public final long dataEnd;
        /** Absolute position and replacement bytes of the Info duration value, or -1 / null. */
        final long durationPosition;
        final byte[] durationBytes;
        public final long originalDurationMs;
        public final long playableDurationMs;
        public final int cuePoints;
        public final long fileLength;
        /** Bytes just past the downloaded data when it was checked (-1 / empty when the file simply ends). */
        final long probePosition;
        final byte[] probe;

        Result(long cuesPosition, byte[] cues, long dataEnd, long durationPosition, byte[] durationBytes,
               long originalDurationMs, long playableDurationMs, int cuePoints, long fileLength,
               long probePosition, byte[] probe) {
            this.cuesPosition = cuesPosition;
            this.cues = cues;
            this.dataEnd = dataEnd;
            this.durationPosition = durationPosition;
            this.durationBytes = durationBytes;
            this.originalDurationMs = originalDurationMs;
            this.playableDurationMs = playableDurationMs;
            this.cuePoints = cuePoints;
            this.fileLength = fileLength;
            this.probePosition = probePosition;
            this.probe = probe;
        }

        @Override
        public String toString() {
            return "cues at " + cuesPosition + " rebuilt with " + cuePoints + " points (" + cues.length
                    + " bytes), data ends at " + dataEnd + " of " + fileLength + ", plays " + playableDurationMs
                    + " of " + originalDurationMs + " ms";
        }
    }

    private static final int ID_EBML = 0x1A45DFA3;
    private static final int ID_SEGMENT = 0x18538067;
    private static final int ID_SEEK_HEAD = 0x114D9B74;
    private static final int ID_SEEK = 0x4DBB;
    private static final int ID_SEEK_ID = 0x53AB;
    private static final int ID_SEEK_POSITION = 0x53AC;
    private static final int ID_INFO = 0x1549A966;
    private static final int ID_TIMECODE_SCALE = 0x2AD7B1;
    private static final int ID_DURATION = 0x4489;
    private static final int ID_TRACKS = 0x1654AE6B;
    private static final int ID_TRACK_ENTRY = 0xAE;
    private static final int ID_TRACK_NUMBER = 0xD7;
    private static final int ID_TRACK_TYPE = 0x83;
    private static final int ID_FLAG_DEFAULT = 0x88;
    private static final int ID_CLUSTER = 0x1F43B675;
    private static final int ID_CUES = 0x1C53BB6B;
    private static final int ID_CUE_POINT = 0xBB;
    private static final int ID_CUE_TIME = 0xB3;
    private static final int ID_CUE_TRACK_POSITIONS = 0xB7;
    private static final int ID_CUE_TRACK = 0xF7;
    private static final int ID_CUE_CLUSTER_POSITION = 0xF1;
    private static final int ID_ATTACHMENTS = 0x1941A469;
    private static final int ID_CHAPTERS = 0x1043A770;
    private static final int ID_TAGS = 0x1254C367;
    private static final int ID_VOID = 0xEC;
    private static final int ID_CRC32 = 0xBF;
    private static final int ID_TIMECODE = 0xE7;
    private static final int ID_SIMPLE_BLOCK = 0xA3;
    private static final int ID_BLOCK_GROUP = 0xA0;
    private static final int ID_BLOCK = 0xA1;
    private static final int ID_REFERENCE_BLOCK = 0xFB;

    private static final int TRACK_TYPE_VIDEO = 1;
    private static final int TRACK_TYPE_AUDIO = 2;

    private MatroskaRepair() {
    }

    /** @return null when the file is not Matroska, is complete, or cannot be repaired */
    static Result analyze(Source s) throws IOException {
        return analyze(s, null);
    }

    /** @param onRebuild run (on the calling thread) when the index is missing and is about to be rebuilt */
    static Result analyze(Source s, Runnable onRebuild) throws IOException {
        long length = s.length();
        if (length < 64) return null;
        Header ebml = header(s, 0);
        if (ebml == null || ebml.id != ID_EBML || ebml.size < 0) return null;
        Header segment = header(s, ebml.dataPos + ebml.size);
        if (segment == null || segment.id != ID_SEGMENT) return null;
        long segData = segment.dataPos;
        long segEnd = segment.size < 0 ? length : segData + segment.size;

        long cuesRel = -1;
        long timecodeScale = 1_000_000;
        long durationPos = -1;
        int durationSize = 0;
        double duration = -1;
        List<long[]> tracks = new ArrayList<>(); // number, type, flagDefault
        long firstCluster = -1;
        long p = segData;
        for (int n = 0; n < 512 && p < length; n++) {
            Header h = header(s, p);
            if (h == null) return null;
            if (h.id == ID_CLUSTER) {
                firstCluster = p;
                break;
            }
            if (h.size < 0) return null;
            if (h.id == ID_SEEK_HEAD && h.size <= (1 << 20)) {
                byte[] body = body(s, h);
                for (Header seek : children(body, 0, body.length)) {
                    if (seek.id != ID_SEEK) continue;
                    long id = -1, pos = -1;
                    for (Header c : children(body, (int) seek.dataPos, (int) (seek.dataPos + seek.size))) {
                        if (c.id == ID_SEEK_ID) id = uint(body, (int) c.dataPos, (int) c.size);
                        if (c.id == ID_SEEK_POSITION) pos = uint(body, (int) c.dataPos, (int) c.size);
                    }
                    if (id == ID_CUES && cuesRel < 0) cuesRel = pos;
                }
            } else if (h.id == ID_INFO && h.size <= (1 << 20)) {
                byte[] body = body(s, h);
                for (Header c : children(body, 0, body.length)) {
                    if (c.id == ID_TIMECODE_SCALE) timecodeScale = uint(body, (int) c.dataPos, (int) c.size);
                    if (c.id == ID_DURATION && (c.size == 4 || c.size == 8)) {
                        ByteBuffer bb = ByteBuffer.wrap(body, (int) c.dataPos, (int) c.size);
                        duration = c.size == 4 ? bb.getFloat() : bb.getDouble();
                        durationPos = h.dataPos + c.dataPos;
                        durationSize = (int) c.size;
                    }
                }
            } else if (h.id == ID_TRACKS && h.size <= (8 << 20)) {
                byte[] body = body(s, h);
                for (Header t : children(body, 0, body.length)) {
                    if (t.id != ID_TRACK_ENTRY) continue;
                    long number = -1, type = -1, flagDefault = 1;
                    for (Header c : children(body, (int) t.dataPos, (int) (t.dataPos + t.size))) {
                        if (c.id == ID_TRACK_NUMBER) number = uint(body, (int) c.dataPos, (int) c.size);
                        if (c.id == ID_TRACK_TYPE) type = uint(body, (int) c.dataPos, (int) c.size);
                        if (c.id == ID_FLAG_DEFAULT) flagDefault = uint(body, (int) c.dataPos, (int) c.size);
                    }
                    if (number > 0) tracks.add(new long[]{number, type, flagDefault});
                }
            }
            p = h.dataPos + h.size;
        }
        if (cuesRel < 0 || firstCluster < 0 || timecodeScale <= 0) return null;
        long cuesPos = segData + cuesRel;
        if (cuesPos + 16 <= length) {
            Header cues = header(s, cuesPos);
            if (cues != null && cues.id == ID_CUES && cues.size >= 0 && cues.dataPos + cues.size <= length) {
                return null; // the index is there
            }
        }

        // The index is missing: rebuild it from the clusters that were downloaded.
        if (onRebuild != null) onRebuild.run();
        long primary = primaryTrack(tracks);
        boolean primaryIsVideo = false;
        for (long[] t : tracks) if (t[0] == primary) primaryIsVideo = t[1] == TRACK_TYPE_VIDEO;
        if (primary < 0) return null;

        // Walk the clusters (each header gives the next one's position). On phones every read through the media
        // storage layer costs milliseconds, so the file is split into regions that are walked in parallel and
        // then joined, checking that each region starts where the previous one ended.
        final long first = firstCluster;
        final long track = primary;
        final boolean video = primaryIsVideo;
        long scanEnd = Math.min(segEnd, length);
        int regionCount = (int) Math.max(1, Math.min(PARALLEL_REGIONS, (scanEnd - first) / MIN_REGION_BYTES));
        final long[] bounds = new long[regionCount + 1];
        for (int i = 0; i <= regionCount; i++) bounds[i] = first + (scanEnd - first) * i / regionCount;
        bounds[regionCount] = scanEnd;
        Region[] regions = new Region[regionCount];
        if (regionCount == 1) {
            regions[0] = walk(s, first, scanEnd, length, track, video);
        } else {
            ExecutorService pool = Executors.newFixedThreadPool(regionCount);
            try {
                List<Future<Region>> futures = new ArrayList<>();
                for (int i = 0; i < regionCount; i++) {
                    final int k = i;
                    futures.add(pool.submit(() -> {
                        Source rs = k == 0 ? s : s.openAnother();
                        try {
                            long from = k == 0 ? first : findCluster(rs, bounds[k], bounds[k + 1], length);
                            return from < 0 ? null : walk(rs, from, bounds[k + 1], length, track, video);
                        } finally {
                            if (rs != s) rs.close();
                        }
                    }));
                }
                for (int i = 0; i < regionCount; i++) regions[i] = futures.get(i).get();
            } catch (ExecutionException e) {
                throw e.getCause() instanceof IOException ? (IOException) e.getCause() : new IOException(e.getCause());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException();
            } finally {
                pool.shutdownNow();
            }
        }
        List<long[]> clusters = new ArrayList<>(); // position, end, timecode, cue timecode
        long dataEnd = scanEnd;
        long nextClusterTimecode = -1; // timecode of the cluster the data stops in, when its header was read
        p = first;
        for (int i = 0; i < regionCount; i++) {
            Region r = regions[i];
            if (r == null || r.start != p) r = walk(s, p, bounds[i + 1], length, track, video); // resync failed
            if (r == null) return null;
            clusters.addAll(r.clusters);
            if (r.broken) {
                dataEnd = r.end;
                nextClusterTimecode = r.nextTimecode;
                break;
            }
            p = r.end;
        }
        // Downloaders that reserve the whole file leave zeros where data is missing: the last cluster read may
        // have started in the downloaded part and ended in the zeros. Leave it out if its last bytes are zero.
        long probePosition = -1;
        byte[] probe = new byte[0];
        if (!clusters.isEmpty() && dataEnd < length) {
            long[] last = clusters.get(clusters.size() - 1);
            byte[] tail = new byte[64];
            if (last[1] == dataEnd && last[1] - last[0] > 64 && readFully(s, last[1] - 64, tail, 64) && allZero(tail)) {
                clusters.remove(clusters.size() - 1);
                dataEnd = last[0];
                nextClusterTimecode = last[2];
                probePosition = last[1] - 64;
                probe = tail;
            }
        }
        if (probePosition < 0 && dataEnd + 64 <= length) {
            byte[] next = new byte[64];
            if (readFully(s, dataEnd, next, 64)) {
                probePosition = dataEnd;
                probe = next;
            }
        }
        List<long[]> cuePoints = new ArrayList<>(); // timecode, cluster position
        for (long[] c : clusters) if (c[3] >= 0) cuePoints.add(new long[]{c[3], c[0]});
        long lastClusterTimecode = clusters.isEmpty() ? -1 : clusters.get(clusters.size() - 1)[2];
        long prevClusterTimecode = clusters.size() < 2 ? -1 : clusters.get(clusters.size() - 2)[2];
        if (cuePoints.isEmpty()) return null;

        long originalTimecode = duration > 0 ? (long) duration : -1;
        long playableTimecode = nextClusterTimecode >= 0 ? nextClusterTimecode
                : lastClusterTimecode >= 0 && prevClusterTimecode >= 0
                ? lastClusterTimecode + (lastClusterTimecode - prevClusterTimecode) : lastClusterTimecode;
        byte[] durationBytes = null;
        if (durationPos >= 0 && playableTimecode > 0 && originalTimecode > 0 && playableTimecode < originalTimecode) {
            ByteBuffer bb = ByteBuffer.allocate(durationSize);
            if (durationSize == 4) bb.putFloat(playableTimecode);
            else bb.putDouble(playableTimecode);
            durationBytes = bb.array();
        } else {
            durationPos = -1;
        }

        ByteArrayOutputStream points = new ByteArrayOutputStream(cuePoints.size() * 24);
        for (long[] cue : cuePoints) {
            byte[] trackPositions = element(ID_CUE_TRACK_POSITIONS,
                    concat(uintElement(ID_CUE_TRACK, primary), uintElement(ID_CUE_CLUSTER_POSITION, cue[1] - segData)));
            byte[] point = element(ID_CUE_POINT, concat(uintElement(ID_CUE_TIME, cue[0]), trackPositions));
            points.write(point, 0, point.length);
        }
        byte[] cues = element(ID_CUES, points.toByteArray());
        long msPerTimecode = timecodeScale; // ns per timecode unit
        return new Result(cuesPos, cues, dataEnd, durationPos, durationBytes,
                originalTimecode > 0 ? originalTimecode * msPerTimecode / 1_000_000 : -1,
                playableTimecode > 0 ? playableTimecode * msPerTimecode / 1_000_000 : -1,
                cuePoints.size(), length, probePosition, probe);
    }

    private static final int PARALLEL_REGIONS = 4;
    private static final long MIN_REGION_BYTES = 256L << 20;
    private static final int RESYNC_CHUNK = 256 * 1024;
    private static final long RESYNC_LIMIT = 64L << 20;

    /** Clusters walked from {@code start}; {@code end} is where the walk stopped. */
    private static final class Region {
        final long start;
        final List<long[]> clusters = new ArrayList<>();
        long end;
        boolean broken; // stopped at something that is not a complete element (missing data)
        long nextTimecode = -1;

        Region(long start) {
            this.start = start;
        }
    }

    /** Walks level 1 elements from {@code start} until {@code limit} is passed or the data stops. */
    private static Region walk(Source s, long start, long limit, long length, long primary, boolean primaryIsVideo)
            throws IOException {
        Region r = new Region(start);
        byte[] buf = new byte[4096];
        long p = start;
        while (p < limit) {
            if (p >= length) {
                r.broken = true;
                break;
            }
            int got = s.read(p, buf, 0, buf.length);
            Header h = header(buf, 0, got);
            if (h == null || !isLevel1(h.id) || h.size < 0) {
                if (h != null && h.id == ID_CLUSTER && h.size < 0) return null; // unknown-size clusters: give up
                r.broken = true;
                break;
            }
            long end = p + h.dataPos + h.size;
            if (h.id == ID_CLUSTER) {
                long[] info = inspectCluster(s, buf, got, p, h, primary, primaryIsVideo);
                if (end > length) { // plain truncation inside this cluster
                    r.nextTimecode = info[0];
                    r.broken = true;
                    break;
                }
                if (info[0] >= 0) r.clusters.add(new long[]{p, end, info[0], info[1]});
            }
            p = end;
        }
        r.end = p;
        return r;
    }

    /** First cluster that starts in [from, limit), confirmed by the element that follows it; -1 if none. */
    private static long findCluster(Source s, long from, long limit, long length) throws IOException {
        byte[] chunk = new byte[RESYNC_CHUNK + 3];
        long stop = Math.min(Math.min(limit, from + RESYNC_LIMIT), length);
        for (long base = from; base < stop; base += RESYNC_CHUNK) {
            int n = s.read(base, chunk, 0, chunk.length);
            if (n < 4) return -1;
            boolean zeros = true;
            for (int i = 0; i + 3 < n; i++) {
                if (chunk[i] != 0) zeros = false;
                if (chunk[i] != 0x1F || chunk[i + 1] != 0x43 || chunk[i + 2] != (byte) 0xB6 || chunk[i + 3] != 0x75) continue;
                long c = base + i;
                if (c >= stop) return -1;
                Header h = header(s, c);
                if (h == null || h.size < 0) continue;
                byte[] firstChild = new byte[1];
                if (!readFully(s, h.dataPos, firstChild, 1)
                        || (firstChild[0] != (byte) ID_TIMECODE && firstChild[0] != (byte) ID_CRC32)) {
                    continue;
                }
                long next = h.dataPos + h.size;
                if (next >= length) return c;
                Header after = header(s, next);
                if (after != null && isLevel1(after.id)) return c;
            }
            if (zeros) return -1; // missing data (zeros)
        }
        return -1;
    }

    /** Media3's choice of seek track: default video, first video, default audio, first audio, first track. */
    private static long primaryTrack(List<long[]> tracks) {
        long[] picks = {-1, -1, -1, -1};
        for (long[] t : tracks) {
            if (t[1] == TRACK_TYPE_VIDEO) {
                if (t[2] != 0 && picks[0] < 0) picks[0] = t[0];
                if (picks[1] < 0) picks[1] = t[0];
            } else if (t[1] == TRACK_TYPE_AUDIO) {
                if (t[2] != 0 && picks[2] < 0) picks[2] = t[0];
                if (picks[3] < 0) picks[3] = t[0];
            }
        }
        for (long pick : picks) if (pick >= 0) return pick;
        return tracks.isEmpty() ? -1 : tracks.get(0)[0];
    }

    /**
     * @return {cluster timecode, cue timecode or -1}. The cue is the first block of the seek track in the cluster
     * when it is a keyframe (audio blocks always are); a cluster that starts mid group of pictures gets no cue.
     * Reads go through a window (the first read of the cluster, then 64 KiB refills) so that small audio blocks
     * ahead of the video do not each cost a read.
     */
    private static long[] inspectCluster(Source s, byte[] buf, int got, long clusterPos, Header cluster,
                                         long primary, boolean primaryIsVideo) throws IOException {
        Window w = new Window(s, buf, clusterPos, got);
        long timecode = -1;
        long q = clusterPos + cluster.dataPos;
        long end = clusterPos + cluster.dataPos + cluster.size;
        for (int i = 0; i < 256 && q < end; i++) {
            int off = w.at(q, 16);
            if (off < 0) break;
            Header e = header(w.data, off, w.available(q));
            if (e == null || e.size < 0) break;
            if (e.id == ID_TIMECODE && e.size <= 8) {
                int d = w.at(q + e.dataPos, (int) e.size);
                if (d < 0) break;
                timecode = uint(w.data, d, (int) e.size);
            } else if (e.id == ID_SIMPLE_BLOCK || e.id == ID_BLOCK_GROUP) {
                if (timecode < 0) break;
                long blockPos = -1;
                boolean hasReference = false;
                if (e.id == ID_SIMPLE_BLOCK) {
                    blockPos = q + e.dataPos;
                } else {
                    long g = q + e.dataPos, gEnd = q + e.dataPos + e.size;
                    for (int k = 0; k < 8 && g < gEnd; k++) {
                        int go = w.at(g, 12);
                        if (go < 0) break;
                        Header c = header(w.data, go, w.available(g));
                        if (c == null || c.size < 0) break;
                        if (c.id == ID_BLOCK) blockPos = g + c.dataPos;
                        if (c.id == ID_REFERENCE_BLOCK) hasReference = true;
                        g += c.dataPos + c.size;
                    }
                }
                if (blockPos >= 0) {
                    int bo = w.at(blockPos, 4);
                    if (bo < 0) break;
                    int trackLen = vintLength(w.data[bo]);
                    if (trackLen > 8) break;
                    bo = w.at(blockPos, trackLen + 3);
                    if (bo < 0) break;
                    long track = vintValue(w.data, bo, trackLen);
                    if (track == primary) {
                        int rel = (short) (((w.data[bo + trackLen] & 0xFF) << 8) | (w.data[bo + trackLen + 1] & 0xFF));
                        boolean key = e.id == ID_SIMPLE_BLOCK ? (w.data[bo + trackLen + 2] & 0x80) != 0 : !hasReference;
                        long cue = timecode + rel;
                        return new long[]{timecode, (key || !primaryIsVideo) && cue >= 0 ? cue : -1};
                    }
                }
            }
            q += e.dataPos + e.size;
        }
        return new long[]{timecode, -1};
    }

    /** A readable window on the file: starts as the cluster's first read, refilled 64 KiB at a time. */
    private static final class Window {
        private static final int REFILL = 64 * 1024;
        private static final int MAX_REFILLS = 4;
        private final Source source;
        byte[] data;
        private long position;
        private int length;
        private byte[] refill;
        private int refills;

        Window(Source source, byte[] first, long position, int length) {
            this.source = source;
            this.data = first;
            this.position = position;
            this.length = length;
        }

        /** Offset in {@link #data} of {@code p}, with at least {@code need} bytes readable; -1 when not possible. */
        int at(long p, int need) throws IOException {
            if (p >= position && p + need <= position + length) return (int) (p - position);
            if (refills++ >= MAX_REFILLS) return -1;
            if (refill == null) refill = new byte[REFILL];
            int n = source.read(p, refill, 0, REFILL);
            data = refill;
            position = p;
            length = Math.max(n, 0);
            return need <= length ? 0 : -1;
        }

        int available(long p) {
            return (int) (position + length - p);
        }
    }

    private static boolean isLevel1(int id) {
        switch (id) {
            case ID_CLUSTER:
            case ID_CUES:
            case ID_TAGS:
            case ID_CHAPTERS:
            case ID_ATTACHMENTS:
            case ID_SEEK_HEAD:
            case ID_INFO:
            case ID_TRACKS:
            case ID_VOID:
            case ID_CRC32:
                return true;
            default:
                return false;
        }
    }

    // ---- EBML ----

    /** An element: id, header length (dataPos, relative to where it was parsed), size (-1 when unknown). */
    static final class Header {
        final int id;
        final long dataPos;
        final long size;

        Header(int id, long dataPos, long size) {
            this.id = id;
            this.dataPos = dataPos;
            this.size = size;
        }
    }

    private static Header header(Source s, long position) throws IOException {
        byte[] b = new byte[16];
        int n = s.read(position, b, 0, b.length);
        Header h = header(b, 0, n);
        return h == null ? null : new Header(h.id, position + h.dataPos, h.size);
    }

    /** Parses an element header at {@code off}; dataPos is relative to {@code off}. */
    static Header header(byte[] b, int off, int available) {
        if (available < 2) return null;
        int idLen = vintLength(b[off]);
        if (idLen > 4 || idLen >= available) return null;
        int id = (int) uint(b, off, idLen);
        int sizeLen = vintLength(b[off + idLen]);
        if (sizeLen > 8 || idLen + sizeLen > available) return null;
        long size = vintValue(b, off + idLen, sizeLen);
        if (size == (1L << (7 * sizeLen)) - 1) size = -1;
        return new Header(id, idLen + sizeLen, size);
    }

    private static List<Header> children(byte[] body, int from, int to) {
        List<Header> list = new ArrayList<>();
        int p = from;
        while (p < to) {
            Header h = header(body, p, to - p);
            if (h == null || h.size < 0 || p + h.dataPos + h.size > to) break;
            list.add(new Header(h.id, p + h.dataPos, h.size));
            p += (int) (h.dataPos + h.size);
        }
        return list;
    }

    private static byte[] body(Source s, Header h) throws IOException {
        byte[] body = new byte[(int) h.size];
        if (!readFully(s, h.dataPos, body, body.length)) throw new IOException("short read");
        return body;
    }

    /** 9 when the first byte is 0 (invalid). */
    private static int vintLength(byte first) {
        int b = first & 0xFF;
        for (int i = 0; i < 8; i++) if ((b & (0x80 >> i)) != 0) return i + 1;
        return 9;
    }

    private static long vintValue(byte[] b, int off, int len) {
        long v = b[off] & 0xFF & ((0x80 >> (len - 1)) - 1);
        for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static long uint(byte[] b, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[off + i] & 0xFF);
        return v;
    }

    private static byte[] element(int id, byte[] payload) {
        byte[] idBytes = idBytes(id);
        int sizeLen = 1;
        while (sizeLen < 8 && payload.length >= (1L << (7 * sizeLen)) - 1) sizeLen++;
        byte[] out = new byte[idBytes.length + sizeLen + payload.length];
        System.arraycopy(idBytes, 0, out, 0, idBytes.length);
        long v = payload.length | (1L << (7 * sizeLen));
        for (int i = 0; i < sizeLen; i++) out[idBytes.length + i] = (byte) (v >>> (8 * (sizeLen - 1 - i)));
        System.arraycopy(payload, 0, out, idBytes.length + sizeLen, payload.length);
        return out;
    }

    private static byte[] uintElement(int id, long value) {
        int n = 1;
        while (n < 8 && (value >>> (8 * n)) != 0) n++;
        byte[] payload = new byte[n];
        for (int i = 0; i < n; i++) payload[i] = (byte) (value >>> (8 * (n - 1 - i)));
        return element(id, payload);
    }

    private static byte[] idBytes(int id) {
        int n = id > 0xFFFFFF ? 4 : id > 0xFFFF ? 3 : id > 0xFF ? 2 : 1;
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) (id >>> (8 * (n - 1 - i)));
        return b;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    private static boolean readFully(Source s, long position, byte[] b, int len) throws IOException {
        int done = 0;
        while (done < len) {
            int n = s.read(position + done, b, done, len - done);
            if (n <= 0) return false;
            done += n;
        }
        return true;
    }

    private static boolean allZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }
}
