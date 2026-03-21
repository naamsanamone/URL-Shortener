package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
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
        } catch (AliasAlreadyExistsException e) {
            model.addAttribute("error", "Custom alias already taken. Please choose a different one.");
        } catch (Exception e) {
            model.addAttribute("error", "Something went wrong: " + e.getMessage());
        }
        return "index";
    }
}
