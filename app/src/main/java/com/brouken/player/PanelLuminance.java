package com.brouken.player;

import android.util.Log;
import android.util.Xml;

import androidx.annotation.Nullable;

import org.xmlpull.v1.XmlPullParser;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Purist: estimates the panel's current white luminance in nits.
 *
 * <p>Logic: the framework publishes the display's actual brightness level (including HDR boost and thermal
 * limits) in {@code debug.tracing.screen_brightness}; the OEM display config
 * ({@code /…/etc/displayconfig/display_id_*.xml}, {@code <screenBrightnessMap>}) maps brightness levels to nits.
 * The framework interpolates that map linearly, so we do the same. If either source is unavailable we
 * return -1 and the caller omits the figure rather than guessing.
 *
 * <p>Blocking (spawns {@code getprop}, reads a file): call off the main thread.
 */
final class PanelLuminance {

    private static final String TAG = "Purist";
    private static final String BRIGHTNESS_PROPERTY = "debug.tracing.screen_brightness";
    private static final String[] CONFIG_DIRS = {
            "/product/etc/displayconfig", "/vendor/etc/displayconfig", "/odm/etc/displayconfig"
    };

    private static boolean mapLoaded;
    @Nullable private static float[] mapBrightness;
    @Nullable private static float[] mapNits;

    private PanelLuminance() {
    }

    /** Current panel white luminance in nits, or -1 if it can't be determined. */
    static int estimateNits() {
        float brightness = readBrightness();
        if (Float.isNaN(brightness) || brightness <= 0f) return -1;
        loadMapOnce();
        if (mapBrightness == null || mapNits == null) return -1;
        return Math.round(interpolate(mapBrightness, mapNits, brightness));
    }

    private static float readBrightness() {
        Process process = null;
        try {
            process = new ProcessBuilder("/system/bin/getprop", BRIGHTNESS_PROPERTY).redirectErrorStream(true).start();
            String line;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                line = reader.readLine();
            }
            process.waitFor();
            return line == null || line.trim().isEmpty() ? Float.NaN : Float.parseFloat(line.trim());
        } catch (Exception e) {
            Log.w(TAG, "brightness property unavailable: " + e);
            return Float.NaN;
        } finally {
            if (process != null) process.destroy();
        }
    }

    private static synchronized void loadMapOnce() {
        if (mapLoaded) return;
        mapLoaded = true;
        File config = findDisplayConfig();
        if (config == null) {
            Log.w(TAG, "no display config with a brightness map found");
            return;
        }
        List<float[]> points = new ArrayList<>();
        try (InputStream in = new FileInputStream(config)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            boolean inMap = false, inPoint = false;
            String tag = null;
            float value = Float.NaN, nits = Float.NaN;
            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event == XmlPullParser.START_TAG) {
                    tag = parser.getName();
                    if ("screenBrightnessMap".equals(tag)) inMap = true;
                    else if (inMap && "point".equals(tag)) {
                        inPoint = true;
                        value = Float.NaN;
                        nits = Float.NaN;
                    }
                } else if (event == XmlPullParser.TEXT && inPoint && tag != null) {
                    String text = parser.getText().trim();
                    if (!text.isEmpty()) {
                        if ("value".equals(tag)) value = Float.parseFloat(text);
                        else if ("nits".equals(tag)) nits = Float.parseFloat(text);
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    String name = parser.getName();
                    if (inPoint && "point".equals(name)) {
                        inPoint = false;
                        if (!Float.isNaN(value) && !Float.isNaN(nits)) points.add(new float[]{value, nits});
                    } else if ("screenBrightnessMap".equals(name)) {
                        break;
                    }
                    tag = null;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "display config unreadable: " + e);
            return;
        }
        if (points.size() < 2) return;
        points.sort((a, b) -> Float.compare(a[0], b[0]));
        float[] b = new float[points.size()], n = new float[points.size()];
        for (int i = 0; i < points.size(); i++) {
            b[i] = points.get(i)[0];
            n[i] = points.get(i)[1];
        }
        mapBrightness = b;
        mapNits = n;
        Log.i(TAG, "panel brightness map " + config + ": " + Arrays.toString(b) + " -> " + Arrays.toString(n) + " nits");
    }

    @Nullable
    private static File findDisplayConfig() {
        for (String dir : CONFIG_DIRS) {
            File[] files = new File(dir).listFiles((d, name) ->
                    (name.startsWith("display_id_") || name.startsWith("display_port_")) && name.endsWith(".xml"));
            if (files != null && files.length > 0) {
                Arrays.sort(files);
                return files[0];
            }
        }
        return null;
    }

    private static float interpolate(float[] x, float[] y, float v) {
        if (v <= x[0]) return y[0];
        for (int i = 1; i < x.length; i++) {
            if (v <= x[i]) {
                float t = (v - x[i - 1]) / (x[i] - x[i - 1]);
                return y[i - 1] + t * (y[i] - y[i - 1]);
            }
        }
        return y[y.length - 1];
    }
}
