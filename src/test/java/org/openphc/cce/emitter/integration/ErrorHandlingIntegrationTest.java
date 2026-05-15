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
    @DisplayName("Malformed callback body → no related-person-path configured, forward skipped, still 200 OK")
    void malformedBody_forwardSkipped_still200OK() throws Exception {
        mockMvc.perform(post("/callback/patient")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(MALFORMED_JSON))
                .andExpect(status().isOk());

        // Verify no forward was attempted — resource type has no configured path
        openhimServer.verify(0, postRequestedFor(anyUrl()));
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
    @DisplayName("OpenHIM returns 400 Bad Request → always 200 OK (always-ACK, failure logged only)")
    void openhimReturns400_forwardingErrorWith400() throws Exception {
        openhimServer.stubFor(WireMock.post(urlPathEqualTo("/fhir/Encounter"))
                .willReturn(aResponse()
                        .withStatus(400)
                        .withBody("Bad Request: missing required field")));

        mockMvc.perform(post("/callback/encounter")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(FHIR_ENCOUNTER_JSON))
                .andExpect(status().isOk());
    }
}
