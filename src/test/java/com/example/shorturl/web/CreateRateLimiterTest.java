package com.example.shorturl.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CreateRateLimiterTest {

    @Test
    void allowsUpToTheLimitPerClientPerWindow() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        CreateRateLimiter limiter = new CreateRateLimiter(2, clock);

        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();
        assertThat(limiter.tryAcquire("2.2.2.2")).isTrue(); // other clients are not affected
    }

    @Test
    void resetsAfterTheWindow() {
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        CreateRateLimiter limiter = new CreateRateLimiter(1, Clock.fixed(start, ZoneOffset.UTC));
        assertThat(limiter.tryAcquire("1.1.1.1")).isTrue();
        assertThat(limiter.tryAcquire("1.1.1.1")).isFalse();

        CreateRateLimiter later = new CreateRateLimiter(1, Clock.fixed(start.plus(CreateRateLimiter.WINDOW), ZoneOffset.UTC));
        assertThat(later.tryAcquire("1.1.1.1")).isTrue();
    }
}
