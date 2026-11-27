package com.brouken.player;

import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Purist: in-player subtitle settings (style + per-video delay) with live preview over the video.
 */
final class SubtitleSettingsPanel {

    interface Host {
        SharedPreferences preferences();

        /** Style prefs changed: re-apply them to the SubtitleView. */
        void onSubtitleStyleChanged();

        long getSubtitleDelayMs();

        void setSubtitleDelayMs(long delayMs);

        void openSystemCaptionSettings();

        void onSubtitlePanelVisibilityChanged(boolean visible);
    }

    private static final long[] DELAY_STEPS_MS = {-500, -50, 50, 500};

    private final PlayerActivity activity;
    private final FrameLayout parent;
    private final Host host;
    private final View overlay;
    private final LinearLayout panel;
    private final TextView delayValue;
    private final Switch useSystem;
    private final View customGroup;
    private final TextView sizeLabel;
    private final SeekBar size;
    private final TextView bgOpacityLabel;
    private final SeekBar bgOpacity;
    private final TextView positionLabel;
    private final SeekBar position;
    private final Switch bold;
    private final Switch embedded;
    private final Switch embeddedSizes;
    private final List<View> textSwatches = new ArrayList<>();
    private final List<View> edgeSwatches = new ArrayList<>();
    private final List<View> bgSwatches = new ArrayList<>();
    private final List<TextView> edgeChips = new ArrayList<>();

    private SubtitleStyleConfig config;
    private boolean binding;
    @Nullable private Object backCallback;

    SubtitleSettingsPanel(PlayerActivity activity, FrameLayout parent, Host host) {
        this.activity = activity;
        this.parent = parent;
        this.host = host;

        overlay = LayoutInflater.from(activity).inflate(R.layout.purist_subtitle_panel, parent, false);
        overlay.setVisibility(View.GONE);
        parent.addView(overlay);

        panel = overlay.findViewById(R.id.purist_sub_panel);
        delayValue = overlay.findViewById(R.id.purist_sub_delay_value);
        useSystem = overlay.findViewById(R.id.purist_sub_use_system);
        customGroup = overlay.findViewById(R.id.purist_sub_custom_group);
        sizeLabel = overlay.findViewById(R.id.purist_sub_size_label);
        size = overlay.findViewById(R.id.purist_sub_size);
        bgOpacityLabel = overlay.findViewById(R.id.purist_sub_bg_opacity_label);
        bgOpacity = overlay.findViewById(R.id.purist_sub_bg_opacity);
        positionLabel = overlay.findViewById(R.id.purist_sub_position_label);
        position = overlay.findViewById(R.id.purist_sub_position);
        bold = overlay.findViewById(R.id.purist_sub_bold);
        embedded = overlay.findViewById(R.id.purist_sub_embedded);
        embeddedSizes = overlay.findViewById(R.id.purist_sub_embedded_sizes);

        overlay.setOnClickListener(v -> hide()); // tap outside the card closes it
        overlay.findViewById(R.id.purist_sub_close).setOnClickListener(v -> hide());
        overlay.findViewById(R.id.purist_sub_system_settings).setOnClickListener(v -> host.openSystemCaptionSettings());
        overlay.findViewById(R.id.purist_sub_reset).setOnClickListener(v -> {
            SubtitleStyleConfig fresh = new SubtitleStyleConfig();
            fresh.useSystemStyle = config.useSystemStyle;
            config = fresh;
            commit();
            bind();
        });

        buildDelayButtons();
        buildSwatches(overlay.findViewById(R.id.purist_sub_text_colors), textSwatches, c -> config.textColor = c);
        buildSwatches(overlay.findViewById(R.id.purist_sub_edge_colors), edgeSwatches, c -> config.edgeColor = c);
        buildSwatches(overlay.findViewById(R.id.purist_sub_bg_colors), bgSwatches, c -> config.backgroundColor = c);
        buildEdgeChips(overlay.findViewById(R.id.purist_sub_edge_types));

        size.setMax((SubtitleStyleConfig.SIZE_MAX - SubtitleStyleConfig.SIZE_MIN) / 5);
        size.setOnSeekBarChangeListener(seek(p -> config.sizePercent = SubtitleStyleConfig.SIZE_MIN + p * 5));
        bgOpacity.setMax(20);
        bgOpacity.setOnSeekBarChangeListener(seek(p -> config.backgroundOpacity = p * 5));
        position.setMax(SubtitleStyleConfig.POSITION_MAX - SubtitleStyleConfig.POSITION_MIN);
        position.setOnSeekBarChangeListener(seek(p -> config.positionPercent = SubtitleStyleConfig.POSITION_MIN + p));

        useSystem.setOnCheckedChangeListener((b, checked) -> change(() -> config.useSystemStyle = checked));
        bold.setOnCheckedChangeListener((b, checked) -> change(() -> config.bold = checked));
        embedded.setOnCheckedChangeListener((b, checked) -> change(() -> config.applyEmbeddedStyles = checked));
        embeddedSizes.setOnCheckedChangeListener((b, checked) -> change(() -> config.applyEmbeddedFontSizes = checked));
    }

