package com.example.URLShortener.controllers;

import com.example.URLShortener.dto.URLRequest;
import com.example.URLShortener.dto.URLResponse;
import com.example.URLShortener.services.UrlService;
import com.example.URLShortener.services.UrlService.AliasAlreadyExistsException;
import com.example.URLShortener.services.UrlService.UrlExpiredException;
import com.example.URLShortener.services.UrlService.UrlNotFoundException;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

public class UrlControllerTest {

        @Test
        void getLongURLByShortURL_redirectsWhenFound() {
                UrlService urlService = Mockito.mock(UrlService.class);
                urlController controller = new urlController(urlService);

                when(urlService.resolveLongUrl("code")).thenReturn("https://example.com");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("code");

                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
                assertThat(response.getHeaders().getLocation()).hasToString("https://example.com");
        }

        @Test
        void getLongURLByShortURL_returnsGoneWhenExpired() {
                UrlService urlService = Mockito.mock(UrlService.class);
                urlController controller = new urlController(urlService);

                Mockito.doThrow(new UrlExpiredException("expired")).when(urlService).resolveLongUrl("expired");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("expired");
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GONE);
        }

        @Test
        void getLongURLByShortURL_returnsNotFoundWhenMissing() {
                UrlService urlService = Mockito.mock(UrlService.class);
                urlController controller = new urlController(urlService);

                Mockito.doThrow(new UrlNotFoundException("missing")).when(urlService).resolveLongUrl("missing");

                ResponseEntity<Void> response = controller.getLongURLByShortURL("missing");
                assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        void createShortURL_returnsCreatedOnSuccess() {
                UrlService urlService = Mockito.mock(UrlService.class);
                urlController controller = new urlController(urlService);

                URLResponse response = URLResponse.builder()
                                .shortUrl("http://localhost:8080/api/urls/code")
                                .shortCode("code")
                                .longUrl("https://example.com")
                                .build();

                when(urlService.createShortUrl(any(URLRequest.class))).thenReturn(response);

                URLRequest request = new URLRequest();
                request.setLongUrl("https://example.com");

                ResponseEntity<URLResponse> resp = controller.createShortURL(request);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
                assertThat(resp.getBody()).isNotNull();
                assertThat(resp.getBody().getShortCode()).isEqualTo("code");
        }

        @Test
        void createShortURL_returnsConflictOnAliasExists() {
                UrlService urlService = Mockito.mock(UrlService.class);
                urlController controller = new urlController(urlService);

                Mockito.doThrow(new AliasAlreadyExistsException("alias exists"))
                                .when(urlService).createShortUrl(any(URLRequest.class));

                URLRequest request = new URLRequest();
                request.setLongUrl("https://example.com");
                request.setCustomAlias("alias");

                ResponseEntity<URLResponse> resp = controller.createShortURL(request);

                assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        }
}
