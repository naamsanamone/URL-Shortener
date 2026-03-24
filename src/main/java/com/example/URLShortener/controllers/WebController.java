package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.AnalyticsService;
import org.springframework.web.bind.annotation.PathVariable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
@RequiredArgsConstructor
public class WebController {

    private final UrlService urlService;
    private final AnalyticsService analyticsService;

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("urlRequest", new URLRequest());
        return "index";
    }

    @PostMapping("/shorten")
    public String shortenUrl(@ModelAttribute URLRequest urlRequest, Model model) {
        model.addAttribute("urlRequest", urlRequest);
        try {
            URLResponse response = urlService.createShortUrl(urlRequest);
            model.addAttribute("result", response);
            model.addAttribute("statsUrl", "/stats/" + response.getShortCode());
        } catch (AliasAlreadyExistsException e) {
            model.addAttribute("error", "Custom alias already taken. Please choose a different one.");
        } catch (Exception e) {
            model.addAttribute("error", "Something went wrong: " + e.getMessage());
        }
        return "index";
    }

    @GetMapping("/stats/{shortCode}")
    public String viewStats(@PathVariable("shortCode") String shortCode, Model model) {
        try {
            // we first check if the url exists via UrlService so we can throw 404 if invalid
            // however urlService doesn't have an explicit `exists` method. We can try to resolve it.
            // but tracking might exist even if expired. Let's just fetch stats directly.
            model.addAttribute("stats", analyticsService.getStats(shortCode));
            return "analytics";
        } catch (Exception e) {
            model.addAttribute("error", "Unable to load statistics for this link.");
            return "index"; // Simple fallback for now
        }
    }
}
