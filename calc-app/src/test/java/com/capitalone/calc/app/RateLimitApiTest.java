package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitalone.calc.app.ApiClient.Response;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** One tenant over its quota gets 429 with Retry-After; the other tenant is unaffected. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tenants.autofinance.limits.requests-per-second=1")
class RateLimitApiTest extends PostgresTestSupport {

    @LocalServerPort
    int port;

    @Test
    void overQuotaTenantIsLimitedAndOtherTenantIsNot() {
        ApiClient api = new ApiClient(port);
        String autoFinance = Tokens.forTenant("autofinance");
        String usCard = Tokens.forTenant("uscard");

        List<Response> burst = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            burst.add(api.post("autofinance", autoFinance, CalculateNDIApiTest.AUTOFINANCE_BODY));
        }

        Response limited = burst.stream().filter(r -> r.status() == 429).findFirst().orElseThrow();
        assertThat(limited.problemType()).isEqualTo("urn:calc:problem:rate-limited");
        assertThat(limited.header("Retry-After")).isEqualTo("1");

        assertThat(api.post("uscard", usCard, CalculateNDIApiTest.USCARD_BODY).status()).isEqualTo(200);
    }
}
