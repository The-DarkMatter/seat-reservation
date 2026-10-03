package dev.amogh.seats.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.HttpStatus;

/**
 * A request we refuse on purpose. Always a 4xx (or a deliberate 503), always
 * rendered as {@code {"error": "<code>", "message": "...", ...details}}.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String error;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String error, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.error = error;
        this.details = details == null ? Map.of() : details;
    }

    public static ApiException badRequest(String error, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, error, message, null);
    }

    public static ApiException badRequest(String error, String message, Map<String, Object> details) {
        return new ApiException(HttpStatus.BAD_REQUEST, error, message, details);
    }

    public static ApiException notFound(String error, String message) {
        return new ApiException(HttpStatus.NOT_FOUND, error, message, null);
    }

    public static ApiException forbidden(String error, String message) {
        return new ApiException(HttpStatus.FORBIDDEN, error, message, null);
    }

    public static ApiException conflict(String error, String message) {
        return new ApiException(HttpStatus.CONFLICT, error, message, null);
    }

    public HttpStatus status() {
        return status;
    }

    public Map<String, Object> body() {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", error);
        body.put("message", getMessage());
        body.putAll(details);
        return body;
    }
}
