package com.example.URLShortener.repository;

import com.example.URLShortener.models.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    List<ClickEvent> findByShortUrlOrderByClickedAtDesc(String shortUrl);
    long countByShortUrl(String shortUrl);
}
