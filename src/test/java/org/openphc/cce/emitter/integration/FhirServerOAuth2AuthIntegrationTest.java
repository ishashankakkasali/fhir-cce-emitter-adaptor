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
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for FHIR server OAuth2 Client Credentials auth during startup subscriptions.
 * <p>
 * Verifies that the adaptor authenticates using grant_type=client_credentials,
 * then uses the obtained access_token for FHIR server requests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
class FhirServerOAuth2AuthIntegrationTest {

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

        // Stub OAuth2 token endpoint
        tokenServer.stubFor(WireMock.post(urlPathEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {
                                  "access_token": "oauth2-access-token-xyz",
                                  "token_type": "Bearer",
                                  "expires_in": 3600
                                }
                                """)));

        // Stub FHIR server
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

        fhirServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Subscription"))
                .willReturn(aResponse()
                        .withStatus(201)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody("""
                                {
                                  "resourceType": "Subscription",
                                  "id": "sub-oauth2-1",
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
        registry.add("emitter.fhir-server.auth.type", () -> "oauth2");
        registry.add("emitter.fhir-server.auth.token-url", () -> tokenServer.baseUrl() + "/oauth2/token");
        registry.add("emitter.fhir-server.auth.client-id", () -> "my-client-id");
        registry.add("emitter.fhir-server.auth.client-secret", () -> "my-client-secret");
        registry.add("emitter.fhir-server.auth.scope", () -> "fhir.read fhir.write");
        registry.add("emitter.openhim.base-url", () -> openhimServer.baseUrl() + "/fhir");
        registry.add("emitter.self-base-url", () -> "http://localhost:9090");
        registry.add("emitter.startup-subscriptions.enabled", () -> "true");
        registry.add("emitter.startup-subscriptions.delay-seconds", () -> "0");
        registry.add("emitter.startup-subscriptions.resource-types", () -> "Patient");
    }

    @Test
    @DisplayName("OAuth2 token endpoint called with grant_type=client_credentials")
    void oauth2TokenEndpointCalled_withClientCredentials() {
        tokenServer.verify(postRequestedFor(urlPathEqualTo("/oauth2/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=my-client-id"))
                .withRequestBody(containing("client_secret=my-client-secret"))
                .withRequestBody(containing("scope=fhir.read+fhir.write")));
    }

    @Test
    @DisplayName("FHIR server requests include Bearer token from OAuth2 endpoint")
    void fhirServerRequests_includeBearerToken() {
        fhirServer.verify(postRequestedFor(urlPathEqualTo("/fhir/Subscription"))
                .withHeader("Authorization", equalTo("Bearer oauth2-access-token-xyz")));
    }

    @Test
    @DisplayName("Application starts normally with OAuth2 auth")
    void applicationStartsNormally() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }
}
