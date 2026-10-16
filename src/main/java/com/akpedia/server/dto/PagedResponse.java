package com.akpedia.server.dto;

import java.util.List;
import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.data.domain.Page;

/**
 * One page of results, with enough about the whole set for a screen to page through it.
 *
 * <p>Spring Data's own {@code Page} is not returned directly: its JSON shape is an
 * implementation detail of the framework, has changed between versions, and carries a
 * {@code pageable} object the caller has no use for. This record is the contract instead.
 *
 * @param items         the results on this page, in the requested order
 * @param page          zero-based index of this page
 * @param size          how many results a full page holds
 * @param totalElements how many results exist across every page
 * @param totalPages    how many pages exist; {@code 0} when there are no results at all
 */
public record PagedResponse<T>(
        List<T> items,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages) {

    /** Wraps a Spring Data page, mapping its contents with {@code mapper}. */
    public static <E, T> PagedResponse<T> of(Page<E> source, Function<E, T> mapper) {
        return new PagedResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}
