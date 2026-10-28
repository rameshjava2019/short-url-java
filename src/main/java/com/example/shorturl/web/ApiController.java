package com.example.shorturl.web;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.example.shorturl.service.ShortUrlService;
import com.example.shorturl.service.ShortUrlView;

/** Read-only JSON endpoints (no state changes, so no CSRF token needed). */
@RestController
public class ApiController {

    private final ShortUrlService shortUrlService;

    public ApiController(ShortUrlService shortUrlService) {
        this.shortUrlService = shortUrlService;
    }

    @GetMapping("/api/urls/{code}")
    public ShortUrlView stats(@PathVariable String code) {
        return shortUrlService.getStats(code);
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
