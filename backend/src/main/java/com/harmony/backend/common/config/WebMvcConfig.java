package com.harmony.backend.common.config;

import com.harmony.backend.common.resolver.CurrentUserArgumentResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUserArgumentResolver;
    private final AppCorsProperties corsProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        var reg = registry.addMapping("/**");
        if (!corsProperties.getAllowedOrigins().isEmpty()) {
            reg.allowedOrigins(corsProperties.getAllowedOrigins().toArray(new String[0]));
        }
        if (!corsProperties.getAllowedOriginPatterns().isEmpty()) {
            reg.allowedOriginPatterns(corsProperties.getAllowedOriginPatterns().toArray(new String[0]));
        }
        reg.allowedMethods(corsProperties.getAllowedMethods().toArray(new String[0]));
        reg.allowedHeaders(corsProperties.getAllowedHeaders().toArray(new String[0]));
        reg.exposedHeaders(corsProperties.getExposedHeaders().toArray(new String[0]));
        reg.allowCredentials(corsProperties.isAllowCredentials());
        reg.maxAge(corsProperties.getMaxAge());
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUserArgumentResolver);
    }
}
