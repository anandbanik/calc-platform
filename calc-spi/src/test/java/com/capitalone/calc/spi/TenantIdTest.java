package com.capitalone.calc.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TenantIdTest {

    @Test
    void acceptsLowercaseSlugs() {
        assertEquals("auto-finance2", new TenantId("auto-finance2").value());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "USCard", "1card", "-card", "us_card", "us card",
            "abcdefghijklmnopqrstuvwxyzabcdef"})
    void rejectsAnythingElse(String value) {
        assertThrows(IllegalArgumentException.class, () -> new TenantId(value));
    }

    @Test
    void rejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new TenantId(null));
    }
}
