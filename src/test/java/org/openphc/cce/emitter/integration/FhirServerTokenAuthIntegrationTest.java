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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for FHIR server token-endpoint auth during startup subscriptions.
 * <p>
 * Verifies that the adaptor authenticates with a token endpoint, then uses the
 * obtained token when creating subscriptions on the FHIR server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class FhirServerTokenAuthIntegrationTest {

    static WireMockServer fhirServer;
    static WireMockServer openhimServer;
    static WireMockServer tokenServer;

    @Autowired
    MockMvc mockMvc;

    @BeforeAll
    static void startServers() {
        fhirServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        fhirServer.start();

        openhimServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        openhimServer.start();

        tokenServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        tokenServer.start();

        // Stub token endpoint to return a JWT token
        tokenServer.stubFor(WireMock.post(urlPathEqualTo("/auth/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withHeader("Authorization", "Bearer test-jwt-token-from-endpoint")
                        .withBody("{\"token\": \"test-jwt-token-from-endpoint\"}")));

        // Stub FHIR server — subscription search (empty bundle)
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

        // Stub FHIR server — subscription creation
        fhirServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Subscription"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody("""
                                {
                                  "resourceType": "Subscription",
                                  "id": "sub-token-1",
                                  "status": "active"
                                }
                                """)));
    }

    @AfterAll
    static void stopServers() {
        if (fhirServer != null) fhirServer.stop();
        if (openhimServer != null) openhimServer.stop();
        if (tokenServer != null) tokenServer.stop();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("emitter.fhir-server.url", () -> fhirServer.baseUrl() + "/fhir");
        registry.add("emitter.fhir-server.auth.type", () -> "token-endpoint");
        registry.add("emitter.fhir-server.auth.token-url", () -> tokenServer.baseUrl() + "/auth/token");
        registry.add("emitter.fhir-server.auth.username", () -> "fhir-user");
        registry.add("emitter.fhir-server.auth.password", () -> "fhir-pass");
        registry.add("emitter.fhir-server.auth.client", () -> "web");
        registry.add("emitter.fhir-server.auth.token-body-field", () -> "token");
        registry.add("emitter.openhim.base-url", () -> openhimServer.baseUrl() + "/fhir");
        registry.add("emitter.self-base-url", () -> "http://localhost:9090");
        registry.add("emitter.startup-subscriptions.enabled", () -> "true");
        registry.add("emitter.startup-subscriptions.delay-seconds", () -> "0");
        registry.add("emitter.startup-subscriptions.resource-types", () -> "Patient");
    }

    @Test
    @DisplayName("Token endpoint called during startup subscription with correct credentials")
    void tokenEndpointCalled_withCredentials() {
        tokenServer.verify(postRequestedFor(urlPathEqualTo("/auth/token"))
                .withRequestBody(containing("username=fhir-user"))
                .withRequestBody(containing("password=fhir-pass")));
    }

    @Test
    @DisplayName("FHIR server requests include Authorization header from token endpoint")
    void fhirServerRequests_includeAuthorizationFromToken() {
        // Verify subscription creation requests include the token
        fhirServer.verify(postRequestedFor(urlPathEqualTo("/fhir/Subscription"))
                .withHeader("Authorization", containing("Bearer")));
    }

    @Test
    @DisplayName("Application starts normally with token-endpoint auth")
    void applicationStartsNormally() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
