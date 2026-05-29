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

import java.util.List;
import java.util.Map;

/**
 * Enriches FHIR resource JSON by ensuring a national-id is present on the
 * appropriate field for the resource type.
 *
 * <p>Two enrichment strategies based on whether the resource type has a
 * {@code subject} or {@code patient} field in the FHIR R4 spec:
 * <ul>
 *   <li><b>Has {@code subject} field</b> (e.g. Encounter, Observation, ServiceRequest):
 *       sets {@code subject.reference = "Patient/<national-id>"}</li>
 *   <li><b>Has {@code patient} field</b> (e.g. Claim, ExplanationOfBenefit):
 *       sets {@code patient.reference = "Patient/<national-id>"}</li>
 *   <li><b>Neither field</b> (e.g. RelatedPerson, Patient, Location, Organization):
 *       adds an identifier entry
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
    private static final String PRACTITIONER_PREFIX = "Practitioner/";
    private static final String LOCATION_PREFIX = "Location/";
    private static final String ENCOUNTER_PREFIX = "Encounter/";

    /** FHIR R4 fields that hold a Patient reference, checked in priority order — first match wins. */
    private static final List<String> FHIR_R4_PATIENT_REFERENCE_FIELDS = List.of("subject", "patient");

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;
    private final FhirContext fhirContext;
    private final String nationalIdIdentifierSystem;
    private final Map<String, String> practitionerDisplayPathMap;
    private final boolean locationEnrichmentEnabled;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper,
                            FhirContext fhirContext, EmitterProperties properties) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
        this.fhirContext = fhirContext;
        this.nationalIdIdentifierSystem = properties.getReferenceResolution().getNationalIdIdentifierSystem();
        this.practitionerDisplayPathMap = ReferenceResolver.parsePathConfig(properties.getReferenceResolution().getPractitionerDisplayPaths());
        this.locationEnrichmentEnabled = properties.getReferenceResolution().isLocationEnrichmentEnabled();
        log.info("Practitioner display paths configured for: {}", practitionerDisplayPathMap.keySet());
        log.info("Location enrichment enabled: {}", locationEnrichmentEnabled);
    }

    /**
     * Enriches the FHIR JSON by resolving the national-id and placing it on the
     * appropriate field based on the resource type's FHIR R4 definition.
     *
     * <p><b>Processing order (fail-fast):</b>
     * <ol>
     *   <li>Parse JSON and validate structure</li>
     *   <li>Resolve national-id via configured path + FHIR server fetch (expensive — network call)</li>
     *   <li>Check if resource type has a {@code subject} or {@code patient} field in the FHIR R4 spec</li>
     *   <li>If {@code subject} exists: set {@code subject.reference = "Patient/<national-id>"}</li>
     *   <li>If {@code patient} exists: set {@code patient.reference = "Patient/<national-id>"}</li>
     *   <li>If neither: add national-id to {@code identifier[]} array</li>
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

            // Enrich based on whether the resource type has a 'subject' or 'patient' field in the FHIR R4 spec
            String patientReferenceField = getFhirR4PatientReferenceField(resourceType);
            if (patientReferenceField != null) {
                // Set subject.reference or patient.reference = Patient/<national-id>
                String patientReference = PATIENT_PREFIX + nationalId;
                setPatientReference(enrichableIncomingPayload, patientReferenceField, patientReference);
                log.debug("Enriched {} — set {}.reference = {}", resourceType, patientReferenceField, patientReference);
            } else {
                // Add national-id to identifier[] array
                addNationalIdIdentifier(enrichableIncomingPayload, nationalId);
                log.debug("Enriched {} — added national-id identifier: {}", resourceType, nationalId);
            }

            // Enrich practitioner display name if configured for this resource type
            enrichPractitionerDisplay(enrichableIncomingPayload, resourceType);

            // Enrich location details if enabled — resolve from Encounter or enrich display
            if (locationEnrichmentEnabled) {
                enrichLocationDetails(enrichableIncomingPayload, resourceType);
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
     * Determines the FHIR R4 patient reference field ({@code subject} or {@code patient}) for the
     * given resource type. Checks {@link #FHIR_R4_PATIENT_REFERENCE_FIELDS} in priority order —
     * first match wins. Returns {@code null} if no patient reference field exists.
     *
     * @param resourceType FHIR resource type name (e.g. "Encounter", "Claim", "Location")
     * @return the patient reference field name ("subject" or "patient"), or {@code null} if none exist
     * @throws RuntimeException if the resource type is unknown (caught by outer handler)
     */
    private String getFhirR4PatientReferenceField(String resourceType) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        for (String patientReferenceField : FHIR_R4_PATIENT_REFERENCE_FIELDS) {
            if (resourceDef.getChildByName(patientReferenceField) != null) {
                return patientReferenceField;
            }
        }
        return null;
    }

    /**
     * Adds or replaces the reference on the specified patient reference field
     * ({@code subject} or {@code patient}). Creates the field object if it doesn't
     * exist; replaces the reference value if it does.
     *
     * @param enrichableIncomingPayload the JSON object to mutate
     * @param patientReferenceField     the FHIR R4 field name ("subject" or "patient")
     * @param patientReference          the full reference string (e.g. "Patient/1212121212")
     */
    private void setPatientReference(ObjectNode enrichableIncomingPayload, String patientReferenceField, String patientReference) {
        if (enrichableIncomingPayload.has(patientReferenceField) && enrichableIncomingPayload.get(patientReferenceField).isObject()) {
            ((ObjectNode) enrichableIncomingPayload.get(patientReferenceField)).put("reference", patientReference);
        } else {
            enrichableIncomingPayload.putObject(patientReferenceField).put("reference", patientReference);
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

    // ── Practitioner display name enrichment ────────────────────────

    /**
     * Finds the first Practitioner reference at the configured path for this resource type
     * and populates its {@code display} field by fetching the Practitioner name from the FHIR server.
     *
     * <p><b>Processing steps:</b>
     * <ol>
     *   <li>Look up the configured dot-path for this resource type (e.g. "participant.individual")</li>
     *   <li>Delegate to {@link ReferenceResolver#extractPractitionerRefNodeAtPath(JsonNode, String)}
     *       which splits the dot-path into segments and recursively walks the JSON tree
     *       (handling arrays by fan-out) to find the first node with a "Practitioner/" reference</li>
     *   <li>If the node already has a non-blank {@code display} field, skip (do not overwrite)</li>
     *   <li>Extract the Practitioner ID from the reference string (e.g. "12345" from "Practitioner/12345")</li>
     *   <li>Fetch the Practitioner resource from the FHIR server via
     *       {@link ReferenceResolver#fetchPractitionerDisplayName(String)} to resolve the display name</li>
     *   <li>Set the {@code display} field on the reference node (mutates the payload tree in-place)</li>
     * </ol>
     *
     * <p>Skips silently if: no path configured for this resource type, no Practitioner reference
     * found at path, display already present, or FHIR server fetch fails.
     *
     * @param enrichableIncomingPayload the mutable JSON payload tree (same object serialized later)
     * @param resourceType              the FHIR resource type (e.g. "Encounter", "Observation")
     */
    private void enrichPractitionerDisplay(ObjectNode enrichableIncomingPayload, String resourceType) {
        // Step 1: Look up configured path for this resource type (e.g. "Encounter" → "participant.individual")
        String practitionerDisplayPath = practitionerDisplayPathMap.get(resourceType);
        if (practitionerDisplayPath == null) {
            return;
        }

        try {
            // Step 2: Delegate to ReferenceResolver to split dot-path and recursively walk JSON tree to extract Practitioner reference node
            JsonNode practitionerRefNode = referenceResolver.extractPractitionerRefNodeAtPath(
                    enrichableIncomingPayload, practitionerDisplayPath);
            if (practitionerRefNode == null) {
                return;
            }

            // Step 3: Skip if display already present — do not overwrite existing values
            String existingDisplay = practitionerRefNode.path("display").asText(null);
            if (existingDisplay != null && !existingDisplay.isBlank()) {
                return;
            }

            // Step 4: Extract Practitioner ID from reference string (e.g. "Practitioner/12345" → "12345")
            String practitionerId = practitionerRefNode.path("reference").asText().substring(PRACTITIONER_PREFIX.length());

            // Step 5: Fetch Practitioner from FHIR server to resolve human-readable display name
            String practitionerDisplayName = referenceResolver.fetchPractitionerDisplayName(practitionerId);

            // Step 6: Set display on the reference node — mutates the payload tree in-place
            if (practitionerDisplayName != null) {
                ((ObjectNode) practitionerRefNode).put("display", practitionerDisplayName);
                log.debug("Enriched Practitioner/{} with display: {}", practitionerId, practitionerDisplayName);
            }
        } catch (Exception e) {
            log.warn("Practitioner display enrichment failed for {} — forwarding without display: {}",
                    resourceType, e.getMessage());
        }
    }

    // ── Location enrichment ─────────────────────────────────────────

    /**
     * Enriches the resource with location details using the correct FHIR R4 field
     * for the resource type:
     *
     * <ul>
     *   <li><b>Encounter</b>: uses {@code location[]} (BackboneElement with nested
     *       {@code .location} Reference(Location))</li>
     *   <li><b>ServiceRequest</b>: uses {@code locationReference[]} (flat Reference(Location) array)</li>
     *   <li><b>Other resources</b> (Observation, Condition, etc.): no location field in FHIR R4
     *       spec — location enrichment is skipped</li>
     * </ul>
     *
     * <p>Two-phase enrichment:
     * <ol>
     *   <li><b>Resolve from Encounter:</b> If the resource has an {@code encounter} reference
     *       but no location field populated, fetches the Encounter from the FHIR server and
     *       populates the correct location field using the Encounter's location data.</li>
     *   <li><b>Display enrichment:</b> For any location references present (existing or just
     *       added), enriches those missing a {@code display} field by fetching the Location
     *       name from the FHIR server.</li>
     * </ol>
     *
     * @param enrichableIncomingPayload the mutable JSON payload tree
     * @param resourceType              the FHIR resource type (e.g. "ServiceRequest", "Encounter")
     */
    private void enrichLocationDetails(ObjectNode enrichableIncomingPayload, String resourceType) {
        try {
            // Determine the correct FHIR R4 location field for this resource type
            String locationField = getLocationFieldForResourceType(resourceType);
            if (locationField == null) {
                return; // Resource type has no location field in FHIR R4 spec — skip
            }

            boolean isLocationReference = "locationReference".equals(locationField);

            JsonNode locationArray = enrichableIncomingPayload.path(locationField);

            // Step 1: If resource has no location populated but has an encounter reference, resolve from Encounter
            if ((!locationArray.isArray() || locationArray.isEmpty()) && hasEncounterReference(enrichableIncomingPayload)) {
                String encounterRef = enrichableIncomingPayload.path("encounter").path("reference").asText(null);
                if (encounterRef != null && encounterRef.startsWith(ENCOUNTER_PREFIX)) {
                    String encounterId = encounterRef.substring(ENCOUNTER_PREFIX.length());
                    JsonNode encounterLocations = referenceResolver.fetchEncounterLocations(encounterId);
                    if (encounterLocations != null) {
                        if (isLocationReference) {
                            // Convert Encounter's location[].location to flat Reference[] for ServiceRequest
                            ArrayNode flatRefs = convertEncounterLocationsToFlatReferences(encounterLocations);
                            enrichableIncomingPayload.set(locationField, flatRefs);
                        } else {
                            // Copy Encounter's location[] as-is (BackboneElement format)
                            enrichableIncomingPayload.set(locationField, encounterLocations.deepCopy());
                        }
                        locationArray = enrichableIncomingPayload.path(locationField);
                        log.debug("Enriched {} — added {} from Encounter/{}", resourceType, locationField, encounterId);
                    }
                }
            }

            // Step 2: Enrich display on existing location entries
            if (locationArray.isArray()) {
                if (isLocationReference) {
                    enrichFlatLocationReferenceDisplayNames(locationArray);
                } else {
                    enrichNestedLocationDisplayNames(locationArray);
                }
            }

        } catch (Exception e) {
            log.warn("Location enrichment failed for {} — forwarding without location details: {}",
                    resourceType, e.getMessage());
        }
    }

    /**
     * Determines the correct FHIR R4 location field for the given resource type using
     * HAPI FHIR's runtime resource definition.
     *
     * @return {@code "locationReference"} for resources like ServiceRequest,
     *         {@code "location"} for resources like Encounter,
     *         or {@code null} if the resource type has no location field
     */
    private String getLocationFieldForResourceType(String resourceType) {
        RuntimeResourceDefinition resourceDef = fhirContext.getResourceDefinition(resourceType);
        if (resourceDef.getChildByName("locationReference") != null) {
            return "locationReference"; // ServiceRequest, etc.
        }
        if (resourceDef.getChildByName("location") != null) {
            return "location"; // Encounter, etc.
        }
        return null;
    }

    /**
     * Converts Encounter's {@code location[]} (BackboneElement) to a flat {@code Reference[]}
     * suitable for FHIR R4 {@code locationReference} fields (e.g. ServiceRequest).
     *
     * <p>Encounter format: {@code [{"location": {"reference": "Location/123"}, "status": "active"}]}
     * <br>Flat format: {@code [{"reference": "Location/123"}]}
     */
    private ArrayNode convertEncounterLocationsToFlatReferences(JsonNode encounterLocations) {
        ArrayNode flatRefs = objectMapper.createArrayNode();
        for (JsonNode entry : encounterLocations) {
            JsonNode locationRef = entry.path("location");
            if (locationRef.isObject()) {
                flatRefs.add(locationRef.deepCopy());
            }
        }
        return flatRefs;
    }

    /**
     * Checks whether the payload has an {@code encounter} field with a reference.
     */
    private boolean hasEncounterReference(ObjectNode payload) {
        JsonNode encounterNode = payload.path("encounter");
        if (!encounterNode.isObject()) return false;
        String ref = encounterNode.path("reference").asText(null);
        return ref != null && ref.startsWith(ENCOUNTER_PREFIX);
    }


    /**
     * Enriches display names on a flat {@code locationReference[]} array (ServiceRequest style).
     *
     * <p>FHIR R4 ServiceRequest locationReference structure:
     * <pre>
     *   "locationReference": [
     *     { "reference": "Location/123", "display": "Ward A" }
     *   ]
     * </pre>
     *
     * @param locationRefArray the {@code locationReference[]} JSON array node
     */
    private void enrichFlatLocationReferenceDisplayNames(JsonNode locationRefArray) {
        for (JsonNode refNode : locationRefArray) {
            if (!refNode.isObject()) continue;

            String reference = refNode.path("reference").asText(null);
            if (reference == null || !reference.startsWith(LOCATION_PREFIX)) continue;

            // Skip if display already present
            String existingDisplay = refNode.path("display").asText(null);
            if (existingDisplay != null && !existingDisplay.isBlank()) continue;

            // Fetch Location display name from FHIR server
            String locationId = reference.substring(LOCATION_PREFIX.length());
            String displayName = referenceResolver.fetchLocationDisplayName(locationId);
            if (displayName != null) {
                ((ObjectNode) refNode).put("display", displayName);
                log.debug("Enriched Location/{} with display: {}", locationId, displayName);
            }
        }
    }

    /**
     * Enriches display names on a nested {@code location[]} array (Encounter style).
     *
     * <p>FHIR R4 Encounter location structure:
     * <pre>
     *   "location": [
     *     { "location": { "reference": "Location/123" }, "status": "active" }
     *   ]
     * </pre>
     *
     * @param locationArray the {@code location[]} JSON array node
     */
    private void enrichNestedLocationDisplayNames(JsonNode locationArray) {
        for (JsonNode locationEntry : locationArray) {
            JsonNode locationRef = locationEntry.path("location");
            if (!locationRef.isObject()) continue;

            String reference = locationRef.path("reference").asText(null);
            if (reference == null || !reference.startsWith(LOCATION_PREFIX)) continue;

            // Skip if display already present
            String existingDisplay = locationRef.path("display").asText(null);
            if (existingDisplay != null && !existingDisplay.isBlank()) continue;

            // Fetch Location display name from FHIR server
            String locationId = reference.substring(LOCATION_PREFIX.length());
            String displayName = referenceResolver.fetchLocationDisplayName(locationId);
            if (displayName != null) {
                ((ObjectNode) locationRef).put("display", displayName);
                log.debug("Enriched Location/{} with display: {}", locationId, displayName);
            }
        }
    }


}
