package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonNode;

public record Computation(String calculatorVersion, JsonNode result) {}
