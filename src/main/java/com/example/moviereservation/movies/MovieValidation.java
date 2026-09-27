package com.example.moviereservation.movies;

/** Validation constants shared by the movie request payloads. */
final class MovieValidation {

    /** Absolute http/https URL with a host and no whitespace. */
    static final String URL_PATTERN = "^https?://[^\\s/?#]+[^\\s]*$";

    static final String URL_MESSAGE = "posterUrl must be a valid http or https URL";

    private MovieValidation() {
    }

    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
