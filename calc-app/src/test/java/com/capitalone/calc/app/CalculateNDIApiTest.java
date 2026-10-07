package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitalone.calc.app.ApiClient.Response;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** The API end to end: HTTP, security, dispatch, both tenant modules and Postgres with row-level security. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CalculateNDIApiTest extends PostgresTestSupport {
    static final String USCARD_BODY = """
            { "input": { "monthlyNetIncome": "5200.00", "monthlyHousingCost": "1500.00", "monthlyDebtPayments": "650.00" } }""";
    static final String AUTOFINANCE_BODY = """
            { "input": { "annualGrossIncome": "84000.00", "monthlyObligations": "2100.00", "dependents": 2 } }""";

    static final String USCARD = Tokens.forTenant("uscard");
    static final String AUTOFINANCE = Tokens.forTenant("autofinance");

    @LocalServerPort
    int port;

    ApiClient api;

    @BeforeEach
    void client() {
        api = new ApiClient(port);
    }

    @Nested
    class WorkedExamples {

        @Test
        void usCard() {
            Response response = api.post("uscard", USCARD, USCARD_BODY);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().path("tenantId").asText()).isEqualTo("uscard");
            assertThat(response.body().path("calculatorVersion").asText()).isEqualTo("uscard-1");
            assertThat(response.body().path("result").path("ndi").isTextual()).isTrue();
            assertThat(response.body().path("result").path("ndi").asText()).isEqualTo("1850.00");
            assertThat(UUID.fromString(response.body().path("calculationId").asText())).isNotNull();
            assertThat(response.body().path("computedAt").asText()).endsWith("Z");
        }

        @Test
        void autoFinance() {
            Response response = api.post("autofinance", AUTOFINANCE, AUTOFINANCE_BODY);

            assertThat(response.status()).isEqualTo(200);
            assertThat(response.body().path("tenantId").asText()).isEqualTo("autofinance");
            assertThat(response.body().path("calculatorVersion").asText()).isEqualTo("autofinance-1");
            assertThat(response.body().path("result").path("ndi").asText()).isEqualTo("2550.00");
        }

        @Test
        void storedResultCanBeFetched() {
            Response created = api.post("uscard", USCARD, USCARD_BODY);
            String id = created.body().path("calculationId").asText();

            Response fetched = api.get("uscard", USCARD, id);

            assertThat(fetched.status()).isEqualTo(200);
            assertThat(fetched.body()).isEqualTo(created.body());
        }
    }

    @Nested
    class Authentication {

        @Test
        void missingTokenIs401() {
            Response response = api.post("uscard", null, USCARD_BODY);

            assertThat(response.status()).isEqualTo(401);
            assertThat(response.header("Content-Type")).startsWith("application/problem+json");
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:unauthenticated");
        }

        @Test
        void tokenSignedWithAnotherKeyIs401() {
            assertThat(api.post("uscard", Tokens.signedWithWrongKey("uscard"), USCARD_BODY).status()).isEqualTo(401);
        }

        @Test
        void expiredTokenIs401() {
            assertThat(api.post("uscard", Tokens.expired("uscard"), USCARD_BODY).status()).isEqualTo(401);
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void tokenForOneTenantIsRejectedOnAnothersPath() {
            Response response = api.post("autofinance", USCARD, AUTOFINANCE_BODY);

            assertThat(response.status()).isEqualTo(403);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:tenant-mismatch");
        }

        @Test
        void unknownAndKnownTenantsLookTheSameToAnOutsider() {
            Response known = api.post("autofinance", USCARD, AUTOFINANCE_BODY);
            Response unknown = api.post("ghost", USCARD, AUTOFINANCE_BODY);

            // Identical apart from "instance", which only echoes the path the caller sent.
            assertThat(unknown.status()).isEqualTo(403);
            ObjectNode unknownBody = ((ObjectNode) unknown.body()).without("instance");
            ObjectNode knownBody = ((ObjectNode) known.body()).without("instance");
            assertThat(unknownBody).isEqualTo(knownBody);
        }

        @Test
        void callersOwnTenantWithoutACalculatorIs404() {
            Response response = api.post("ghost", Tokens.forTenant("ghost"), USCARD_BODY);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:tenant-not-found");
        }

        @Test
        void oneTenantCannotReadAnothersCalculation() {
            String usCardId = api.post("uscard", USCARD, USCARD_BODY).body().path("calculationId").asText();

            Response response = api.get("autofinance", AUTOFINANCE, usCardId);

            assertThat(response.status()).isEqualTo(404);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:calculation-not-found");
        }

        @Test
        void usCardBodyIsInvalidOnAutoFinanceEndpoint() {
            Response response = api.post("autofinance", AUTOFINANCE, USCARD_BODY);

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:invalid-input");
            assertThat(response.body().path("detail").asText()).doesNotContain("com.capitalone");
        }
    }

    @Nested
    class Errors {

        @Test
        void malformedJsonIs400() {
            Response response = api.post("uscard", USCARD, "{ not json");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:invalid-input");
        }

        @Test
        void missingEnvelopeIs400() {
            assertThat(api.post("uscard", USCARD, "{}").problemType()).isEqualTo("urn:calc:problem:invalid-input");
            assertThat(api.post("uscard", USCARD, "{\"input\":null}").problemType())
                    .isEqualTo("urn:calc:problem:invalid-input");
        }

        @Test
        void moneyAsJsonNumberIs400() {
            Response response = api.post("uscard", USCARD, """
                    { "input": { "monthlyNetIncome": 5200.00, "monthlyHousingCost": "1500.00", "monthlyDebtPayments": "650.00" } }""");

            assertThat(response.status()).isEqualTo(400);
            assertThat(response.body().path("detail").asText()).isEqualTo("Invalid value for field 'input.monthlyNetIncome'");
        }

        @Test
        void tenantRuleRejectionIs422() {
            Response response = api.post("autofinance", AUTOFINANCE, """
                    { "input": { "annualGrossIncome": "84000.00", "monthlyObligations": "2100.00", "dependents": -1 } }""");

            assertThat(response.status()).isEqualTo(422);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:calculation-rejected");
            assertThat(response.body().path("detail").asText()).isEqualTo("dependents must not be negative");
        }

        @Test
        void unknownOrMalformedCalculationIdIs404() {
            assertThat(api.get("uscard", USCARD, UUID.randomUUID().toString()).status()).isEqualTo(404);
            assertThat(api.get("uscard", USCARD, "not-a-uuid").problemType())
                    .isEqualTo("urn:calc:problem:calculation-not-found");
        }
    }

    @Nested
    class Idempotency {

        @Test
        void retryWithSameKeyAndBodyReturnsStoredResult() {
            String key = UUID.randomUUID().toString();
            Response first = api.post("uscard", USCARD, USCARD_BODY, Map.of("Idempotency-Key", key));
            Response retry = api.post("uscard", USCARD, USCARD_BODY, Map.of("Idempotency-Key", key));

            assertThat(retry.status()).isEqualTo(200);
            assertThat(retry.body()).isEqualTo(first.body());
        }

        @Test
        void sameKeyWithDifferentBodyIs409() {
            String key = UUID.randomUUID().toString();
            api.post("uscard", USCARD, USCARD_BODY, Map.of("Idempotency-Key", key));

            Response response = api.post("uscard", USCARD, USCARD_BODY.replace("650.00", "700.00"),
                    Map.of("Idempotency-Key", key));

            assertThat(response.status()).isEqualTo(409);
            assertThat(response.problemType()).isEqualTo("urn:calc:problem:idempotency-conflict");
        }

        @Test
        void keysAreScopedPerTenant() {
            String key = UUID.randomUUID().toString();
            Response usCard = api.post("uscard", USCARD, USCARD_BODY, Map.of("Idempotency-Key", key));
            Response autoFinance = api.post("autofinance", AUTOFINANCE, AUTOFINANCE_BODY, Map.of("Idempotency-Key", key));

            assertThat(autoFinance.status()).isEqualTo(200);
            assertThat(autoFinance.body().path("calculationId")).isNotEqualTo(usCard.body().path("calculationId"));
        }
    }

    @Nested
    class RequestId {

        @Test
        void suppliedIdIsEchoed() {
            Response response = api.post("uscard", USCARD, USCARD_BODY, Map.of("X-Request-Id", "abc-123"));
            assertThat(response.header("X-Request-Id")).isEqualTo("abc-123");
        }

        @Test
        void idIsGeneratedWhenAbsentOrUnsafe() {
            assertThat(api.post("uscard", USCARD, USCARD_BODY).header("X-Request-Id")).isNotBlank();
            assertThat(api.post("uscard", null, USCARD_BODY).header("X-Request-Id")).isNotBlank();
            assertThat(api.post("uscard", USCARD, USCARD_BODY, Map.of("X-Request-Id", "bad id\tinjected"))
                    .header("X-Request-Id")).isNotEqualTo("bad id\tinjected");
        }
    }
}
