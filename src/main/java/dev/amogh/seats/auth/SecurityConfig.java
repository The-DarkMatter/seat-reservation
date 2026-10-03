package dev.amogh.seats.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy;

import dev.amogh.seats.AppProperties;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Stateless bearer-token auth. The token's {@code sub} is the ONLY source of
 * user identity: controllers read it from the validated JWT and never from the
 * request body, so a spoofed {@code user_id} field is simply ignored.
 */
@Configuration
public class SecurityConfig {

    private static final AuthenticationEntryPoint UNAUTHORIZED = (req, res, e) ->
            writeError(res, HttpStatus.UNAUTHORIZED, "unauthorized",
                    "A valid bearer token is required (get one from POST /auth/token)");

    private static final AccessDeniedHandler FORBIDDEN = (req, res, e) ->
            writeError(res, HttpStatus.FORBIDDEN, "forbidden", "This token is not allowed to do that");

    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; font-src 'self' data:; connect-src 'self'; base-uri 'self'; form-action 'self'; "
            + "frame-ancestors 'self' https://amogh.cloud https://*.amogh.cloud";

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/shows", "/shows/*", "/shows/*/seatmap", "/health", "/health/**",
                                "/metrics", "/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        // The Kursi UI: static files and the app's own routes.
                        .requestMatchers(HttpMethod.GET, "/index.html", "/favicon.svg", "/assets/**",
                                "/events/*", "/checkout/*", "/tickets/*", "/me", "/lab").permitAll()
                        // The public demo (rate limited per IP in DemoController).
                        .requestMatchers(HttpMethod.GET, "/demo/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/demo/shows", "/demo/shows/*/rush").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasAuthority("SCOPE_admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(UNAUTHORIZED)
                        .accessDeniedHandler(FORBIDDEN))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(UNAUTHORIZED)
                        .accessDeniedHandler(FORBIDDEN))
                // The UI loads nothing from other origins. It may be framed by the
                // portfolio site (frame-ancestors), so X-Frame-Options is replaced by CSP.
                .headers(h -> h
                        .frameOptions(f -> f.disable())
                        .contentSecurityPolicy(csp -> csp.policyDirectives(CSP))
                        .referrerPolicy(r -> r.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    @Bean
    SecretKey jwtSigningKey(AppProperties props) {
        byte[] secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("JWT_SECRET must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey key) {
        return NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey key) {
        return NimbusJwtEncoder.withSecretKey(key).algorithm(MacAlgorithm.HS256).build();
    }

    private static void writeError(HttpServletResponse res, HttpStatus status, String code, String message)
            throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.getWriter().write("{\"error\":\"" + code + "\",\"message\":\"" + message + "\"}");
    }
}
