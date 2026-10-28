package com.example.shorturl.service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.example.shorturl.config.AppProperties;

/**
 * Validates a user-supplied URL and returns it in normalized form.
 *
 * Rules:
 *  - must be non-empty and at most 2048 characters
 *  - must be an absolute http:// or https:// URL (blocks javascript:, data:, file:, ...)
 *  - must not point back at this service (prevents redirect loops)
 */
@Component
public class UrlValidator {

    public static final int MAX_URL_LENGTH = 2048;
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private final String ownHost;

    public UrlValidator(AppProperties properties) {
        this.ownHost = properties.host();
    }

    public String normalize(String input) {
        if (input == null || input.isBlank()) {
            throw ShortUrlException.badRequest("URL_REQUIRED", "Please provide a URL.");
        }

        String trimmed = input.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw ShortUrlException.badRequest("URL_TOO_LONG", "URL must be at most " + MAX_URL_LENGTH + " characters.");
        }

        URI uri;
        try {
            uri = new URI(trimmed);
        } catch (URISyntaxException e) {
            throw ShortUrlException.badRequest("URL_INVALID", "That does not look like a valid URL.");
        }

        if (uri.getScheme() == null || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase())) {
            throw ShortUrlException.badRequest("URL_PROTOCOL_NOT_ALLOWED", "Only http:// and https:// URLs are allowed.");
        }
        if (uri.getHost() == null) {
            throw ShortUrlException.badRequest("URL_INVALID", "That does not look like a valid URL.");
        }
        if (uri.getRawAuthority().equalsIgnoreCase(ownHost)) {
            throw ShortUrlException.badRequest("URL_SELF_REFERENCE", "Links to this service cannot be shortened.");
        }

        // ASCII form, so the URL is always safe to put in a Location header.
        return uri.normalize().toASCIIString();
    }
}
