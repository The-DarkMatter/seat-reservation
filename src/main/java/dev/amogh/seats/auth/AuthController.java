package dev.amogh.seats.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import dev.amogh.seats.AppProperties;
import dev.amogh.seats.web.ApiException;

@RestController
public class AuthController {

    static final Pattern USER_ID = Pattern.compile("[A-Za-z0-9._@-]{1,64}");

    private final TokenService tokens;
    private final AppProperties props;

    public AuthController(TokenService tokens, AppProperties props) {
        this.tokens = tokens;
        this.props = props;
    }

    public record TokenRequest(String userId, String role, String adminSecret) {
    }

    public record TokenResponse(String token, String tokenType, String userId, String role, long expiresIn) {
    }

    /**
     * {"user_id": "alice"} gets a user token. {"user_id": "ops", "role": "admin",
     * "admin_secret": "..."} gets an admin token (needed for POST /shows).
     */
    @PostMapping("/auth/token")
    public TokenResponse token(@RequestBody TokenRequest req) {
        if (req.userId() == null || !USER_ID.matcher(req.userId()).matches()) {
            throw ApiException.badRequest("invalid_user_id",
                    "user_id must be 1-64 characters of A-Z a-z 0-9 . _ @ -");
        }
        String role = req.role() == null ? "user" : req.role();
        boolean admin = switch (role) {
            case "user" -> false;
            case "admin" -> {
                if (!secretMatches(req.adminSecret())) {
                    throw ApiException.forbidden("invalid_admin_secret", "admin_secret is wrong");
                }
                yield true;
            }
            default -> throw ApiException.badRequest("invalid_role", "role must be 'user' or 'admin'");
        };
        return new TokenResponse(tokens.mint(req.userId(), admin), "Bearer", req.userId(), role,
                tokens.ttlSeconds());
    }

    /** Constant-time compare, so the secret can't be recovered byte by byte from response timings. */
    private boolean secretMatches(String candidate) {
        if (candidate == null) {
            return false;
        }
        return MessageDigest.isEqual(
                candidate.getBytes(StandardCharsets.UTF_8),
                props.adminSecret().getBytes(StandardCharsets.UTF_8));
    }
}
