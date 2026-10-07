package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

/** The shared envelope. The shape of "result" belongs to the tenant's module. */
record CalculateNDIResponse(UUID calculationId, String tenantId, String calculatorVersion,
                            JsonNode result, Instant computedAt) {

    static CalculateNDIResponse of(StoredNDI stored) {
        return new CalculateNDIResponse(stored.calculationId(), stored.tenantId(), stored.calculatorVersion(),
                stored.result(), stored.computedAt());
    }
}
