package com.example.moviereservation.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

/** Translates exceptions into a consistent {@link ApiError} JSON body. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, ex.getMessage(), request);
    }

    @ExceptionHandler(BadRequestException.class)
    public ResponseEntity<ApiError> handleBadRequest(BadRequestException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiError> handleServiceUnavailable(ServiceUnavailableException ex,
                                                            HttpServletRequest request) {
        // A capability that is not configured (phase 7: Stripe) is neither a bad request nor an
        // internal error: the client gets 503 plus a message naming what is missing.
        return build(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), request);
    }

    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiError> handleBadCredentials(BadCredentialsException ex, HttpServletRequest request) {
        // The message is intentionally generic: it must not reveal whether the email exists.
        return build(HttpStatus.UNAUTHORIZED, ex.getMessage(), request);
    }

    /**
     * Method-security failures ({@code @PreAuthorize}) are rethrown so Spring Security's
     * {@code ExceptionTranslationFilter} answers them with 401 or 403 through the security handlers,
     * instead of the catch-all below turning them into 500s.
     */
    @ExceptionHandler({AccessDeniedException.class, AuthenticationException.class})
    public void rethrowSecurityException(RuntimeException ex) {
        throw ex;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        for (FieldError fieldError : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.merge(fieldError.getField(), defaultMessage(fieldError), (first, second) -> first + "; " + second);
        }
        ApiError body = ApiError.of(
                HttpStatus.BAD_REQUEST.value(),
                HttpStatus.BAD_REQUEST.getReasonPhrase(),
                "Validation failed",
                request.getRequestURI(),
                fieldErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Invalid value for parameter '%s'".formatted(ex.getName()), request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest request) {
        // Spring MVC's own client errors (405, 404 no handler, 415, missing parameter, ResponseStatusException, ...)
        // carry their status; keep it instead of turning them into 500s.
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            return buildFrameworkError(errorResponse, request);
        }
        // Logged server side only; the client never sees the stack trace or the internal message.
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", request);
    }

    private static String defaultMessage(FieldError fieldError) {
        return fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage();
    }

    private static ResponseEntity<ApiError> buildFrameworkError(ErrorResponse errorResponse, HttpServletRequest request) {
        HttpStatusCode statusCode = errorResponse.getStatusCode();
        HttpStatus status = HttpStatus.resolve(statusCode.value());
        String reason = status != null ? status.getReasonPhrase() : String.valueOf(statusCode.value());
        String detail = errorResponse.getBody().getDetail();
        ApiError body = ApiError.of(statusCode.value(), reason, detail != null ? detail : reason, request.getRequestURI());
        return ResponseEntity.status(statusCode).headers(errorResponse.getHeaders()).body(body);
    }

    private static ResponseEntity<ApiError> build(HttpStatus status, String message, HttpServletRequest request) {
        ApiError body = ApiError.of(status.value(), status.getReasonPhrase(), message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
