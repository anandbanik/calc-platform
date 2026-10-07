package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/** Writes 401 as application/problem+json, like every other error. */
@Component
class ProblemAuthenticationEntryPoint implements AuthenticationEntryPoint {
    private final ObjectMapper mapper;

    ProblemAuthenticationEntryPoint(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        Problems.write(response, mapper,
                Problems.of(HttpStatus.UNAUTHORIZED, Problems.UNAUTHENTICATED, "Missing or invalid bearer token"));
    }
}
