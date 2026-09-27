package com.example.moviereservation.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Standard error payload returned by every failing endpoint.
 *
 * @param timestamp   when the error was produced
 * @param status      HTTP status code
 * @param error       HTTP status reason phrase
 * @param message     human readable description
 * @param path        request path that produced the error
 * @param fieldErrors field name -&gt; validation message, only present for validation failures
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors
) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, null);
    }

    public static ApiError of(int status, String error, String message, String path, Map<String, String> fieldErrors) {
        return new ApiError(OffsetDateTime.now(), status, error, message, path, fieldErrors);
    }
}
