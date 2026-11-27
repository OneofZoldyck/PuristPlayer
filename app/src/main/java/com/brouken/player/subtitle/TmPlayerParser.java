package com.brouken.player.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: TMPlayer (.txt): "hh:mm:ss:text" or "h:mm:ss=text"; "|" = line break. There is no end time: a line
 * lasts until the next one, capped at 5 s.
 */
final class TmPlayerParser extends TextSubtitleParser {

    private static final Pattern LINE = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})[:=](.*)$");
    private static final long MAX_DURATION_MS = 5000;

    @Override
    List<Entry> parseEntries(String document) {
        List<long[]> starts = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (String raw : document.split("\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (!m.matches()) continue;
            long start = ((Long.parseLong(m.group(1)) * 60 + Long.parseLong(m.group(2))) * 60 + Long.parseLong(m.group(3))) * 1000;
            String text = m.group(4).replace('|', '\n');
            if (!starts.isEmpty() && starts.get(starts.size() - 1)[0] == start) {
                // Same timestamp on consecutive lines = one multi-line subtitle
                int last = texts.size() - 1;
                texts.set(last, texts.get(last) + "\n" + text);
                continue;
            }
            starts.add(new long[]{start});
            texts.add(text);
        }
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < starts.size(); i++) {
            long start = starts.get(i)[0];
            long next = i + 1 < starts.size() ? starts.get(i + 1)[0] : Long.MAX_VALUE;
            long end = Math.min(next, start + MAX_DURATION_MS);
            entries.add(new Entry(msToUs(start), msToUs(end), texts.get(i)));
        }
        return entries;
    }
}
