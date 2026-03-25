package org.openphc.cce.emitter.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Type-safe configuration properties for the FHIR CCE Emitter Adaptor.
 * Binds to the {@code emitter.*} namespace in application YAML.
 *
 * <p>This is a stub for F1 scaffolding — inner classes will be fully
 * populated in F2 (Config Properties & FHIR Context).</p>
 */
@Data
@ConfigurationProperties(prefix = "emitter")
public class EmitterProperties {

    @NotBlank
    private String selfBaseUrl;

    @Valid
    @NotNull
    private FhirServerConfig fhirServer;

    @Valid
    @NotNull
    private TargetConfig target;

    @Valid
    private StartupSubscriptionConfig startupSubscriptions = new StartupSubscriptionConfig();

    // --- Inner config classes ---

    @Data
    public static class FhirServerConfig {
        @NotBlank
        private String name;
        @NotBlank
        private String url;
        @Valid
        private FhirServerAuthConfig auth = new FhirServerAuthConfig();
    }

    @Data
    public static class FhirServerAuthConfig {
        private String type = "none";
        private String username;
        private String password;
        private String token;
        private String tokenUrl;
        private String client = "web";
        private String tokenCookieName = "AuthCookie";
        private boolean tokenCookieBase64 = true;
        private String tokenBodyField;
        // OAuth2 Client Credentials grant fields
        private String clientId;
        private String clientSecret;
        private String scope;
        private long tokenTtlSeconds = 3600;
    }

    @Data
    public static class TargetConfig {
        @NotBlank
        private String name;
        @NotBlank
        private String baseUrl;
        @Valid
        private TargetAuthConfig auth = new TargetAuthConfig();
        private boolean sslTrustAll = false;
        private boolean appendResourceType = true;
        @Valid
        private RetryConfig retry = new RetryConfig();
    }

    @Data
    public static class TargetAuthConfig {
        private String type = "basic";    // none | basic | jwt | custom-token
        private String username;
        private String password;
        private String token;              // JWT or Custom Token string
    }

    @Data
    public static class RetryConfig {
        private int maxAttempts = 3;
        private long backoffMs = 2000;
    }

    @Data
    public static class StartupSubscriptionConfig {
        private boolean enabled = false;
        private int delaySeconds = 10;
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
