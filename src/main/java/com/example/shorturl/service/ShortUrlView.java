package com.example.shorturl.service;

import java.io.Serializable;
import java.time.Instant;

import com.example.shorturl.model.ShortUrl;

/** What the UI and API show about a short link. */
public record ShortUrlView(
        String code,
        String shortUrl,
        String originalUrl,
        long clickCount,
        Instant createdAt,
        Instant lastAccessedAt) implements Serializable {

    public static ShortUrlView of(ShortUrl doc, String baseUrl) {
        return new ShortUrlView(doc.getCode(), baseUrl + "/" + doc.getCode(), doc.getOriginalUrl(),
                doc.getClickCount(), doc.getCreatedAt(), doc.getLastAccessedAt());
    }
}
