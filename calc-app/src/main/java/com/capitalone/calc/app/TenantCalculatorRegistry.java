package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantCalculator;
import com.capitalone.calc.spi.TenantId;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The only place shared code meets tenant code. Refuses to start if two modules claim one tenant. */
public final class TenantCalculatorRegistry {
    private static final Logger log = LoggerFactory.getLogger(TenantCalculatorRegistry.class);

    private final Map<TenantId, TenantCalculator<?, ?>> byTenant;

    /** Discovers every calculator on the runtime classpath. */
    @SuppressWarnings("rawtypes")
    public static TenantCalculatorRegistry discover() {
        Map<TenantId, TenantCalculator<?, ?>> found = new HashMap<>();
        for (TenantCalculator calculator : ServiceLoader.load(TenantCalculator.class)) {
            register(found, calculator);
        }
        TenantCalculatorRegistry registry = new TenantCalculatorRegistry(found);
        log.info("Registered tenant calculators: {}", registry.tenants());
        return registry;
    }

    TenantCalculatorRegistry(Map<TenantId, TenantCalculator<?, ?>> byTenant) {
        this.byTenant = Map.copyOf(byTenant);
    }

    static void register(Map<TenantId, TenantCalculator<?, ?>> into, TenantCalculator<?, ?> calculator) {
        if (into.putIfAbsent(calculator.tenantId(), calculator) != null) {
            throw new IllegalStateException(
                    "More than one calculator registered for tenant " + calculator.tenantId().value());
        }
    }

    public TenantCalculator<?, ?> forTenant(TenantId id) {
        TenantCalculator<?, ?> calculator = byTenant.get(id);
        if (calculator == null) {
            throw new TenantNotFoundException(id);
        }
        return calculator;
    }

    Set<String> tenants() {
        Set<String> ids = new TreeSet<>();
        byTenant.keySet().forEach(id -> ids.add(id.value()));
        return ids;
    }
}
