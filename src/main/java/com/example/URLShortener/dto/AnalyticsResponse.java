package com.example.URLShortener.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AnalyticsResponse {
    private String shortUrl;
    private long totalClicks;
    private List<ClickDetails> recentClicks;

    @Data
    @Builder
    public static class ClickDetails {
        private String ipAddress;
        private String userAgent;
        private String clickedAt;
    }
}
