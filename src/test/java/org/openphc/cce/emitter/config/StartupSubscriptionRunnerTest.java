package org.openphc.cce.emitter.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.service.RegistrationResult;
import org.openphc.cce.emitter.service.SubscriptionRegistrationService;
import org.springframework.boot.ApplicationArguments;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link StartupSubscriptionRunner}.
 * <p>
 * Verifies startup auto-subscription behavior: parses entries, delegates to
 * {@code subscribeAll()}, and handles empty config gracefully.
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
    @SuppressWarnings("unchecked")
    void subscribesToAllConfiguredResourceTypes() {
        when(registrationService.subscribeAll(anyList()))
                .thenReturn(List.of(
                        new RegistrationResult("Patient", "test-fhir", "sub-1", "registered"),
                        new RegistrationResult("Observation", "test-fhir", "sub-2", "registered"),
                        new RegistrationResult("Encounter", "test-fhir", "sub-3", "registered")));

        runner.run(args);

        ArgumentCaptor<List<String[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(registrationService).subscribeAll(captor.capture());

        List<String[]> entries = captor.getValue();
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0)).containsExactly("Patient", "");
        assertThat(entries.get(1)).containsExactly("Observation", "");
        assertThat(entries.get(2)).containsExactly("Encounter", "");
    }

    @Test
    void emptyResourceTypesSkipsSubscription() {
        emitterProperties.getStartupSubscriptions().setResourceTypes(List.of());

        runner.run(args);

        verify(registrationService, never()).subscribeAll(anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesResourceTypeWithCriteriaFilter() {
        emitterProperties.getStartupSubscriptions().setResourceTypes(
                List.of("Patient", "Observation?code=1234", "Encounter?status=finished"));

        when(registrationService.subscribeAll(anyList()))
                .thenReturn(List.of(
                        new RegistrationResult("Patient", "test-fhir", "sub-1", "registered"),
                        new RegistrationResult("Observation", "test-fhir", "sub-2", "registered"),
                        new RegistrationResult("Encounter", "test-fhir", "sub-3", "registered")));

        runner.run(args);

        ArgumentCaptor<List<String[]>> captor = ArgumentCaptor.forClass(List.class);
        verify(registrationService).subscribeAll(captor.capture());

        List<String[]> entries = captor.getValue();
        assertThat(entries.get(0)).containsExactly("Patient", "");
        assertThat(entries.get(1)).containsExactly("Observation", "code=1234");
        assertThat(entries.get(2)).containsExactly("Encounter", "status=finished");
    }

    @Test
    void parseCriteria_plainResourceType() {
        String[] result = runner.parseCriteria("Patient");
        assertThat(result).containsExactly("Patient", "");
    }

    @Test
    void parseCriteria_withSingleFilter() {
        String[] result = runner.parseCriteria("Observation?code=1234");
        assertThat(result).containsExactly("Observation", "code=1234");
    }

    @Test
    void parseCriteria_withMultipleFilters() {
        String[] result = runner.parseCriteria("Encounter?status=finished&class=AMB");
        assertThat(result).containsExactly("Encounter", "status=finished&class=AMB");
    }

    @Test
    void parseCriteria_trimsWhitespace() {
        String[] result = runner.parseCriteria("  Patient ? code=1234 ");
        assertThat(result).containsExactly("Patient", "code=1234");
    }
}
