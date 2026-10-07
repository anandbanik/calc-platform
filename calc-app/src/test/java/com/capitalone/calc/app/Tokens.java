package com.capitalone.calc.app;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

/** Mints HS256 tokens the way the identity provider would. */
final class Tokens {
    /** Matches the dev default in application.yml. */
    static final String SECRET = "dev-only-secret-do-not-use-in-production-0123456789";

    private Tokens() {}

    static String forTenant(String tenant) {
        return sign(SECRET, tenant, Instant.now().plus(Duration.ofMinutes(5)));
    }

    static String expired(String tenant) {
        return sign(SECRET, tenant, Instant.now().minus(Duration.ofMinutes(5)));
    }

    static String signedWithWrongKey(String tenant) {
        return sign("some-other-secret-that-is-at-least-32-bytes", tenant, Instant.now().plus(Duration.ofMinutes(5)));
    }

    private static String sign(String secret, String tenant, Instant expiresAt) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                    .subject("client-" + tenant)
                    .claim("tenant_id", tenant)
                    .issueTime(Date.from(expiresAt.minus(Duration.ofMinutes(10))))
                    .expirationTime(Date.from(expiresAt))
                    .build());
            jwt.sign(new MACSigner(secret.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }
}
