package com.capitalone.calc.spi;

/** Implementations must be stateless and must not do I/O. */
public interface TenantCalculator<I, O> {
    TenantId tenantId();

    /** Recorded with every result, so a stored result can be traced to the logic that made it. */
    String version();

    /** The tenant's own input type. The platform deserializes the request's "input" into it. */
    Class<I> inputType();

    O calculateNDI(TenantContext ctx, I input);
}
