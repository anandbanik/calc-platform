package com.capitalone.calc.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.capitalone.calc.app.ApiClient.Response;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/** An oversized body is refused whether or not the caller declares its length. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "platform.max-request-bytes=2KB")
class PayloadLimitApiTest extends PostgresTestSupport {
    private static final int CAP = 2048;

    @LocalServerPort
    int port;

    @Test
    void aDeclaredOversizeBodyIs413() {
        Response response = new ApiClient(port).post("uscard", Tokens.forTenant("uscard"), oversizeBody());

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.problemType()).isEqualTo("urn:calc:problem:payload-too-large");
    }

    /** Without Content-Length the cap can only be enforced while reading, which must still give 413. */
    @Test
    void aChunkedOversizeBodyIs413() {
        Response response = new ApiClient(port).postChunked("uscard", Tokens.forTenant("uscard"), oversizeBody());

        assertThat(response.status()).isEqualTo(413);
        assertThat(response.problemType()).isEqualTo("urn:calc:problem:payload-too-large");
    }

    @Test
    void anOversizeBodyIsRefusedBeforeAuthentication() {
        Response response = new ApiClient(port).post("uscard", null, oversizeBody());

        assertThat(response.status()).isEqualTo(413);
    }

    @Test
    void aBodyUnderTheCapIsStillAccepted() {
        Response response = new ApiClient(port).post("uscard", Tokens.forTenant("uscard"),
                CalculateNDIApiTest.USCARD_BODY);

        assertThat(response.status()).isEqualTo(200);
    }

    /** Valid JSON, so a 413 can only come from the size cap and not from the parser. */
    private static String oversizeBody() {
        return "{\"input\":{\"padding\":\"" + "x".repeat(CAP * 2) + "\"}}";
    }
}
