package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enriches FHIR resource JSON by ensuring a national-id is present on the
 * appropriate field for the resource type.
 *
 * <p>Two enrichment strategies based on whether the resource type has a
 * {@code subject} field in the FHIR R4 spec:
 * <ul>
 *   <li><b>Has {@code subject} field</b> (e.g. Encounter, Observation, ServiceRequest):
 *       sets {@code subject.reference = "Patient/<national-id>"}</li>
 *   <li><b>No {@code subject} field</b> (e.g. RelatedPerson, Patient, AllergyIntolerance,
 *       Location, Organization): adds an identifier entry
 *       {@code {"system": "<configured-system>", "value": "<national-id>"}}</li>
 * </ul>
 *
 * <p>Delegates national-id resolution to {@link ReferenceResolver}, which handles
 * identity-source detection, path-based reference lookup, FHIR server fetching,
 * and multi-strategy identifier matching. This class is solely responsible for
 * placing the resolved national-id on the enriched payload.
 *
 * <p>If national-id resolution fails for any reason, the resource is forwarded
 * as-is without enrichment (never skipped).
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);
    private static final String PATIENT_PREFIX = "Patient/";
    private static final String SUBJECT_FIELD = "subject";

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;
    private final String nationalIdIdentifierSystem;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper,
                            FhirContext fhirContext, EmitterProperties properties) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
        this.fhirContext = fhirContext;
        this.nationalIdIdentifierSystem = properties.getReferenceResolution().getNationalIdIdentifierSystem();
    }

    /**
     * Enriches the FHIR JSON by resolving the national-id and placing it on the
     * appropriate field based on the resource type's FHIR R4 definition.
     *
     * <p><b>Processing order (fail-fast):</b>
     * <ol>
     *   <li>Parse JSON and validate structure</li>
     *   <li>Resolve national-id via configured path + FHIR server fetch (expensive — network call)</li>
     *   <li>Check if resource type has a {@code subject} field in the FHIR R4 spec</li>
     *   <li>If yes: set {@code subject.reference = "Patient/<national-id>"}</li>
     *   <li>If no: add national-id to {@code identifier[]} array</li>
     * </ol>
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON if national-id resolved, or original JSON as-is if resolution fails;
     *         {@code null} only for structurally invalid payloads (not a JSON object, blank resourceType)
     */
    public String enrichReferences(String resourceJson) {
        try {
            JsonNode incomingPayload = objectMapper.readTree(resourceJson);
            if (!incomingPayload.isObject()) {
                log.error("Payload is not a JSON object — skipping forward");
                return null;
            }

            String resourceType = incomingPayload.path("resourceType").asText("");
            if (resourceType.isBlank()) {
                log.error("No resourceType in payload — skipping forward");
                return null;
            }

            // Resolve national-id (may involve FHIR server fetch for identity-source identifiers)
            String nationalId = referenceResolver.resolveNationalIdFromPayload(incomingPayload);
            if (nationalId == null) {
                log.warn("National-id resolution failed for {} — forwarding as-is without enrichment", resourceType);
                return resourceJson;
            }

            // Cast is safe — guarded by isObject() check above; ObjectNode required for mutation methods
            ObjectNode enrichableIncomingPayload = (ObjectNode) incomingPayload;

            // Enrich based on whether the resource type has a 'subject' field in the FHIR R4 spec
            if (hasFhirR4SubjectField(resourceType)) {
                // Set subject.reference = Patient/<national-id>
                String patientReference = PATIENT_PREFIX + nationalId;
                setSubjectReference(enrichableIncomingPayload, patientReference);
                log.debug("Enriched {} — set subject.reference = {}", resourceType, patientReference);
            } else {
                // Add national-id to identifier[] array
                addNationalIdIdentifier(enrichableIncomingPayload, nationalId);
                log.debug("Enriched {} — added national-id identifier: {}", resourceType, nationalId);
            }

            return objectMapper.writeValueAsString(enrichableIncomingPayload);

        } catch (JsonProcessingException e) {
            log.error("Invalid JSON — skipping forward: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Reference enrichment failed — forwarding as-is: {}", e.getMessage());
            return resourceJson;
        }
    }

    /**
     * Checks whether the given resource type has a {@code subject} field in the
     * FHIR R4 specification.
     *
     * @param resourceType FHIR resource type name (e.g. "Encounter", "RelatedPerson")
     * @return {@code true} if the resource type has a {@code subject} field
     * @throws RuntimeException if the resource type is unknown (caught by outer handler)
     */
    private boolean hasFhirR4SubjectField(String resourceType) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        return resourceDef.getChildByName(SUBJECT_FIELD) != null;
    }

    /**
     * Adds or replaces {@code subject.reference} on the payload. Creates the subject object
     * if it doesn't exist; replaces the reference value if it does.
     *
     * @param enrichableIncomingPayload the JSON object to mutate
     * @param patientReference          the full reference string (e.g. "Patient/1212121212")
     */
    private void setSubjectReference(ObjectNode enrichableIncomingPayload, String patientReference) {
        if (enrichableIncomingPayload.has(SUBJECT_FIELD) && enrichableIncomingPayload.get(SUBJECT_FIELD).isObject()) {
            ((ObjectNode) enrichableIncomingPayload.get(SUBJECT_FIELD)).put("reference", patientReference);
        } else {
            enrichableIncomingPayload.putObject(SUBJECT_FIELD).put("reference", patientReference);
        }
    }

    /**
     * Adds a national-id identifier entry to the payload's {@code identifier[]} array.
     * Creates the array if it doesn't exist; appends to it if it does.
     *
     * @param enrichableIncomingPayload the JSON object to mutate
     * @param nationalId                the national-id value to add
     */
    private void addNationalIdIdentifier(ObjectNode enrichableIncomingPayload, String nationalId) {
        ObjectNode identifierEntry = objectMapper.createObjectNode();
        identifierEntry.put("system", nationalIdIdentifierSystem);
        identifierEntry.put("value", nationalId);

        if (enrichableIncomingPayload.has("identifier") && enrichableIncomingPayload.get("identifier").isArray()) {
            ((ArrayNode) enrichableIncomingPayload.get("identifier")).add(identifierEntry);
        } else {
            enrichableIncomingPayload.putArray("identifier").add(identifierEntry);
        }
    }

}
