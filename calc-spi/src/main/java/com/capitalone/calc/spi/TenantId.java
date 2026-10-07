package com.capitalone.calc.spi;

import java.util.regex.Pattern;

/** A tenant's slug, as it appears in the URL and in the token's tenant_id claim. */
public record TenantId(String value) {
    private static final Pattern SLUG = Pattern.compile("[a-z][a-z0-9-]{1,30}");

    public TenantId {
        if (value == null || !SLUG.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid tenant id");
        }
    }
}
