package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.ui.Model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebControllerTest {

    @Mock
    private UrlService urlService;

    @Mock
    private Model model;

    private WebController webController;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        webController = new WebController(urlService);
    }

    @Test
    void index_addsEmptyRequestToModel() {
        String view = webController.index(model);
        assertThat(view).isEqualTo("index");
        verify(model).addAttribute(eq("urlRequest"), any(URLRequest.class));
    }

    @Test
    void shortenUrl_addsResultToModel_onSuccess() {
        URLRequest request = new URLRequest();
        URLResponse response = URLResponse.builder().shortCode("abc").build();
        when(urlService.createShortUrl(request)).thenReturn(response);

        String view = webController.shortenUrl(request, model);
        
        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute("result", response);
    }

    @Test
    void shortenUrl_addsErrorToModel_whenAliasExists() {
        URLRequest request = new URLRequest();
        when(urlService.createShortUrl(request)).thenThrow(new AliasAlreadyExistsException("exists"));

        String view = webController.shortenUrl(request, model);

        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute(eq("error"), any(String.class));
    }

    @Test
    void shortenUrl_addsErrorToModel_onGeneralException() {
        URLRequest request = new URLRequest();
        when(urlService.createShortUrl(request)).thenThrow(new RuntimeException("DB error"));

        String view = webController.shortenUrl(request, model);

        assertThat(view).isEqualTo("index");
        verify(model).addAttribute("urlRequest", request);
        verify(model).addAttribute(eq("error"), any(String.class));
    }
}
