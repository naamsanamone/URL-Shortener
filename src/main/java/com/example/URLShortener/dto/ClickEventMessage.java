package com.example.URLShortener.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Kafka message DTO for click events.
 * Published by the redirect endpoint and consumed by ClickEventConsumer.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ClickEventMessage {

    private String shortUrl;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime clickedAt;
}
