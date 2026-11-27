package com.brouken.player.subtitle;

import android.content.Context;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MergingMediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.upstream.CmcdConfiguration;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.DefaultSubtitleParserFactory;
import androidx.media3.extractor.text.SubtitleParser;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Purist: plays standalone bitmap subtitle files (PGS .sup, VobSub .sub+.idx) that Media3 can only read when
 * they are embedded in a container. Everything is delegated to the app's DefaultMediaSourceFactory; such
 * sidecars are taken out of the media item and merged back in as their own progressive sources with
 * {@link SupExtractor} / {@link VobSubExtractor}. Without bitmap sidecars nothing changes.
 */
public final class BitmapSubtitleMediaSourceFactory implements MediaSource.Factory {

    private final Context context;
    private final DefaultMediaSourceFactory delegate;
    private final DataSource.Factory dataSourceFactory;
    private final SubtitleParser.Factory bitmapParserFactory = VobSubTiming.wrap(new DefaultSubtitleParserFactory());

    public BitmapSubtitleMediaSourceFactory(Context context, DefaultMediaSourceFactory delegate) {
        this.context = context.getApplicationContext();
        this.delegate = delegate;
        this.dataSourceFactory = new DefaultDataSource.Factory(this.context);
    }

    public static boolean isBitmapSidecar(String mime) {
        return SubtitleMimes.APPLICATION_PGS_SUP.equals(mime) || SubtitleMimes.APPLICATION_VOBSUB_SUB.equals(mime);
    }

    @Override
    public MediaSource createMediaSource(MediaItem mediaItem) {
        if (mediaItem.localConfiguration == null) {
            return delegate.createMediaSource(mediaItem);
        }
        List<MediaItem.SubtitleConfiguration> bitmap = new ArrayList<>();
        List<MediaItem.SubtitleConfiguration> other = new ArrayList<>();
        for (MediaItem.SubtitleConfiguration s : mediaItem.localConfiguration.subtitleConfigurations) {
            (isBitmapSidecar(s.mimeType) ? bitmap : other).add(s);
        }
        if (bitmap.isEmpty()) {
            return delegate.createMediaSource(mediaItem);
        }
        MediaSource[] sources = new MediaSource[1 + bitmap.size()];
        sources[0] = delegate.createMediaSource(mediaItem.buildUpon().setSubtitleConfigurations(other).build());
        for (int i = 0; i < bitmap.size(); i++) {
            MediaItem.SubtitleConfiguration s = bitmap.get(i);
            final Format format = new Format.Builder()
                    .setId(s.id)
                    .setLabel(s.label)
                    .setLanguage(s.language)
                    .setSelectionFlags(s.selectionFlags)
                    .setRoleFlags(s.roleFlags != 0 ? s.roleFlags : C.ROLE_FLAG_SUBTITLE)
                    .build();
            ExtractorsFactory extractors;
            if (SubtitleMimes.APPLICATION_PGS_SUP.equals(s.mimeType)) {
                extractors = () -> new Extractor[]{new SupExtractor(format, bitmapParserFactory)};
            } else {
                final File idx = VobSubIndex.cacheFile(context, s.uri);
                extractors = () -> new Extractor[]{new VobSubExtractor(format, idx, bitmapParserFactory)};
            }
            sources[1 + i] = new ProgressiveMediaSource.Factory(dataSourceFactory, extractors)
                    .createMediaSource(new MediaItem.Builder().setUri(s.uri).build());
        }
        return new MergingMediaSource(sources);
    }

    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(DrmSessionManagerProvider drmSessionManagerProvider) {
        delegate.setDrmSessionManagerProvider(drmSessionManagerProvider);
        return this;
    }

    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
        delegate.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy);
        return this;
    }

    @Override
    public MediaSource.Factory setCmcdConfigurationFactory(CmcdConfiguration.Factory cmcdConfigurationFactory) {
        delegate.setCmcdConfigurationFactory(cmcdConfigurationFactory);
        return this;
    }

    @Override
    public MediaSource.Factory setSubtitleParserFactory(SubtitleParser.Factory subtitleParserFactory) {
        delegate.setSubtitleParserFactory(subtitleParserFactory);
        return this;
    }

    @Override
    @SuppressWarnings("deprecation")
    public MediaSource.Factory experimentalParseSubtitlesDuringExtraction(boolean parseSubtitlesDuringExtraction) {
        delegate.experimentalParseSubtitlesDuringExtraction(parseSubtitlesDuringExtraction);
        return this;
    }

    @Override
    public int[] getSupportedTypes() {
        return delegate.getSupportedTypes();
    }
}
