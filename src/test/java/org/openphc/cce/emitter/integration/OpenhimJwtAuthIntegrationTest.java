package org.openphc.cce.emitter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests verifying JWT token auth for OpenHIM forwarding.
 */
class OpenhimJwtAuthIntegrationTest extends AbstractIntegrationTest {

    @DynamicPropertySource
    static void configureJwtAuth(DynamicPropertyRegistry registry) {
        registry.add("emitter.openhim.auth.type", () -> "jwt");
        registry.add("emitter.openhim.auth.token", () -> "eyJhbGciOiJSUzI1NiJ9.test-jwt-token");
    }

    @Test
    @DisplayName("OpenHIM auth type=jwt → forwarded request includes Authorization: Bearer header")
    void jwtAuth_forwardedRequestIncludesBearerHeader() throws Exception {
        openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        mockMvc.perform(post("/callback/patient")
                        .contentType("application/json")
                        .content(FHIR_PATIENT_JSON))
                .andExpect(status().isOk());

        openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient"))
                .withHeader("Authorization", equalTo("Bearer eyJhbGciOiJSUzI1NiJ9.test-jwt-token")));
    }
}
