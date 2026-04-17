package org.openphc.cce.emitter.config;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link EmitterProperties} — config binding, defaults, and validation.
 */
class EmitterPropertiesTest {

    @Test
    void defaultStartupSubscriptionConfigHas21ResourceTypes() {
        EmitterProperties.StartupSubscriptionConfig config = new EmitterProperties.StartupSubscriptionConfig();
        assertNotNull(config.getResourceTypes());
        assertEquals(21, config.getResourceTypes().size());
        assertTrue(config.getResourceTypes().contains("Patient"));
        assertTrue(config.getResourceTypes().contains("Observation"));
        assertTrue(config.getResourceTypes().contains("Provenance"));
    }

    @Test
    void defaultStartupSubscriptionsDisabled() {
        EmitterProperties.StartupSubscriptionConfig config = new EmitterProperties.StartupSubscriptionConfig();
        assertFalse(config.isEnabled());
        assertEquals(10, config.getDelaySeconds());
    }

    @Test
    void defaultFhirServerAuthConfig() {
        EmitterProperties.FhirServerAuthConfig config = new EmitterProperties.FhirServerAuthConfig();
        assertEquals("none", config.getType());
        assertEquals("web", config.getClient());
        assertEquals("AuthCookie", config.getTokenCookieName());
        assertTrue(config.isTokenCookieBase64());
        assertEquals(3600, config.getTokenTtlSeconds());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertNull(config.getToken());
        assertNull(config.getTokenUrl());
        assertNull(config.getTokenBodyField());
    }

    @Test
    void defaultOpenhimAuthTypeIsBasic() {
        EmitterProperties.OpenhimAuthConfig config = new EmitterProperties.OpenhimAuthConfig();
        assertEquals("basic", config.getType());
        assertNull(config.getUsername());
        assertNull(config.getPassword());
        assertNull(config.getToken());
    }

    @Test
    void selfBaseUrlBlankFailsValidation() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            Validator validator = factory.getValidator();
            EmitterProperties props = new EmitterProperties();
            // selfBaseUrl is null by default — @NotBlank should trigger
            Set<ConstraintViolation<EmitterProperties>> violations = validator.validate(props);
            assertTrue(violations.stream().anyMatch(
                    v -> v.getPropertyPath().toString().equals("selfBaseUrl")),
                    "Expected @NotBlank violation on selfBaseUrl");
        }
    }

    @Test
    void defaultOpenhimConfig() {
        EmitterProperties.OpenhimConfig config = new EmitterProperties.OpenhimConfig();
        assertEquals("openhim", config.getName());
        assertEquals("http://localhost:5001/fhir", config.getBaseUrl());
        assertFalse(config.isSslTrustAll());
        assertTrue(config.isAppendResourceType());
        assertNotNull(config.getAuth());
    }

    @Test
    void defaultFhirServerConfig() {
        EmitterProperties.FhirServerConfig config = new EmitterProperties.FhirServerConfig();
        assertEquals("default-fhir", config.getName());
        assertEquals("http://localhost:8090/fhir", config.getUrl());
        assertNotNull(config.getAuth());
    }
}
