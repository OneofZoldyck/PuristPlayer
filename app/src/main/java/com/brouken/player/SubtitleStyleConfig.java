package com.brouken.player;

import android.content.SharedPreferences;
import android.graphics.Color;

import androidx.media3.ui.CaptionStyleCompat;

/**
 * Purist: user subtitle style shared by the in-player panel and the Settings screen (same SharedPreferences keys).
 * Colours are stored as "#AARRGGBB" strings so ListPreference can edit them too.
 */
final class SubtitleStyleConfig {

    static final String SOURCE_CUSTOM = "custom";
    static final String SOURCE_SYSTEM = "system";

    static final String KEY_SOURCE = "puristSubStyleSource";
    static final String KEY_SIZE = "puristSubSize";
    static final String KEY_TEXT_COLOR = "puristSubTextColor";
    static final String KEY_EDGE_TYPE = "puristSubEdgeType";
    static final String KEY_EDGE_COLOR = "puristSubEdgeColor";
    static final String KEY_BG_COLOR = "puristSubBgColor";
    static final String KEY_BG_OPACITY = "puristSubBgOpacity";
    static final String KEY_POSITION = "puristSubPosition";
    static final String KEY_EMBEDDED_FONT_SIZES = "puristSubEmbeddedFontSizes";
    // Pre-existing Just Player keys, reused
    static final String KEY_EMBEDDED_STYLES = "subtitleStyleEmbedded";
    static final String KEY_BOLD = "subtitleStyleBold";

    static final int SIZE_MIN = 50, SIZE_MAX = 200, SIZE_DEFAULT = 100;
    static final int POSITION_MIN = 0, POSITION_MAX = 30, POSITION_DEFAULT = 5;

    /** Swatch palette (also the ListPreference values in arrays.xml, same order). */
    static final int[] PALETTE = {
            0xFFFFFFFF, 0xFFFFFF00, 0xFF00FFFF, 0xFF00FF00, 0xFFFF00FF, 0xFFFF0000, 0xFF0000FF, 0xFFBDBDBD, 0xFF000000
    };

    static final int[] EDGE_TYPES = {
            CaptionStyleCompat.EDGE_TYPE_NONE, CaptionStyleCompat.EDGE_TYPE_OUTLINE, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW,
            CaptionStyleCompat.EDGE_TYPE_RAISED, CaptionStyleCompat.EDGE_TYPE_DEPRESSED
    };

    boolean useSystemStyle;
    int sizePercent = SIZE_DEFAULT;
    int textColor = Color.WHITE;
    int edgeType = CaptionStyleCompat.EDGE_TYPE_OUTLINE;
    int edgeColor = Color.BLACK;
    int backgroundColor = Color.BLACK;
    int backgroundOpacity = 0;
    int positionPercent = POSITION_DEFAULT;
    boolean applyEmbeddedStyles = true;
    boolean applyEmbeddedFontSizes = true;
    boolean bold = false;

    static SubtitleStyleConfig load(SharedPreferences sp) {
        SubtitleStyleConfig c = new SubtitleStyleConfig();
        c.useSystemStyle = SOURCE_SYSTEM.equals(sp.getString(KEY_SOURCE, SOURCE_CUSTOM));
        c.sizePercent = clamp(sp.getInt(KEY_SIZE, SIZE_DEFAULT), SIZE_MIN, SIZE_MAX);
        c.textColor = parseColor(sp.getString(KEY_TEXT_COLOR, null), Color.WHITE);
        c.edgeType = parseInt(sp.getString(KEY_EDGE_TYPE, null), CaptionStyleCompat.EDGE_TYPE_OUTLINE);
        c.edgeColor = parseColor(sp.getString(KEY_EDGE_COLOR, null), Color.BLACK);
        c.backgroundColor = parseColor(sp.getString(KEY_BG_COLOR, null), Color.BLACK);
        c.backgroundOpacity = clamp(sp.getInt(KEY_BG_OPACITY, 0), 0, 100);
        c.positionPercent = clamp(sp.getInt(KEY_POSITION, POSITION_DEFAULT), POSITION_MIN, POSITION_MAX);
        c.applyEmbeddedStyles = sp.getBoolean(KEY_EMBEDDED_STYLES, true);
        c.applyEmbeddedFontSizes = sp.getBoolean(KEY_EMBEDDED_FONT_SIZES, true);
        c.bold = sp.getBoolean(KEY_BOLD, false);
        return c;
    }

    void save(SharedPreferences sp) {
        sp.edit()
                .putString(KEY_SOURCE, useSystemStyle ? SOURCE_SYSTEM : SOURCE_CUSTOM)
                .putInt(KEY_SIZE, sizePercent)
                .putString(KEY_TEXT_COLOR, colorToString(textColor))
                .putString(KEY_EDGE_TYPE, String.valueOf(edgeType))
                .putString(KEY_EDGE_COLOR, colorToString(edgeColor))
                .putString(KEY_BG_COLOR, colorToString(backgroundColor))
                .putInt(KEY_BG_OPACITY, backgroundOpacity)
                .putInt(KEY_POSITION, positionPercent)
                .putBoolean(KEY_EMBEDDED_STYLES, applyEmbeddedStyles)
                .putBoolean(KEY_EMBEDDED_FONT_SIZES, applyEmbeddedFontSizes)
                .putBoolean(KEY_BOLD, bold)
                .apply();
    }

    /** Background colour with the chosen opacity applied. */
    int backgroundArgb() {
        int alpha = Math.round(backgroundOpacity * 255f / 100f);
        return (alpha << 24) | (backgroundColor & 0x00FFFFFF);
    }

    static String colorToString(int argb) {
        return String.format("#%08X", argb);
    }

    private static int parseColor(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }
}
