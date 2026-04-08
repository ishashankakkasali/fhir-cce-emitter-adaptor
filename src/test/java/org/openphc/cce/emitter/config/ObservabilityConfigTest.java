package org.openphc.cce.emitter.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ObservabilityConfig")
class ObservabilityConfigTest {

    private final ObservabilityConfig config = new ObservabilityConfig();

    @Test
    @DisplayName("emitterMetricsCustomizer adds service common tag")
    void emitterMetricsCustomizer_addsServiceTag() {
        MeterRegistryCustomizer<MeterRegistry> customizer = config.emitterMetricsCustomizer();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        customizer.customize(registry);

        // Verify by registering a counter and checking it has the service tag
        registry.counter("test.counter").increment();
        assertThat(registry.get("test.counter").counter().getId().getTag("service"))
                .isEqualTo("fhir-cce-emitter-adaptor");
    }
}
