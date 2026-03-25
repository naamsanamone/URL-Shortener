package com.example.URLShortener.services;

import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CleanupServiceTest {

    @Mock
    private UrlRepository urlRepository;

    @Mock
    private ClickEventRepository clickEventRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private CleanupService cleanupService;

    @Test
    void cleanupExpiredUrls_withExpiredUrls_deletesAndEvicts() {
        URL expiredUrl = URL.builder()
                .shortUrl("abcd")
                .longUrl("http://example.com")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();

        when(urlRepository.findByExpiresAtBefore(any(LocalDateTime.class)))
                .thenReturn(List.of(expiredUrl));

        cleanupService.cleanupExpiredUrls();

        verify(clickEventRepository, times(1)).deleteByShortUrl("abcd");
        verify(urlRepository, times(1)).delete(expiredUrl);
        verify(redisTemplate, times(1)).delete("short:abcd");
        verify(redisTemplate, times(1)).delete("long:http://example.com");
    }

    @Test
    void cleanupExpiredUrls_noExpiredUrls_doesNothing() {
        when(urlRepository.findByExpiresAtBefore(any(LocalDateTime.class)))
                .thenReturn(Collections.emptyList());

        cleanupService.cleanupExpiredUrls();

        verify(clickEventRepository, never()).deleteByShortUrl(anyString());
        verify(urlRepository, never()).delete(any(URL.class));
        verify(redisTemplate, never()).delete(anyString());
    }
}
