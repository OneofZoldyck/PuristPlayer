package com.brouken.player;

import android.content.Context;
import android.system.Os;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.NoSampleRenderer;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.extractor.DefaultExtractorsFactory;
import androidx.media3.extractor.ExtractorsFactory;
import androidx.media3.extractor.text.SubtitleParser;
import androidx.media3.ui.PlayerView;

import com.brouken.player.subtitle.AssExtractorsFactory;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.peerless2012.ass.media.AssHandler;
import io.github.peerless2012.ass.media.AssHandlerConfig;
import io.github.peerless2012.ass.media.type.AssRenderType;
import io.github.peerless2012.ass.media.widget.AssSubtitleView;

/**
 * Purist: full ASS/SSA rendering with libass, one instance per player.
 *
 * Only the OVERLAY_CANVAS render type is used: libass draws the subtitles into an ordinary View that sits
 * above the video surface, exactly like Media3's own subtitles. The decoded video never passes through the
 * GPU effects pipeline (the EFFECTS_* types would do that and are never used), so Dolby Vision / HDR
 * decoding, the panel's HDR mode and the video renderer are unchanged.
 */
final class LibassSupport {

    private static final String TAG = "PuristLibass";

    private final AssHandler handler = new AssHandler(AssRenderType.OVERLAY_CANVAS, new AssHandlerConfig());
    private final AtomicBoolean released = new AtomicBoolean();
    /** Subtitle size setting (1 = as authored), applied as libass font scale. */
    private volatile float fontScale = 1f;

    LibassSupport(Context context) {
        ensureFontconfig(context.getApplicationContext());
    }

    // ---- fonts ----

    private static boolean fontconfigReady;

    /**
     * libass finds system fonts through fontconfig. Its built-in config only lists the font folders, so a
     * missing font (most ASS files name Windows fonts like Arial or Georgia) and even "sans-serif" resolve to
     * HyperOS's MiSans VF, which loses ordinary spaces in libass. This config sends those to fonts that render
     * properly and caches the font scan so it isn't repeated for every video. Fonts embedded in the file or
     * installed on the phone under their own name are still used as authored.
     */
    private static synchronized void ensureFontconfig(Context context) {
        if (fontconfigReady) return;
        try {
            File dir = new File(context.getFilesDir(), "libass");
            File cache = new File(context.getCacheDir(), "fontconfig");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            File conf = new File(dir, "fonts.conf");
            String nl = "\n";
            String xml = "<?xml version=\"1.0\"?>" + nl
                    + "<!DOCTYPE fontconfig SYSTEM \"fonts.dtd\">" + nl + "<fontconfig>" + nl
                    + "  <dir>/system/fonts</dir>" + nl + "  <dir>/product/fonts</dir>" + nl
                    + "  <cachedir>" + cache.getAbsolutePath() + "</cachedir>" + nl
                    + alias(new String[]{"Arial", "Helvetica", "Helvetica Neue", "Verdana", "Tahoma", "Trebuchet MS",
                            "Segoe UI", "Calibri", "Candara", "Franklin Gothic Medium", "Gill Sans", "Lucida Sans",
                            "Lucida Grande", "Open Sans", "Liberation Sans", "DejaVu Sans", "Arial Unicode MS"}, "sans-serif")
                    + alias(new String[]{"Times New Roman", "Times", "Georgia", "Cambria", "Garamond", "Book Antiqua",
                            "Palatino Linotype", "Palatino", "Constantia", "Liberation Serif", "DejaVu Serif"}, "serif")
                    + alias(new String[]{"Courier New", "Courier", "Consolas", "Lucida Console", "Monaco",
                            "Liberation Mono", "DejaVu Sans Mono"}, "monospace")
                    + alias(new String[]{"Comic Sans MS", "Comic Sans"}, "Coming Soon")
                    + "  <alias><family>sans-serif</family><prefer><family>Roboto</family>"
                    + "<family>Source Sans Pro</family><family>Droid Sans</family></prefer></alias>" + nl
                    + "  <alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>" + nl
                    + "  <alias><family>monospace</family><prefer><family>Droid Sans Mono</family>"
                    + "<family>Cutive Mono</family></prefer></alias>" + nl
                    // any other unknown family: fall back to sans-serif (fontconfig's 49-sansserif.conf)
                    + "  <match target=\"pattern\">" + nl
                    + "    <test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>sans-serif</string></test>" + nl
                    + "    <test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>serif</string></test>" + nl
                    + "    <test qual=\"all\" name=\"family\" compare=\"not_eq\"><string>monospace</string></test>" + nl
                    + "    <edit name=\"family\" mode=\"append_last\"><string>sans-serif</string></edit>" + nl
                    + "  </match>" + nl + "</fontconfig>" + nl;
            byte[] bytes = xml.getBytes(StandardCharsets.UTF_8);
            if (!conf.exists() || conf.length() != bytes.length) {
                try (FileOutputStream out = new FileOutputStream(conf)) {
                    out.write(bytes);
                }
            }
            Os.setenv("FONTCONFIG_FILE", conf.getAbsolutePath(), true);
            fontconfigReady = true;
        } catch (Exception e) {
            Log.w(TAG, "fontconfig setup failed; libass uses its built-in font config", e);
        }
    }

