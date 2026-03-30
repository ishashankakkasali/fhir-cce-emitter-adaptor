package org.openphc.cce.emitter.config;

import ca.uhn.fhir.context.FhirContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * FHIR configuration — provides the shared {@link FhirContext} singleton bean.
 * <p>
 * {@code FhirContext} is expensive to create (classpath scanning for FHIR structures)
 * and thread-safe. It must be initialized once and shared across all components.
 */
@Configuration
public class FhirConfig {

    /**
     * Singleton FHIR R4 context bean.
     * <p>
     * Eagerly initialized at startup (Spring default for singleton beans).
     * Used by all components that parse or serialize FHIR resources.
     */
    @Bean
    public FhirContext fhirContext() {
        return FhirContext.forR4();
    }
}
