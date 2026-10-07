package com.capitalone.calc.app;

import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
class WebConfig implements WebMvcConfigurer {
    private final TenantContextInterceptor interceptor;
    private final TenantContextArgumentResolver resolver;

    WebConfig(TenantContextInterceptor interceptor, TenantContextArgumentResolver resolver) {
        this.interceptor = interceptor;
        this.resolver = resolver;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/v1/tenants/**");
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(resolver);
    }
}
