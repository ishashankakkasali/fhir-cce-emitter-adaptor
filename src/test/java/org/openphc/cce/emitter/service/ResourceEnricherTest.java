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

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceEnricher}.
 *
 * <p>Verifies patient subject resolution (Phase 1) and standard reference
 * enrichment (Phase 2) across the RMNCH payload patterns:
 * <ul>
 *   <li>Encounter with Patient subject + RelatedPerson participant → resolve national-id from RelatedPerson</li>
 *   <li>Encounter with non-Patient subject (Group) + RelatedPerson participant → add Patient subject</li>
 *   <li>RelatedPerson resource → extract national-id from own identifiers, add Patient subject</li>
 *   <li>Resource with non-Patient subject and no RelatedPerson → skip forwarding</li>
 *   <li>Identity resources (Patient, Practitioner, etc.) without subject → forward as-is</li>
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
        enricher = new ResourceEnricher(referenceResolver, objectMapper);
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("falls through to standard enrichment when RelatedPerson resolution fails")
        void fallsThroughWhenRelatedPersonResolutionFails() throws Exception {
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            // Subject unchanged since resolution failed
            assertEquals("Patient/616", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("pre-populates cache so tree walk doesn't re-fetch Patient reference")
        void prePopulatesCacheForTreeWalk() throws Exception {
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());

            // resolveNationalIdDirect called once (for RelatedPerson),
            // resolveNationalId NOT called for Patient (pre-populated cache)
            verify(referenceResolver, times(1)).resolveNationalIdDirect(anyString(), anyString(), anyMap());
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null (skip) when RelatedPerson resolution fails");
        }
    }

    // ── Case 3: Non-Patient subject, no RelatedPerson → skip ────────

    @Nested
    @DisplayName("Case 3: Non-Patient subject, no RelatedPerson → skip")
    class NonPatientSubjectNoRelatedPerson {

        @Test
        @DisplayName("skips forwarding when subject is Group and no RelatedPerson reference exists")
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

            assertNull(result, "Should return null (skip) when no Patient subject and no RelatedPerson");
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

    // ── Identity resources (no subject field) → forward as-is ───────

    @Nested
    @DisplayName("Identity resources without subject field — forward as-is")
    class IdentityResources {

        @Test
        @DisplayName("Patient resource without subject is forwarded as-is (not skipped)")
        void patientResourceForwardedAsIs() throws Exception {
            String json = """
                    {
                      "resourceType": "Patient",
                      "id": "test-123",
                      "name": [{"family": "Smith", "given": ["John"]}],
                      "gender": "male"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Patient resource should not be skipped");
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient", root.path("resourceType").asText());
        }

        @Test
        @DisplayName("Organization resource without subject is forwarded as-is")
        void organizationResourceForwardedAsIs() throws Exception {
            String json = """
                    {
                      "resourceType": "Organization",
                      "id": "14",
                      "name": "Test Hospital"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Organization resource should not be skipped");
        }

        @Test
        @DisplayName("Practitioner resource without subject is forwarded as-is")
        void practitionerResourceForwardedAsIs() throws Exception {
            String json = """
                    {
                      "resourceType": "Practitioner",
                      "id": "497436",
                      "name": [{"text": "Dr. Test"}]
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Practitioner resource should not be skipped");
        }
    }

    // ── Patient subject, no RelatedPerson → standard enrichment ─────

    @Nested
    @DisplayName("Patient subject exists but no RelatedPerson — standard enrichment")
    class PatientSubjectNoRelatedPerson {

        @Test
        @DisplayName("Patient subject with no RelatedPerson uses standard enrichment")
        void standardEnrichmentWhenNoRelatedPerson() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-456",
                      "subject": {"reference": "Patient/test-123"},
                      "status": "finished"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Should not be skipped when Patient subject exists");
            JsonNode root = objectMapper.readTree(result);
            // Subject remains as-is since no RelatedPerson and standard enrichment
            // depends on referenceResolver.resolveNationalId (for tree walk)
            assertEquals("Patient/test-123", root.path("subject").path("reference").asText());
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

            when(referenceResolver.resolveNationalIdDirect(eq("RelatedPerson"), eq("499063"), anyMap()))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }
    }

    // ── Fail-safe behavior ──────────────────────────────────────────

    @Nested
    @DisplayName("Fail-safe: returns original JSON on exception")
    class FailSafe {

        @Test
        @DisplayName("returns original JSON when enrichment throws exception")
        void returnsOriginalOnException() {
            String invalidJson = "not valid json {{{";

            // Should not throw — returns original JSON
            String result = enricher.enrichReferences(invalidJson);

            assertEquals(invalidJson, result);
        }
    }
}
