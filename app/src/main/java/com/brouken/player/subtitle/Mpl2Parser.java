package com.brouken.player.subtitle;

import android.text.SpannableStringBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: MPL2 (.mpl / .txt): "[start][end]text" in deciseconds, "|" = line break, a leading "/" = italic line.
 */
final class Mpl2Parser extends TextSubtitleParser {

    private static final Pattern LINE = Pattern.compile("^\\[(\\d+)\\]\\[(\\d*)\\](.*)$");

    @Override
    List<Entry> parseEntries(String document) {
        List<Entry> entries = new ArrayList<>();
        List<String[]> pending = new ArrayList<>();
        for (String raw : document.split("\n")) {
            Matcher m = LINE.matcher(raw.trim());
            if (m.matches()) pending.add(new String[]{m.group(1), m.group(2), m.group(3)});
        }
        for (int i = 0; i < pending.size(); i++) {
            long start = Long.parseLong(pending.get(i)[0]) * 100;
            long end = pending.get(i)[1].isEmpty()
                    ? (i + 1 < pending.size() ? Long.parseLong(pending.get(i + 1)[0]) * 100 : start + 4000)
                    : Long.parseLong(pending.get(i)[1]) * 100;
            SpannableStringBuilder text = new SpannableStringBuilder();
            String[] lines = pending.get(i)[2].split("\\|");
            for (int l = 0; l < lines.length; l++) {
                if (l > 0) text.append('\n');
                String line = lines[l];
                if (line.startsWith("/")) text.append(italic(line.substring(1)));
                else text.append(line);
            }
            entries.add(new Entry(msToUs(start), msToUs(end), text));
        }
        return entries;
    }
}
