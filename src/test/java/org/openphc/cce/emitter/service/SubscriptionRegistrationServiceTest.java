package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.client.api.IClientInterceptor;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.IHttpRequest;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SubscriptionRegistrationService}.
 * Covers subscribe flow (tag-based bulk fetch), auth client creation, and metrics.
 */
@ExtendWith(MockitoExtension.class)
class SubscriptionRegistrationServiceTest {

    @Mock
    private FhirContext fhirContext;

    @Mock
    private TokenEndpointAuthService tokenEndpointAuthService;

    @Mock
    private IGenericClient client;

    private MeterRegistry meterRegistry;
    private EmitterProperties properties;
    private SubscriptionRegistrationService service;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        properties = buildDefaultProperties();

        lenient().when(fhirContext.newRestfulGenericClient(anyString())).thenReturn(client);

        service = new SubscriptionRegistrationService(
                fhirContext, properties, tokenEndpointAuthService, meterRegistry);
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

    // ── a. Subscribe flow ───────────────────────────────────────────────

    @Nested
    class SubscribeFlow {

        @Test
        void newSubscription_registered_trackedInMap() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-new-1");

            RegistrationResult result = service.subscribe("Patient", null);

            assertEquals("registered", result.status());
            assertEquals("test-fhir", result.serverName());
            assertTrue(result.subscriptionId().contains("sub-new-1"));
            assertEquals(1, service.getActiveSubscriptionCount());
        }

        @Test
        void alreadyExists_detectedViaBulkFetch() {
            // Bulk fetch returns an existing subscription for Patient?
            stubTagSearchReturnsExisting("Patient?", "existing-123");

            RegistrationResult result = service.subscribe("Patient", null);

            assertEquals("already-exists", result.status());
            assertEquals("test-fhir", result.serverName());
            // No create call — subscription already exists
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

            RegistrationResult result = service.subscribe("Patient", null);

            assertEquals("test-fhir", result.serverName());
            assertTrue(result.status().startsWith("failed:"));
            assertTrue(result.status().contains("500 Server Error"));
            assertNull(result.subscriptionId());
        }

        @Test
        void withCriteriaFilter_criteriaContainsFilter() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-filtered");

            RegistrationResult result = service.subscribe("Observation", "code=1234");

