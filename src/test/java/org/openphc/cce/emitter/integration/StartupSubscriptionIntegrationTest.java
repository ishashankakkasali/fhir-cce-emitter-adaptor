package org.openphc.cce.emitter.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for { StartupSubscriptionRunner} auto-subscribe flow.
 * <p>
 * Uses a separate Spring context with startup subscriptions enabled. The FHIR
 * server is simulated via WireMock, stubs are set up before the context starts
 * so the runner can interact with them on startup.
 * <p>
 * Note: Because StartupSubscriptionRunner fires during ApplicationRunner phase,
 * we pre-configure WireMock stubs before Spring context initialization via
 * static initialization and @BeforeAll.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class StartupSubscriptionIntegrationTest {

    static WireMockServer fhirServer;
    static WireMockServer openhimServer;

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void startWireMockServers() {
        fhirServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        fhirServer.start();

        openhimServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        openhimServer.start();

        // Pre-stub FHIR server to handle subscription search (bulk fetch by tag)
        // Returns empty bundle — no existing subscriptions
        fhirServer.stubFor(WireMock.get(urlPathEqualTo("/fhir/Subscription"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody("""
                                {
                                  "resourceType": "Bundle",
                                  "type": "searchset",
                                  "total": 0,
                                  "entry": []
                                }
                                """)));

        // Pre-stub FHIR server to accept subscription creation
        fhirServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Subscription"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody("""
                                {
                                  "resourceType": "Subscription",
                                  "id": "sub-1",
                                  "status": "active"
                                }
                                """)));
    }

    @AfterAll
    static void stopWireMockServers() {
        if (fhirServer != null) fhirServer.stop();
        if (openhimServer != null) openhimServer.stop();
    }

    @AfterEach
    void resetStubs() {
        // Don't reset fhirServer stubs entirely — the runner may have already executed
        openhimServer.resetAll();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("emitter.fhir-server.url", () -> fhirServer.baseUrl() + "/fhir");
        registry.add("emitter.fhir-server.auth.type", () -> "none");
        registry.add("emitter.openhim.base-url", () -> openhimServer.baseUrl() + "/fhir");
        registry.add("emitter.self-base-url", () -> "http://localhost:9090");
        // Enable startup subscriptions with no delay
        registry.add("emitter.startup-subscriptions.enabled", () -> "true");
        registry.add("emitter.startup-subscriptions.delay-seconds", () -> "0");
        registry.add("emitter.startup-subscriptions.resource-types", () -> "Patient,Encounter");
    }

    @Test
    @DisplayName("Startup subscriptions create Subscription resources on FHIR server")
    void startupSubscriptions_createSubscriptionsOnFhirServer() {
        // By the time tests run, StartupSubscriptionRunner has already executed.
        // Verify the FHIR server received subscription creation POST requests.
        // HAPI FHIR serializes JSON without spaces: "type":"rest-hook"
        fhirServer.verify(postRequestedFor(urlPathEqualTo("/fhir/Subscription"))
                .withRequestBody(containing("rest-hook"))
                .withRequestBody(containing("application/fhir+json")));
    }

    @Test
    @DisplayName("Startup subscriptions: 2 subscriptions created for Patient and Encounter")
    void startupSubscriptions_createsSubscriptionsForEachResourceType() {
        // Verify Patient and Encounter subscriptions were created
        fhirServer.verify(postRequestedFor(urlPathEqualTo("/fhir/Subscription"))
                .withRequestBody(containing("Patient")));
        fhirServer.verify(postRequestedFor(urlPathEqualTo("/fhir/Subscription"))
                .withRequestBody(containing("Encounter")));
    }

    @Test
    @DisplayName("Application starts normally even with startup subscriptions enabled")
    void applicationStartsNormally_withStartupSubscriptions() throws Exception {
        // If we get here, the context loaded successfully despite startup subscriptions.
        // Verify the application is healthy.
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
