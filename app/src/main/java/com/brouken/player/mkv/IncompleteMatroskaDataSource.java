package com.brouken.player.mkv;

import android.content.Context;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Purist: serves an incomplete Matroska download as a playable file (see {@link MatroskaRepair}): the rebuilt
 * index where the file says its index is, the downloaded data up to where it ends, and the playable duration in
 * place of the full one. Every other file, and every non-local one, is passed straight through.
 */
public final class IncompleteMatroskaDataSource implements DataSource {

    static final String TAG = "PuristMkv";

    /** One per player, so that each playback checks the file again (a download may have been resumed). */
    public static final class Factory implements DataSource.Factory {
        private static final Object NONE = new Object();
        private final Context context;
        private final DataSource.Factory upstream;
        private final Map<String, Object> results = new HashMap<>();

        public Factory(Context context, DataSource.Factory upstream) {
            this.context = context.getApplicationContext();
            this.upstream = upstream;
        }

        @Override
        public DataSource createDataSource() {
            return new IncompleteMatroskaDataSource(this, upstream.createDataSource());
        }

        /** The repair applied to {@code uri} by this player, if any; never reads the file. */
        @Nullable
        public synchronized MatroskaRepair.Result peek(@Nullable Uri uri) {
            Object r = uri == null ? null : results.get(uri.toString());
            return r instanceof MatroskaRepair.Result ? (MatroskaRepair.Result) r : null;
        }

        /** The first check of an incomplete file takes a few seconds: say why playback has not started yet. */
        private void announceRebuild() {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> android.widget.Toast.makeText(context,
                    com.brouken.player.R.string.purist_incomplete_file_scanning, android.widget.Toast.LENGTH_LONG).show());
        }

