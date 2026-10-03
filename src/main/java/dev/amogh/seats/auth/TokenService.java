package dev.amogh.seats.auth;

import java.time.Instant;

import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

import dev.amogh.seats.AppProperties;

/**
 * Mints HS256 tokens. In production identity would come from a real IdP
 * (OIDC); this endpoint stands in for one so load tests can create thousands
 * of distinct users. The rest of the app only ever sees a validated JWT.
 */
@Service
public class TokenService {

    private final JwtEncoder encoder;
    private final AppProperties props;

    public TokenService(JwtEncoder encoder, AppProperties props) {
        this.encoder = encoder;
        this.props = props;
    }

    public String mint(String userId, boolean admin) {
        Instant now = Instant.now();
        var claims = JwtClaimsSet.builder()
                .issuer("seat-reservation")
                .subject(userId)
                .issuedAt(now)
                .expiresAt(now.plus(props.tokenTtl()))
                .claim("scope", admin ? "user admin" : "user")
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).type("JWT").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    public long ttlSeconds() {
        return props.tokenTtl().toSeconds();
    }
}
