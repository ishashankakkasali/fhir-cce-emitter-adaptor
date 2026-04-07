package org.openphc.cce.emitter.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
@DisplayName("LoggingFilter")
class LoggingFilterTest {

    private final LoggingFilter loggingFilter = new LoggingFilter();

    @Test
    @DisplayName("Sets MDC requestId with generated UUID when X-Request-ID header is absent")
    void doFilter_setsGeneratedRequestId_whenNoHeader() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/patient");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedRequestId = new AtomicReference<>();

        FilterChain chain = (req, res) -> capturedRequestId.set(MDC.get("requestId"));

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(capturedRequestId.get()).isNotNull().isNotBlank();
        // UUID format
        assertThat(capturedRequestId.get()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    @DisplayName("Uses X-Request-ID header value when present")
    void doFilter_usesXRequestIdHeader_whenPresent() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/patient");
        request.addHeader("X-Request-ID", "custom-request-id-123");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedRequestId = new AtomicReference<>();

        FilterChain chain = (req, res) -> capturedRequestId.set(MDC.get("requestId"));

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(capturedRequestId.get()).isEqualTo("custom-request-id-123");
    }

    @Test
    @DisplayName("Sets MDC callbackResourceType from callback path")
    void doFilter_setsCallbackResourceType() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/callback/encounter/Encounter/456");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedValue = new AtomicReference<>();

        FilterChain chain = (req, res) -> capturedValue.set(MDC.get("callbackResourceType"));

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(capturedValue.get()).isEqualTo("encounter");
    }

    @Test
    @DisplayName("Clears all MDC fields after request completes")
    void doFilter_clearsMdcAfterRequest() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/patient");
        request.addHeader("X-Request-ID", "test-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("callbackResourceType")).isNull();
        assertThat(MDC.get("resourceType")).isNull();
        assertThat(MDC.get("resourceId")).isNull();
    }

    @Test
    @DisplayName("Clears MDC fields even when filter chain throws exception")
    void doFilter_clearsMdcOnException() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/patient");
        request.addHeader("X-Request-ID", "test-id");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = (req, res) -> { throw new ServletException("test error"); };

        try {
            loggingFilter.doFilterInternal(request, response, chain);
        } catch (ServletException ignored) {
            // expected
        }

        assertThat(MDC.get("requestId")).isNull();
        assertThat(MDC.get("callbackResourceType")).isNull();
    }

    @Test
    @DisplayName("Does not set callbackResourceType for non-callback paths")
    void doFilter_noCallbackResourceType_forNonCallbackPath() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedValue = new AtomicReference<>();

        FilterChain chain = (req, res) -> capturedValue.set(MDC.get("callbackResourceType"));

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(capturedValue.get()).isNull();
    }

    @Test
    @DisplayName("extractCallbackResourceType extracts resource type from callback path")
    void extractCallbackResourceType_validPath() {
        assertThat(loggingFilter.extractCallbackResourceType("/callback/patient/Patient/123")).isEqualTo("patient");
        assertThat(loggingFilter.extractCallbackResourceType("/callback/encounter")).isEqualTo("encounter");
        assertThat(loggingFilter.extractCallbackResourceType("/actuator/health")).isNull();
        assertThat(loggingFilter.extractCallbackResourceType(null)).isNull();
    }

    @Test
    @DisplayName("Generates UUID when X-Request-ID header is blank")
    void doFilter_generatesUuid_whenHeaderBlank() throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/callback/patient");
        request.addHeader("X-Request-ID", "   ");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> capturedRequestId = new AtomicReference<>();

        FilterChain chain = (req, res) -> capturedRequestId.set(MDC.get("requestId"));

        loggingFilter.doFilterInternal(request, response, chain);

        assertThat(capturedRequestId.get()).isNotBlank();
        assertThat(capturedRequestId.get()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }
}
