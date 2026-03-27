package org.openphc.cce.emitter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe configuration properties for the FHIR CCE Emitter Adaptor.
 * Bound from {@code emitter.*} in application.yml.
 */
@Data
@ConfigurationProperties(prefix = "emitter")
public class EmitterProperties {

    /** Callback URL base — must be reachable by the FHIR server. */
    @NotBlank
    private String selfBaseUrl;

    /** FHIR R4 server configuration. */
    @Valid
    @NotNull
    private FhirServerConfig fhirServer = new FhirServerConfig();

    /** OpenHIM connection configuration. */
    @Valid
    @NotNull
    private OpenhimConfig openhim = new OpenhimConfig();

    /** Startup auto-subscription configuration. */
    @Valid
    private StartupSubscriptionConfig startupSubscriptions = new StartupSubscriptionConfig();

    // ── Inner config classes ────────────────────────────────────────────

    @Data
    public static class FhirServerConfig {
        @NotBlank
        private String name = "default-fhir";

        @NotBlank
        private String url = "http://localhost:8090/fhir";

        @Valid
        private FhirServerAuthConfig auth = new FhirServerAuthConfig();
    }

    @Data
    public static class FhirServerAuthConfig {
        /** Auth type: none | basic | bearer | token-endpoint | oauth2 */
        private String type = "none";

        /** Username for basic / token-endpoint auth. */
        private String username;

        /** Password for basic / token-endpoint auth. */
        private String password;

        /** Static Bearer token for bearer auth. */
        private String token;

        /** Token endpoint URL for token-endpoint / oauth2 auth. */
        private String tokenUrl;

        /** Client type header sent to token endpoint. */
        private String client = "web";

        /** Cookie name to extract JWT from Set-Cookie header. */
        private String tokenCookieName = "AuthCookie";

        /** Whether the cookie value is base64-encoded. */
        private boolean tokenCookieBase64 = true;

        /** JSON field name for token in response body. */
        private String tokenBodyField;

        /** OAuth2 client ID (for oauth2 auth type). */
        private String clientId;

        /** OAuth2 client secret (for oauth2 auth type). */
        private String clientSecret;

        /** OAuth2 scope, space-separated (for oauth2 auth type). */
        private String scope;

        /** Token cache TTL in seconds. Overridden by expires_in for oauth2. */
        private long tokenTtlSeconds = 3600;
    }

    @Data
    public static class OpenhimConfig {
        @NotBlank
        private String name = "openhim";

        @NotBlank
        private String baseUrl = "http://localhost:5001/fhir";

        @Valid
        private OpenhimAuthConfig auth = new OpenhimAuthConfig();

        /** Trust all SSL certificates for OpenHIM connections. */
        private boolean sslTrustAll = false;

        /** Append FHIR resource type to OpenHIM URL. */
        private boolean appendResourceType = true;

        @Valid
        private RetryConfig retry = new RetryConfig();
    }

    @Data
    public static class OpenhimAuthConfig {
        /** Auth type: none | basic | jwt | custom-token */
        private String type = "basic";

        /** Username for basic auth. */
        private String username;

        /** Password for basic auth. */
        private String password;

        /** Token string for jwt / custom-token auth. */
        private String token;
    }

    @Data
    public static class RetryConfig {
        /** Total forward attempts (including first try). */
        private int maxAttempts = 3;

        /** Base backoff in milliseconds (linear: backoffMs × attemptNumber). */
        private long backoffMs = 2000;
    }

    @Data
    public static class StartupSubscriptionConfig {
        /** Enable automatic subscription on startup. */
        private boolean enabled = false;

        /** Delay in seconds before subscribing (allows FHIR server to become ready). */
        private int delaySeconds = 10;

        /** FHIR R4 resource types to subscribe to on startup. */
        private List<String> resourceTypes = List.of(
                "Patient", "RelatedPerson", "Encounter", "Observation",
                "Condition", "MedicationRequest", "MedicationDispense",
                "MedicationStatement", "DiagnosticReport", "QuestionnaireResponse",
                "ServiceRequest", "CarePlan", "Appointment", "Group",
                "Location", "Organization", "Practitioner", "Coverage",
                "PaymentNotice", "Device", "Provenance"
        );
    }
}
