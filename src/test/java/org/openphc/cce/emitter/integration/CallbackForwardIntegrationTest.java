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
        @DisplayName("POST valid FHIR Encounter to /callback/encounter → resource forwarded to OpenHIM")
        void postValidEncounter_forwardedToOpenhim() throws Exception {
            // Stub OpenHIM to accept the forward
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(post("/callback/encounter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_ENCOUNTER_JSON))
                    .andExpect(status().isOk());

            // Verify OpenHIM received the resource with correct body and headers
            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter"))
                    .withHeader("Content-Type", containing("application/json"))
                    .withRequestBody(containing("\"resourceType\":\"Encounter\""))
                    .withRequestBody(containing("\"id\":\"enc-456\"")));
        }

        @Test
        @DisplayName("PUT callback with sub-path /callback/encounter/Encounter/456 → resource forwarded")
        void putCallbackWithSubPath_forwardedToOpenhim() throws Exception {
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(put("/callback/encounter/Encounter/456")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_ENCOUNTER_JSON))
                    .andExpect(status().isOk());

            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter"))
                    .withRequestBody(containing("\"resourceType\":\"Encounter\"")));
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
                    .andExpect(status().isOk());

            // Verify URL includes the parsed resource type (Encounter), not the callback key
            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter")));
        }

        @Test
        @DisplayName("POST with application/fhir+json content type → accepted and forwarded")
        void postWithFhirContentType_acceptedAndForwarded() throws Exception {
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                    .willReturn(aResponse()
                            .withStatus(200)
                            .withBody("{\"ok\": true}")));

            mockMvc.perform(post("/callback/encounter")
                            .contentType("application/fhir+json")
                            .content(FHIR_ENCOUNTER_JSON))
                    .andExpect(status().isOk());

            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter")));
        }
    }

    @Nested
    @DisplayName("Callback → Forward — Retry & Failure")
    class RetryAndFailure {

        @Test
        @DisplayName("OpenHIM returns 500 → single attempt, always 200 OK (no retry, always-ACK)")
        void openhimReturns500_retriedThenError() throws Exception {
            // Stub OpenHIM to always return 500
            openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                    .willReturn(aResponse()
                            .withStatus(500)
                            .withBody("Internal Server Error")));

            mockMvc.perform(post("/callback/encounter")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_ENCOUNTER_JSON))
                    .andExpect(status().isOk());

            // No retry: single attempt only
            openhimServer.verify(1, postRequestedFor(urlPathEqualTo("/fhir/Encounter")));
        }
    }
}
