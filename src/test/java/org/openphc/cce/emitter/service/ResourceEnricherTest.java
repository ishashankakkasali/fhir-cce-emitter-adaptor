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

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link ResourceEnricher}.
 *
 * <p>Verifies that the enricher delegates national-id resolution to
 * {@link ReferenceResolver} and correctly sets the {@code subject.reference}
 * field on the enriched payload.
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

    // ── Successful enrichment ───────────────────────────────────────

    @Nested
    @DisplayName("Successful enrichment — sets Patient subject from resolved national-id")
    class SuccessfulEnrichment {

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

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
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

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }

        @Test
        @DisplayName("RelatedPerson resource — adds Patient subject from own national-id")
        void relatedPersonAddsSubject() throws Exception {
            String json = """
                    {
                      "resourceType": "RelatedPerson",
                      "id": "499063",
                      "identifier": [
                        {"system": "http://mdtlabs.com/national-id", "value": "1212121212"}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
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

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
            assertEquals("Encounter/499084", root.path("encounter").path("reference").asText());
        }

        @Test
        @DisplayName("Resource without subject — creates subject object")
        void createsSubjectWhenMissing() throws Exception {
            String json = """
                    {
                      "resourceType": "AllergyIntolerance",
                      "id": "allergy-101",
                      "asserter": {"reference": "RelatedPerson/499063"}
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
                    .thenReturn("1212121212");

            String result = enricher.enrichReferences(json);

            assertNotNull(result);
            JsonNode root = objectMapper.readTree(result);
            assertEquals("Patient/1212121212", root.path("subject").path("reference").asText());
        }
    }

    // ── Skipped forwarding (returns null) ───────────────────────────

    @Nested
    @DisplayName("Skipped forwarding — returns null when resolution fails")
    class SkippedForwarding {

        @Test
        @DisplayName("returns null when resolver returns null")
        void returnsNullWhenResolverReturnsNull() {
            String json = """
                    {
                      "resourceType": "Encounter",
                      "id": "499084",
                      "participant": [
                        {"individual": {"reference": "Practitioner/497436"}}
                      ]
                    }
                    """;

            when(referenceResolver.resolveNationalIdFromResource(any(JsonNode.class)))
                    .thenReturn(null);

            String result = enricher.enrichReferences(json);

            assertNull(result, "Should return null when resolver returns null");
        }

        @Test
        @DisplayName("returns null for invalid JSON")
        void returnsNullForInvalidJson() {
            String result = enricher.enrichReferences("not valid json {{{");
            assertNull(result, "Should return null on parse exception");
        }

        @Test
        @DisplayName("returns null for non-object JSON")
        void returnsNullForNonObjectJson() {
            String result = enricher.enrichReferences("[1, 2, 3]");
            assertNull(result, "Should return null when payload is not a JSON object");
        }
    }
}
