package com.example.URLShortener.services;

import com.example.URLShortener.dto.AnalyticsResponse;
import com.example.URLShortener.models.ClickEvent;
import com.example.URLShortener.repository.ClickEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AnalyticsService {

    private final ClickEventRepository clickEventRepository;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Transactional
    public void recordClick(String shortUrl, HttpServletRequest request) {
        String ipAddress = request.getHeader("X-Forwarded-For");
        if (ipAddress == null || ipAddress.isEmpty() || "unknown".equalsIgnoreCase(ipAddress)) {
            ipAddress = request.getRemoteAddr();
        }

        String userAgent = request.getHeader("User-Agent");

        ClickEvent event = ClickEvent.builder()
                .shortUrl(shortUrl)
                .ipAddress(ipAddress)
                .userAgent(userAgent)
                .clickedAt(LocalDateTime.now())
                .build();

        clickEventRepository.save(event);
    }

    public AnalyticsResponse getStats(String shortUrl) {
        long totalClicks = clickEventRepository.countByShortUrl(shortUrl);
        
        List<ClickEvent> recentEvents = clickEventRepository.findByShortUrlOrderByClickedAtDesc(shortUrl);
        List<AnalyticsResponse.ClickDetails> details = recentEvents.stream()
                .limit(50) // only show last 50 clicks in UI for performance
                .map(e -> AnalyticsResponse.ClickDetails.builder()
                        .ipAddress(e.getIpAddress())
                        .userAgent(e.getUserAgent())
                        .clickedAt(e.getClickedAt().format(FORMATTER))
                        .build())
                .collect(Collectors.toList());

        return AnalyticsResponse.builder()
                .shortUrl(shortUrl)
                .totalClicks(totalClicks)
                .recentClicks(details)
                .build();
    }
}
