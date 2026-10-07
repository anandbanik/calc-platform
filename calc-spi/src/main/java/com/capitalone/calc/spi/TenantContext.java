package com.capitalone.calc.spi;

/** Who the request is for. Built once at the edge, immutable, passed explicitly. */
public record TenantContext(TenantId tenantId, String requestId, TenantSettings settings) {}
