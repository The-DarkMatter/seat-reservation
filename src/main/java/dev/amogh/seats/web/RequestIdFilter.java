package dev.amogh.seats.web;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request a correlation id (the caller's X-Request-Id if it sent a
 * sane one, otherwise a fresh UUID), puts it in the MDC so every log line for
 * the request carries it, echoes it back in the response, and writes one
 * structured access-log line per request. Runs before Spring Security, so 401s
 * are correlated too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Logger access = LoggerFactory.getLogger("http.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(HEADER);
        String requestId = incoming != null && SAFE_ID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString();
        MDC.put("request_id", requestId);
        response.setHeader(HEADER, requestId);
        long start = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            String path = request.getRequestURI();
            if (!isProbeOrScrape(path)) {
                access.atInfo()
                        .addKeyValue("http.method", request.getMethod())
                        .addKeyValue("http.path", path)
                        .addKeyValue("http.status", response.getStatus())
                        .addKeyValue("duration_ms", (System.nanoTime() - start) / 1_000_000)
                        .log("{} {} {}", request.getMethod(), path, response.getStatus());
            }
            MDC.remove("request_id");
        }
    }

    /** Health probes and metric scrapes run every few seconds; logging them is just noise. */
    private static boolean isProbeOrScrape(String path) {
        return path.startsWith("/health") || path.equals("/metrics");
    }
}
