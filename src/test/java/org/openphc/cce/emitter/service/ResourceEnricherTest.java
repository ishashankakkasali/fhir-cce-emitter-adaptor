package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceEnricher}.
 *
 * <p>Verifies that the enricher delegates national-id resolution to
 * {@link ReferenceResolver} and correctly enriches the payload based on the
 * resource type's FHIR R4 definition:
 * <ul>
 *   <li>Has {@code subject} field → sets {@code subject.reference = "Patient/<national-id>"}</li>
 *   <li>No {@code subject} field → adds national-id to {@code identifier[]} array</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class ResourceEnricherTest {

    @Mock
    private ReferenceResolver referenceResolver;

    private ObjectMapper objectMapper;
    private FhirContext fhirContext;
    private ResourceEnricher enricher;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fhirContext = FhirContext.forR4();

        EmitterProperties properties = new EmitterProperties();
        properties.setReferenceResolution(new EmitterProperties.ReferenceResolutionConfig());

        // extractPractitionerNodeAtPath is pure JSON logic — delegate to real implementation
        lenient().when(referenceResolver.extractPractitionerRefNodeAtPath(any(), any())).thenCallRealMethod();

        enricher = new ResourceEnricher(referenceResolver, objectMapper, fhirContext, properties);
    }

    // ── Successful enrichment — subject reference ───────────────────

    @Nested
    @DisplayName("Subject enrichment — sets Patient subject from resolved national-id")
    class SubjectEnrichment {

        @Test
        @DisplayName("Encounter with existing subject — overwrites with resolved national-id")
        void overwritesExistingSubject() throws Exception {
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

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("Encounter with Group subject — overwrites with Patient subject")
        void overwritesNonPatientSubject() throws Exception {
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

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("Encounter without existing subject — creates subject with resolved national-id")
        void createsSubjectWhenMissing() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "enc-no-subject",
                      "status": "finished",
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499063"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("ServiceRequest — sets Patient subject, leaves other references unchanged")
        void serviceRequestPreservesOtherReferences() throws Exception {
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

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
            assertEquals("Encounter/499084", root.path("encounter").path("reference").asText());
        }
    }

    // ── Enrichment — patient.reference or identifier[] ────────────────

    @Nested
    @DisplayName("Enrichment — patient.reference for resources with 'patient' field, identifier[] for resources without subject/patient")
    class IdentifierEnrichment {

        @Test
        @DisplayName("RelatedPerson — sets patient.reference (has 'patient' field in FHIR R4)")
        void relatedPersonSetsPatientReference() throws Exception {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://mdtlabs.com/national-id", "value": "1212121212"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            // Should set patient.reference = Patient/<national-id>
            assertEquals("Patient/1212121212", root.path("patient").path("reference").asText());
            // Original identifier[] should remain unchanged (1 entry)
            assertTrue(root.path("identifier").isArray());
            assertEquals(1, root.path("identifier").size());
        }

        @Test
        @DisplayName("AllergyIntolerance — sets patient.reference (has 'patient' field in FHIR R4)")
        void allergyIntoleranceSetsPatientReference() throws Exception {
            String json = """
                    {
                      "resourceType": "AllergyIntolerance",
                      "id": "allergy-101",
                      "asserter": {"reference": "RelatedPerson/499063"}
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            // Should set patient.reference = Patient/<national-id>
            assertEquals("Patient/1212121212", root.path("patient").path("reference").asText());
            // Should NOT have identifier[] enrichment
            assertTrue(root.path("identifier").isMissingNode());
        }

        @Test
        @DisplayName("Patient — adds national-id to identifier[] (no subject field in FHIR R4)")
        void patientAddsNationalIdIdentifier() throws Exception {
            String json = """
                    {
                      "resourceType": "Patient",
                      "id": "500861",
                      "identifier": [
                        {"system": "http://mdtlabs.com/some-id", "value": "existing-id"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("9999999999");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("identifier").isArray());
            assertEquals(2, root.path("identifier").size());
            JsonNode enrichedIdentifier = root.path("identifier").get(1);
            assertEquals("http://openphc.org/identifier/upid", enrichedIdentifier.path("system").asText());
            assertEquals("9999999999", enrichedIdentifier.path("value").asText());
        }

        @Test
        @DisplayName("Resource with no existing identifier[] — creates array with national-id entry")
        void createsIdentifierArrayWhenMissing() throws Exception {
            String json = """
                    {
                      "resourceType": "Location",
                      "id": "loc-no-ids"
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("5555555555");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("identifier").isArray());
            assertEquals(1, root.path("identifier").size());
            JsonNode enrichedIdentifier = root.path("identifier").get(0);
            assertEquals("http://openphc.org/identifier/upid", enrichedIdentifier.path("system").asText());
            assertEquals("5555555555", enrichedIdentifier.path("value").asText());
        }
    }

    // ── Forward as-is (resolver returns null) ───────────────────────

    @Nested
    @DisplayName("Forward as-is — returns original JSON when resolution fails")
    class ForwardAsIs {

        @Test
        @DisplayName("forwards as-is when resolver returns null (resource with subject)")
        void forwardsAsIsWhenResolverReturnsNull() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "participant": [
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Should forward as-is when resolver returns null");
            assertEquals(json, result, "Should return original JSON unchanged");
        }

        @Test
        @DisplayName("forwards as-is when resolver returns null (resource without subject)")
        void forwardsAsIsWhenResolverReturnsNullForNonSubjectResource() {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063"
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNotNull(result, "Should forward as-is when resolver returns null");
            assertEquals(json, result, "Should return original JSON unchanged");
        }

        @Test
        @DisplayName("forwards as-is for invalid JSON")
        void forwardsAsIsForInvalidJson() {
            String json = "not valid json {{{";
            String result = enricher.enrichReferences(json);
            assertNull(result, "Should return null on parse exception — not a JSON object");
        }

        @Test
        @DisplayName("returns null for non-object JSON")
        void returnsNullForNonObjectJson() {
            String result = enricher.enrichReferences("[1, 2, 3]");
            assertNull(result, "Should return null when payload is not a JSON object");
        }

        @Test
        @DisplayName("returns null for payload with blank resourceType")
        void returnsNullForBlankResourceType() {
            String json = """
                    {
                      "resourceType": "",
                      "id": "123"
                    }
                    """;

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null when resourceType is blank");
            verifyNoInteractions(referenceResolver);
        }
    }

    // ── Practitioner display enrichment ─────────────────────────────

    @Nested
    @DisplayName("Practitioner display enrichment — fetches and sets display name on Practitioner references")
    class PractitionerDisplayEnrichment {

        @Test
        @DisplayName("Encounter with Practitioner participant — enriches display field")
        void enrichesPractitionerDisplayOnEncounter() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(referenceResolver.fetchPractitionerDisplayName("12345"))
                    .thenReturn("Dr. Aziz Muhammed");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Dr. Aziz Muhammed",
                    root.path("participant").get(0).path("individual").path("display").asText());
        }

        @Test
        @DisplayName("Encounter with RelatedPerson participant — no display enrichment (not Practitioner)")
        void skipsNonPractitionerReference() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499291"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("participant").get(0).path("individual").path("display").isMissingNode(),
                    "Should not add display for non-Practitioner reference");
        }

        @Test
        @DisplayName("Encounter with existing display — does not overwrite")
        void doesNotOverwriteExistingDisplay() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345", "display": "Existing Name"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Existing Name",
                    root.path("participant").get(0).path("individual").path("display").asText(),
                    "Should not overwrite existing display");
            verify(referenceResolver, never()).fetchPractitionerDisplayName(anyString());
        }

        @Test
        @DisplayName("Encounter with multiple participants — only enriches Practitioner")
        void enrichesOnlyPractitionerInMixedParticipants() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "RelatedPerson/499291"}},
                        {"individual": {"reference": "Practitioner/12345"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(referenceResolver.fetchPractitionerDisplayName("12345"))
                    .thenReturn("Dr. Aziz Muhammed");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            // First participant (RelatedPerson) — no display
            assertTrue(root.path("participant").get(0).path("individual").path("display").isMissingNode());
            // Second participant (Practitioner) — display enriched
            assertEquals("Dr. Aziz Muhammed",
                    root.path("participant").get(1).path("individual").path("display").asText());
        }

        @Test
        @DisplayName("Observation with Practitioner performer — enriches display")
        void enrichesPractitionerDisplayOnObservation() throws Exception {
            String json = """
                    {
                      "resourceType": "Observation",
                      "id": "obs-123",
                      "subject": {"reference": "Patient/499304"},
                      "performer": [
                        {"reference": "Practitioner/67890"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(referenceResolver.fetchPractitionerDisplayName("67890"))
                    .thenReturn("Dr. Jean");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Dr. Jean",
                    root.path("performer").get(0).path("display").asText());
        }

        @Test
        @DisplayName("Condition with Practitioner asserter — enriches display")
        void enrichesPractitionerDisplayOnCondition() throws Exception {
            String json = """
                    {
                      "resourceType": "Condition",
                      "id": "cond-456",
                      "subject": {"reference": "Patient/499304"},
                      "asserter": {"reference": "Practitioner/11111"}
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(referenceResolver.fetchPractitionerDisplayName("11111"))
                    .thenReturn("Dr. Jean");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Dr. Jean",
                    root.path("asserter").path("display").asText());
        }

        @Test
        @DisplayName("Practitioner fetch fails — forwards without display (graceful degradation)")
        void forwardsWithoutDisplayOnFetchFailure() throws Exception {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "502969",
                      "subject": {"reference": "Patient/499304"},
                      "participant": [
                        {"individual": {"reference": "Practitioner/12345"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");
            when(referenceResolver.fetchPractitionerDisplayName("12345"))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertTrue(root.path("participant").get(0).path("individual").path("display").isMissingNode(),
                    "Should not add display when fetch returns null");
        }

        @Test
        @DisplayName("Resource type not in practitioner-display-paths — no enrichment attempt")
        void skipsResourceTypeWithoutPractitionerPath() throws Exception {
            String json = """
                    {
                      "resourceType": "MedicationRequest",
                      "id": "mr-789",
                      "subject": {"reference": "Patient/499304"},
                      "performer": [
                        {"reference": "Practitioner/12345"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromPayload(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            verify(referenceResolver, never()).fetchPractitionerDisplayName(anyString());
        }
    }
}
