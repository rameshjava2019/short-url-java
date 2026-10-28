package com.example.shorturl.service;

import org.springframework.http.HttpStatus;

/**
 * An error that is safe to show to the user.
 * {@code status} is the HTTP status, {@code code} a stable machine-readable identifier.
 */
public class ShortUrlException extends RuntimeException {

    private final HttpStatus status;
    private final String code;

    public ShortUrlException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static ShortUrlException badRequest(String code, String message) {
        return new ShortUrlException(HttpStatus.BAD_REQUEST, code, message);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }
}
