package org.openphc.cce.emitter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Base64;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying authentication headers are correctly applied
 * when forwarding to OpenHIM.
 */
class OpenhimBasicAuthIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void configureBasicAuth(DynamicPropertyRegistry registry) {
        registry.add("emitter.openhim.auth.type", () -> "basic");
        registry.add("emitter.openhim.auth.username", () -> "openhim-user");
        registry.add("emitter.openhim.auth.password", () -> "openhim-pass");
    }

    @Test
    @DisplayName("OpenHIM auth type=basic → forwarded request includes Authorization: Basic header")
    void basicAuth_forwardedRequestIncludesBasicHeader() throws Exception {
        openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        mockMvc.perform(post("/callback/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FHIR_PATIENT_JSON))
                .andExpect(status().isOk());

        String expectedEncoded = Base64.getEncoder().encodeToString("openhim-user:openhim-pass".getBytes());
        openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient"))
                .withHeader("Authorization", equalTo("Basic " + expectedEncoded)));
    }

    @Test
    @DisplayName("OpenHIM auth type=basic → forwarding failed, always 200 OK (always-ACK), single attempt")
    void basicAuth_retryAttemptsIncludeAuthHeader() throws Exception {
        openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                .willReturn(aResponse().withStatus(500).withBody("error")));

        mockMvc.perform(post("/callback/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FHIR_PATIENT_JSON))
                .andExpect(status().isOk());

        // No retry: single attempt; auth header still present
        String expectedEncoded = Base64.getEncoder().encodeToString("openhim-user:openhim-pass".getBytes());
        openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient"))
                .withHeader("Authorization", equalTo("Basic " + expectedEncoded)));
    }
}
