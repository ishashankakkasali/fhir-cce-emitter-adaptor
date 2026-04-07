package org.openphc.cce.emitter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Servlet filter that populates SLF4J MDC with request context for structured logging.
 * <p>
 * Sets the following MDC fields on every request:
 * <ul>
 *   <li>{@code requestId} — from {@code X-Request-ID} header, or a generated UUID</li>
 *   <li>{@code callbackResourceType} — extracted from {@code /callback/{resourceType}/...} path</li>
 * </ul>
 * All MDC fields are cleared in a {@code finally} block to prevent ThreadLocal leaks.
 */
@Component
public class LoggingFilter extends OncePerRequestFilter {

    static final String MDC_REQUEST_ID = "requestId";
    static final String MDC_CALLBACK_RESOURCE_TYPE = "callbackResourceType";

    private static final String CALLBACK_PATH_PREFIX = "/callback/";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        try {
            // Set requestId from header or generate UUID
            String requestId = request.getHeader("X-Request-ID");
            if (requestId == null || requestId.isBlank()) {
                requestId = UUID.randomUUID().toString();
            }
            MDC.put(MDC_REQUEST_ID, requestId);

            // Extract callbackResourceType from /callback/{resourceType}/... path
            String uri = request.getRequestURI();
            String callbackResourceType = extractCallbackResourceType(uri);
            if (callbackResourceType != null) {
                MDC.put(MDC_CALLBACK_RESOURCE_TYPE, callbackResourceType);
            }

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_REQUEST_ID);
            MDC.remove(MDC_CALLBACK_RESOURCE_TYPE);
            MDC.remove("resourceType");
            MDC.remove("resourceId");
        }
    }

    /**
     * Extracts the resource type from a URI matching {@code /callback/{resourceType}/...}.
     *
     * @param uri the request URI
     * @return the callback resource type, or null if the URI doesn't match the callback pattern
     */
    String extractCallbackResourceType(String uri) {
        if (uri == null || !uri.startsWith(CALLBACK_PATH_PREFIX)) {
            return null;
        }
        String remainder = uri.substring(CALLBACK_PATH_PREFIX.length());
        int slashIndex = remainder.indexOf('/');
        return slashIndex > 0 ? remainder.substring(0, slashIndex) : remainder;
    }
}
