package org.openphc.cce.emitter.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

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

    /**
     * Stubs the FHIR server to return a RelatedPerson with a national-id
     * for any RelatedPerson read request. This is needed because the path-based
     * enricher requires every resource to resolve a national-id from a
     * RelatedPerson before forwarding.
     */
    @BeforeEach
    void stubFhirServerRelatedPerson() {
        // Stub FHIR metadata endpoint (HAPI FHIR client calls this before any read)
        fhirServer.stubFor(WireMock.get(urlPathEqualTo("/fhir/metadata"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody("""
                                {"resourceType": "CapabilityStatement", "status": "active", "fhirVersion": "4.0.1"}
                                """)));
        fhirServer.stubFor(WireMock.get(urlPathMatching("/fhir/RelatedPerson/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody(FHIR_RELATED_PERSON_RESPONSE)));
        fhirServer.stubFor(WireMock.get(urlPathMatching("/fhir/Patient/.*"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/fhir+json")
                        .withBody(FHIR_PATIENT_RESPONSE)));
    }

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

    /** Sample FHIR Encounter JSON for testing (includes RelatedPerson in participant for path-based lookup). */
    protected static final String FHIR_ENCOUNTER_JSON = """
            {
              "resourceType": "Encounter",
              "id": "enc-456",
              "status": "finished",
              "class": {
                "system": "http://terminology.hl7.org/CodeSystem/v3-ActCode",
                "code": "AMB"
              },
              "subject": {"reference": "Patient/test-123"},
              "participant": [
                {"individual": {"reference": "RelatedPerson/rp-001"}}
              ]
            }
            """;

    /** Malformed JSON that is not a valid FHIR resource. */
    protected static final String MALFORMED_JSON = """
            {"not": "a fhir resource", "random": true}
            """;

    /** FHIR RelatedPerson response from WireMock FHIR server (has national-id for enrichment). */
    protected static final String FHIR_RELATED_PERSON_RESPONSE = """
            {
              "resourceType": "RelatedPerson",
              "id": "rp-001",
              "identifier": [
                {"system": "http://mdtlabs.com/national-id", "value": "NID-12345"}
              ]
            }
            """;

    /** FHIR Patient response from WireMock FHIR server (has national-id for reference resolution). */
    protected static final String FHIR_PATIENT_RESPONSE = """
            {
              "resourceType": "Patient",
              "id": "test-123",
              "identifier": [
                {"system": "http://mdtlabs.com/national-id", "value": "NID-12345"}
              ]
            }
            """;
}
