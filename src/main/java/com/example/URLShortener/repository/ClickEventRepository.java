package com.example.URLShortener.repository;

import com.example.URLShortener.models.ClickEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickEventRepository extends JpaRepository<ClickEvent, Long> {
    List<ClickEvent> findByShortUrlOrderByClickedAtDesc(String shortUrl);
    long countByShortUrl(String shortUrl);
    
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("DELETE FROM ClickEvent c WHERE c.shortUrl = :shortUrl")
    void deleteByShortUrl(@org.springframework.data.repository.query.Param("shortUrl") String shortUrl);
}
