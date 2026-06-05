package org.openphc.cce.emitter.service;

import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.ICreateTyped;
import ca.uhn.fhir.rest.gclient.IUntypedQuery;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Subscription;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

        // Use minimal retry for fast tests
        EmitterProperties.StartupSubscriptionConfig startupConfig = props.getStartupSubscriptions();
        startupConfig.setFetchRetryMaxAttempts(1);
        startupConfig.setFetchRetryBackoffMs(10);

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
        when(typedQuery.count(anyInt())).thenReturn(typedQuery);
        when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);

        Bundle emptyBundle = new Bundle();
        when(typedQuery.execute()).thenReturn(emptyBundle);
    }

    /**
     * Stubs tag search to return a bundle with one existing adaptor-owned subscription.
     */
    @SuppressWarnings("unchecked")
    private void stubTagSearchReturnsExisting(String criteria, String subscriptionId) {
        stubTagSearchReturnsExistingMultiple(new String[]{criteria}, new String[]{subscriptionId});
    }

    /**
     * Stubs tag search to return a bundle with multiple existing adaptor-owned subscriptions.
     */
    @SuppressWarnings("unchecked")
    private void stubTagSearchReturnsExistingMultiple(String[] criteriaArray, String[] subscriptionIds) {
        var untypedQuery = mock(IUntypedQuery.class);
        var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);

        when(client.search()).thenReturn(untypedQuery);
        when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
        when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
        when(typedQuery.count(anyInt())).thenReturn(typedQuery);
        when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);

        Bundle bundle = new Bundle();
        for (int i = 0; i < criteriaArray.length; i++) {
            Subscription existing = new Subscription();
            existing.setId("Subscription/" + subscriptionIds[i]);
            existing.setCriteria(criteriaArray[i]);
            existing.getMeta().addTag(new Coding(
                    SubscriptionRegistrationService.OWNER_TAG_SYSTEM,
                    SubscriptionRegistrationService.OWNER_TAG_CODE, "test"));
            bundle.addEntry().setResource(existing);
        }
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

    /**
     * Stubs the HAPI FHIR Delete fluent API chain.
     */
    private void stubDeleteSuccess() {
        var deleteTyped = mock(ca.uhn.fhir.rest.gclient.IDeleteTyped.class);
        var deleteWithQuery = mock(ca.uhn.fhir.rest.gclient.IDelete.class);

        when(client.delete()).thenReturn(deleteWithQuery);
        when(deleteWithQuery.resourceById(any(IIdType.class))).thenReturn(deleteTyped);
        when(deleteTyped.execute()).thenReturn(new ca.uhn.fhir.rest.api.MethodOutcome());
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
            assertEquals(1.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());
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
        @DisplayName("Fetch failure aborts reconciliation — throws to prevent duplicates")
        void fetchFailure_abortsReconciliation_noSubscriptionsCreated() {
            // Simulate FHIR server unreachable during bulk fetch (e.g., HAPI-1357 metadata failure)
            var untypedQuery = mock(IUntypedQuery.class);
            var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);
            when(client.search()).thenReturn(untypedQuery);
            when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
            when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
            when(typedQuery.count(anyInt())).thenReturn(typedQuery);
            when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);
            when(typedQuery.execute()).thenThrow(new RuntimeException("HAPI-1357: Failed to retrieve the server metadata statement"));

            var ex = assertThrows(IllegalStateException.class,
                    () -> service.subscribeAll(entries("Patient", "Encounter")));

            assertTrue(ex.getMessage().contains("HAPI-1357"));
            verify(client, never()).create();
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
        void alreadyExists_createdCounterNotIncremented() {
            stubTagSearchReturnsExisting("Patient?", "existing-123");

            service.subscribeAll(entries("Patient"));

            assertEquals(0.0, meterRegistry.counter("fhir.emitter.subscriptions.created").count());
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

    // ── c. Reconcile flow (delete stale subscriptions) ──────────────────

    @Nested
    @DisplayName("Reconcile — delete stale subscriptions")
    class ReconcileFlow {

        @Test
        @DisplayName("Stale subscription is deleted when resource type removed from config")
        void staleSubscription_deleted() {
            // Server has Patient and Encounter subscriptions, but config only has Patient
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?"},
                    new String[]{"sub-patient", "sub-encounter"});
            stubDeleteSuccess();

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            // Patient: already-exists, Encounter: deleted
            assertEquals(2, results.size());
            assertEquals("already-exists", results.get(0).status());
            assertEquals("deleted", results.get(1).status());
            assertTrue(results.get(1).subscriptionId().contains("sub-encounter"));
            verify(client).delete();
        }

        @Test
        @DisplayName("Multiple stale subscriptions are all deleted")
        void multipleStaleSubscriptions_allDeleted() {
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?", "Observation?"},
                    new String[]{"sub-patient", "sub-encounter", "sub-observation"});
            stubDeleteSuccess();

            // Only keep Patient — Encounter and Observation should be deleted
            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            long deletedCount = results.stream().filter(r -> "deleted".equals(r.status())).count();
            assertEquals(2, deletedCount);
            verify(client, times(2)).delete();
        }

        @Test
        @DisplayName("No stale subscriptions — nothing deleted")
        void noStaleSubscriptions_nothingDeleted() {
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?"},
                    new String[]{"sub-patient", "sub-encounter"});

            List<RegistrationResult> results = service.subscribeAll(entries("Patient", "Encounter"));

            long deletedCount = results.stream().filter(r -> "deleted".equals(r.status())).count();
            assertEquals(0, deletedCount);
            verify(client, never()).delete();
        }

        @Test
        @DisplayName("Delete failure is non-fatal — logged as delete-failed")
        void deleteFailure_nonFatal() {
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?"},
                    new String[]{"sub-patient", "sub-encounter"});

            var deleteWithQuery = mock(ca.uhn.fhir.rest.gclient.IDelete.class);
            var deleteTyped = mock(ca.uhn.fhir.rest.gclient.IDeleteTyped.class);
            when(client.delete()).thenReturn(deleteWithQuery);
            when(deleteWithQuery.resourceById(any(IIdType.class))).thenReturn(deleteTyped);
            when(deleteTyped.execute()).thenThrow(new RuntimeException("403 Forbidden"));

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            RegistrationResult deleteResult = results.stream()
                    .filter(r -> r.status().startsWith("delete-failed:"))
                    .findFirst().orElse(null);
            assertNotNull(deleteResult);
            assertTrue(deleteResult.status().contains("403 Forbidden"));
        }

        @Test
        @DisplayName("Active count reflects state after deletions")
        void activeCount_reflectsStateAfterDeletions() {
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?", "Observation?"},
                    new String[]{"sub-patient", "sub-encounter", "sub-observation"});
            stubDeleteSuccess();

            // Keep only Patient — delete Encounter and Observation
            service.subscribeAll(entries("Patient"));

            // Active count: 1 (Patient remains)
            assertEquals(1.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());
        }

        @Test
        @DisplayName("Deleted counter metric is incremented")
        void deletedCounter_incremented() {
            stubTagSearchReturnsExistingMultiple(
                    new String[]{"Patient?", "Encounter?"},
                    new String[]{"sub-patient", "sub-encounter"});
            stubDeleteSuccess();

            service.subscribeAll(entries("Patient"));

            assertEquals(1.0, meterRegistry.counter("fhir.emitter.subscriptions.deleted").count());
        }

        @Test
        @DisplayName("New subscription created and stale one deleted in same call")
        void createAndDelete_inSameCall() {
            // Server has Encounter, config wants Patient (not Encounter)
            stubTagSearchReturnsExisting("Encounter?", "sub-encounter");
            stubCreateSuccess("sub-patient-new");
            stubDeleteSuccess();

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            assertEquals(2, results.size());
            assertEquals("registered", results.get(0).status());
            assertEquals("deleted", results.get(1).status());
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

    // ── d. Retry ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("Retry")
    class RetryTests {

        @Test
        @DisplayName("Retry succeeds on second attempt after first failure")
        @SuppressWarnings("unchecked")
        void retrySucceeds_onSecondAttempt() {
            // Configure 3 retries with minimal backoff
            properties.getStartupSubscriptions().setFetchRetryMaxAttempts(3);
            properties.getStartupSubscriptions().setFetchRetryBackoffMs(10);

            var untypedQuery = mock(IUntypedQuery.class);
            var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);
            when(client.search()).thenReturn(untypedQuery);
            when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
            when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
            when(typedQuery.count(anyInt())).thenReturn(typedQuery);
            when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);

            // First call fails, second succeeds with empty bundle
            Bundle emptyBundle = new Bundle();
            when(typedQuery.execute())
                    .thenThrow(new RuntimeException("HAPI-1357: metadata failure"))
                    .thenReturn(emptyBundle);

            stubCreateSuccess("sub-retry-1");

            List<RegistrationResult> results = service.subscribeAll(entries("Patient"));

            assertEquals(1, results.size());
            assertEquals("registered", results.get(0).status());
        }

        @Test
        @DisplayName("All retries exhausted — throws IllegalStateException")
        @SuppressWarnings("unchecked")
        void allRetriesExhausted_throwsIllegalStateException() {
            properties.getStartupSubscriptions().setFetchRetryMaxAttempts(2);
            properties.getStartupSubscriptions().setFetchRetryBackoffMs(10);

            var untypedQuery = mock(IUntypedQuery.class);
            var typedQuery = mock(ca.uhn.fhir.rest.gclient.IQuery.class);
            when(client.search()).thenReturn(untypedQuery);
            when(untypedQuery.forResource(Subscription.class)).thenReturn(typedQuery);
            when(typedQuery.withTag(anyString(), anyString())).thenReturn(typedQuery);
            when(typedQuery.count(anyInt())).thenReturn(typedQuery);
            when(typedQuery.returnBundle(Bundle.class)).thenReturn(typedQuery);
            when(typedQuery.execute()).thenThrow(new RuntimeException("Connection refused"));

            var ex = assertThrows(IllegalStateException.class,
                    () -> service.subscribeAll(entries("Patient")));

            assertTrue(ex.getMessage().contains("Connection refused"));
            verify(client, never()).create();
        }
    }
}
