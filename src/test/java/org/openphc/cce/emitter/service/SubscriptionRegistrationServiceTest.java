package org.openphc.cce.emitter.service;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICreateTyped;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerAuthConfig;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SubscriptionRegistrationService}.
 * Covers subscribeAll flow (bulk fetch + create) and metrics.
 * Auth client creation tests are in {@link FhirClientFactoryTest}.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionRegistrationServiceTest {

    @Mock
    private FhirClientFactory fhirClientFactory;

    @Mock
    private IGenericClient client;

    private MeterRegistry meterRegistry;
    private EmitterProperties properties;
    private SubscriptionRegistrationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        properties = buildDefaultProperties();

        lenient().when(fhirClientFactory.createClient(any(FhirServerConfig.class))).thenReturn(client);

        service = new SubscriptionRegistrationService(
                fhirClientFactory, properties, meterRegistry);
    }

    private EmitterProperties buildDefaultProperties() {
        EmitterProperties props = new EmitterProperties();
        props.setSelfBaseUrl("http://localhost:9090");

        FhirServerConfig fhirServer = new FhirServerConfig();
        fhirServer.setName("test-fhir");
        fhirServer.setUrl("http://fhir-server:8090/fhir");
        FhirServerAuthConfig auth = new FhirServerAuthConfig();
        auth.setType("none");
        fhirServer.setAuth(auth);
        props.setFhirServer(fhirServer);

        return props;
    }

    /**
     * Stubs the HAPI FHIR Search fluent API (withTag) to return an empty bundle.
     */
    @SuppressWarnings("unchecked")
    private void stubTagSearchReturnsEmpty() {
        var untypedQuery = mock(IUntypedQuery.class);
        var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);

        when(client.search()).thenReturn(untypedQuery);
        when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
        when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
        when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);

        Bundle emptyBundle = new Bundle();
        when(typedQuery.execute()).thenReturn(emptyBundle);
    }

    /**
     * Stubs tag search to return a bundle with one existing adaptor-owned subscription.
     */
    @SuppressWarnings("unchecked")
    private void stubTagSearchReturnsExisting(String criteria, String subscriptionId) {
        var untypedQuery = mock(IUntypedQuery.class);
        var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);

        when(client.search()).thenReturn(untypedQuery);
        when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
        when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
        when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);

        Subscription existing = new Subscription();
        existing.setId("Subscription/" + subscriptionId);
        existing.setCriteria(criteria);
        existing.getMeta().addTag(new Coding(
                SubscriptionRegistrationService.OWNER_TAG_SYSTEM,
                SubscriptionRegistrationService.OWNER_TAG_CODE, "test"));

        Bundle bundle = new Bundle();
        bundle.addEntry().setResource(existing);
        when(typedQuery.execute()).thenReturn(bundle);
    }

    /**
     * Stubs the HAPI FHIR Create fluent API chain.
     */
    private void stubCreateSuccess(String subscriptionId) {
        var createWithMatch = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
        var createTyped = mock(ICreateTyped.class);

        when(client.create()).thenReturn(createWithMatch);
        when(createWithMatch.resource(any(Subscription.class))).thenReturn(createTyped);

        MethodOutcome outcome = new MethodOutcome();
        outcome.setId(new IdType("Subscription", subscriptionId));
        when(createTyped.execute()).thenReturn(outcome);
    }

    private List<String[]> entries(String... resourceTypes) {
        return java.util.Arrays.stream(resourceTypes)
                .map(rt -> new String[]{rt, ""})
                .toList();
    }

    private List<String[]> entriesWithFilter(String resourceType, String filter) {
        return List.<String[]>of(new String[]{resourceType, filter});
    }

    // ── a. Subscribe flow ───────────────────────────────────────────────

    @Nested
    class SubscribeFlow {

        @Test
        void newSubscription_registered() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-new-1");

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            assertEquals(1, results.size());
            assertEquals("registered", results.get(0).status());
            assertEquals("test-fhir", results.get(0).serverName());
            assertTrue(results.get(0).subscriptionId().contains("sub-new-1"));
            assertEquals(1, service.getActiveSubscriptionCount());
        }

        @Test
        void alreadyExists_detectedViaBulkFetch() {
            stubTagSearchReturnsExisting("Patient?", "existing-123");

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            assertEquals(1, results.size());
            assertEquals("already-exists", results.get(0).status());
            verify(client, never()).create();
        }

        @Test
        void serverError_failedWithMessage() {
            stubTagSearchReturnsEmpty();

            var createWithMatch = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
            var createTyped = mock(ICreateTyped.class);
            when(client.create()).thenReturn(createWithMatch);
            when(createWithMatch.resource(any(Subscription.class))).thenReturn(createTyped);
            when(createTyped.execute()).thenThrow(new RuntimeException("500 Server Error"));

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            assertEquals(1, results.size());
            assertTrue(results.get(0).status().startsWith("failed:"));
            assertTrue(results.get(0).status().contains("500 Server Error"));
            assertNull(results.get(0).subscriptionId());
        }

        @Test
        void withCriteriaFilter_criteriaContainsFilter() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-filtered");

            List<RegistrationResult> results = service.subscribeAll(
                    entriesWithFilter("Observation", "code=1234"));

            assertEquals(1, results.size());
            assertEquals("registered", results.get(0).status());
            verify(client).create();
        }

        @Test
        void noCriteria_criteriaEndsWithQuestionMark() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-no-filter");

            List<RegistrationResult> results = service.subscribeAll(entries("Encounter"));

            assertEquals(1, results.size());
            assertEquals("registered", results.get(0).status());
            verify(client).create();
        }

        @Test
        void bulkFetchCalledOnlyOnce_forMultipleEntries() {
            stubTagSearchReturnsEmpty();

            // Stub create to return different IDs per call
            var createWithMatch = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
            var createTyped = mock(ICreateTyped.class);
            when(client.create()).thenReturn(createWithMatch);
            when(createWithMatch.resource(any(Subscription.class))).thenReturn(createTyped);
            MethodOutcome outcome1 = new MethodOutcome();
            outcome1.setId(new IdType("Subscription", "sub-1"));
            MethodOutcome outcome2 = new MethodOutcome();
            outcome2.setId(new IdType("Subscription", "sub-2"));
            when(createTyped.execute()).thenReturn(outcome1, outcome2);

            List<RegistrationResult> results = service.subscribeAll(entries("Patient", "Observation"));

            assertEquals(2, results.size());
            // search() (bulk fetch) called only once — not per entry
            verify(client, times(1)).search();
        }

        @Test
        void createdSubscription_hasOwnerTag() {
            stubTagSearchReturnsEmpty();

            var createWithMatch = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
            var createTyped = mock(ICreateTyped.class);
            when(client.create()).thenReturn(createWithMatch);
            ArgumentCaptor<Subscription> subCaptor = ArgumentCaptor.forClass(Subscription.class);
            when(createWithMatch.resource(subCaptor.capture())).thenReturn(createTyped);
            MethodOutcome outcome = new MethodOutcome();
            outcome.setId(new IdType("Subscription", "sub-tagged"));
            when(createTyped.execute()).thenReturn(outcome);

            service.subscribeAll(entries("Patient"));

            Subscription created = subCaptor.getValue();
            assertFalse(created.getMeta().getTag().isEmpty());
            Coding tag = created.getMeta().getTag().get(0);
            assertEquals(SubscriptionRegistrationService.OWNER_TAG_SYSTEM, tag.getSystem());
            assertEquals(SubscriptionRegistrationService.OWNER_TAG_CODE, tag.getCode());
        }
    }

    // ── b. Metrics ──────────────────────────────────────────────────────

    @Nested
    class MetricsTests {

        @Test
        void subscribeSuccess_createdCounterIncremented() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-metric-1");

            service.subscribeAll(entries("Patient"));

            assertEquals(1.0, meterRegistry.counter("fhir.emitter.subscriptions.created").count());
            assertEquals(0.0, meterRegistry.counter("fhir.emitter.subscriptions.failed").count());
        }

        @Test
        void subscribeFailure_failedCounterIncremented() {
            stubTagSearchReturnsEmpty();

            var createWithMatch = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
            var createTyped = mock(ICreateTyped.class);
            when(client.create()).thenReturn(createWithMatch);
            when(createWithMatch.resource(any(Subscription.class))).thenReturn(createTyped);
            when(createTyped.execute()).thenThrow(new RuntimeException("Server down"));

            service.subscribeAll(entries("Patient"));

            assertEquals(0.0, meterRegistry.counter("fhir.emitter.subscriptions.created").count());
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.subscriptions.failed").count());
        }

        @Test
        void activeGauge_reflectsCount() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-gauge-1");

            assertEquals(0.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());

            service.subscribeAll(entries("Patient"));

            assertEquals(1.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());
        }
    }

    // ── Utility methods ─────────────────────────────────────────────────

    @Test
    void buildSubscriptionLookupKey_lowercasesResourceType() {
        assertEquals("patient|Patient?", service.buildSubscriptionLookupKey("Patient", "Patient?"));
        assertEquals("observation|Observation?code=123",
                service.buildSubscriptionLookupKey("Observation", "Observation?code=123"));
    }

    @Test
    void ownerTagConstants_areCorrect() {
        assertEquals("https://openphc.org/cce/fhir-emitter",
                SubscriptionRegistrationService.OWNER_TAG_SYSTEM);
        assertEquals("fhir-cce-emitter-adaptor",
                SubscriptionRegistrationService.OWNER_TAG_CODE);
    }
}
