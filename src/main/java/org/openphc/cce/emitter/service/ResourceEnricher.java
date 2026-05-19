package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.RuntimeResourceDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enriches FHIR resource JSON by ensuring a {@code Patient/<national-id>}
 * reference is present on the appropriate field for the resource type.
 *
 * <p>Delegates national-id resolution to {@link ReferenceResolver}, which handles
 * identity-source detection, path-based reference lookup, FHIR server fetching,
 * and multi-strategy identifier matching. This class is solely responsible for
 * setting the patient reference field on the enriched payload.
 *
 * <p>The correct reference field is determined dynamically using HAPI FHIR's
 * {@link FhirContext} resource definitions — mirroring the reflection-based
 * approach used downstream. For most clinical resources (Encounter, Observation,
 * ServiceRequest, etc.) the field is {@code subject}; for resources like
 * RelatedPerson it is {@code patient}.
 *
 * <p>If national-id resolution fails for any reason, forwarding is skipped
 * (returns {@code null}).
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);
    private static final String PATIENT_PREFIX = "Patient/";

    /** Reference field names to try, in priority order (mirrors PatientIdExtractor). */
    private static final String[] PATIENT_REFERENCE_FIELDS = {"subject", "patient"};

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper,
                            FhirContext fhirContext) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
        this.fhirContext = fhirContext;
    }

    /**
     * Enriches the FHIR JSON by resolving the national-id and setting the
     * appropriate patient reference field (e.g. {@code subject.reference} or
     * {@code patient.reference}) based on the resource type.
     *
     * <p><b>Processing order (fail-fast):</b>
     * <ol>
     *   <li>Parse JSON and validate structure</li>
     *   <li>Resolve patient reference field name from FHIR R4 definition (cheap — metadata only);
     *       if the resource type has neither {@code subject} nor {@code patient} in the FHIR R4 spec, forward as-is without enrichment</li>
     *   <li>Resolve national-id via configured path + FHIR server fetch (expensive — network call)</li>
     *   <li>Set the patient reference on the enriched payload</li>
     * </ol>
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON, original JSON (if resource type has no patient reference field in the FHIR R4 spec),
     *         or {@code null} if forwarding should be skipped
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

            // Early exit: if resource type has no subject/patient field in the FHIR R4 spec (e.g. Patient, Location,
            // Organization, Practitioner, Device, Provenance), forward as-is without the expensive FHIR server call
            String patientReferenceFieldName = resolvePatientReferenceField(resourceType);
            if (patientReferenceFieldName == null) {
                log.warn("No subject or patient field on {} — forwarding as-is without enrichment", resourceType);
                return resourceJson;
            }

            // Resolve national-id (expensive — may involve FHIR server fetch for RelatedPerson identifiers)
            String nationalId = referenceResolver.resolveNationalIdFromPayload(incomingPayload);
            if (nationalId == null) {
                return null;
            }

            // Enrich: set the patient reference on the resolved field
            String patientReference = PATIENT_PREFIX + nationalId;
            // Cast is safe — guarded by isObject() check above; ObjectNode required for mutation methods
            setPatientReference((ObjectNode) incomingPayload, patientReferenceFieldName, patientReference);

            log.debug("Enriched {} — set {}.reference = {}", resourceType, patientReferenceFieldName, patientReference);
            return objectMapper.writeValueAsString(incomingPayload);

        } catch (Exception e) {
            log.error("Reference enrichment failed — skipping forward: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolves which JSON field name to use for the patient reference on the given
     * resource type, using HAPI FHIR's resource definition to check which fields exist.
     *
     * <p>Tries {@code "subject"} first, then {@code "patient"} — same priority order
     * as the downstream PatientIdExtractor. Returns {@code null} if neither field
     * exists on the resource type in the FHIR R4 spec (e.g. Patient, Location, Organization, Practitioner,
     * Device, Provenance) — the caller forwards the payload as-is without enrichment.
     *
     * @param resourceType FHIR resource type name (e.g. "Encounter", "RelatedPerson")
     * @return the field name to set (e.g. "subject" or "patient"), or {@code null} if
     *         the resource type has neither field
     */
    private String resolvePatientReferenceField(String resourceType) {
        try {
            RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
            for (String fieldName : PATIENT_REFERENCE_FIELDS) {
                if (resourceDef.getChildByName(fieldName) != null) {
                    return fieldName;
                }
            }
        } catch (Exception e) {
            log.error("Could not resolve resource definition for '{}': {} — skipping forward",
                    resourceType, e.getMessage());
        }
        return null;
    }

    /**
     * Sets the patient reference value on the specified field. Creates the field object
     * if it doesn't exist, or updates the existing one preserving other sibling fields.
     *
     * @param incomingPayload       the JSON object to mutate
     * @param patientReferenceFieldName the field name (e.g. "subject" or "patient")
     * @param patientReference     the full reference string (e.g. "Patient/1212121212")
     */
    private void setPatientReference(ObjectNode incomingPayload, String patientReferenceFieldName, String patientReference) {
        if (incomingPayload.has(patientReferenceFieldName) && incomingPayload.get(patientReferenceFieldName).isObject()) {
            ((ObjectNode) incomingPayload.get(patientReferenceFieldName)).put("reference", patientReference);
        } else {
            incomingPayload.putObject(patientReferenceFieldName).put("reference", patientReference);
        }
    }

}
