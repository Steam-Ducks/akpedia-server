package com.akpedia.server.dto;

/** The shape of a Redis Stream entry id, as the call-back bodies carry it. */
final class StreamMessageIds {

    /** {@code <milliseconds>-<sequence>}, e.g. {@code 1761234567890-0}. */
    static final String PATTERN = "\\d+-\\d+";

    private StreamMessageIds() {
    }
}
