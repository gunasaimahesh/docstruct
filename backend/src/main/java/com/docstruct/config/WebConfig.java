package com.docstruct.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.docstruct.ratelimit.RateLimitInterceptor;

/**
 * CORS configuration so the Next.js frontend can also call the API
 * directly (in addition to going through the dev-server proxy), plus
 * registration of the rate limiter on the endpoints that spend LLM calls.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Value("${docstruct.cors.allowed-origins}")
    private String[] allowedOrigins;

    private final RateLimitInterceptor rateLimitInterceptor;

    public WebConfig(RateLimitInterceptor rateLimitInterceptor) {
        this.rateLimitInterceptor = rateLimitInterceptor;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PATCH", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                // Response headers are hidden from cross-origin JavaScript unless named
                // here, and a Retry-After the browser can't read is a Retry-After the UI
                // can't show.
                .exposedHeaders(HttpHeaders.RETRY_AFTER);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Only the three paths whose POST handler can reach the LLM: creating a
        // collection, adding a document to one, and querying one.
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns(
                        "/api/collections",
                        "/api/collections/*/documents",
                        "/api/collections/*/query");
    }
}