    boolean isShowing() {
        return overlay.getVisibility() == View.VISIBLE;
    }

    /** The panel card (to keep the live subtitle preview out from under it). */
    View card() {
        return panel;
    }

    void show() {
        config = SubtitleStyleConfig.load(host.preferences());
        bind();
        layoutForOrientation();
        overlay.bringToFront();
        overlay.setAlpha(0f);
        overlay.setVisibility(View.VISIBLE);
        overlay.animate().alpha(1f).setDuration(150).start();
        registerBack();
        host.onSubtitlePanelVisibilityChanged(true);
    }

    void hide() {
        if (!isShowing()) return;
        overlay.animate().cancel();
        overlay.setVisibility(View.GONE);
        unregisterBack();
        host.onSubtitlePanelVisibilityChanged(false);
    }

    /** Re-layout after rotation while visible. */
    void onConfigurationChanged() {
        if (isShowing()) layoutForOrientation();
    }

    // ---- binding ----

    private void bind() {
        binding = true;
        updateDelayLabel();
        useSystem.setChecked(config.useSystemStyle);
        size.setProgress((config.sizePercent - SubtitleStyleConfig.SIZE_MIN) / 5);
        bgOpacity.setProgress(config.backgroundOpacity / 5);
        position.setProgress(config.positionPercent - SubtitleStyleConfig.POSITION_MIN);
        bold.setChecked(config.bold);
        embedded.setChecked(config.applyEmbeddedStyles);
        embeddedSizes.setChecked(config.applyEmbeddedFontSizes);
        binding = false;
        refreshLabelsAndSelection();
    }

    private void refreshLabelsAndSelection() {
        sizeLabel.setText(activity.getString(R.string.purist_sub_size, config.sizePercent));
        bgOpacityLabel.setText(activity.getString(R.string.purist_sub_bg_opacity, config.backgroundOpacity));
        positionLabel.setText(activity.getString(R.string.purist_sub_position, config.positionPercent));
        selectSwatch(textSwatches, config.textColor);
        selectSwatch(edgeSwatches, config.edgeColor);
        selectSwatch(bgSwatches, config.backgroundColor);
        for (int i = 0; i < edgeChips.size(); i++) {
            edgeChips.get(i).setSelected(SubtitleStyleConfig.EDGE_TYPES[i] == config.edgeType);
        }
        customGroup.setAlpha(config.useSystemStyle ? 0.35f : 1f);
        setEnabledRecursive(customGroup, !config.useSystemStyle);
        embeddedSizes.setEnabled(config.applyEmbeddedStyles);
    }

    private void change(Runnable mutation) {
        if (binding) return;
        mutation.run();
        commit();
        refreshLabelsAndSelection();
    }

    private void commit() {
        config.save(host.preferences());
        host.onSubtitleStyleChanged();
    }

    private interface IntSetter {
        void set(int value);
    }

    private SeekBar.OnSeekBarChangeListener seek(IntSetter setter) {
        return new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) change(() -> setter.set(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        };
    }

    // ---- delay ----

    private void buildDelayButtons() {
        LinearLayout row = overlay.findViewById(R.id.purist_sub_delay_buttons);
        for (long step : DELAY_STEPS_MS) {
            String label = (step > 0 ? "+" : "−") + formatSeconds(Math.abs(step), false);
            row.addView(chip(label, v -> {
                host.setSubtitleDelayMs(host.getSubtitleDelayMs() + step);
                updateDelayLabel();
            }), weighted());
        }
        row.addView(chip("0", v -> {
            host.setSubtitleDelayMs(0);
            updateDelayLabel();
        }), weighted());
    }

    private void updateDelayLabel() {
        long ms = host.getSubtitleDelayMs();
        String sign = ms > 0 ? "+" : ms < 0 ? "−" : "";
        delayValue.setText(sign + formatSeconds(Math.abs(ms), true));
    }

