package com.example.URLShortener.services;

import com.example.URLShortener.dto.AnalyticsResponse;
import com.example.URLShortener.models.ClickEvent;
import com.example.URLShortener.repository.ClickEventRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AnalyticsServiceTest {

    private ClickEventRepository clickEventRepository;
    private AnalyticsService analyticsService;

    @BeforeEach
    void setUp() {
        clickEventRepository = mock(ClickEventRepository.class);
        analyticsService = new AnalyticsService(clickEventRepository);
    }

    @Test
    void recordClick_usesXForwardedFor_whenPresent() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn("192.168.1.1");
        when(request.getHeader("User-Agent")).thenReturn("Mozilla/5.0");

        analyticsService.recordClick("abcd", request);

        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(eventCaptor.capture());

        ClickEvent saved = eventCaptor.getValue();
        assertThat(saved.getShortUrl()).isEqualTo("abcd");
        assertThat(saved.getIpAddress()).isEqualTo("192.168.1.1");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getClickedAt()).isNotNull();
    }

    @Test
    void recordClick_usesRemoteAddr_whenXForwardedForIsMissing() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Forwarded-For")).thenReturn(null);
        when(request.getRemoteAddr()).thenReturn("10.0.0.5");
        when(request.getHeader("User-Agent")).thenReturn("Chrome");

        analyticsService.recordClick("1234", request);

        ArgumentCaptor<ClickEvent> eventCaptor = ArgumentCaptor.forClass(ClickEvent.class);
        verify(clickEventRepository).save(eventCaptor.capture());

        ClickEvent saved = eventCaptor.getValue();
        assertThat(saved.getIpAddress()).isEqualTo("10.0.0.5");
        assertThat(saved.getUserAgent()).isEqualTo("Chrome");
    }

    @Test
    void getStats_returnsFormattedStats() {
        when(clickEventRepository.countByShortUrl("xyz")).thenReturn(150L);

        ClickEvent event1 = ClickEvent.builder()
                .ipAddress("1.1.1.1")
                .userAgent("Safari")
                .clickedAt(LocalDateTime.of(2026, 3, 23, 10, 30, 0))
                .build();
        
        ClickEvent event2 = ClickEvent.builder()
                .ipAddress("2.2.2.2")
                .userAgent("Firefox")
                .clickedAt(LocalDateTime.of(2026, 3, 23, 11, 45, 30))
                .build();

        when(clickEventRepository.findByShortUrlOrderByClickedAtDesc("xyz"))
                .thenReturn(List.of(event1, event2));

        AnalyticsResponse response = analyticsService.getStats("xyz");

        assertThat(response.getShortUrl()).isEqualTo("xyz");
        assertThat(response.getTotalClicks()).isEqualTo(150L);
        assertThat(response.getRecentClicks()).hasSize(2);

        assertThat(response.getRecentClicks().get(0).getIpAddress()).isEqualTo("1.1.1.1");
        assertThat(response.getRecentClicks().get(0).getClickedAt()).isEqualTo("2026-03-23 10:30:00");
    }
}
