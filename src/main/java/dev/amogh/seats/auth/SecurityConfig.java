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

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/auth/token").permitAll()
                        .requestMatchers(HttpMethod.GET, "/", "/shows/*", "/health", "/health/**",
                                "/metrics", "/info").permitAll()
                        .requestMatchers("/error").permitAll()
                        .requestMatchers(HttpMethod.POST, "/shows").hasAuthority("SCOPE_admin")
                        .anyRequest().authenticated())
                .oauth2ResourceServer(rs -> rs
                        .jwt(jwt -> { })
                        .authenticationEntryPoint(UNAUTHORIZED)
                        .accessDeniedHandler(FORBIDDEN))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(UNAUTHORIZED)
                        .accessDeniedHandler(FORBIDDEN));
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
