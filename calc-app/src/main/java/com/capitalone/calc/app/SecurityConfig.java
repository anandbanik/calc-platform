package com.capitalone.calc.app;

import java.nio.charset.StandardCharsets;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API call needs a valid bearer token; an invalid one gets 401 before any tenant logic runs.
 * The prototype verifies HS256 tokens with a shared secret. Production would point
 * spring.security.oauth2.resourceserver.jwt.issuer-uri at the real identity provider instead.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, ProblemAuthenticationEntryPoint entryPoint) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/error").permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(o -> o
                        .jwt(Customizer.withDefaults())
                        .authenticationEntryPoint(entryPoint))
                .exceptionHandling(e -> e.authenticationEntryPoint(entryPoint))
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${calc.security.jwt-secret}") String secret) {
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("calc.security.jwt-secret must be at least 32 bytes");
        }
        return NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }
}
