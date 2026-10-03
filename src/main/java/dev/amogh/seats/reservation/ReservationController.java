package dev.amogh.seats.reservation;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import dev.amogh.seats.web.ApiException;

@RestController
public class ReservationController {

    private final ReservationService service;

    public ReservationController(ReservationService service) {
        this.service = service;
    }

    /**
     * The user is {@code jwt.getSubject()}, nothing else. The key may come as an
     * Idempotency-Key header or an idempotency_key body field; if both are sent
     * they must agree.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<String> reserve(@PathVariable String showId,
                                          @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
                                          @RequestBody ReserveRequest body,
                                          @AuthenticationPrincipal Jwt jwt) {
        String key = resolveKey(headerKey, body.idempotencyKey());
        ReserveResult result = service.reserve(jwt.getSubject(), showId, body.seats(), key);

        var response = ResponseEntity.status(result.httpStatus()).contentType(MediaType.APPLICATION_JSON);
        if (result instanceof ReserveResult.Replayed) {
            response.header("Idempotent-Replayed", "true");
        }
        return response.body(result.body());
    }

    @GetMapping("/reservations/{reservationId}")
    public ReservationView get(@PathVariable String reservationId, @AuthenticationPrincipal Jwt jwt) {
        return service.get(jwt.getSubject(), reservationId);
    }

    private static String resolveKey(String header, String body) {
        if (header != null && body != null && !header.equals(body)) {
            throw ApiException.badRequest("idempotency_key_mismatch",
                    "Idempotency-Key header and idempotency_key body field differ");
        }
        return header != null ? header : body;
    }
}
