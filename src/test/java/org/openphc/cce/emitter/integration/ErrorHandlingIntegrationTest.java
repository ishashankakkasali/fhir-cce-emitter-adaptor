package org.openphc.cce.emitter.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.github.tomakehurst.wiremock.client.WireMock;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for error handling scenarios in the callback → forward pipeline.
 */
class ErrorHandlingIntegrationTest extends AbstractIntegrationTest {

    @Test
    @DisplayName("Malformed callback body → forward still attempted with Unknown type, no 500 returned")
    void malformedBody_forwardAttemptedWithUnknownType_no500() throws Exception {
        // Stub OpenHIM to accept any resource type (Unknown will be appended)
        openhimServer.stubFor(WireMock.post(anyUrl())
                .willReturn(aResponse().withStatus(200).withBody("{\"ok\": true}")));

        mockMvc.perform(post("/callback/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MALFORMED_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ok"));

        // Verify the forward was attempted — resource type falls back to "Unknown"
        openhimServer.verify(1, postRequestedFor(anyUrl())
                .withRequestBody(containing("\"not\": \"a fhir resource\"")));
    }

    @Test
    @DisplayName("GET /callback/patient ping → 200 OK, no OpenHIM request")
    void getCallbackPing_returns200_noOpenhimRequest() throws Exception {
        mockMvc.perform(get("/callback/patient"))
                .andExpect(status().isOk())
                .andExpect(content().string("OK"));

        // Verify no request was made to OpenHIM
        openhimServer.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    @DisplayName("OpenHIM returns 400 Bad Request → FORWARDING_ERROR with 400 status")
    void openhimReturns400_forwardingErrorWith400() throws Exception {
        openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Patient"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Bad Request: missing required field")));

        mockMvc.perform(post("/callback/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FHIR_PATIENT_JSON))
                .andExpect(status().isBadGateway())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("FORWARDING_ERROR")));
    }
}
