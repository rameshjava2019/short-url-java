package com.example.shorturl.config;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings, bound from the "app.*" properties in application.yml
 * (which in turn read environment variables).
 *
 * @param baseUrl         public base URL used to build short links, e.g. http://localhost:8080
 * @param createRateLimit link-creation requests allowed per client IP per 15 minutes
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String baseUrl, int createRateLimit) {

    public AppProperties {
        baseUrl = baseUrl.replaceAll("/+$", "");
    }

    /** Host (and port) of this service, e.g. "localhost:8080". */
    public String host() {
        return URI.create(baseUrl).getRawAuthority();
    }
}
