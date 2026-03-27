package org.openphc.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link FhirConfig} — verifies FhirContext bean is R4 and singleton.
 */
@SpringBootTest
class FhirConfigTest {

    @Autowired
    private FhirContext fhirContext;

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void fhirContextBeanIsR4() {
        assertEquals(FhirVersionEnum.R4, fhirContext.getVersion().getVersion(),
                "FhirContext should be configured for FHIR R4");
    }

    @Test
    void fhirContextBeanIsSingleton() {
        FhirContext first = applicationContext.getBean(FhirContext.class);
        FhirContext second = applicationContext.getBean(FhirContext.class);
        assertSame(first, second, "FhirContext bean should be a singleton");
    }

    @Test
    void startupSubscriptionRunnerNotCreatedWhenDisabled() {
        // Default config has startup-subscriptions.enabled=false
        assertFalse(applicationContext.containsBean("startupSubscriptionRunner"),
                "StartupSubscriptionRunner should NOT be created when startup-subscriptions.enabled=false");
    }
}
