package com.brouken.player.subtitle;

import android.content.Context;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;

import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

/**
 * Purist: Media3's subtitle parsers plus SAMI, MicroDVD, MPL2, SubViewer/SBV and TMPlayer.
 * Set on DefaultMediaSourceFactory, so it is used for external (sidecar) subtitle files.
 */
public final class PuristSubtitleParserFactory implements SubtitleParser.Factory {

    // VobSub timing corrected (also for VobSub embedded in MKV, which gets this factory via the extractors)
    private final SubtitleParser.Factory delegate = VobSubTiming.wrap(new DefaultSubtitleParserFactory());
    private final Context context;
    @Nullable private final Uri mediaUri;
    private volatile double probedFrameRate = Double.NaN;
    /** libass parser factory for ASS/SSA, or null for Media3's simple SSA parser. */
    @Nullable private volatile SubtitleParser.Factory assFactory;

    /** @param mediaUri the video being played (MicroDVD timing needs its frame rate) */
    public PuristSubtitleParserFactory(Context context, @Nullable Uri mediaUri) {
        this.context = context.getApplicationContext();
        this.mediaUri = mediaUri;
    }

    /** Purist: route ASS/SSA to libass (null = Media3's simple SSA parser). */
    public void setAssFactory(@Nullable SubtitleParser.Factory assFactory) {
        this.assFactory = assFactory;
    }

    @Override
    public boolean supportsFormat(Format format) {
        return isCustom(format.sampleMimeType) || delegate.supportsFormat(format);
    }

    @Override
    public int getCueReplacementBehavior(Format format) {
        SubtitleParser.Factory ass = assFactory;
        if (ass != null && MimeTypes.TEXT_SSA.equals(format.sampleMimeType)) {
            return ass.getCueReplacementBehavior(format);
        }
        return isCustom(format.sampleMimeType) ? Format.CUE_REPLACEMENT_BEHAVIOR_MERGE : delegate.getCueReplacementBehavior(format);
    }

    @Override
    public SubtitleParser create(Format format) {
        String mime = format.sampleMimeType;
        SubtitleParser.Factory ass = assFactory;
        if (ass != null && MimeTypes.TEXT_SSA.equals(mime)) {
            try {
                return ass.create(format);
            } catch (Throwable t) {
                // libass failed to load or create the track: fall back to the simple parser (plain styled text)
                Log.w("PuristLibass", "libass unavailable, using simple SSA rendering", t);
            }
        }
        if (mime != null) {
            switch (mime) {
                case SubtitleMimes.TEXT_SAMI: return new SamiParser();
                case SubtitleMimes.TEXT_MICRODVD: return new MicroDvdParser(this::videoFrameRate);
                case SubtitleMimes.TEXT_MPL2: return new Mpl2Parser();
                case SubtitleMimes.TEXT_SUBVIEWER: return new SubViewerParser();
                case SubtitleMimes.TEXT_TMPLAYER: return new TmPlayerParser();
            }
        }
        return delegate.create(format);
    }

    private static boolean isCustom(@Nullable String mime) {
        return SubtitleMimes.TEXT_SAMI.equals(mime) || SubtitleMimes.TEXT_MICRODVD.equals(mime)
                || SubtitleMimes.TEXT_MPL2.equals(mime) || SubtitleMimes.TEXT_SUBVIEWER.equals(mime)
                || SubtitleMimes.TEXT_TMPLAYER.equals(mime);
    }

    /** Video frame rate (called on the loader thread, only for MicroDVD without a fps header). */
    private double videoFrameRate() {
        double cached = probedFrameRate;
        if (!Double.isNaN(cached)) return cached;
        double fps = 0;
        if (mediaUri != null) {
            MediaExtractor extractor = new MediaExtractor();
            try {
                extractor.setDataSource(context, mediaUri, null);
                for (int i = 0; i < extractor.getTrackCount(); i++) {
                    MediaFormat f = extractor.getTrackFormat(i);
                    String mime = f.getString(MediaFormat.KEY_MIME);
                    if (mime != null && mime.startsWith("video/") && f.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                        try {
                            fps = f.getFloat(MediaFormat.KEY_FRAME_RATE);
                        } catch (ClassCastException e) {
                            fps = f.getInteger(MediaFormat.KEY_FRAME_RATE);
                        }
                        break;
                    }
                }
            } catch (Exception ignored) {
                // fall back to the parser default
            } finally {
                extractor.release();
            }
        }
        probedFrameRate = fps;
        return fps;
    }
}
