package org.openphc.cce.emitter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for the Callback → Forward pipeline.
 * <p>
 * Verifies end-to-end: POST/PUT to callback endpoint → ForwardingEngine parses
 * FHIR resource → forwards to WireMock OpenHIM → correct headers, body, and URL.
 */
class CallbackForwardIntegrationTest extends AbstractIntegrationTest {

    @Nested
    @DisplayName("Callback → Forward — Success")
    class SuccessForwarding {

        @Test
        @DisplayName("POST valid FHIR Patient to /callback/patient → resource forwarded to OpenHIM")
        void postValidPatient_forwardedToOpenhim() throws Exception {
            // Stub OpenHIM to accept the forward
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.data.status").value("ok"));

            // Verify OpenHIM received the resource with correct body and headers
            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(containing("\"resourceType\": \"Patient\""))
                    .withRequestBody(containing("\"id\": \"test-123\"")));
        }

        @Test
        @DisplayName("PUT callback with sub-path /callback/patient/Patient/123 → resource forwarded")
        void putCallbackWithSubPath_forwardedToOpenhim() throws Exception {
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(put("/callback/patient/Patient/123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ok"));

            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient"))
                    .withRequestBody(containing("\"resourceType\": \"Patient\"")));
        }

        @Test
        @DisplayName("POST valid FHIR Encounter → forwarded to OpenHIM with correct resource type URL")
        void postEncounter_forwardedWithCorrectResourceTypeUrl() throws Exception {
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(post("/callback/encounter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_ENCOUNTER_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ok"));

            // Verify URL includes the parsed resource type (Encounter), not the callback key
            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter")));
        }

        @Test
        @DisplayName("POST with application/fhir+json content type → accepted and forwarded")
        void postWithFhirContentType_acceptedAndForwarded() throws Exception {
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(post("/callback/patient")
                            .contentType("application/fhir+json")
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.status").value("ok"));

            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Patient")));
        }
    }

    @Nested
    @DisplayName("Callback → Forward — Retry & Failure")
    class RetryAndFailure {

        @Test
        @DisplayName("OpenHIM returns 500 → retried maxAttempts times then FORWARDING_ERROR")
        void openhimReturns500_retriedThenError() throws Exception {
            // Stub OpenHIM to always return 500
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("Internal Server Error")));

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isBadGateway())
                    .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("FORWARDING_ERROR")));

            // Verify retry: maxAttempts=2 (configured in integration test profile)
            openhimServer.verify(2, postRequestedFor(urlPathEqualTo("/fhir/Patient")));
        }
    }
}
