package com.capitalone.calc.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantSettingsTest {

    @Test
    void readsDecimalsWithFallback() {
        TenantSettings settings = new TenantSettings(Map.of("rate", "0.30"));
        assertEquals(new BigDecimal("0.30"), settings.decimal("rate", "0.25"));
        assertEquals(new BigDecimal("0.25"), settings.decimal("missing", "0.25"));
    }

    @Test
    void isImmutableAndDetachedFromItsSource() {
        Map<String, String> source = new HashMap<>(Map.of("rate", "0.30"));
        TenantSettings settings = new TenantSettings(source);
        source.put("rate", "0.99");

        assertEquals(new BigDecimal("0.30"), settings.decimal("rate", "0"));
        assertThrows(UnsupportedOperationException.class, () -> settings.values().put("x", "1"));
    }
}
