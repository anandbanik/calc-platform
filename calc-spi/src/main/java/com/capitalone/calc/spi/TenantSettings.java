package com.capitalone.calc.spi;

import java.math.BigDecimal;
import java.util.Map;

/** Read-only settings for one tenant. The platform fills it from tenants.<id>.* only. */
public record TenantSettings(Map<String, String> values) {
    public TenantSettings {
        values = Map.copyOf(values);
    }

    public BigDecimal decimal(String key, String fallback) {
        return new BigDecimal(values.getOrDefault(key, fallback));
    }
}
