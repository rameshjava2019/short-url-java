package com.example.shorturl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ShortCodeGeneratorTest {

    private final ShortCodeGenerator generator = new ShortCodeGenerator();

    @Test
    void generatedCodesHaveTheExpectedLengthAndCharacters() {
        for (int i = 0; i < 100; i++) {
            assertThat(generator.generate()).hasSize(ShortCodeGenerator.GENERATED_CODE_LENGTH).matches("[A-Za-z0-9]+");
        }
    }

    @Test
    void generatedCodesArePracticallyUnique() {
        Set<String> codes = new HashSet<>();
        for (int i = 0; i < 10_000; i++) {
            codes.add(generator.generate());
        }
        assertThat(codes).hasSize(10_000);
    }

    @ParameterizedTest
    @ValueSource(strings = {"my-link", "Promo_2026", "abc"})
    void acceptsValidAliases(String alias) {
        assertThatCode(() -> generator.validateAlias(alias)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"ab", "has space", "slash/path", "dot.com", "<script>", ""})
    void rejectsAliasesWithBadLengthOrCharacters(String alias) {
        assertThatThrownBy(() -> generator.validateAlias(alias)).hasFieldOrPropertyWithValue("code", "ALIAS_INVALID");
    }

    @Test
    void rejectsAliasesThatAreTooLong() {
        assertThatThrownBy(() -> generator.validateAlias("a".repeat(31))).hasFieldOrPropertyWithValue("code", "ALIAS_INVALID");
    }

    @ParameterizedTest
    @ValueSource(strings = {"api", "API", "static", "health", "css", "shorten"})
    void rejectsReservedAliasesRegardlessOfCase(String alias) {
        assertThatThrownBy(() -> generator.validateAlias(alias)).hasFieldOrPropertyWithValue("code", "ALIAS_RESERVED");
    }
}
