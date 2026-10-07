package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.UUID;

record StoredNDI(String tenantId, UUID calculationId, String calculatorVersion,
                 JsonNode input, JsonNode result, Instant computedAt) {}
