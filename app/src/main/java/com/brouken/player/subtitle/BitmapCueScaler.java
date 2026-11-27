package com.brouken.player.subtitle;

import androidx.media3.common.text.Cue;
import androidx.media3.common.text.CueGroup;
import androidx.media3.exoplayer.text.TextOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Purist: applies the subtitle size setting to image subtitles (PGS, VobSub, DVB; embedded or external).
 *
 * Text cues are sized by SubtitleView; image cues carry their own size relative to the video, so this sits
 * between the text renderer and the player and scales each bitmap cue around its horizontal centre, keeping it
 * anchored to the nearer of the top/bottom edge (bottom subtitles grow upwards, top signs downwards). At 100 %
 * cues pass through untouched. Changing the scale re-emits the current cues, so the change is visible at once.
 * Runs on the text output (main) thread.
 */
public final class BitmapCueScaler implements TextOutput {

    private final TextOutput delegate;
    private float scale = 1f;
    private List<Cue> lastCues = Collections.emptyList();
    private long lastPresentationTimeUs;

    public BitmapCueScaler(TextOutput delegate) {
        this.delegate = delegate;
    }

    /** @param scale 1 = as authored */
    public void setScale(float scale) {
        if (scale <= 0 || scale == this.scale) return;
        this.scale = scale;
        if (hasBitmap(lastCues)) {
            List<Cue> scaled = scale(lastCues);
            delegate.onCues(scaled);
            delegate.onCues(new CueGroup(scaled, lastPresentationTimeUs));
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onCues(List<Cue> cues) {
        lastCues = cues;
        delegate.onCues(scale(cues));
    }

    @Override
    public void onCues(CueGroup cueGroup) {
        lastCues = cueGroup.cues;
        lastPresentationTimeUs = cueGroup.presentationTimeUs;
        delegate.onCues(hasBitmap(cueGroup.cues) && scale != 1f
                ? new CueGroup(scale(cueGroup.cues), cueGroup.presentationTimeUs) : cueGroup);
    }

    private List<Cue> scale(List<Cue> cues) {
        if (scale == 1f || !hasBitmap(cues)) return cues;
        List<Cue> out = new ArrayList<>(cues.size());
        for (Cue c : cues) out.add(c.bitmap != null ? scaled(c, scale) : c);
        return out;
    }

    private static boolean hasBitmap(List<Cue> cues) {
        for (Cue c : cues) {
            if (c.bitmap != null) return true;
        }
        return false;
    }

    static Cue scaled(Cue c, float s) {
        if (c.size == Cue.DIMEN_UNSET || c.size <= 0) return c;
        float w = c.size;
        float left = c.position == Cue.DIMEN_UNSET ? (1 - w) / 2 : start(c.position, w, c.positionAnchor);
        float nw = Math.min(1f, w * s);
        float nl = clamp(left + w / 2 - nw / 2, 0, 1 - nw);
        Cue.Builder b = c.buildUpon()
                .setSize(nw)
                .setPosition(nl)
                .setPositionAnchor(Cue.ANCHOR_TYPE_START);
        if (c.bitmapHeight != Cue.DIMEN_UNSET && c.line != Cue.DIMEN_UNSET && c.lineType == Cue.LINE_TYPE_FRACTION) {
            float h = c.bitmapHeight;
            float top = start(c.line, h, c.lineAnchor);
            float nh = Math.min(1f, h * s);
            float nt = top + h / 2 > 0.5f ? top + h - nh : top; // keep the edge nearest the frame border
            b.setBitmapHeight(nh)
                    .setLine(clamp(nt, 0, 1 - nh), Cue.LINE_TYPE_FRACTION)
                    .setLineAnchor(Cue.ANCHOR_TYPE_START);
        } else if (c.bitmapHeight != Cue.DIMEN_UNSET) {
            b.setBitmapHeight(Math.min(1f, c.bitmapHeight * s));
        }
        return b.build();
    }

    private static float start(float position, float extent, int anchor) {
        if (anchor == Cue.ANCHOR_TYPE_MIDDLE) return position - extent / 2;
        if (anchor == Cue.ANCHOR_TYPE_END) return position - extent;
        return position;
    }

    private static float clamp(float v, float min, float max) {
        return max < min ? min : Math.max(min, Math.min(max, v));
    }
}