    private static String formatSeconds(long ms, boolean threeDecimals) {
        if (threeDecimals) return String.format(Locale.US, "%.3f s", ms / 1000f);
        String s = String.format(Locale.US, "%.2f", ms / 1000f).replaceAll("0+$", "").replaceAll("\\.$", "");
        return s + "s";
    }

    // ---- swatches & chips ----

    private interface ColorSetter {
        void set(int argb);
    }

    private void buildSwatches(LinearLayout row, List<View> out, ColorSetter setter) {
        int sizePx = Utils.dpToPx(28);
        int margin = Utils.dpToPx(4);
        for (int color : SubtitleStyleConfig.PALETTE) {
            View v = new View(activity);
            v.setTag(color);
            v.setBackground(swatchDrawable(color, false));
            v.setContentDescription(String.format("#%06X", color & 0xFFFFFF));
            v.setOnClickListener(x -> change(() -> setter.set(color)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(sizePx, sizePx);
            lp.setMargins(margin, margin, margin, margin);
            row.addView(v, lp);
            out.add(v);
        }
    }

    private static GradientDrawable swatchDrawable(int color, boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(Utils.dpToPx(selected ? 3 : 1), selected ? 0xFF3A7BD5 : 0x80FFFFFF);
        return d;
    }

    private static void selectSwatch(List<View> swatches, int color) {
        for (View v : swatches) {
            int c = (Integer) v.getTag();
            v.setBackground(swatchDrawable(c, (c | 0xFF000000) == (color | 0xFF000000)));
        }
    }

    private void buildEdgeChips(LinearLayout container) {
        int[] labels = {R.string.purist_sub_edge_none, R.string.purist_sub_edge_outline, R.string.purist_sub_edge_shadow,
                R.string.purist_sub_edge_raised, R.string.purist_sub_edge_depressed};
        LinearLayout row = null;
        for (int i = 0; i < labels.length; i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                container.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final int edgeType = SubtitleStyleConfig.EDGE_TYPES[i];
            TextView chip = chip(activity.getString(labels[i]), v -> change(() -> config.edgeType = edgeType));
            row.addView(chip, weighted());
            edgeChips.add(chip);
        }
        if (row != null) {
            for (int pad = labels.length % 3; pad != 0 && pad < 3; pad++) {
                row.addView(new View(activity), weighted()); // keep chip widths equal on the last row
            }
        }
    }

    private TextView chip(String text, View.OnClickListener listener) {
        TextView t = new TextView(activity);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(Utils.dpToPx(36));
        t.setPadding(Utils.dpToPx(6), Utils.dpToPx(6), Utils.dpToPx(6), Utils.dpToPx(6));
        t.setBackgroundResource(R.drawable.purist_chip_background);
        t.setOnClickListener(listener);
        return t;
    }

    private static LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        int m = Utils.dpToPx(3);
        lp.setMargins(m, m, m, m);
        return lp;
    }

    private static void setEnabledRecursive(View v, boolean enabled) {
        v.setEnabled(enabled);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) setEnabledRecursive(g.getChildAt(i), enabled);
        }
    }

    // ---- layout, insets, back ----

    private void layoutForOrientation() {
        boolean landscape = activity.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int top = 0, bottom = 0, left = 0, right = 0;
        WindowInsets insets = parent.getRootWindowInsets();
        if (insets != null && Build.VERSION.SDK_INT >= 30) {
            Insets in = insets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            top = in.top;
            bottom = in.bottom;
            left = in.left;
            right = in.right;
        } else if (insets != null) {
            top = insets.getStableInsetTop();
            bottom = insets.getStableInsetBottom();
            left = insets.getStableInsetLeft();
            right = insets.getStableInsetRight();
        }
        int m = Utils.dpToPx(8);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (landscape) {
            lp.width = Utils.dpToPx(360);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.END;
            lp.setMargins(m, m + top, m + right, m + bottom);
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = Math.round(parent.getHeight() * 0.58f);
            lp.gravity = Gravity.BOTTOM;
            lp.setMargins(m + left, m, m + right, m + bottom);
        }
        panel.setLayoutParams(lp);
    }

    private void registerBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback == null) {
            OnBackInvokedCallback cb = this::hide;
            activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, cb);
            backCallback = cb;
        }
    }

    private void unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33 && backCallback != null) {
            activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }
}
