package dev.amogh.seats.web;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Every response, including failures, is JSON with an {@code error} code. Domain
 * declines and malformed requests are 4xx; the only 5xx paths are "the database
 * is unreachable" (503, fail closed) and genuine bugs (500, logged loudly).
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Map<String, Object>> api(ApiException e) {
        return ResponseEntity.status(e.status()).body(e.body());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException e) {
        // Covers malformed JSON, wrong types and floats where integer paise are expected.
        return error(HttpStatus.BAD_REQUEST, "malformed_request", rootMessage(e));
    }

    @ExceptionHandler(DataAccessResourceFailureException.class)
    ResponseEntity<Map<String, Object>> databaseDown(DataAccessResourceFailureException e) {
        log.error("database unavailable", e);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", "1")
                .body(body("database_unavailable", "Reservations are paused while the database is unreachable"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Map<String, Object>> other(Exception e) {
        // Spring MVC's own failures (405, 415, 404 for unknown routes, missing
        // headers...) already know their 4xx status.
        if (e instanceof ErrorResponse er) {
            HttpStatusCode status = er.getStatusCode();
            String detail = er.getBody().getDetail();
            return error(status, codeFor(status), detail != null ? detail : e.getMessage());
        }
        log.error("unhandled exception", e);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "Unexpected server error");
    }

    private static ResponseEntity<Map<String, Object>> error(HttpStatusCode status, String code, String message) {
        return ResponseEntity.status(status).body(body(code, message));
    }

    private static Map<String, Object> body(String code, String message) {
        var body = new LinkedHashMap<String, Object>();
        body.put("error", code);
        body.put("message", message);
        return body;
    }

    private static String codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> "bad_request";
            case 404 -> "not_found";
            case 405 -> "method_not_allowed";
            case 406 -> "not_acceptable";
            case 415 -> "unsupported_media_type";
            default -> "http_" + status.value();
        };
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        String msg = t.getMessage();
        if (msg == null) {
            return "Request body could not be read";
        }
        // Jackson messages can echo large inputs; keep the first line only.
        int nl = msg.indexOf('\n');
        return nl > 0 ? msg.substring(0, nl) : msg;
    }
}
