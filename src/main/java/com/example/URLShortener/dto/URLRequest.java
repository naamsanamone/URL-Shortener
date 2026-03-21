package com.example.URLShortener.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class URLRequest {

    @NotBlank
    private String longUrl;

    @Size(min = 1, max = 8)
    private String customAlias;

    private LocalDateTime expirationTime;
}

