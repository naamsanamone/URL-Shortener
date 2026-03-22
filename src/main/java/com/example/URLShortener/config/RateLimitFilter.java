package com.example.URLShortener.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limiting filter using Bucket4j token-bucket algorithm.
 * Limits requests per client IP on API endpoints.
 */
@Component
public class RateLimitFilter implements Filter {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Value("${app.rate-limit.requests-per-minute:20}")
    private int requestsPerMinute;

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpReq = (HttpServletRequest) request;
        HttpServletResponse httpRes = (HttpServletResponse) response;

        // Only apply rate limiting to POST / creating data
        if (!"POST".equalsIgnoreCase(httpReq.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(httpReq);
        Bucket bucket = buckets.computeIfAbsent(clientIp, k -> createBucket());

        if (bucket.tryConsume(1)) {
            // add remaining tokens header for transparency
            httpRes.addHeader("X-Rate-Limit-Remaining",
                    String.valueOf(bucket.getAvailableTokens()));
            chain.doFilter(request, response);
        } else {
            httpRes.setStatus(429);
            httpRes.setContentType("application/json");
            httpRes.addHeader("Retry-After", "60");
            httpRes.addHeader("X-Rate-Limit-Remaining", "0");
            httpRes.getWriter().write(
                    "{\"error\":\"Too many requests. Please try again later.\",\"retryAfterSeconds\":60}");
        }
    }

    private Bucket createBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(requestsPerMinute)
                .refillGreedy(requestsPerMinute, Duration.ofMinutes(1))
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
