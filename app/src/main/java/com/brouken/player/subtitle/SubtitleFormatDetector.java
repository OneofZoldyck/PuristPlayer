package com.brouken.player.subtitle;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Purist: works out a subtitle file's format from its real file name (DISPLAY_NAME for content:// URIs, which
 * carry no extension), the provider's MIME type and, when those are ambiguous or missing, the file content.
 */
public final class SubtitleFormatDetector {

    public static final class Result {
        public final String mime;
        @Nullable public final String displayName;
        public final boolean binary;

        Result(String mime, @Nullable String displayName) {
            this.mime = mime;
            this.displayName = displayName;
            this.binary = SubtitleMimes.isBinarySidecar(mime);
        }
    }

    private static final Pattern MICRODVD = Pattern.compile("(?m)^\\s*\\{\\d+\\}\\{\\d*\\}");
    private static final Pattern MPL2 = Pattern.compile("(?m)^\\s*\\[\\d+\\]\\[\\d*\\]");
    private static final Pattern SUBVIEWER = Pattern.compile("(?m)^\\s*\\d+:\\d{1,2}:\\d{1,2}[.,]\\d{1,3}\\s*,\\s*\\d+:\\d{1,2}:\\d{1,2}[.,]\\d{1,3}\\s*$");
    private static final Pattern TMPLAYER = Pattern.compile("(?m)^\\s*\\d{1,2}:\\d{2}:\\d{2}[:=]");
    private static final Pattern SUBRIP = Pattern.compile("\\d{1,2}:\\d{2}:\\d{2}[,.]\\d{1,3}\\s*-->");

    private SubtitleFormatDetector() {
    }

    /** @return the detected format, or null if the file isn't a recognised subtitle format */
    @Nullable
    public static Result detect(Context context, Uri uri) {
        String name = displayName(context, uri);
        String ext = extension(name != null ? name : uri.getLastPathSegment());
        byte[] head = readHead(context, uri, 8192);

        String mime = fromExtension(ext, head);
        if (mime == null) {
            String type = ContentResolver.SCHEME_CONTENT.equals(uri.getScheme()) ? context.getContentResolver().getType(uri) : null;
            mime = fromProviderType(type);
        }
        if (mime == null && head != null) {
            mime = sniff(head);
        }
        return mime != null ? new Result(mime, name) : null;
    }

    @Nullable
    private static String fromExtension(@Nullable String ext, @Nullable byte[] head) {
        if (ext == null) return null;
        switch (ext) {
            case "srt": return MimeTypes.APPLICATION_SUBRIP;
            case "ssa":
            case "ass": return MimeTypes.TEXT_SSA;
            case "vtt":
            case "webvtt": return MimeTypes.TEXT_VTT;
            case "ttml":
            case "dfxp":
            case "xml": return MimeTypes.APPLICATION_TTML;
            case "smi":
            case "sami": return SubtitleMimes.TEXT_SAMI;
            case "mpl": return SubtitleMimes.TEXT_MPL2;
            case "sbv": return SubtitleMimes.TEXT_SUBVIEWER;
            case "sup": return SubtitleMimes.APPLICATION_PGS_SUP;
            case "idx": return SubtitleMimes.APPLICATION_VOBSUB_IDX;
            case "sub":
                if (head != null && isVobSubPack(head)) return SubtitleMimes.APPLICATION_VOBSUB_SUB;
                String sniffed = head != null ? sniff(head) : null;
                return sniffed != null ? sniffed : SubtitleMimes.TEXT_MICRODVD;
            case "txt":
                return head != null ? sniff(head) : null; // .txt is only a subtitle if its content says so
            default:
                return null;
        }
    }

    @Nullable
    private static String fromProviderType(@Nullable String type) {
        if (type == null) return null;
        switch (type) {
            case MimeTypes.APPLICATION_SUBRIP: return MimeTypes.APPLICATION_SUBRIP;
            case MimeTypes.TEXT_SSA:
            case "application/ass":
            case "application/ssa": return MimeTypes.TEXT_SSA;
            case MimeTypes.TEXT_VTT:
            case "application/vtt": return MimeTypes.TEXT_VTT;
            case MimeTypes.APPLICATION_TTML: return MimeTypes.APPLICATION_TTML;
            case "application/x-sami":
            case "application/smil":
            case "application/smil+xml": return SubtitleMimes.TEXT_SAMI;
            default: return null; // text/plain, octet-stream, …: decide from content
        }
    }

    /** Content sniffing on the first few KB. */
    @Nullable
    static String sniff(byte[] head) {
        if (head.length >= 2 && head[0] == 'P' && head[1] == 'G') return SubtitleMimes.APPLICATION_PGS_SUP;
        if (isVobSubPack(head)) return SubtitleMimes.APPLICATION_VOBSUB_SUB;
        String s = new String(head, StandardCharsets.UTF_8);
        if (!s.isEmpty() && s.charAt(0) == '﻿') s = s.substring(1);
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("# vobsub index file")) return SubtitleMimes.APPLICATION_VOBSUB_IDX;
        if (s.startsWith("WEBVTT")) return MimeTypes.TEXT_VTT;
        if (lower.contains("[script info]")) return MimeTypes.TEXT_SSA;
        if (lower.contains("<sami")) return SubtitleMimes.TEXT_SAMI;
        if (lower.contains("<tt ") || lower.contains("<tt>") || lower.contains("<tt:tt")) return MimeTypes.APPLICATION_TTML;
        if (SUBRIP.matcher(s).find()) return MimeTypes.APPLICATION_SUBRIP;
        if (MICRODVD.matcher(s).find()) return SubtitleMimes.TEXT_MICRODVD;
        if (MPL2.matcher(s).find()) return SubtitleMimes.TEXT_MPL2;
        if (SUBVIEWER.matcher(s).find() || lower.contains("[information]")) return SubtitleMimes.TEXT_SUBVIEWER;
        if (TMPLAYER.matcher(s).find()) return SubtitleMimes.TEXT_TMPLAYER;
        return null;
    }

    /** MPEG-PS pack header (VobSub .sub): 00 00 01 BA. */
    private static boolean isVobSubPack(byte[] head) {
        return head.length >= 4 && head[0] == 0 && head[1] == 0 && head[2] == 1 && (head[3] & 0xFF) == 0xBA;
    }

    @Nullable
    public static String displayName(Context context, Uri uri) {
        if (ContentResolver.SCHEME_CONTENT.equals(uri.getScheme())) {
            try (Cursor c = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String n = c.getString(0);
                    if (n != null) return n;
                }
            } catch (Exception ignored) {
            }
        }
        return uri.getLastPathSegment();
    }

    @Nullable
    private static String extension(@Nullable String name) {
        if (name == null) return null;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        int dot = name.lastIndexOf('.');
        return dot > slash && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(Locale.US) : null;
    }

    @Nullable
    private static byte[] readHead(Context context, Uri uri, int max) {
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            byte[] buf = new byte[max];
            int n = 0, r;
            while (n < max && (r = in.read(buf, n, max - n)) > 0) n += r;
            byte[] out = new byte[n];
            System.arraycopy(buf, 0, out, 0, n);
            return out;
        } catch (Exception e) {
            return null;
        }
    }
}
