package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;

@RestController
@RequestMapping("/api/urls")
@RequiredArgsConstructor
public class urlController {

    private final UrlService urlService;

    @GetMapping("/{shortUrl}")
    public ResponseEntity<Void> getLongURLByShortURL(@NotNull @PathVariable("shortUrl") String shortUrl) {
        try {
            String longUrl = urlService.resolveLongUrl(shortUrl);
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create(longUrl)).build();
        } catch (UrlExpiredException e) {
            return ResponseEntity.status(HttpStatus.GONE).build();
        } catch (UrlNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
    }

    @PostMapping
    public ResponseEntity<URLResponse> createShortURL(@Valid @RequestBody URLRequest urlRequest) {
        try {
            URLResponse response = urlService.createShortUrl(urlRequest);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (AliasAlreadyExistsException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(URLResponse.builder().shortUrl("error").build());
        }
    }
}
