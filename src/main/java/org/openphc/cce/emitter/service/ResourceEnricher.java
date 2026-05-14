package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Enriches FHIR resource JSON by ensuring a Patient subject reference with a
 * national-id is present, and by resolving HAPI internal numeric IDs in
 * configured reference types to their national-id identifiers.
 *
 * <h3>Patient subject resolution (Phase 1)</h3>
 * <p>For each incoming callback, the enricher applies the following rules:
 * <ol>
 *   <li>If {@code subject.reference} is {@code "Patient/<fhirId>"} and a
 *       {@code RelatedPerson} reference exists in the payload — resolves
 *       the national-id from the RelatedPerson and replaces the Patient
 *       subject reference with {@code "Patient/<national-id>"}.</li>
 *   <li>If no Patient subject exists but a {@code RelatedPerson} reference
 *       is found — resolves the national-id from the RelatedPerson and
 *       adds {@code subject.reference = "Patient/<national-id>"}.</li>
 *   <li>If the resource type IS {@code RelatedPerson} — extracts the
 *       national-id from its own identifiers and adds a Patient subject.</li>
 *   <li>If neither a Patient subject nor a RelatedPerson reference exists —
 *       returns {@code null} to signal that forwarding should be skipped.</li>
 * </ol>
 *
 * <h3>Standard reference enrichment (Phase 2)</h3>
 * <p>After patient subject resolution, the enricher walks the full JSON tree
 * and replaces every {@code reference} field whose type is in the configured
 * {@code resolvable-types} with the national-id equivalent.
 *
 * <p>This is a fail-safe operation — any exception during enrichment is logged
 * and the original JSON is returned so that forwarding to OpenHIM is never blocked.
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches the FHIR JSON by ensuring a Patient subject with national-id
     * is present, then resolves other resolvable references.
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON, the original JSON on failure, or {@code null}
     *         if forwarding should be skipped (no Patient subject or
     *         RelatedPerson reference found)
     */
    public String enrichReferences(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            Map<String, String> requestCache = new HashMap<>();

            if (root.isObject()) {
                ObjectNode rootObj = (ObjectNode) root;

                // Phase 1: Ensure Patient subject reference with national-id
                boolean shouldSkip = ensurePatientSubject(rootObj, requestCache);
                if (shouldSkip) {
                    return null;
                }

                // Phase 2: Standard reference enrichment (tree walk for resolvable types)
                enrichNode(rootObj, requestCache);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Reference enrichment failed — forwarding original resource: {}", e.getMessage());
            return resourceJson;
        }
    }

    /**
     * Ensures the payload has a {@code subject.reference} pointing to
     * {@code Patient/<national-id>}.
     *
     * <p>Only applies skip logic to resources that have a {@code subject} field
     * (clinical event resources). Resources without a subject field
     * (Patient, Practitioner, Organization, etc.) are forwarded as-is.
     *
     * @return {@code true} if forwarding should be skipped (no patient context found)
     */
    private boolean ensurePatientSubject(ObjectNode root, Map<String, String> requestCache) {
        String resourceType = root.path("resourceType").asText("");

        // Case A: Resource IS a RelatedPerson — extract national-id from own identifiers
        if ("RelatedPerson".equals(resourceType)) {
            JsonNode identifiers = root.path("identifier");
            String nationalId = referenceResolver.extractNationalIdFromIdentifiers(identifiers);
            if (nationalId != null) {
                setSubjectReference(root, "Patient/" + nationalId);
                requestCache.put("Patient/" + nationalId, nationalId);
                log.debug("RelatedPerson resource — added subject Patient/{}", nationalId);
                return false;
            }
            log.info("RelatedPerson resource has no national-id — skipping forward");
            return true;
        }

        // Find a RelatedPerson reference anywhere in the payload
        String relatedPersonId = findRelatedPersonId(root);

        // If no subject field exists, check if a RelatedPerson reference is present
        // to determine whether this is a clinical resource that needs a Patient subject
        // or an identity/admin resource (Patient, Practitioner, Organization) to forward as-is
        JsonNode subjectNode = root.path("subject");
        if (subjectNode.isMissingNode() || !subjectNode.isObject()) {
            if (relatedPersonId != null) {
                // No subject but has RelatedPerson → add Patient subject from RelatedPerson
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    setSubjectReference(root, "Patient/" + nationalId);
                    requestCache.put("Patient/" + nationalId, nationalId);
                    log.debug("Added Patient subject (no prior subject) from RelatedPerson: Patient/{}", nationalId);
                    return false;
                }
                log.info("No Patient subject or RelatedPerson national-id found — skipping forward");
                return true;
            }
            // No subject and no RelatedPerson — identity/admin resource, forward as-is
            return false;
        }

        String subjectRef = subjectNode.path("reference").asText(null);
        boolean hasPatientSubject = subjectRef != null && subjectRef.startsWith("Patient/");

        if (hasPatientSubject) {
            // Case 1: Patient subject exists — resolve national-id from RelatedPerson
            if (relatedPersonId != null) {
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    String originalPatientId = subjectRef.substring("Patient/".length());
                    setSubjectReference(root, "Patient/" + nationalId);
                    // Pre-populate cache so tree walk doesn't re-fetch
                    requestCache.put("Patient/" + originalPatientId, nationalId);
                    requestCache.put("Patient/" + nationalId, nationalId);
                    log.debug("Enriched Patient subject: Patient/{} → Patient/{}", originalPatientId, nationalId);
                    return false;
                }
            }
            // RelatedPerson not found or resolution failed — continue with standard enrichment
            return false;
        } else {
            // Case 2/3: Subject exists but is NOT Patient (e.g. Group)
            if (relatedPersonId != null) {
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    setSubjectReference(root, "Patient/" + nationalId);
                    requestCache.put("Patient/" + nationalId, nationalId);
                    log.debug("Added Patient subject from RelatedPerson: Patient/{}", nationalId);
                    return false;
                }
            }
            // Subject exists (non-Patient) but no resolvable RelatedPerson → skip
            log.info("No Patient subject or RelatedPerson reference found — skipping forward");
            return true;
        }
    }

    /**
     * Sets {@code subject.reference} on the root node. Creates the {@code subject}
     * object if it doesn't exist, or updates the existing one preserving other fields.
     */
    private void setSubjectReference(ObjectNode root, String reference) {
        if (root.has("subject") && root.get("subject").isObject()) {
            ((ObjectNode) root.get("subject")).put("reference", reference);
        } else {
            root.putObject("subject").put("reference", reference);
        }
    }

    /**
     * Scans the payload tree for the first {@code RelatedPerson/{id}} reference.
     * Checks {@code participant[].individual.reference} and {@code performer[].reference}
     * and falls back to a recursive tree scan.
     *
     * @return the RelatedPerson ID (e.g. {@code "499063"}), or {@code null} if not found
     */
    private String findRelatedPersonId(ObjectNode root) {
        // Fast path: check participant[].individual.reference (Encounter pattern)
        JsonNode participants = root.path("participant");
        if (participants.isArray()) {
            for (JsonNode participant : participants) {
                String ref = participant.path("individual").path("reference").asText("");
                if (ref.startsWith("RelatedPerson/")) {
                    return ref.substring("RelatedPerson/".length());
                }
            }
        }

        // Fast path: check performer[].reference (ServiceRequest pattern)
        JsonNode performers = root.path("performer");
        if (performers.isArray()) {
            for (JsonNode performer : performers) {
                String ref = performer.path("reference").asText("");
                if (ref.startsWith("RelatedPerson/")) {
                    return ref.substring("RelatedPerson/".length());
                }
            }
        }

        return null;
    }

    /**
     * Recursively walks an {@link ObjectNode} and resolves any {@code reference}
     * fields that point to resolvable resource types.
     *
     * <p>Skips {@code meta} and {@code text} sub-trees — they are not meaningful
     * for reference resolution and skipping them avoids traversing large narrative HTML.
     */
    private void enrichNode(ObjectNode node, Map<String, String> requestCache) {
        // If this node IS a Reference object, attempt resolution
        if (node.has("reference")) {
            String original = node.get("reference").asText();
            String resolved = resolveReference(original, requestCache);
            if (resolved != null && !resolved.equals(original)) {
                node.put("reference", resolved);
                log.debug("Enriched reference: {} → {}", original, resolved);
            }
        }

        // Walk children for nested references (e.g., performer[], subject, requester)
        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode child = entry.getValue();
            if ("meta".equals(key) || "text".equals(key)) {
                return; // skip — not reference-bearing sub-trees
            }
            if (child.isObject()) {
                enrichNode((ObjectNode) child, requestCache);
            } else if (child.isArray()) {
                child.forEach(element -> {
                    if (element.isObject()) {
                        enrichNode((ObjectNode) element, requestCache);
                    }
                });
            }
        });
    }

    /**
     * Parses a FHIR reference string and resolves the ID to its national-id value.
     *
     * <p>Handles both relative ({@code "Patient/616"}) and absolute references
     * ({@code "http://server/fhir/Patient/616"}). Returns the resolved reference
     * string, or {@code null} if the type is not resolvable or no national-id was found.
     */
    private String resolveReference(String reference, Map<String, String> requestCache) {
        if (reference == null || !reference.contains("/")) {
            return null;
        }

        // Split: everything up to the last slash is the base; last segment is the ID
        int lastSlash = reference.lastIndexOf('/');
        String rawId = reference.substring(lastSlash + 1);
        String base = reference.substring(0, lastSlash); // e.g. "Patient" or "http://server/fhir/Patient"

        // Extract resource type from the base (last path segment)
        int typeSlash = base.lastIndexOf('/');
        String resourceType = typeSlash >= 0 ? base.substring(typeSlash + 1) : base;

        if (!referenceResolver.isResolvable(resourceType)) {
            return null;
        }

        String nationalId = referenceResolver.resolveNationalId(resourceType, rawId, requestCache);
        if (nationalId == null) {
            return null; // leave unchanged
        }

        // Re-assemble: preserve the base (handles both relative and absolute refs)
        return base + "/" + nationalId;
    }
}
