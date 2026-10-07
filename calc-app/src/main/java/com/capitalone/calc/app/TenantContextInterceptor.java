package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantContext;
import com.capitalone.calc.spi.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/** The only code that reads {tenantId} from the URL. Everything after it receives a TenantContext. */
@Component
class TenantContextInterceptor implements HandlerInterceptor {
    static final String ATTRIBUTE = TenantContext.class.getName();
    private static final String TIMER_SAMPLE = TenantContextInterceptor.class.getName() + ".timer";

    private final TenantSettingsProvider settings;
    private final MeterRegistry meters;

    TenantContextInterceptor(TenantSettingsProvider settings, MeterRegistry meters) {
        this.settings = settings;
        this.meters = meters;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        @SuppressWarnings("unchecked")
        Map<String, String> pathVariables = (Map<String, String>)
                request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        String pathTenant = pathVariables == null ? null : pathVariables.get("tenantId");
        String tokenTenant = SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken jwt
                ? jwt.getToken().getClaimAsString("tenant_id")
                : null;

        if (pathTenant == null || !pathTenant.equals(tokenTenant)) {
            throw new TenantMismatchException();   // 403, whether or not the tenant exists
        }
        TenantId tenantId;
        try {
            tenantId = new TenantId(pathTenant);
        } catch (IllegalArgumentException e) {
            throw new TenantMismatchException();
        }
        String requestId = (String) request.getAttribute(RequestIdFilter.ATTRIBUTE);

        request.setAttribute(ATTRIBUTE, new TenantContext(tenantId, requestId, settings.forTenant(tenantId)));
        request.setAttribute(TIMER_SAMPLE, Timer.start(meters));
        MDC.put("tenant", tenantId.value());
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        if (request.getAttribute(TIMER_SAMPLE) instanceof Timer.Sample sample
                && request.getAttribute(ATTRIBUTE) instanceof TenantContext ctx) {
            sample.stop(Timer.builder("ndi.requests")
                    .tag("tenant", ctx.tenantId().value())
                    .tag("method", request.getMethod())
                    .tag("status", Integer.toString(response.getStatus()))
                    .register(meters));
        }
        MDC.remove("tenant");
    }
}
