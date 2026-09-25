package br.com.walletcore.adapter.in.rest;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebMvcConfig implements WebMvcConfigurer {

    private final TenantLoggingInterceptor tenantLoggingInterceptor;

    WebMvcConfig(TenantLoggingInterceptor tenantLoggingInterceptor) {
        this.tenantLoggingInterceptor = tenantLoggingInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantLoggingInterceptor);
    }
}
