package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantId;
import com.capitalone.calc.spi.TenantSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Reads tenants.<id>.* for one tenant. Calculators get the settings without limits.*;
 * the guard gets the limits, falling back to platform.limits.*.
 */
@Component
class TenantSettingsProvider {
    private static final String LIMITS = "limits.";

    private final Binder binder;
    private final TenantLimits defaults;
    private final Map<TenantId, Map<String, String>> raw = new ConcurrentHashMap<>();

    TenantSettingsProvider(Environment environment) {
        this.binder = Binder.get(environment);
        this.defaults = new TenantLimits(
                binder.bind("platform.limits.requests-per-second", Integer.class).orElse(50),
                binder.bind("platform.limits.max-concurrent", Integer.class).orElse(10),
                binder.bind("platform.limits.statement-timeout-ms", Integer.class).orElse(2000));
    }

    TenantSettings forTenant(TenantId id) {
        Map<String, String> settings = new HashMap<>(raw(id));
        settings.keySet().removeIf(key -> key.startsWith(LIMITS));
        return new TenantSettings(settings);
    }

    TenantLimits limits(TenantId id) {
        Map<String, String> values = raw(id);
        return new TenantLimits(
                intOr(values.get(LIMITS + "requests-per-second"), defaults.requestsPerSecond()),
                intOr(values.get(LIMITS + "max-concurrent"), defaults.maxConcurrent()),
                intOr(values.get(LIMITS + "statement-timeout-ms"), defaults.statementTimeoutMs()));
    }

    private Map<String, String> raw(TenantId id) {
        return raw.computeIfAbsent(id, tenant -> Map.copyOf(binder
                .bind("tenants." + tenant.value(), Bindable.mapOf(String.class, String.class))
                .orElse(Map.of())));
    }

    private static int intOr(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value.trim());
    }
}
