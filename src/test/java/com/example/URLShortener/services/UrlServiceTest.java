package com.example.URLShortener.services;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.UrlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UrlServiceTest {

    private UrlRepository urlRepository;
    private StringRedisTemplate redisTemplate;
    private ValueOperations<String, String> valueOperations;

    private UrlService urlService;

    @BeforeEach
    void setUp() {
        urlRepository = mock(UrlRepository.class);
        redisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        urlService = new UrlService(urlRepository, redisTemplate);
    }

    @Test
    void createShortUrl_generatesBase62FromId_whenNoCustomAlias() {
        URLRequest request = new URLRequest();
        request.setLongUrl("https://example.com");

        URL savedNoId = URL.builder()
                .id(null)
                .longUrl("https://example.com")
                .build();

        URL savedWithId = URL.builder()
                .id(1)
                .longUrl("https://example.com")
                .build();

        when(urlRepository.save(any(URL.class)))
                .thenReturn(savedNoId)
                .thenReturn(savedWithId);

        URLResponse response = urlService.createShortUrl(request);

        assertThat(response.getShortCode()).isNotBlank();
        assertThat(response.getLongUrl()).isEqualTo("https://example.com");
        verify(urlRepository, times(2)).save(any(URL.class));
        verify(valueOperations, atLeastOnce()).set(startsWith("short:"), eq("https://example.com"), any());
        verify(valueOperations, atLeastOnce()).set(startsWith("long:"), anyString(), any());
    }

    @Test
    void createShortUrl_throwsConflict_whenCustomAliasExists() {
        URLRequest request = new URLRequest();
        request.setLongUrl("https://example.com");
        request.setCustomAlias("alias");

        when(urlRepository.existsByShortUrl("alias")).thenReturn(true);

        assertThrows(UrlService.AliasAlreadyExistsException.class, () -> urlService.createShortUrl(request));
        verify(urlRepository).existsByShortUrl("alias");
    }

    @Test
    void resolveLongUrl_returnsFromCache_whenPresent() {
        when(valueOperations.get("short:code")).thenReturn("https://cached.com");

        String result = urlService.resolveLongUrl("code");

        assertThat(result).isEqualTo("https://cached.com");
        verify(urlRepository, never()).findByShortUrlAndActiveTrue(anyString());
    }

    @Test
    void resolveLongUrl_throwsExpired_whenPastExpiration() {
        URL entity = URL.builder()
                .id(1)
                .shortUrl("code")
                .longUrl("https://expired.com")
                .expiresAt(LocalDateTime.now().minusMinutes(1))
                .active(true)
                .build();

        when(urlRepository.findByShortUrlAndActiveTrue("code")).thenReturn(Optional.of(entity));

        assertThrows(UrlService.UrlExpiredException.class, () -> urlService.resolveLongUrl("code"));
        ArgumentCaptor<URL> captor = ArgumentCaptor.forClass(URL.class);
        verify(urlRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    void resolveLongUrl_throwsNotFound_whenMissing() {
        when(urlRepository.findByShortUrlAndActiveTrue("missing")).thenReturn(Optional.empty());

        assertThrows(UrlService.UrlNotFoundException.class, () -> urlService.resolveLongUrl("missing"));
    }
}

