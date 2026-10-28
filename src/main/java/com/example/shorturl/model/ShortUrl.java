package com.example.shorturl.model;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** One short link, stored in the "shorturls" collection. */
@Document("shorturls")
public class ShortUrl {

    @Id
    private String id;

    /** The code in the public link, e.g. "aZ3kP9q" in http://host/aZ3kP9q. Unique. */
    @Indexed(unique = true)
    private String code;

    /** Where the short link redirects to. */
    private String originalUrl;

    /** True when the user picked the code themselves. */
    private boolean customAlias;

    // Basic analytics
    private long clickCount;
    private Instant lastAccessedAt;

    private Instant createdAt;

    protected ShortUrl() {
        // for MongoDB mapping
    }

    public ShortUrl(String code, String originalUrl, boolean customAlias) {
        this.code = code;
        this.originalUrl = originalUrl;
        this.customAlias = customAlias;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getOriginalUrl() {
        return originalUrl;
    }

    public boolean isCustomAlias() {
        return customAlias;
    }

    public long getClickCount() {
        return clickCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
