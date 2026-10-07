package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.capitalone.calc.app.ApiClient.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The guard wraps the whole handler, so a rejected request never reaches the database.
 * Before that change the idempotency lookup ran first and every over-quota request still
 * cost a connection and a query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "tenants.uscard.limits.requests-per-second=1")
class RateLimitOrderingTest extends PostgresTestSupport {

    @LocalServerPort
    int port;

    @MockitoSpyBean
    CalculateNDIRepository repository;

    @Test
    void aRejectedRequestDoesNotTouchTheDatabase() {
        ApiClient api = new ApiClient(port);
        String token = Tokens.forTenant("uscard");

        List<Response> burst = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            burst.add(api.post("uscard", token, CalculateNDIApiTest.USCARD_BODY,
                    Map.of("Idempotency-Key", UUID.randomUUID().toString())));
        }

        long admitted = burst.stream().filter(r -> r.status() != 429).count();
        assertThat(admitted).isLessThan(burst.size());   // the quota did bite
        verify(repository, times((int) admitted)).findByIdempotencyKey(any(), any());
    }
}
