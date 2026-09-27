package com.example.moviereservation.common;

/** Thrown when the request is syntactically valid but semantically wrong. Mapped to HTTP 400. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