            assertEquals("registered", result.status());
            verify(client).create();
        }

        @Test
        void noCriteria_criteriaEndsWithQuestionMark() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-no-filter");

            RegistrationResult result = service.subscribe("Encounter", null);

            assertEquals("registered", result.status());
            verify(client).create();
        }

        @Test
        void bulkFetchCalledOnlyOnce() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-1");

            service.subscribe("Patient", null);

            // Reset create stub for second call (different ID)
            var createWithMatch2 = mock(ca.uhn.fhir.rest.gclient.ICreate.class);
            var createTyped2 = mock(ICreateTyped.class);
            when(client.create()).thenReturn(createWithMatch2);
            when(createWithMatch2.resource(any(Subscription.class))).thenReturn(createTyped2);
            MethodOutcome outcome2 = new MethodOutcome();
            outcome2.setId(new IdType("Subscription", "sub-2"));
            when(createTyped2.execute()).thenReturn(outcome2);

            service.subscribe("Observation", null);

            // search() was called only once (for bulk fetch on first subscribe)
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

            service.subscribe("Patient", null);

            Subscription created = subCaptor.getValue();
            assertFalse(created.getMeta().getTag().isEmpty());
            Coding tag = created.getMeta().getTag().get(0);
            assertEquals(SubscriptionRegistrationService.OWNER_TAG_SYSTEM, tag.getSystem());
            assertEquals(SubscriptionRegistrationService.OWNER_TAG_CODE, tag.getCode());
        }
    }

    // ── b. Auth client creation ─────────────────────────────────────────

    @Nested
    class AuthClientCreation {

        @Test
        void authNone_noInterceptor() {
            properties.getFhirServer().getAuth().setType("none");

            IGenericClient result = service.createAuthenticatedClient(properties.getFhirServer());

            assertNotNull(result);
            verify(client, never()).registerInterceptor(any());
        }

        @Test
        void authBasic_basicAuthHeader() {
            FhirServerAuthConfig auth = properties.getFhirServer().getAuth();
            auth.setType("basic");
            auth.setUsername("user");
            auth.setPassword("pass");

            service.createAuthenticatedClient(properties.getFhirServer());

            ArgumentCaptor<IClientInterceptor> captor = ArgumentCaptor.forClass(IClientInterceptor.class);
            verify(client).registerInterceptor(captor.capture());

            IHttpRequest mockRequest = mock(IHttpRequest.class);
            captor.getValue().interceptRequest(mockRequest);

            String expectedAuth = "Basic " + java.util.Base64.getEncoder()
                    .encodeToString("user:pass".getBytes());
            verify(mockRequest).addHeader("Authorization", expectedAuth);
        }

        @Test
        void authBearer_bearerAuthHeader() {
            FhirServerAuthConfig auth = properties.getFhirServer().getAuth();
            auth.setType("bearer");
            auth.setToken("static-bearer-token");

            service.createAuthenticatedClient(properties.getFhirServer());

            ArgumentCaptor<IClientInterceptor> captor = ArgumentCaptor.forClass(IClientInterceptor.class);
            verify(client).registerInterceptor(captor.capture());

            IHttpRequest mockRequest = mock(IHttpRequest.class);
            captor.getValue().interceptRequest(mockRequest);
            verify(mockRequest).addHeader("Authorization", "Bearer static-bearer-token");
        }

        @Test
        void authTokenEndpoint_getTokenCalled_interceptorRegistered() {
            FhirServerAuthConfig auth = properties.getFhirServer().getAuth();
            auth.setType("token-endpoint");
            auth.setTokenUrl("http://auth:8089/authenticate");
            auth.setClient("web");
            when(tokenEndpointAuthService.getToken(auth)).thenReturn("Bearer fetched-token");

            service.createAuthenticatedClient(properties.getFhirServer());

            verify(tokenEndpointAuthService).getToken(auth);
            verify(client, times(2)).registerInterceptor(any(IClientInterceptor.class));
        }

        @Test
        void authOauth2_getTokenCalled_bearerInterceptor() {
            FhirServerAuthConfig auth = properties.getFhirServer().getAuth();
            auth.setType("oauth2");
            auth.setTokenUrl("http://keycloak/token");
            when(tokenEndpointAuthService.getToken(auth)).thenReturn("Bearer oauth2-token");

            service.createAuthenticatedClient(properties.getFhirServer());

            verify(tokenEndpointAuthService).getToken(auth);
            verify(client, times(1)).registerInterceptor(any(IClientInterceptor.class));
        }

        @Test
        void authUnknown_logWarning_noInterceptor() {
            properties.getFhirServer().getAuth().setType("ldap");

            IGenericClient result = service.createAuthenticatedClient(properties.getFhirServer());

            assertNotNull(result);
            verify(client, never()).registerInterceptor(any());
        }
    }

    // ── c. Metrics ──────────────────────────────────────────────────────

    @Nested
    class MetricsTests {

        @Test
        void subscribeSuccess_createdCounterIncremented() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-metric-1");

            service.subscribe("Patient", null);

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

            service.subscribe("Patient", null);

            assertEquals(0.0, meterRegistry.counter("fhir.emitter.subscriptions.created").count());
            assertEquals(1.0, meterRegistry.counter("fhir.emitter.subscriptions.failed").count());
        }

        @Test
        void activeGauge_reflectsMapSize() {
            stubTagSearchReturnsEmpty();
            stubCreateSuccess("sub-gauge-1");

            assertEquals(0.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());

            service.subscribe("Patient", null);

            assertEquals(1.0, meterRegistry.get("fhir.emitter.subscriptions.active").gauge().value());
        }
    }

    // ── Utility methods ─────────────────────────────────────────────────

    @Test
    void buildKey_lowercasesResourceType() {
        assertEquals("patient|Patient?", service.buildKey("Patient", "Patient?"));
        assertEquals("observation|Observation?code=123",
                service.buildKey("Observation", "Observation?code=123"));
    }

    @Test
    void ownerTagConstants_areCorrect() {
        assertEquals("https://openphc.org/cce/fhir-emitter",
                SubscriptionRegistrationService.OWNER_TAG_SYSTEM);
        assertEquals("fhir-cce-emitter-adaptor",
                SubscriptionRegistrationService.OWNER_TAG_CODE);
    }
}
