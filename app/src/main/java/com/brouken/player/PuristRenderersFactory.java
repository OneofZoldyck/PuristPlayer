package com.brouken.player;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.text.TextOutput;
import androidx.media3.exoplayer.text.TextRenderer;
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.ArrayList;
import java.util.Collections;

/**
 * Purist: {@link DefaultRenderersFactory} that swaps the stock {@link MediaCodecVideoRenderer} for an
 * {@link InspectingVideoRenderer}, built with exactly the same arguments as the stock factory.
 */
public class PuristRenderersFactory extends DefaultRenderersFactory {

    // Defaults of the (patched) DefaultRenderersFactory; its fields are private and the app never changes them.
    private static final int MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY = 50;
    private static final long LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US = 15_000;
    private static final long VIDEO_EARLY_SCHEDULING_THRESHOLD_US = 50_000;

    private final InspectingVideoRenderer.Callback callback;
    private final SubtitleDelay subtitleDelay;
    private final boolean forceDolbyVisionBaseLayer;
    @Nullable private InspectingVideoRenderer inspectingRenderer;
    @Nullable private LibassSupport libass;
    @Nullable private com.brouken.player.subtitle.BitmapCueScaler cueScaler;
    private float subtitleScale = 1f;

    /**
     * @param subtitleDelay             shared subtitle delay applied to every text renderer
     * @param forceDolbyVisionBaseLayer debug only: hide Dolby Vision decoders so DV tracks fall back to
     *                                  their HEVC/AVC base layer (used to test fallback detection)
     */
    public PuristRenderersFactory(Context context, InspectingVideoRenderer.Callback callback, SubtitleDelay subtitleDelay,
                                  boolean forceDolbyVisionBaseLayer) {
        super(context);
        this.callback = callback;
        this.subtitleDelay = subtitleDelay;
        this.forceDolbyVisionBaseLayer = forceDolbyVisionBaseLayer;
        if (forceDolbyVisionBaseLayer) {
            setMediaCodecSelector((mimeType, requiresSecureDecoder, requiresTunnelingDecoder) ->
                    MimeTypes.VIDEO_DOLBY_VISION.equals(mimeType)
                            ? Collections.emptyList()
                            : MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder));
        }
    }

    @Override
    protected void buildVideoRenderers(Context context, int extensionRendererMode, MediaCodecSelector mediaCodecSelector,
                                       boolean enableDecoderFallback, Handler eventHandler,
                                       VideoRendererEventListener eventListener, long allowedVideoJoiningTimeMs,
                                       ArrayList<Renderer> out) {
        super.buildVideoRenderers(context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback,
                eventHandler, eventListener, allowedVideoJoiningTimeMs, out);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i).getClass() == MediaCodecVideoRenderer.class) {
                MediaCodecVideoRenderer.Builder builder = new MediaCodecVideoRenderer.Builder(context)
                        .setCodecAdapterFactory(getCodecAdapterFactory())
                        .setMediaCodecSelector(mediaCodecSelector)
                        .setAllowedJoiningTimeMs(allowedVideoJoiningTimeMs)
                        .setEnableDecoderFallback(enableDecoderFallback)
                        .setEventHandler(eventHandler)
                        .setEventListener(eventListener)
                        .setMaxDroppedFramesToNotify(MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY)
                        .experimentalSetLateThresholdToDropDecoderInputUs(LATE_THRESHOLD_TO_DROP_DECODER_INPUT_US)
                        .setEarlySchedulingThresholdUs(VIDEO_EARLY_SCHEDULING_THRESHOLD_US);
                inspectingRenderer = new InspectingVideoRenderer(builder, callback, forceDolbyVisionBaseLayer);
                out.set(i, inspectingRenderer);
                break;
            }
        }
    }

    @Override
    protected void buildTextRenderers(Context context, TextOutput output, Looper outputLooper, int extensionRendererMode,
                                      ArrayList<Renderer> out) {
        // Purist: subtitle size setting for image subtitles (PGS, VobSub, DVB)
        cueScaler = new com.brouken.player.subtitle.BitmapCueScaler(output);
        cueScaler.setScale(subtitleScale);
        super.buildTextRenderers(context, cueScaler, outputLooper, extensionRendererMode, out);
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) instanceof TextRenderer) {
                out.set(i, new SubtitleDelayRenderer((TextRenderer) out.get(i), subtitleDelay));
            }
        }
    }

    /** Purist: subtitle size (1 = as authored) for image subtitles; call on the main thread. */
    public void setSubtitleScale(float scale) {
        subtitleScale = scale;
        if (cueScaler != null) {
            cueScaler.setScale(scale);
        }
    }

    /** Purist: adds libass's clock renderer (it only feeds the playback position to the overlay). */
    public PuristRenderersFactory setLibass(@Nullable LibassSupport libass) {
        this.libass = libass;
        return this;
    }

    @Override
    protected void buildMiscellaneousRenderers(Context context, Handler eventHandler, int extensionRendererMode,
                                               ArrayList<Renderer> out) {
        super.buildMiscellaneousRenderers(context, eventHandler, extensionRendererMode, out);
        if (libass != null) {
            out.add(libass.createRenderer(subtitleDelay));
        }
    }

    /** Stop delivering format callbacks from the renderer created by this factory. */
    public void release() {
        if (inspectingRenderer != null) {
            inspectingRenderer.clearCallback();
        }
    }
}
