package com.docstruct.ratelimit;

import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Charges the caller's budget before an LLM-backed handler runs. Registered against
 * the upload and query paths in {@code WebConfig}; the method check is here because
 * path patterns can't distinguish {@code POST /api/collections} (an upload, which
 * costs an extraction) from {@code GET /api/collections} (a list, which costs nothing).
 *
 * Rejecting from {@code preHandle} lets the exception reach {@code GlobalExceptionHandler},
 * so a 429 carries the same error body as every other failure.
 */
@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    private final RateLimiter rateLimiter;

    public RateLimitInterceptor(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (HttpMethod.POST.matches(request.getMethod())) {
            rateLimiter.consumeOrReject(clientId(request));
        }
        return true;
    }

    /**
     * The remote address, taken as the servlet container reports it. Proxy headers are
     * not read here on purpose: any client can forge {@code X-Forwarded-For}, so trusting
     * it would hand out an unlimited budget to anyone who noticed. Behind a real proxy,
     * set {@code server.forward-headers-strategy: framework} — then Spring resolves the
     * forwarded address for the whole app and this reads the client rather than the hop.
     */
    private static String clientId(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        return remoteAddress != null ? remoteAddress : "unknown";
    }
}
