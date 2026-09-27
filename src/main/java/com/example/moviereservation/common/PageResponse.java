package com.example.moviereservation.common;

import org.springframework.data.domain.Page;

import java.util.List;

/**
 * Stable JSON envelope for paginated responses.
 *
 * <p>Spring's {@code Page} is never serialised directly: its JSON shape is an implementation
 * detail and changes between versions.
 *
 * @param content       the items of the current page
 * @param page          zero-based page number
 * @param size          requested page size
 * @param totalElements total number of matching items
 * @param totalPages    total number of pages
 * @param last          whether this is the last page
 * @param <T> item type
 */
public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        boolean last
) {

    /** Wraps a Spring Data page, optionally after mapping its content to DTOs. */
    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(
                page.getContent(),
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }

    /** Wraps a Spring Data page whose entities are converted with {@code content}. */
    public static <S, T> PageResponse<T> of(Page<S> page, List<T> content) {
        return new PageResponse<>(
                content,
                page.getNumber(),
                page.getSize(),
                page.getTotalElements(),
                page.getTotalPages(),
                page.isLast());
    }
}
