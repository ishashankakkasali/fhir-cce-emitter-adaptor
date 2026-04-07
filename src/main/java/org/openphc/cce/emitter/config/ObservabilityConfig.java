package org.openphc.cce.emitter.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Observability configuration — registers common Micrometer tags and
 * logs metrics availability at startup.
 */
@Configuration
public class ObservabilityConfig {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityConfig.class);

    /**
     * Adds the {@code service} common tag to all metrics emitted by this application.
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> emitterMetricsCustomizer() {
        return registry -> {
            registry.config().commonTags("service", "fhir-cce-emitter-adaptor");
            log.info("Registered common metrics tag: service=fhir-cce-emitter-adaptor");
        };
    }
}
