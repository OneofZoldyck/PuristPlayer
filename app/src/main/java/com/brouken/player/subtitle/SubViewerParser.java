package com.brouken.player.subtitle;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: SubViewer 1/2 (.sub) and YouTube SBV (.sbv): a "h:mm:ss.ff,h:mm:ss.ff" timing line followed by text
 * lines until a blank line. "[br]" = line break. Header sections ([INFORMATION] etc.) are ignored.
 */
final class SubViewerParser extends TextSubtitleParser {

    private static final Pattern TIMING = Pattern.compile(
            "^(\\d+):(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})\\s*,\\s*(\\d+):(\\d{1,2}):(\\d{1,2})[.,](\\d{1,3})\\s*$");

    @Override
    List<Entry> parseEntries(String document) {
        List<Entry> entries = new ArrayList<>();
        String[] lines = document.split("\n");
        for (int i = 0; i < lines.length; i++) {
            Matcher m = TIMING.matcher(lines[i].trim());
            if (!m.matches()) continue;
            long start = toMs(m.group(1), m.group(2), m.group(3), m.group(4));
            long end = toMs(m.group(5), m.group(6), m.group(7), m.group(8));
            StringBuilder text = new StringBuilder();
            while (i + 1 < lines.length && !lines[i + 1].trim().isEmpty() && !TIMING.matcher(lines[i + 1].trim()).matches()) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[++i].trim());
            }
            String clean = text.toString().replaceAll("(?i)\\[br\\]", "\n");
            entries.add(new Entry(msToUs(start), msToUs(end), clean));
        }
        return entries;
    }

    private static long toMs(String h, String m, String s, String frac) {
        long ms = Long.parseLong(frac);
        for (int d = frac.length(); d < 3; d++) ms *= 10; // "5" = 500 ms, "50" = 500 ms, "500" = 500 ms
        return ((Long.parseLong(h) * 60 + Long.parseLong(m)) * 60 + Long.parseLong(s)) * 1000 + ms;
    }
}
