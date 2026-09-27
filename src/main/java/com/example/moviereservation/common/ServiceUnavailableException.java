package com.example.moviereservation.common;

/**
 * Thrown when the request is perfectly valid but a capability the server needs is not available
 * right now — typically an external dependency that is not configured or temporarily down. Mapped
 * to HTTP 503 with a {@link ApiError} body, never to a 500.
 *
 * <p>Phase 7 uses it for "online payment is not configured": with an empty
 * {@code app.stripe.secret-key} the application still starts and serves everything else, and only
 * the endpoints that really need Stripe answer 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