        @Nullable
        synchronized MatroskaRepair.Result repairFor(Uri uri) {
            String key = uri.toString();
            Object cached = results.get(key);
            if (cached != null) return cached instanceof MatroskaRepair.Result ? (MatroskaRepair.Result) cached : null;
            MatroskaRepair.Result result = null;
            boolean saved = false;
            long start = System.nanoTime();
            try (FileSource source = FileSource.open(context, uri)) {
                long headCrc = source == null ? -1 : RepairStore.headCrc(source);
                if (headCrc >= 0) {
                    result = RepairStore.load(context, key, source, headCrc);
                    saved = result != null;
                    if (result == null) {
                        result = MatroskaRepair.analyze(source, this::announceRebuild);
                        if (result != null) RepairStore.save(context, key, result, headCrc);
                        else RepairStore.remove(context, key);
                    }
                }
            } catch (Exception | OutOfMemoryError e) {
                Log.w(TAG, "check failed for " + uri, e);
            }
            if (result != null) {
                Log.i(TAG, "incomplete Matroska file, " + result + (saved ? " (saved index, " : " (checked in ")
                        + (System.nanoTime() - start) / 1_000_000 + " ms)");
            }
            results.put(key, result != null ? result : NONE);
            return result;
        }
    }

    private final Factory factory;
    private final DataSource upstream;
    @Nullable private MatroskaRepair.Result repair;
    @Nullable private Uri uri;
    private boolean upstreamOpen;
    @Nullable private byte[] served; // rebuilt index being served
    private long position;
    private long remaining;

    private IncompleteMatroskaDataSource(Factory factory, DataSource upstream) {
        this.factory = factory;
        this.upstream = upstream;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {
        upstream.addTransferListener(transferListener);
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        uri = dataSpec.uri;
        repair = isLocal(dataSpec.uri) ? factory.repairFor(dataSpec.uri) : null;
        served = null;
        if (repair == null) {
            upstreamOpen = true;
            return upstream.open(dataSpec);
        }
        position = dataSpec.position;
        long wanted = dataSpec.length;
        MatroskaRepair.Result r = repair;
        if (position >= r.cuesPosition && position < r.cuesPosition + r.cues.length) {
            served = r.cues;
            remaining = r.cuesPosition + r.cues.length - position;
        } else if (position >= r.dataEnd) {
            remaining = 0;
        } else {
            remaining = r.dataEnd - position;
            if (wanted != C.LENGTH_UNSET) remaining = Math.min(remaining, wanted);
            upstreamOpen = true;
            long opened = upstream.open(dataSpec.subrange(0, remaining));
            if (opened != C.LENGTH_UNSET) remaining = Math.min(remaining, opened);
            return remaining;
        }
        if (wanted != C.LENGTH_UNSET) remaining = Math.min(remaining, wanted);
        return remaining;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (repair == null) return upstream.read(buffer, offset, length);
        if (length == 0) return 0;
        if (remaining <= 0) return C.RESULT_END_OF_INPUT;
        int toRead = (int) Math.min(length, remaining);
        int n;
        if (served != null) {
            System.arraycopy(served, (int) (position - repair.cuesPosition), buffer, offset, toRead);
            n = toRead;
        } else if (upstreamOpen) {
            n = upstream.read(buffer, offset, toRead);
            if (n == C.RESULT_END_OF_INPUT) return n;
            patchDuration(buffer, offset, n);
        } else {
            return C.RESULT_END_OF_INPUT;
        }
        position += n;
        remaining -= n;
        return n;
    }

    /** Puts the playable duration in place of the full one, so the seek bar shows what can be played. */
    private void patchDuration(byte[] buffer, int offset, int n) {
        MatroskaRepair.Result r = repair;
        if (r == null || r.durationBytes == null) return;
        long from = Math.max(position, r.durationPosition);
        long to = Math.min(position + n, r.durationPosition + r.durationBytes.length);
        for (long p = from; p < to; p++) {
            buffer[offset + (int) (p - position)] = r.durationBytes[(int) (p - r.durationPosition)];
        }
    }

    @Nullable
    @Override
    public Uri getUri() {
        return upstreamOpen ? upstream.getUri() : uri;
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
        return upstream.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
        served = null;
        if (upstreamOpen) {
            upstreamOpen = false;
            upstream.close();
        }
    }

    private static boolean isLocal(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null || "content".equals(scheme) || "file".equals(scheme);
    }

    /** Positional reads of a local file, for the check. */
    private static final class FileSource implements MatroskaRepair.Source {
        private final Context context;
        private final Uri uri;
        private final ParcelFileDescriptor pfd;
        private final long length;

        private FileSource(Context context, Uri uri, ParcelFileDescriptor pfd) throws IOException {
            this.context = context;
            this.uri = uri;
            this.pfd = pfd;
            long size = pfd.getStatSize();
            if (size < 0) {
                try {
                    size = Os.fstat(pfd.getFileDescriptor()).st_size;
                } catch (ErrnoException e) {
                    throw new IOException(e);
                }
            }
            length = size;
        }

        @Override
        public MatroskaRepair.Source openAnother() throws IOException {
            FileSource other = open(context, uri);
            if (other == null) throw new IOException("cannot reopen " + uri);
            return other;
        }

        @Nullable
        static FileSource open(Context context, Uri uri) throws IOException {
            ParcelFileDescriptor pfd;
            if ("content".equals(uri.getScheme())) {
                pfd = context.getContentResolver().openFileDescriptor(uri, "r");
            } else if (uri.getPath() != null) {
                pfd = ParcelFileDescriptor.open(new File(uri.getPath()), ParcelFileDescriptor.MODE_READ_ONLY);
            } else {
                return null;
            }
            return pfd == null ? null : new FileSource(context, uri, pfd);
        }

        @Override
        public long length() {
            return length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) throws IOException {
            if (position >= this.length) return 0;
            try {
                return Os.pread(pfd.getFileDescriptor(), buffer, offset, (int) Math.min(length, this.length - position), position);
            } catch (ErrnoException e) {
                throw new IOException(e);
            }
        }

        @Override
        public void close() throws IOException {
            pfd.close();
        }
    }
}
