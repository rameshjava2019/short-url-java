package com.example.shorturl.service;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** Generates random short codes and checks user-chosen aliases. */
@Component
public class ShortCodeGenerator {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    public static final int GENERATED_CODE_LENGTH = 7; // 62^7 ≈ 3.5 trillion combinations

    /** Codes a user may choose: letters, digits, "-" and "_", 3 to 30 characters. */
    public static final String CODE_REGEX = "[A-Za-z0-9_-]{3,30}";
    private static final Pattern CODE_PATTERN = Pattern.compile(CODE_REGEX);

    /** Paths the application itself uses; they can never be a short code. */
    private static final Set<String> RESERVED_CODES = Set.of("api", "static", "health", "css", "js", "error", "shorten");

    // Cryptographically secure, so codes cannot be guessed from previously issued ones.
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        StringBuilder code = new StringBuilder(GENERATED_CODE_LENGTH);
        for (int i = 0; i < GENERATED_CODE_LENGTH; i++) {
            code.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }

    /** Throws a 400 error if a user-chosen alias is not acceptable. */
    public void validateAlias(String alias) {
        if (alias == null || !CODE_PATTERN.matcher(alias).matches()) {
            throw ShortUrlException.badRequest("ALIAS_INVALID",
                    "Alias must be 3-30 characters using letters, numbers, \"-\" or \"_\".");
        }
        if (RESERVED_CODES.contains(alias.toLowerCase(Locale.ROOT))) {
            throw ShortUrlException.badRequest("ALIAS_RESERVED",
                    "\"" + alias + "\" is reserved. Please choose another alias.");
        }
    }

    public boolean isValidCodeFormat(String code) {
        return code != null && CODE_PATTERN.matcher(code).matches();
    }
}
