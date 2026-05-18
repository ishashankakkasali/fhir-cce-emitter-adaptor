package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceEnricher}.
 *
 * <p>Verifies the path-based RelatedPerson lookup and patient subject resolution:
 * <ul>
 *   <li>Encounter with Patient subject + RelatedPerson participant → resolve national-id</li>
 *   <li>Encounter with non-Patient subject (Group) + RelatedPerson participant → add Patient subject</li>
 *   <li>RelatedPerson resource → extract national-id from own identifiers, add Patient subject</li>
 *   <li>Resource with non-Patient subject and no RelatedPerson at configured path → skip</li>
 *   <li>Resource type with no configured path → skip with error</li>
 *   <li>Identity resources (Patient, Practitioner) without configured path → skip</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ResourceEnricherTest {

    @Mock
    private ReferenceResolver referenceResolver;

    private ObjectMapper objectMapper;
    private ResourceEnricher enricher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();

        EmitterProperties props = new EmitterProperties();
        EmitterProperties.ReferenceResolutionConfig refConfig = new EmitterProperties.ReferenceResolutionConfig();
        refConfig.setRelatedPersonPaths(List.of(
                "Encounter:participant.individual.reference",
                "ServiceRequest:performer.reference",
                "Observation:performer.reference",
                "Person:link.other.reference",
                "Condition:participant.individual.reference",
                "MedicationRequest:performer.reference",
                "MedicationStatement:informationSource.reference",
                "AllergyIntolerance:asserter.reference"
        ));
        props.setReferenceResolution(refConfig);

        enricher = new ResourceEnricher(referenceResolver, objectMapper, props);
    }

    // ── Case 1: Patient subject + RelatedPerson participant ─────────

    @Nested
    @DisplayName("Case 1: Patient subject exists with RelatedPerson participant")
    class PatientSubjectWithRelatedPerson {

        @Test
        @DisplayName("resolves Patient subject from RelatedPerson's national-id")
        void resolvesPatientSubjectFromRelatedPerson() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "subject": {"reference": "Patient/616"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}},
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("skips forwarding when RelatedPerson national-id resolution fails")
        void skipsWhenRelatedPersonResolutionFails() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "subject": {"reference": "Patient/616"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should skip when RelatedPerson national-id resolution fails");
        }

        @Test
        @DisplayName("resolves Patient subject for ServiceRequest with RelatedPerson in performer")
        void resolvesSubjectForServiceRequest() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "499114",
                      "subject": {"reference": "Patient/616"},
                      "encounter": {"reference": "Encounter/499084"},
                      "performer": [
                        {"reference": "RelatedPerson/499063"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
            // Other references (Encounter, RelatedPerson in performer) left unchanged
            assertEquals("Encounter/499084", root.path("encounter").path("reference").asText());
        }
    }

    // ── Case 2: Non-Patient subject + RelatedPerson participant ─────

    @Nested
    @DisplayName("Case 2: Non-Patient subject (Group) with RelatedPerson participant")
    class NonPatientSubjectWithRelatedPerson {

        @Test
        @DisplayName("adds Patient subject from RelatedPerson's national-id (Encounter with Group subject)")
        void addsPatientSubjectFromRelatedPerson() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499066",
                      "subject": {"reference": "Group/498166"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}},
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("skips forwarding when RelatedPerson resolution fails (non-Patient subject)")
        void skipsWhenRelatedPersonResolutionFails() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499066",
                      "subject": {"reference": "Group/498166"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null (skip) when RelatedPerson resolution fails");
        }
    }

    // ── Case 3: Non-Patient subject, no RelatedPerson at path → skip ──

    @Nested
    @DisplayName("Case 3: Non-Patient subject, no RelatedPerson at configured path → skip")
    class NonPatientSubjectNoRelatedPerson {

        @Test
        @DisplayName("skips forwarding when subject is Group and no RelatedPerson at configured path")
        void skipsWhenNoRelatedPersonAndNonPatientSubject() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499066",
                      "subject": {"reference": "Group/498166"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null (skip) when no RelatedPerson at configured path");
        }
    }

    // ── Case A: RelatedPerson resource → extract from own identifiers ─

    @Nested
    @DisplayName("Case A: RelatedPerson resource — extract national-id from own identifiers")
    class RelatedPersonResource {

        @Test
        @DisplayName("extracts national-id from own identifiers and adds Patient subject")
        void extractsNationalIdFromOwnIdentifiers() throws Exception {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://mdtlabs.com/patient-id", "value": "07006101342552"},
                        {"system": "http://mdtlabs.com/national-id", "value": "1212121212"}
                      ],
                      "name": [{"text": "Ashwariya"}]
                    }
                    """;

            when(referenceResolver.extractNationalIdFromIdentifiers(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("skips forwarding when RelatedPerson has no national-id")
        void skipsWhenNoNationalId() {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://mdtlabs.com/village-id", "value": "312"}
                      ]
                    }
                    """;

            when(referenceResolver.extractNationalIdFromIdentifiers(any(JsonNode.class)))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null (skip) when RelatedPerson has no national-id");
        }
    }

    // ── Unconfigured resource type → skip with error ──────────────

    @Nested
    @DisplayName("Unconfigured resource type — skipped with error")
    class UnconfiguredResourceType {

        @Test
        @DisplayName("Patient resource skipped — no path configured")
        void patientResourceSkipped() {
            String json = """
                    {
                      "resourceType": "Patient",
                      "id": "test-123",
                      "name": [{"family": "Smith", "given": ["John"]}],
                      "gender": "male"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Patient should be skipped when no related-person-path configured");
        }

        @Test
        @DisplayName("Organization resource skipped — no path configured")
        void organizationResourceSkipped() {
            String json = """
                    {
                      "resourceType": "Organization",
                      "id": "14",
                      "name": "Test Hospital"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Organization should be skipped when no related-person-path configured");
        }

        @Test
        @DisplayName("Practitioner resource skipped — no path configured")
        void practitionerResourceSkipped() {
            String json = """
                    {
                      "resourceType": "Practitioner",
                      "id": "497436",
                      "name": [{"text": "Dr. Test"}]
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Practitioner should be skipped when no related-person-path configured");
        }
    }

    // ── Patient subject, no RelatedPerson at path → skip ─────────

    @Nested
    @DisplayName("Patient subject exists but no RelatedPerson at configured path → skip")
    class PatientSubjectNoRelatedPerson {

        @Test
        @DisplayName("Patient subject with no RelatedPerson at path — skipped")
        void skipsWhenNoRelatedPersonAtPath() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-456",
                      "subject": {"reference": "Patient/test-123"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/497436"}}
                      ],
                      "status": "finished"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should skip when no RelatedPerson found at configured path");
        }
    }

    // ── ServiceRequest with performer containing RelatedPerson ──────

    @Nested
    @DisplayName("ServiceRequest with RelatedPerson in performer[]")
    class ServiceRequestWithRelatedPerson {

        @Test
        @DisplayName("finds RelatedPerson in performer[] and resolves Patient subject")
        void findsRelatedPersonInPerformer() throws Exception {
            String json = """
                    {
                      "resourceType": "ServiceRequest",
                      "id": "499114",
                      "subject": {"reference": "Patient/616"},
                      "performer": [
                        {"reference": "Practitioner/497436"},
                        {"reference": "RelatedPerson/499063"},
                        {"reference": "Organization/14"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }
    }

    // ── RelatedPerson found via configured paths ─────────────────

    @Nested
    @DisplayName("RelatedPerson found via configured path for various resource types")
    class RelatedPersonViaConfiguredPaths {

        @Test
        @DisplayName("finds RelatedPerson in MedicationStatement.informationSource via configured path")
        void findsRelatedPersonInInformationSource() throws Exception {
            String json = """
                    {
                      "resourceType": "MedicationStatement",
                      "id": "med-123",
                      "subject": {"reference": "Patient/616"},
                      "informationSource": {"reference": "RelatedPerson/499063"}
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("finds RelatedPerson in Condition.participant.individual via configured path")
        void findsRelatedPersonInConditionParticipant() throws Exception {
            String json = """
                    {
                      "resourceType": "Condition",
                      "id": "cond-456",
                      "subject": {"reference": "Group/498166"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("finds RelatedPerson in AllergyIntolerance.asserter via configured path (no subject)")
        void findsRelatedPersonInAsserterNoSubject() throws Exception {
            String json = """
                    {
                      "resourceType": "AllergyIntolerance",
                      "id": "allergy-101",
                      "asserter": {"reference": "RelatedPerson/499063"}
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("finds RelatedPerson in Person.link.other via configured path")
        void findsRelatedPersonInPersonLink() throws Exception {
            String json = """
                    {
                      "resourceType": "Person",
                      "id": "person-001",
                      "link": [
                        {"other": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalId(eq("RelatedPerson"), eq("499063")))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }
    }

    // ── Fail-safe behavior ──────────────────────────────────────────

    @Nested
    @DisplayName("Fail-safe: returns null (skip) on exception")
    class FailSafe {

        @Test
        @DisplayName("returns null when enrichment throws exception")
        void returnsNullOnException() {
            String invalidJson = "not valid json {{{";

            String result = enricher.enrichReferences(invalidJson);

            assertNull(result, "Should return null (skip) on parse exception");
        }
    }
}
