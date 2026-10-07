package com.capitalone.calc.app;

import com.capitalone.calc.spi.TenantContext;
import org.springframework.core.MethodParameter;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/** Hands controllers the TenantContext the interceptor built, so none of them reads the path tenant. */
@Component
class TenantContextArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == TenantContext.class;
    }

    @Override
    public TenantContext resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                         NativeWebRequest request, WebDataBinderFactory binderFactory) {
        Object ctx = request.getAttribute(TenantContextInterceptor.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST);
        if (ctx == null) {
            throw new IllegalStateException("No TenantContext: route is not covered by TenantContextInterceptor");
        }
        return (TenantContext) ctx;
    }
}
