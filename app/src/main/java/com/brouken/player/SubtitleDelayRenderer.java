package com.brouken.player;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Timeline;
import androidx.media3.exoplayer.ExoPlaybackException;
import androidx.media3.exoplayer.ForwardingRenderer;
import androidx.media3.exoplayer.RendererConfiguration;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.text.TextRenderer;

/**
 * Purist: applies a subtitle delay by shifting the clock the {@link TextRenderer} sees (samples are untouched).
 * Works for embedded and external (sidecar) subtitle tracks alike.
 *
 * <p>The player only calls {@link TextRenderer#setFinalStreamEndPositionUs} on renderers that are an
 * {@code instanceof TextRenderer}; a wrapper would lose it (a final cue running past the end of the video could
 * then keep playback from reaching ENDED), so it is re-implemented here from the timeline.
 */
final class SubtitleDelayRenderer extends ForwardingRenderer {

    private final TextRenderer textRenderer;
    private final SubtitleDelay delay;
    private final Timeline.Period period = new Timeline.Period();

    @Nullable private Timeline timeline;
    @Nullable private MediaSource.MediaPeriodId mediaPeriodId;
    private long rendererOffsetUs;
    private boolean streamFinal;

    SubtitleDelayRenderer(TextRenderer textRenderer, SubtitleDelay delay) {
        super(textRenderer);
        this.textRenderer = textRenderer;
        this.delay = delay;
    }

    @Override
    public void enable(RendererConfiguration configuration, Format[] formats, SampleStream stream, long positionUs,
                       boolean joining, boolean mayRenderStartOfStream, long startPositionUs, long offsetUs,
                       MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
        this.rendererOffsetUs = offsetUs;
        this.mediaPeriodId = mediaPeriodId;
        this.streamFinal = false;
        long d = delay.getUs();
        super.enable(configuration, formats, stream, positionUs - d, joining, mayRenderStartOfStream,
                startPositionUs - d, offsetUs, mediaPeriodId);
    }

    @Override
    public void replaceStream(Format[] formats, SampleStream stream, long startPositionUs, long offsetUs,
                              MediaSource.MediaPeriodId mediaPeriodId) throws ExoPlaybackException {
        this.rendererOffsetUs = offsetUs;
        this.mediaPeriodId = mediaPeriodId;
        this.streamFinal = false;
        super.replaceStream(formats, stream, startPositionUs - delay.getUs(), offsetUs, mediaPeriodId);
    }

    @Override
    public void resetPosition(long positionUs, boolean sampleStreamIsResetToKeyFrame) throws ExoPlaybackException {
        super.resetPosition(positionUs - delay.getUs(), sampleStreamIsResetToKeyFrame);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        long d = delay.getUs();
        if (streamFinal) {
            updateFinalStreamEnd(d);
        }
        super.render(positionUs - d, elapsedRealtimeUs);
    }

    @Override
    public long getDurationToProgressUs(long positionUs, long elapsedRealtimeUs) {
        return super.getDurationToProgressUs(positionUs - delay.getUs(), elapsedRealtimeUs);
    }

    @Override
    public void setTimeline(Timeline timeline) {
        this.timeline = timeline;
        super.setTimeline(timeline);
    }

    @Override
    public void setCurrentStreamFinal() {
        super.setCurrentStreamFinal();
        streamFinal = true;
        updateFinalStreamEnd(delay.getUs());
    }

    @Override
    public void disable() {
        streamFinal = false;
        super.disable();
    }

    /** Mirrors RendererHolder: end = renderer offset + period duration, in the renderer's (delayed) clock. */
    private void updateFinalStreamEnd(long delayUs) {
        if (timeline == null || mediaPeriodId == null) return;
        int index = timeline.getIndexOfPeriod(mediaPeriodId.periodUid);
        if (index == C.INDEX_UNSET) return;
        timeline.getPeriod(index, period);
        if (period.durationUs == C.TIME_UNSET) return;
        textRenderer.setFinalStreamEndPositionUs(rendererOffsetUs + period.durationUs - delayUs);
    }
}
