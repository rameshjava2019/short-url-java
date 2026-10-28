package com.example.shorturl.web;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.example.shorturl.config.AppProperties;

/**
 * Limits how many links one client can create per 15-minute window.
 * In memory, so the limit is per application instance.
 */
@Component
public class CreateRateLimiter {

    static final Duration WINDOW = Duration.ofMinutes(15);
    private static final int CLEANUP_THRESHOLD = 10_000;

    private record Window(Instant start, int count) {
    }

    private final int limit;
    private final Clock clock;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Autowired
    public CreateRateLimiter(AppProperties properties) {
        this(properties.createRateLimit(), Clock.systemUTC());
    }

    CreateRateLimiter(int limit, Clock clock) {
        this.limit = limit;
        this.clock = clock;
    }

    /** Records one request for this client; returns false if it is over the limit. */
    public boolean tryAcquire(String clientKey) {
        Instant now = clock.instant();
        if (windows.size() > CLEANUP_THRESHOLD) {
            windows.values().removeIf(w -> isExpired(w, now));
        }
        Window window = windows.compute(clientKey,
                (key, current) -> current == null || isExpired(current, now) ? new Window(now, 1) : new Window(current.start(), current.count() + 1));
        return window.count() <= limit;
    }

    private static boolean isExpired(Window window, Instant now) {
        return !now.isBefore(window.start().plus(WINDOW));
    }
}
