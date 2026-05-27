package com.example.URLShortener.controllers;

import com.example.URLShortener.config.KafkaConfig;
import com.example.URLShortener.dto.ClickEventMessage;
import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.LocalDateTime;

@Slf4j
@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class urlController {

    private final UrlService urlService;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @GetMapping("/{shortUrl}")
    public ResponseEntity<Void> getLongURLByShortURL(@NotNull @PathVariable("shortUrl") String shortUrl,
            HttpServletRequest request) {
        try {
            String longUrl = urlService.resolveLongUrl(shortUrl);

            // Fire-and-forget async publish to Kafka — non-blocking (~1ms)
            // Analytics are persisted by ClickEventConsumer in the background
            try {
                ClickEventMessage clickEvent = ClickEventMessage.builder()
                        .shortUrl(shortUrl)
                        .ipAddress(resolveClientIp(request))
                        .userAgent(request.getHeader("User-Agent"))
                        .clickedAt(LocalDateTime.now())
                        .build();
                String json = objectMapper.writeValueAsString(clickEvent);
                kafkaTemplate.send(KafkaConfig.CLICK_EVENTS_TOPIC, shortUrl, json);
            } catch (Exception e) {
                // Kafka publish failure should NOT block the redirect
                log.warn("Failed to publish click event to Kafka for shortUrl={}: {}", shortUrl, e.getMessage());
            }

            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(longUrl)).build();
        } catch (UrlExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        } catch (UrlNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping
    public ResponseEntity<URLResponse> createShortURL(@Valid @RequestBody URLRequest urlRequest) {
        try {
            URLResponse response = urlService.createShortUrl(urlRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AliasAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(URLResponse.builder().shortUrl("error").build());
        }
    }

    private String resolveClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty() && !"unknown".equalsIgnoreCase(xForwardedFor)) {
            return xForwardedFor.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
