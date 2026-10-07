package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** A plain HTTP client, so tests see exactly what a caller sees. */
final class ApiClient {
    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String base;

    ApiClient(int port) {
        this.base = "http://localhost:" + port;
    }

    Response post(String tenant, String token, String body) {
        return post(tenant, token, body, Map.of());
    }

    Response post(String tenant, String token, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/tenants/" + tenant + "/calculate-ndi"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return send(request, token, headers);
    }

    /** No Content-Length, so the body arrives chunked and its size is unknown up front. */
    Response postChunked(String tenant, String token, String body) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/v1/tenants/" + tenant + "/calculate-ndi"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8))));
        return send(request, token, Map.of());
    }

    Response get(String tenant, String token, String calculationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create(base + "/v1/tenants/" + tenant + "/calculate-ndi/" + calculationId)).GET();
        return send(request, token, Map.of());
    }

    private Response send(HttpRequest.Builder request, String token, Map<String, String> headers) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode body = response.body().isEmpty() ? mapper.nullNode() : mapper.readTree(response.body());
            return new Response(response.statusCode(), response.headers(), body);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    record Response(int status, HttpHeaders headers, JsonNode body) {
        String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        String problemType() {
            return body.path("type").asText();
        }
    }
}
