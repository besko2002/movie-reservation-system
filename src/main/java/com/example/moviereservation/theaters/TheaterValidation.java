package com.example.moviereservation.theaters;

/** Validation constants and helpers shared by the theater request records. */
final class TheaterValidation {

    /** A row label is a single letter; whether it exists in the grid is checked by the service. */
    static final String ROW_LABEL_PATTERN = "^[A-Za-z]$";

    static final String ROW_LABEL_MESSAGE = "vipRows entries must be a single letter A-Z";

    private TheaterValidation() {
    }

    /** @return {@code null} for a {@code null} or blank value, otherwise the trimmed value */
    static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
