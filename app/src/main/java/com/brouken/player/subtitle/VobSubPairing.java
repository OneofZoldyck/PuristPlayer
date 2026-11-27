package com.brouken.player.subtitle;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import androidx.annotation.Nullable;
import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Purist: VobSub comes as two files (.idx + .sub) but Android only grants access to the file that was picked.
 * This looks for the partner file next to it where the app is allowed to read; if it can't be found the user is
 * asked to pick it as well.
 */
public final class VobSubPairing {

    private VobSubPairing() {
    }

    /**
     * @param picked      the file the user picked (.idx or .sub)
     * @param displayName its real name (with extension)
     * @param wantedExt   "idx" or "sub"
     * @param scopeUri    folder the user granted to the app (Just Player's "subtitle scope"), or null
     * @return a readable URI of the partner, or null
     */
    @Nullable
    public static Uri findPartner(Context context, Uri picked, @Nullable String displayName, String wantedExt,
                                  @Nullable Uri scopeUri) {
        String name = displayName != null ? displayName : picked.getLastPathSegment();
        if (name == null) return null;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String wantedName = base + "." + wantedExt;

        // 1. plain file path
        if (ContentResolver.SCHEME_FILE.equals(picked.getScheme()) && picked.getPath() != null) {
            File parent = new File(picked.getPath()).getParentFile();
            File[] files = parent != null ? parent.listFiles() : null;
            if (files != null) {
                for (File f : files) {
                    if (f.getName().equalsIgnoreCase(wantedName)) return Uri.fromFile(f);
                }
            }
        }

        // 2. external storage document: sibling document id, readable if inside the granted folder
        if ("com.android.externalstorage.documents".equals(picked.getAuthority()) && DocumentsContract.isDocumentUri(context, picked)) {
            String docId = DocumentsContract.getDocumentId(picked);
            int slash = docId.lastIndexOf('/');
            int colon = docId.indexOf(':');
            String dir = slash >= 0 ? docId.substring(0, slash + 1) : (colon >= 0 ? docId.substring(0, colon + 1) : "");
            for (String candidateName : new String[]{wantedName, base + "." + wantedExt.toUpperCase(Locale.US)}) {
                String siblingId = dir + candidateName;
                if (scopeUri != null) {
                    try {
                        Uri u = DocumentsContract.buildDocumentUriUsingTree(scopeUri, siblingId);
                        if (readable(context, u)) return u;
                    } catch (Exception ignored) {
                    }
                }
                Uri u = DocumentsContract.buildDocumentUri(picked.getAuthority(), siblingId);
                if (readable(context, u)) return u;
            }
        }

        // 3. inside the granted folder (any provider): find the picked file, then its sibling
        if (scopeUri != null) {
            try {
                DocumentFile tree = DocumentFile.fromTreeUri(context, scopeUri);
                DocumentFile single = DocumentFile.fromSingleUri(context, picked);
                DocumentFile found = findByNameAndSize(tree, single != null ? single.getName() : name,
                        single != null ? single.length() : -1, 4);
                DocumentFile parent = found != null ? found.getParentFile() : null;
                if (parent != null) {
                    for (DocumentFile f : parent.listFiles()) {
                        if (f.getName() != null && f.getName().equalsIgnoreCase(wantedName) && readable(context, f.getUri())) {
                            return f.getUri();
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

        // 4. MediaStore (same folder, same base name)
        if (Build.VERSION.SDK_INT >= 29) {
            String relativePath = relativePath(context, picked);
            if (relativePath != null) {
                Uri collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL);
                try (Cursor c = context.getContentResolver().query(collection, new String[]{MediaStore.MediaColumns._ID},
                        "LOWER(" + MediaStore.MediaColumns.DISPLAY_NAME + ")=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                        new String[]{wantedName.toLowerCase(Locale.US), relativePath}, null)) {
                    if (c != null && c.moveToFirst()) {
                        Uri u = ContentUris.withAppendedId(collection, c.getLong(0));
                        if (readable(context, u)) return u;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    @Nullable
    private static String relativePath(Context context, Uri uri) {
        try {
            if (MediaStore.AUTHORITY.equals(uri.getAuthority())) {
                try (Cursor c = context.getContentResolver().query(uri, new String[]{MediaStore.MediaColumns.RELATIVE_PATH},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) return c.getString(0);
                }
            } else if ("com.android.externalstorage.documents".equals(uri.getAuthority())) {
                String docId = DocumentsContract.getDocumentId(uri); // "primary:Movies/x.idx"
                int colon = docId.indexOf(':');
                int slash = docId.lastIndexOf('/');
                if (colon >= 0 && slash > colon) return docId.substring(colon + 1, slash + 1);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @Nullable
    private static DocumentFile findByNameAndSize(@Nullable DocumentFile dir, @Nullable String name, long size, int depth) {
        if (dir == null || name == null || depth < 0) return null;
        for (DocumentFile f : dir.listFiles()) {
            if (f.isDirectory()) {
                DocumentFile r = findByNameAndSize(f, name, size, depth - 1);
                if (r != null) return r;
            } else if (name.equals(f.getName()) && (size < 0 || f.length() == size)) {
                return f;
            }
        }
        return null;
    }

    private static boolean readable(Context context, Uri uri) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            return in != null && in.read() >= 0;
        } catch (Exception e) {
            return false;
        }
    }
}
