package com.example.shorturl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.example.shorturl.config.AppProperties;

class UrlValidatorTest {

    private final UrlValidator validator = new UrlValidator(new AppProperties("http://localhost:8080", 100));

    private void assertRejected(String input, String expectedCode) {
        assertThatThrownBy(() -> validator.normalize(input))
                .isInstanceOf(ShortUrlException.class)
                .hasFieldOrPropertyWithValue("code", expectedCode);
    }

    @Test
    void acceptsHttpAndHttpsUrls() {
        assertThat(validator.normalize("https://example.com/path?q=1")).isEqualTo("https://example.com/path?q=1");
        assertThat(validator.normalize("http://example.com")).isEqualTo("http://example.com");
    }

    @Test
    void trimsSurroundingWhitespace() {
        assertThat(validator.normalize("  https://example.com/a  ")).isEqualTo("https://example.com/a");
    }

    @Test
    void rejectsEmptyInput() {
        assertRejected(null, "URL_REQUIRED");
        assertRejected("", "URL_REQUIRED");
        assertRejected("   ", "URL_REQUIRED");
    }

    @Test
    void rejectsStringsThatAreNotUrls() {
        assertRejected("not a url", "URL_INVALID");
        assertRejected("https://", "URL_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"javascript:alert(1)", "data:text/html,hello", "file:///etc/passwd", "ftp://example.com", "example.com"})
    void rejectsDangerousOrMissingProtocols(String input) {
        assertRejected(input, "URL_PROTOCOL_NOT_ALLOWED");
    }

    @Test
    void rejectsUrlsLongerThanTheLimit() {
        assertRejected("https://example.com/" + "a".repeat(UrlValidator.MAX_URL_LENGTH), "URL_TOO_LONG");
    }

    @Test
    void rejectsLinksBackToThisServiceToAvoidRedirectLoops() {
        assertRejected("http://localhost:8080/abc1234", "URL_SELF_REFERENCE");
        assertRejected("http://LOCALHOST:8080/x", "URL_SELF_REFERENCE");
        assertThat(validator.normalize("http://localhost:9090/x")).isEqualTo("http://localhost:9090/x");
    }
}
