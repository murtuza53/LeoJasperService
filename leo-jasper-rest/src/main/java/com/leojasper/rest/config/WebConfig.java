package com.leojasper.rest.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Open CORS — any origin, any method. Allows the React dev server (and any
 * other browser client) to call the API directly.
 *
 * <p>{@code allowedOriginPatterns("*")} (rather than {@code allowedOrigins("*")})
 * lets us flip {@code allowCredentials} on later if a session/cookie is added,
 * without breaking the wildcard.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS", "HEAD", "PATCH")
                .allowedHeaders("*")
                .exposedHeaders("Content-Disposition", "Location", "X-Job-Id")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
