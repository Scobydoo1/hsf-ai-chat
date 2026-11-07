package com.hsf.aichat.service;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a long text into overlapping pieces (~1000 characters, 150 overlap).
 * Small pieces = more precise search; the overlap keeps sentences that sit on a border findable.
 */
public final class TextChunker {

    public static final int CHUNK_SIZE = 1000;
    public static final int OVERLAP = 150;

    private TextChunker() {
    }

    public static List<String> split(String text) {
        String clean = text.replace("\r\n", "\n").replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < clean.length()) {
            int end = Math.min(start + CHUNK_SIZE, clean.length());
            if (end < clean.length()) {
                // try to cut at a paragraph, then a sentence, then a space - but not too early
                int cut = lastBreak(clean, start + CHUNK_SIZE / 2, end);
                if (cut > 0) {
                    end = cut;
                }
            }
            String chunk = clean.substring(start, end).trim();
            if (!chunk.isEmpty()) {
                chunks.add(chunk);
            }
            if (end >= clean.length()) {
                break;
            }
            start = Math.max(end - OVERLAP, start + 1);
        }
        return chunks;
    }

    private static int lastBreak(String s, int from, int to) {
        for (String sep : new String[]{"\n\n", ". ", "\n", " "}) {
            int i = s.lastIndexOf(sep, to - sep.length());
            if (i >= from) {
                return i + sep.length();
            }
        }
        return -1;
    }
}
