package com.example.URLShortener.services;

import com.example.URLShortener.models.URL;
import com.example.URLShortener.repository.ClickEventRepository;
import com.example.URLShortener.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class CleanupService {

    private final UrlRepository urlRepository;
    private final ClickEventRepository clickEventRepository;
    private final StringRedisTemplate redisTemplate;

    // Run every hour at minute 0
    @Scheduled(cron = "0 0 * * * *")
    @Transactional
    public void cleanupExpiredUrls() {
        log.info("Starting background cleanup of expired URLs");
        LocalDateTime now = LocalDateTime.now();
        
        List<URL> expiredUrls = urlRepository.findByExpiresAtBefore(now);
        
        if (expiredUrls.isEmpty()) {
            log.info("No expired URLs found to clean up.");
            return;
        }

        int count = 0;
        for (URL url : expiredUrls) {
            String shortCode = url.getShortUrl();
            String longUrl = url.getLongUrl();

            // 1. Delete associated analytics
            clickEventRepository.deleteByShortUrl(shortCode);

            // 2. Delete the URL
            urlRepository.delete(url);

            // 3. Purge from Redis Cache
            redisTemplate.delete("short:" + shortCode);
            redisTemplate.delete("long:" + longUrl);
            
            count++;
        }

        log.info("Successfully cleaned up {} expired URLs and their associated analytics.", count);
    }
}
