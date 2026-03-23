package com.example.URLShortener.services;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class UrlService {

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private final UrlRepository urlRepository;
    private final StringRedisTemplate redisTemplate;
    @Value("${app.base-url:http://localhost:8080/api/urls}")
    private String baseUrl;

    private String shortKey(String shortCode) {
        return "short:" + shortCode;
    }

    private String longKey(String longUrl) {
        return "long:" + longUrl;
    }

    @Transactional
    public URLResponse createShortUrl(URLRequest request) {
        String longUrl = request.getLongUrl();
        LocalDateTime expirationTime = request.getExpirationTime();

        // check cache for existing mapping (idempotent behavior)
        String cachedShortCode = redisTemplate.opsForValue().get(longKey(longUrl));
        if (cachedShortCode != null) {
            URL existing = urlRepository.findByShortUrlAndActiveTrue(cachedShortCode)
                    .orElseThrow(() -> new UrlNotFoundException("Short URL not found for cached code"));
            if (isExpired(existing)) {
                deactivate(existing);
            } else {
                return toResponse(existing);
            }
        }

        String desiredShortCode = request.getCustomAlias();
        if (desiredShortCode != null && !desiredShortCode.isBlank()) {
            if (urlRepository.existsByShortUrl(desiredShortCode)) {
                throw new AliasAlreadyExistsException("Custom alias already exists: " + desiredShortCode);
            }
        }

        URL url = URL.builder()
                .longUrl(longUrl)
                .expiresAt(expirationTime)
                .active(true)
                .shortUrl(desiredShortCode != null && !desiredShortCode.isBlank() ? desiredShortCode : "T" + (int)(Math.random() * 9000000 + 1000000))
                .build();

        // first save to generate numeric ID
        URL saved = urlRepository.save(url);

        String shortCode = desiredShortCode;
        if (shortCode == null || shortCode.isBlank()) {
            shortCode = Base62Encoder.encode(saved.getId());
        }

        saved.setShortUrl(shortCode);
        System.out.println("UrlService.createShortUrl short Code : " + shortCode);
        URL finalEntity = urlRepository.save(saved);

        cacheMapping(finalEntity);

        return toResponse(finalEntity);
    }

    @Transactional
    public String resolveLongUrl(String shortCode) {
        // check cache first
        String cachedLong = redisTemplate.opsForValue().get(shortKey(shortCode));
        if (cachedLong != null) {
            // even on cache hit, verify the URL has not expired
            Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
            if (optionalUrl.isPresent() && isExpired(optionalUrl.get())) {
                deactivate(optionalUrl.get());
                evictCache(shortCode, cachedLong);
                throw new UrlExpiredException("Short URL has expired: " + shortCode);
            }
            return cachedLong;
        }

        Optional<URL> optionalUrl = urlRepository.findByShortUrlAndActiveTrue(shortCode);
        URL url = optionalUrl.orElseThrow(() -> new UrlNotFoundException("Short URL not found: " + shortCode));

        if (isExpired(url)) {
            // mark inactive and throw dedicated exception
            deactivate(url);
            throw new UrlExpiredException("Short URL has expired: " + shortCode);
        }

        cacheMapping(url);
        return url.getLongUrl();
    }

    private void cacheMapping(URL url) {
        if (!url.isActive()) {
            return;
        }
        String code = url.getShortUrl();
        String longUrl = url.getLongUrl();
        redisTemplate.opsForValue().set(shortKey(code), longUrl, CACHE_TTL);
        redisTemplate.opsForValue().set(longKey(longUrl), code, CACHE_TTL);
    }

    private boolean isExpired(URL url) {
        LocalDateTime expiresAt = url.getExpiresAt();
        return expiresAt != null && LocalDateTime.now().isAfter(expiresAt);
    }

    private void deactivate(URL url) {
        if (url.isActive()) {
            url.setActive(false);
            urlRepository.save(url);
        }
    }

    private void evictCache(String shortCode, String longUrl) {
        redisTemplate.delete(shortKey(shortCode));
        redisTemplate.delete(longKey(longUrl));
    }

    private URLResponse toResponse(URL url) {
        String shortCode = url.getShortUrl();
        String fullShortUrl = baseUrl.endsWith("/")
                ? baseUrl + shortCode
                : baseUrl + "/" + shortCode;

        return URLResponse.builder()
                .shortUrl(fullShortUrl)
                .shortCode(shortCode)
                .longUrl(url.getLongUrl())
                .expirationTime(url.getExpiresAt())
                .build();
    }

    public static class AliasAlreadyExistsException extends RuntimeException {
        public AliasAlreadyExistsException(String message) {
            super(message);
        }
    }

    public static class UrlNotFoundException extends RuntimeException {
        public UrlNotFoundException(String message) {
            super(message);
        }
    }

    public static class UrlExpiredException extends RuntimeException {
        public UrlExpiredException(String message) {
            super(message);
        }
    }
}
