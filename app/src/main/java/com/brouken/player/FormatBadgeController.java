package com.brouken.player;

import android.graphics.Color;
import android.graphics.Insets;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.DisplayCutout;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

/**
 * Purist: the format badge (logo + optional one-line detail) shown in the top-right corner.
 * Added as the top-most child of the player view so the controller never dims it.
 */
public final class FormatBadgeController {

    private static final long FADE_IN_MS = 200;
    private static final long HOLD_MS = 3000;
    private static final long FADE_OUT_MS = 400;
    private static final int BASE_MARGIN_DP = 16;
    private static final int MAX_LOGO_WIDTH_DP = 132;

    private final FrameLayout host;
    private final LinearLayout container;
    private final ImageView logo;
    private final TextView detail;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = this::fadeOut;

    @Nullable private VideoFormatInfo shown;
    private boolean visible;

    public FormatBadgeController(FrameLayout playerView) {
        host = playerView;

        container = new LinearLayout(host.getContext());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.END);
        container.setClickable(false);
        container.setFocusable(false);
        container.setVisibility(View.GONE);

        logo = new ImageView(host.getContext());
        logo.setAdjustViewBounds(true);
        logo.setScaleType(ImageView.ScaleType.FIT_END);
        logo.setMaxWidth(Utils.dpToPx(MAX_LOGO_WIDTH_DP));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        container.addView(logo, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, Utils.dpToPx(32)));

        detail = new TextView(host.getContext());
        detail.setTextColor(Color.WHITE);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        detail.setShadowLayer(Utils.dpToPx(3), 0, Utils.dpToPx(1), 0xCC000000);
        detail.setGravity(Gravity.END);
        detail.setMaxWidth(Utils.dpToPx(440));
        detail.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailParams.topMargin = Utils.dpToPx(4);
        container.addView(detail, detailParams);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.RIGHT);
        host.addView(container, params);

        host.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol || b - t != ob - ot) {
                updateMargins();
            }
        });
    }

    /** Show the badge for {@code info}; {@code detailText} null = logo only. Restarts the 3 s hold. */
    public void show(VideoFormatInfo info, @Nullable String detailText) {
        handler.removeCallbacks(hideRunnable);
        container.animate().cancel();
        container.bringToFront();
        updateMargins();
        applyLogo(info);
        if (detailText != null) {
            detail.setText(detailText);
            detail.setVisibility(View.VISIBLE);
        } else {
            detail.setVisibility(View.GONE);
        }
        container.setAlpha(visible ? container.getAlpha() : 0f);
        container.setVisibility(View.VISIBLE);
        visible = true;
        container.animate().alpha(1f).setDuration(FADE_IN_MS).setListener(null).start();
        handler.postDelayed(hideRunnable, FADE_IN_MS + HOLD_MS);
    }

    /** Snapshot changed (e.g. HDR10+ metadata found): swap the logo if the badge is on screen. */
    public void onVideoInfo(VideoFormatInfo info) {
        if (visible && shown != null && shown.kind != info.kind) {
            applyLogo(info);
        }
    }

    public void hideNow() {
        handler.removeCallbacks(hideRunnable);
        container.animate().cancel();
        container.setVisibility(View.GONE);
        visible = false;
        shown = null;
    }

    private void fadeOut() {
        container.animate().alpha(0f).setDuration(FADE_OUT_MS).withEndAction(() -> {
            container.setVisibility(View.GONE);
            visible = false;
            shown = null;
        }).start();
    }

    private void applyLogo(VideoFormatInfo info) {
        shown = info;
        logo.setImageResource(info.badgeDrawable());
        logo.setContentDescription(info.kindLabel());
        // Per-logo heights balance the very different aspect ratios (portrait DV mark vs. wide HDR10+).
        // Tuned on the Redmi Note 13 Pro+ (1220 px tall landscape, 480 dpi) for similar visual weight.
        int heightDp;
        switch (info.kind) {
            case DOLBY_VISION: heightDp = 40; break;
            case HDR: heightDp = 34; break;
            case HDR10: heightDp = 30; break;
            case HDR10_PLUS: heightDp = 26; break;
            default: heightDp = 22; break;
        }
        ViewGroup.LayoutParams lp = logo.getLayoutParams();
        lp.height = Utils.dpToPx(heightDp);
        logo.setLayoutParams(lp);
    }

    /** Margin = 16 dp + system bars and display cutout, ignoring bar visibility so the badge never jumps. */
    private void updateMargins() {
        int top = 0, right = 0;
        WindowInsets insets = host.getRootWindowInsets();
        if (insets != null) {
            if (Build.VERSION.SDK_INT >= 30) {
                Insets in = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                top = in.top;
                right = in.right;
            } else {
                top = insets.getStableInsetTop();
                right = insets.getStableInsetRight();
                if (Build.VERSION.SDK_INT >= 28) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        top = Math.max(top, cutout.getSafeInsetTop());
                        right = Math.max(right, cutout.getSafeInsetRight());
                    }
                }
            }
        }
        int base = Utils.dpToPx(BASE_MARGIN_DP);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) container.getLayoutParams();
        if (params.topMargin != base + top || params.rightMargin != base + right) {
            params.topMargin = base + top;
            params.rightMargin = base + right;
            container.setLayoutParams(params);
        }
    }
}
