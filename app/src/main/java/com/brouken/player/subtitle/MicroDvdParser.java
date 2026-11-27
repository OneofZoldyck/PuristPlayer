package com.brouken.player.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.DoubleSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: MicroDVD (.sub / .txt): "{startFrame}{endFrame}text", "|" = line break, "{y:i}" = italic.
 * Frame rate: a "{1}{1}23.976" header line if present, otherwise the video's frame rate, otherwise 23.976.
 */
final class MicroDvdParser extends TextSubtitleParser {

    private static final Pattern LINE = Pattern.compile("^\\{(\\d+)\\}\\{(\\d*)\\}(.*)$");
    private static final Pattern CONTROL = Pattern.compile("\\{[^}]*\\}");
    private static final double DEFAULT_FPS = 24000.0 / 1001.0;

    private final DoubleSupplier videoFrameRate;

    MicroDvdParser(DoubleSupplier videoFrameRate) {
        this.videoFrameRate = videoFrameRate;
    }

    @Override
    List<Entry> parseEntries(String document) {
        List<long[]> frames = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        double fps = 0;
        for (String raw : document.split("\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            long start = Long.parseLong(m.group(1));
            long end = m.group(2).isEmpty() ? -1 : Long.parseLong(m.group(2));
            String text = m.group(3);
            if (frames.isEmpty() && start <= 1 && end <= 1 && fps == 0) {
                try {
                    double header = Double.parseDouble(text.trim());
                    if (header > 1 && header < 200) {
                        fps = header;
                        continue;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
            frames.add(new long[]{start, end});
            texts.add(text);
        }
        if (fps <= 0) {
            double probed = videoFrameRate.getAsDouble();
            fps = probed > 1 ? probed : DEFAULT_FPS;
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < frames.size(); i++) {
            long startUs = Math.round(frames.get(i)[0] * 1_000_000.0 / fps);
            long endFrame = frames.get(i)[1];
            long endUs = endFrame >= 0 ? Math.round(endFrame * 1_000_000.0 / fps)
                    : (i + 1 < frames.size() ? Math.round(frames.get(i + 1)[0] * 1_000_000.0 / fps) : startUs + 4_000_000);
            String raw = texts.get(i);
            boolean italic = raw.toLowerCase(Locale.US).contains("{y:i}");
            String text = CONTROL.matcher(raw).replaceAll("").replace('|', '\n');
            entries.add(new Entry(startUs, endUs, italic ? italic(text) : text));
        }
        return entries;
    }
}
