package com.example.URLShortener.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class URLResponse {

    private String shortUrl;

    private String shortCode;

    private String longUrl;

    private LocalDateTime expirationTime;
}
