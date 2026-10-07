package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonNode;

/** The shared envelope. The shape of "input" belongs to the tenant's module. */
record CalculateNDIRequest(JsonNode input) {}
