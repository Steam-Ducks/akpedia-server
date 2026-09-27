package com.akpedia.server.service;

import java.util.regex.Pattern;

/** Cuts document text down to a snippet worth putting on a card. */
final class Snippets {

    /** What marks a snippet as cut short. One character, so it barely eats into the budget. */
    private static final String ELLIPSIS = "…";

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

    private Snippets() {
    }

    /**
     * Cuts a chunk down to at most {@code limit} characters, ellipsis included.
     *
     * <p>A chunk is however long akpedia-ml decided to make it, and it comes out of a PDF, so it
     * arrives with the line breaks of the page it was taken from. Runs of whitespace collapse into
     * single spaces to spend the budget on words instead of layout, and the cut lands on the last
     * whole word that fits, with an ellipsis marking that the text goes on.
     *
     * <p>The returned snippet never exceeds {@code limit}, so a caller can size a card from the
     * configured value alone. A single word longer than half the budget is cut mid-word rather than
     * turning the snippet into just an ellipsis.
     */
    static String of(String chunk, int limit) {
        if (chunk == null) {
            return null;
        }
        String text = WHITESPACE_RUN.matcher(chunk).replaceAll(" ").strip();
        if (text.length() <= limit) {
            return text;
        }

        int room = limit - ELLIPSIS.length();
        int cut = text.lastIndexOf(' ', room);
        if (cut < room / 2) {
            cut = room;
        }
        return text.substring(0, cut).stripTrailing() + ELLIPSIS;
    }
}
