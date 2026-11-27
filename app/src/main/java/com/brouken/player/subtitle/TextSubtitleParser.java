package com.brouken.player.subtitle;

import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.StyleSpan;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.text.Cue;
import androidx.media3.extractor.text.CuesWithTiming;
import androidx.media3.extractor.text.SubtitleParser;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * Purist: base for simple timed-text formats. Subclasses turn the decoded text into timed entries; this class
 * handles decoding (input is UTF-8 after Just Player's charset conversion), ordering and {@link OutputOptions}.
 */
abstract class TextSubtitleParser implements SubtitleParser {

    static final class Entry {
        final long startUs;
        final long endUs;
        final CharSequence text;

        Entry(long startUs, long endUs, CharSequence text) {
            this.startUs = startUs;
            this.endUs = endUs;
            this.text = text;
        }
    }

    /** Parse the whole document into entries (any order; empty text entries are dropped). */
    abstract List<Entry> parseEntries(String document);

    @Override
    public final void parse(byte[] data, int offset, int length, OutputOptions outputOptions, Consumer<CuesWithTiming> output) {
        String document = new String(data, offset, length, StandardCharsets.UTF_8);
        if (!document.isEmpty() && document.charAt(0) == '﻿') document = document.substring(1);
        document = document.replace("\r\n", "\n").replace('\r', '\n');

        List<Entry> entries = new ArrayList<>();
        for (Entry e : parseEntries(document)) {
            if (e.text != null && e.text.toString().trim().length() > 0 && e.endUs > e.startUs) entries.add(e);
        }
        Collections.sort(entries, (a, b) -> Long.compare(a.startUs, b.startUs));

        long startTimeUs = outputOptions.startTimeUs;
        List<Entry> before = new ArrayList<>();
        for (Entry e : entries) {
            if (startTimeUs == C.TIME_UNSET || e.endUs > startTimeUs) {
                emit(e, output);
            } else if (outputOptions.outputAllCues) {
                before.add(e);
            }
        }
        for (Entry e : before) emit(e, output);
    }

    private static void emit(Entry e, Consumer<CuesWithTiming> output) {
        Cue cue = new Cue.Builder().setText(e.text).build();
        output.accept(new CuesWithTiming(Collections.singletonList(cue), e.startUs, e.endUs - e.startUs));
    }

    @Override
    public int getCueReplacementBehavior() {
        return Format.CUE_REPLACEMENT_BEHAVIOR_MERGE;
    }

    // ---- helpers for subclasses ----

    /** Text with an italic span over all of it. */
    static CharSequence italic(CharSequence text) {
        SpannableStringBuilder sb = new SpannableStringBuilder(text);
        sb.setSpan(new StyleSpan(Typeface.ITALIC), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** Trim leading/trailing whitespace (incl. newlines) of a possibly-spanned text. */
    static CharSequence trim(CharSequence text) {
        int start = 0, end = text.length();
        while (start < end && Character.isWhitespace(text.charAt(start))) start++;
        while (end > start && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.subSequence(start, end);
    }

    static long msToUs(long ms) {
        return ms * 1000L;
    }
}
