package com.akpedia.server.dto;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One page of a paginated list, with what a client needs to page through the rest.
 *
 * <p>Answered instead of Spring's own {@link Page}, whose JSON form is an implementation detail
 * Spring itself warns against exposing.
 *
 * @param content       the items on this page; empty past the last page
 * @param page          index of this page, counted from 0
 * @param size          the requested page size; the last page may hold fewer items
 * @param totalElements how many items there are across all pages
 * @param totalPages    how many pages there are at this size
 * @param <T>           item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        @JsonProperty("total_elements") long totalElements,
        @JsonProperty("total_pages") int totalPages) {

    public static <S, T> PageResponse<T> from(Page<S> page, Function<? super S, ? extends T> mapper) {
        return new PageResponse<>(
                page.getContent().stream().<T>map(mapper).toList(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages());
    }
}