    private static String alias(String[] families, String target) {
        StringBuilder sb = new StringBuilder();
        for (String f : families) {
            sb.append("  <alias binding=\"same\"><family>").append(f).append("</family><prefer><family>")
                    .append(target).append("</family></prefer></alias>\n");
        }
        return sb.toString();
    }
    @Nullable private AssSubtitleView overlay;
    @Nullable private View videoSurface;
    @Nullable private ViewTreeObserver.OnPreDrawListener zoomFollower;

    /** Parser factory for external ASS/SSA files. */
    SubtitleParser.Factory parserFactory() {
        return AssExtractorsFactory.externalFileFactory(handler, released);
    }

    ExtractorsFactory wrapExtractors(DefaultExtractorsFactory extractorsFactory) {
        return new AssExtractorsFactory(extractorsFactory, handler, released);
    }

    /** Clock for libass, shifted by the user's subtitle delay. */
    Renderer createRenderer(SubtitleDelay delay) {
        return new AssClockRenderer(this, delay);
    }

    /** Subtitle size setting: scales the text of ASS subtitles (1 = as authored). */
    void setFontScale(float scale) {
        if (scale > 0) {
            fontScale = scale;
        }
    }

    /** Call right after the player is built. */
    void attach(ExoPlayer player, PlayerView playerView) {
        handler.init(player);

        // Media3's standard layout keeps subtitles inside the video's content frame, which is what libass expects
        // (script coordinates map onto the picture). Just Player moved its SubtitleView out to the full screen, so
        // the libass overlay goes into the content frame, above the video surface and shutter.
        ViewGroup contentFrame = playerView.findViewById(androidx.media3.ui.R.id.exo_content_frame);
        if (contentFrame == null) {
            Log.w(TAG, "no content frame; libass overlay not attached");
            return;
        }
        overlay = new AssSubtitleView(playerView.getContext(), handler);
        overlay.setClickable(false);
        overlay.setFocusable(false);
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        View shutter = contentFrame.findViewById(androidx.media3.ui.R.id.exo_shutter);
        int index = shutter != null ? contentFrame.indexOfChild(shutter) + 1 : contentFrame.getChildCount();
        contentFrame.addView(overlay, index, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Pinch-zoom scales only the video surface: keep typeset signs on the picture.
        videoSurface = playerView.getVideoSurfaceView();
        zoomFollower = () -> {
            View s = videoSurface;
            View o = overlay;
            if (s != null && o != null && (o.getScaleX() != s.getScaleX() || o.getScaleY() != s.getScaleY()
                    || o.getTranslationX() != s.getTranslationX() || o.getTranslationY() != s.getTranslationY())) {
                o.setScaleX(s.getScaleX());
                o.setScaleY(s.getScaleY());
                o.setTranslationX(s.getTranslationX());
                o.setTranslationY(s.getTranslationY());
            }
            return true;
        };
        overlay.getViewTreeObserver().addOnPreDrawListener(zoomFollower);
    }

    /** Call after player.release(). */
    void release() {
        released.set(true);
        if (overlay != null) {
            if (zoomFollower != null && overlay.getViewTreeObserver().isAlive()) {
                overlay.getViewTreeObserver().removeOnPreDrawListener(zoomFollower);
            }
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay); // stops its render executor
            }
            overlay = null;
        }
        zoomFollower = null;
        videoSurface = null;
        try {
            handler.release();
        } catch (Throwable t) {
            Log.w(TAG, "release", t);
        }
    }

    /**
     * Feeds libass the media position minus the subtitle delay (positive delay = subtitles later). Takes no track
     * and never touches the video: it is only a clock (ass-media's AssRenderer assumes a fixed renderer offset).
     */
    private static final class AssClockRenderer extends NoSampleRenderer {
        private final LibassSupport support;
        private final AssHandler handler;
        private final SubtitleDelay delay;
        private long offsetUs;
        private long lastTimeUs = Long.MIN_VALUE;
        private boolean flushed;
        @Nullable private io.github.peerless2012.ass.AssRender scaledRender;
        private float appliedScale = 1f;

        AssClockRenderer(LibassSupport support, SubtitleDelay delay) {
            this.support = support;
            this.handler = support.handler;
            this.delay = delay;
        }

        @Override
        public String getName() {
            return "PuristAssClock";
        }

        @Override
        protected void onRendererOffsetChanged(long offsetUs) {
            this.offsetUs = offsetUs;
        }

        @Override
        public void render(long positionUs, long elapsedRealtimeUs) {
            // font scale (subtitle size setting); the libass renderer is created lazily with the first ASS track
            io.github.peerless2012.ass.AssRender render = handler.getRender();
            float scale = support.fontScale;
            boolean redraw = false;
            if (render != null && (render != scaledRender || scale != appliedScale)) {
                render.setFontScale(scale);
                scaledRender = render;
                appliedScale = scale;
                redraw = true;
            }
            long t = positionUs - offsetUs - delay.getUs();
            if (redraw) {
                // three new distinct times (AssHandler draws on every 3rd change): redraw now, even when paused
                handler.setVideoTime(t + 3);
                handler.setVideoTime(t + 4);
                handler.setVideoTime(t + 5);
                lastTimeUs = t;
                flushed = true;
            } else if (t != lastTimeUs) {
                handler.setVideoTime(t);
                lastTimeUs = t;
                flushed = false;
            } else if (!flushed) {
                // AssHandler redraws on every 3rd distinct time; when paused (e.g. after a seek) make sure the
                // current position gets drawn.
                handler.setVideoTime(t + 1);
                handler.setVideoTime(t + 2);
                flushed = true;
            }
        }
    }
}
