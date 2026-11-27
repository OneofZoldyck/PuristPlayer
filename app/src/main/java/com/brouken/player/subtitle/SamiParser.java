package com.brouken.player.subtitle;

import android.text.Html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Purist: SAMI (.smi / .sami). Each &lt;SYNC Start=ms&gt; block lasts until the next one; a block whose text is
 * empty or &amp;nbsp; clears the screen. Multi-language files: the first language class found is used.
 * HTML formatting (&lt;i&gt;, &lt;b&gt;, &lt;font color&gt;, &lt;br&gt;) is kept as styled text.
 */
final class SamiParser extends TextSubtitleParser {

    private static final Pattern SYNC = Pattern.compile("<sync\\s+start\\s*=\\s*[\"']?(\\d+)[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern P_CLASS = Pattern.compile("<p[^>]*class\\s*=\\s*[\"']?([\\w-]+)[\"']?[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern END_BODY = Pattern.compile("</body>", Pattern.CASE_INSENSITIVE);

    @Override
    List<Entry> parseEntries(String document) {
        List<long[]> times = new ArrayList<>();
        List<String> bodies = new ArrayList<>();
        Matcher m = SYNC.matcher(document);
        int lastEnd = -1;
        long lastStart = -1;
        while (m.find()) {
            if (lastEnd >= 0) {
                times.add(new long[]{lastStart});
                bodies.add(document.substring(lastEnd, m.start()));
            }
            lastStart = Long.parseLong(m.group(1));
            lastEnd = m.end();
        }
        if (lastEnd >= 0) {
            Matcher end = END_BODY.matcher(document);
            int stop = end.find(lastEnd) ? end.start() : document.length();
            times.add(new long[]{lastStart});
            bodies.add(document.substring(lastEnd, stop));
        }

        String language = firstLanguageClass(bodies);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < bodies.size(); i++) {
            String html = paragraphFor(bodies.get(i), language);
            if (html == null) continue;
            String cleaned = html.replaceAll("(?i)&nbsp;", " ");
            CharSequence text = trim(Html.fromHtml(cleaned, Html.FROM_HTML_MODE_LEGACY));
            if (text.length() == 0) continue;
            long start = times.get(i)[0];
            long end = (i + 1 < times.size()) ? times.get(i + 1)[0] : start + 4000;
            entries.add(new Entry(msToUs(start), msToUs(end), text));
        }
        return entries;
    }

    private static String firstLanguageClass(List<String> bodies) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String b : bodies) {
            Matcher m = P_CLASS.matcher(b);
            while (m.find()) {
                String c = m.group(1).toLowerCase(Locale.US);
                Integer n = counts.get(c);
                counts.put(c, n == null ? 1 : n + 1);
            }
        }
        return counts.isEmpty() ? null : counts.keySet().iterator().next();
    }

    /** The paragraph of the chosen language class (or the whole block when untagged). */
    private static String paragraphFor(String body, String language) {
        if (language == null) return body;
        Matcher m = P_CLASS.matcher(body);
        while (m.find()) {
            if (m.group(1).toLowerCase(Locale.US).equals(language)) {
                int next = indexOfIgnoreCase(body, "<p", m.end());
                return body.substring(m.end(), next < 0 ? body.length() : next);
            }
        }
        // Block without the chosen class (e.g. a bare <SYNC> clear): treat as clear.
        return P_CLASS.matcher(body).find() ? null : body;
    }

    private static int indexOfIgnoreCase(String s, String needle, int from) {
        return s.toLowerCase(Locale.US).indexOf(needle, from);
    }
}
