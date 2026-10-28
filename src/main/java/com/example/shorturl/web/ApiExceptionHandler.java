package com.example.shorturl.web;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.example.shorturl.service.ShortUrlException;

/** Consistent JSON errors for the API: {"error": {"code": "...", "message": "..."}}. */
@RestControllerAdvice(assignableTypes = ApiController.class)
public class ApiExceptionHandler {

    @ExceptionHandler(ShortUrlException.class)
    public ResponseEntity<Map<String, Map<String, String>>> handle(ShortUrlException e) {
        return ResponseEntity.status(e.getStatus())
                .body(Map.of("error", Map.of("code", e.getCode(), "message", e.getMessage())));
    }
}
