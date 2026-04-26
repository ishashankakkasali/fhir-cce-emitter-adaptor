package org.openphc.cce.emitter.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.openphc.cce.emitter.service.ForwardResult;
import org.openphc.cce.emitter.service.ForwardingEngine;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for {@link SubscriptionCallbackController}.
 * Uses {@code @WebMvcTest} slice testing with mocked {@link ForwardingEngine}.
 */
@WebMvcTest(SubscriptionCallbackController.class)
class SubscriptionCallbackControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ForwardingEngine forwardingEngine;

    private static final String FHIR_PATIENT_JSON = """
            {
              "resourceType": "Patient",
              "id": "123",
              "name": [{"family": "Smith", "given": ["John"]}]
            }
            """;

    @Nested
    @DisplayName("POST/PUT Callback — Forwarding")
    class CallbackForwarding {

        @Test
        @DisplayName("POST /callback/patient → 200 OK with ApiResponse on success")
        void postCallback_success_returns200WithApiResponse() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("patient"), anyString());
        }

        @Test
        @DisplayName("POST /callback/patient → OpenHIM 500 → always returns 200 OK (always-ACK)")
        void postCallback_openhim500_returnsForwardingError() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.failure(500, "Internal Server Error"));

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /callback/patient → OpenHIM 400 → always returns 200 OK (always-ACK)")
        void postCallback_openhim400_returnsForwardingErrorWith400() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.failure(400, "Bad Request"));

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /callback/patient → OpenHIM unreachable → always returns 200 OK (always-ACK)")
        void postCallback_unreachable_returns502TargetUnreachable() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.unreachable(3));

            mockMvc.perform(post("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());
        }

        @Test
        @DisplayName("POST /callback/unknown-key → still forwards and returns 200")
        void postCallback_unknownKey_stillForwards() throws Exception {
            when(forwardingEngine.forward(eq("unknown-key"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(post("/callback/unknown-key")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("unknown-key"), anyString());
        }

        @Test
        @DisplayName("PUT /callback/patient → 200 OK on success")
        void putCallback_success_returns200() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(put("/callback/patient")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("patient"), anyString());
        }

        @Test
        @DisplayName("PUT /callback/patient/Patient/123 → 200 OK with sub-path")
        void putCallback_withSubPath_returns200() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(put("/callback/patient/Patient/123")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("patient"), anyString());
        }

        @Test
        @DisplayName("POST /callback/encounter/Encounter/456 → 200 OK with sub-path")
        void postCallback_withSubPath_returns200() throws Exception {
            when(forwardingEngine.forward(eq("encounter"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(post("/callback/encounter/Encounter/456")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("encounter"), anyString());
        }

        @Test
        @DisplayName("POST with application/fhir+json content type → 200 OK")
        void postCallback_fhirContentType_returns200() throws Exception {
            when(forwardingEngine.forward(eq("patient"), anyString()))
                    .thenReturn(ForwardResult.success());

            mockMvc.perform(post("/callback/patient")
                            .contentType("application/fhir+json")
                            .content(FHIR_PATIENT_JSON))
                    .andExpect(status().isOk());

            verify(forwardingEngine).forward(eq("patient"), anyString());
        }
    }

    @Nested
    @DisplayName("GET/HEAD Ping — No Forwarding")
    class PingEndpoint {

        @Test
        @DisplayName("GET /callback/patient → 200 OK 'OK' — no forwarding triggered")
        void getCallback_ping_returns200NoForwarding() throws Exception {
            mockMvc.perform(get("/callback/patient"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("OK"));

            verifyNoInteractions(forwardingEngine);
        }

        @Test
        @DisplayName("GET /callback/patient/Patient/123 → 200 OK with sub-path — no forwarding")
        void getCallback_pingWithSubPath_returns200NoForwarding() throws Exception {
            mockMvc.perform(get("/callback/patient/Patient/123"))
                    .andExpect(status().isOk())
                    .andExpect(content().string("OK"));

            verifyNoInteractions(forwardingEngine);
        }
    }
}
