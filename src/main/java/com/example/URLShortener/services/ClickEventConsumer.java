package com.example.URLShortener.services;

import com.example.URLShortener.config.KafkaConfig;
import com.example.URLShortener.dto.ClickEventMessage;
import com.example.URLShortener.models.ClickEvent;
import com.example.URLShortener.repository.ClickEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Kafka consumer that persists click events to the database.
 * Decoupled from the redirect hot path — events arrive asynchronously
 * via the "url-click-events" topic, preserving redirect latency.
 *
 * Uses String deserialization + Jackson 3 ObjectMapper to avoid
 * Jackson 2 vs 3 classpath conflicts in Spring Boot 4.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickEventConsumer {

    private final ClickEventRepository clickEventRepository;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaConfig.CLICK_EVENTS_TOPIC,
            groupId = "analytics-consumer-group"
    )
    @Transactional
    public void consume(String messageJson) {
        try {
            ClickEventMessage message = objectMapper.readValue(messageJson, ClickEventMessage.class);
            log.debug("Consuming click event for shortUrl={}", message.getShortUrl());

            ClickEvent event = ClickEvent.builder()
                    .shortUrl(message.getShortUrl())
                    .ipAddress(message.getIpAddress())
                    .userAgent(message.getUserAgent())
                    .clickedAt(message.getClickedAt())
                    .build();

            clickEventRepository.save(event);
            log.info("Persisted click event for shortUrl={}", message.getShortUrl());
        } catch (Exception e) {
            log.error("Failed to process click event: {}", e.getMessage(), e);
        }
    }
}
