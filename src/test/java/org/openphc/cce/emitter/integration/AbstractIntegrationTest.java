package org.openphc.cce.emitter.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base class for integration tests.
 * <p>
 * Provides two WireMock servers (FHIR server + OpenHIM target) with dynamic ports
 * injected into Spring properties via {@code @DynamicPropertySource}.
 * Subclasses get a fully configured Spring context with MockMvc for HTTP testing.
 * <p>
 * WireMock servers are started once (static initializer) and shared across all
 * subclasses to avoid port mismatch with Spring's cached test context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("integration-test")
public abstract class AbstractIntegrationTest {

    protected static final WireMockServer fhirServer;
    protected static final WireMockServer openhimServer;

    static {
        fhirServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        fhirServer.start();

        openhimServer = new WireMockServer(WireMockConfiguration.wireMockConfig().dynamicPort());
        openhimServer.start();
    }

    @Autowired
    protected MockMvc mockMvc;

    @AfterEach
    void resetWireMockStubs() {
        fhirServer.resetAll();
        openhimServer.resetAll();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("emitter.fhir-server.url", () -> fhirServer.baseUrl() + "/fhir");
        registry.add("emitter.openhim.base-url", () -> openhimServer.baseUrl() + "/fhir");
        registry.add("emitter.self-base-url", () -> "http://localhost:9090");
    }

    /** Sample FHIR Patient JSON for testing. */
    protected static final String FHIR_PATIENT_JSON = """
            {
              "resourceType": "Patient",
              "id": "test-123",
              "meta": {
                "versionId": "1",
                "lastUpdated": "2025-01-15T10:30:00Z"
              },
              "name": [{"family": "Smith", "given": ["John"]}],
              "gender": "male",
              "birthDate": "1990-01-15"
            }
            """;

    /** Sample FHIR Encounter JSON for testing. */
    protected static final String FHIR_ENCOUNTER_JSON = """
            {
              "resourceType": "Encounter",
              "id": "enc-456",
              "status": "finished",
              "class": {
                "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code": "AMB"
              },
              "subject": {"reference": "Patient/test-123"}
            }
            """;

    /** Malformed JSON that is not a valid FHIR resource. */
    protected static final String MALFORMED_JSON = """
            {"not": "a fhir resource", "random": true}
            """;
}
