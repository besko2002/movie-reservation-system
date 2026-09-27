package com.example.moviereservation.common;

/** Thrown when a request conflicts with the current state (duplicates, double booking). Mapped to HTTP 409. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
