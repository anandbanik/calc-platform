package com.capitalone.calc.app;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Caps the request body before anything parses it, so no tenant can make the shared heap
 * another tenant's problem. A declared Content-Length is refused outright; a chunked body
 * with no declared length is cut off mid-read, which is the only way to bound it.
 *
 * <p>Runs ahead of security, so an oversized body is refused without being authenticated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class PayloadLimitFilter extends OncePerRequestFilter {
    private final long maxBytes;
    private final ObjectMapper mapper;

    PayloadLimitFilter(@Value("${platform.max-request-bytes}") DataSize maxRequestSize, ObjectMapper mapper) {
        this.maxBytes = maxRequestSize.toBytes();
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBytes) {
            Problems.write(response, mapper, Problems.of(HttpStatus.PAYLOAD_TOO_LARGE, Problems.PAYLOAD_TOO_LARGE,
                    new PayloadTooLargeException(maxBytes).getMessage()));
            return;
        }
        chain.doFilter(new CappedBodyRequest(request, maxBytes), response);
    }

    /** Wraps the body so a chunked request cannot outgrow the cap either. */
    private static final class CappedBodyRequest extends HttpServletRequestWrapper {
        private final long maxBytes;

        CappedBodyRequest(HttpServletRequest request, long maxBytes) {
            super(request);
            this.maxBytes = maxBytes;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            return new CappedServletInputStream(super.getInputStream(), maxBytes);
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    /**
     * Throws PayloadTooLargeException, not an IOException: it is read from inside the
     * DispatcherServlet, so the unchecked exception reaches ApiExceptionHandler and becomes
     * a 413 problem document rather than a 500.
     */
    private static final class CappedServletInputStream extends ServletInputStream {
        private final ServletInputStream delegate;
        private final long maxBytes;
        private long consumed;

        CappedServletInputStream(ServletInputStream delegate, long maxBytes) {
            this.delegate = delegate;
            this.maxBytes = maxBytes;
        }

        @Override
        public int read() throws IOException {
            int b = delegate.read();
            if (b != -1) {
                count(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = delegate.read(buffer, offset, length);
            if (n > 0) {
                count(n);
            }
            return n;
        }

        private void count(int n) {
            consumed += n;
            if (consumed > maxBytes) {
                throw new PayloadTooLargeException(maxBytes);
            }
        }

        @Override
        public boolean isFinished() {
            return delegate.isFinished();
        }

        @Override
        public boolean isReady() {
            return delegate.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            delegate.setReadListener(listener);
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }
}
