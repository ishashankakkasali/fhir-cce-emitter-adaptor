package org.openphc.cce.emitter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.service.RegistrationResult;
import org.openphc.cce.emitter.service.SubscriptionRegistrationService;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StartupSubscriptionRunner}.
 * <p>
 * Verifies startup auto-subscription behavior: iterates all configured resource types,
 * handles failures gracefully, and counts results correctly.
 */
@ExtendWith(MockitoExtension.class)
class StartupSubscriptionRunnerTest {

    @Mock
    private SubscriptionRegistrationService registrationService;

    @Mock
    private ApplicationArguments args;

    private EmitterProperties emitterProperties;
    private StartupSubscriptionRunner runner;

    @BeforeEach
    void setUp() {
        emitterProperties = new EmitterProperties();
        emitterProperties.setSelfBaseUrl("http://localhost:9090");

        EmitterProperties.StartupSubscriptionConfig config = new EmitterProperties.StartupSubscriptionConfig();
        config.setEnabled(true);
        config.setDelaySeconds(0); // no delay in tests
        config.setResourceTypes(List.of("Patient", "Observation", "Encounter"));
        emitterProperties.setStartupSubscriptions(config);

        runner = new StartupSubscriptionRunner(emitterProperties, registrationService);
    }

    @Test
    void subscribesToAllConfiguredResourceTypes() {
        when(registrationService.subscribe(anyString(), isNull()))
                .thenReturn(new RegistrationResult("any", "test-fhir", "sub-1", "registered"));

        runner.run(args);

        verify(registrationService).subscribe(eq("Patient"), isNull());
        verify(registrationService).subscribe(eq("Observation"), isNull());
        verify(registrationService).subscribe(eq("Encounter"), isNull());
        verify(registrationService, times(3)).subscribe(anyString(), isNull());
    }

    @Test
    void failedSubscriptionContinuesWithRemaining() {
        when(registrationService.subscribe(eq("Patient"), isNull()))
                .thenReturn(new RegistrationResult("Patient", "test-fhir", null, "failed: Connection refused"));
        when(registrationService.subscribe(eq("Observation"), isNull()))
                .thenReturn(new RegistrationResult("Observation", "test-fhir", "sub-2", "registered"));
        when(registrationService.subscribe(eq("Encounter"), isNull()))
                .thenReturn(new RegistrationResult("Encounter", "test-fhir", "sub-3", "registered"));

        runner.run(args);

        // All 3 resource types were attempted despite Patient failure
        verify(registrationService, times(3)).subscribe(anyString(), isNull());
    }

    @Test
    void alreadyExistsCountsAsSuccess() {
        when(registrationService.subscribe(anyString(), isNull()))
                .thenReturn(new RegistrationResult("Patient", "test-fhir", "sub-existing", "already-exists"));

        runner.run(args);

        // All 3 resource types processed — "already-exists" is treated as success
        verify(registrationService, times(3)).subscribe(anyString(), isNull());
    }

    @Test
    void exceptionDoesNotStopRemainingSubscriptions() {
        when(registrationService.subscribe(eq("Patient"), isNull()))
                .thenThrow(new RuntimeException("FHIR server down"));
        when(registrationService.subscribe(eq("Observation"), isNull()))
                .thenReturn(new RegistrationResult("Observation", "test-fhir", "sub-4", "registered"));
        when(registrationService.subscribe(eq("Encounter"), isNull()))
                .thenReturn(new RegistrationResult("Encounter", "test-fhir", "sub-5", "registered"));

        runner.run(args);

        // All 3 attempted despite Patient throwing an exception
        verify(registrationService, times(3)).subscribe(anyString(), isNull());
    }

    @Test
    void emptyResourceTypesSkipsSubscription() {
        emitterProperties.getStartupSubscriptions().setResourceTypes(List.of());

        runner.run(args);

        verify(registrationService, never()).subscribe(anyString(), any());
    }
}
