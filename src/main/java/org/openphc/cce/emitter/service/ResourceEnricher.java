package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Enriches FHIR resource JSON by ensuring a {@code Patient/<national-id>}
 * subject reference is present.
 *
 * <p>Delegates national-id resolution to {@link ReferenceResolver}, which handles
 * identity-source detection, path-based reference lookup, FHIR server fetching,
 * and multi-strategy identifier matching. This class is solely responsible for
 * setting the {@code subject.reference} field on the enriched payload.
 *
 * <p>Only the {@code subject.reference} is modified. All other references in the
 * payload (e.g. {@code performer}, {@code encounter}) are forwarded as-is.
 *
 * <p>If national-id resolution fails for any reason, forwarding is skipped
 * (returns {@code null}).
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);
    private static final String PATIENT_PREFIX = "Patient/";

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
    }

    /**
     * Enriches the FHIR JSON by resolving the national-id and setting
     * {@code subject.reference = "Patient/<national-id>"}.
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON, or {@code null} if forwarding should be skipped
     */
    public String enrichReferences(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!root.isObject()) {
                log.error("Payload is not a JSON object — skipping forward");
                return null;
            }

            // Resolve national-id (handles identity-source detection + path lookup + FHIR fetch)
            String nationalId = referenceResolver.resolveNationalIdFromResource(root);
            if (nationalId == null) {
                return null;
            }

            // Set subject.reference = "Patient/<national-id>"
            ObjectNode rootObj = (ObjectNode) root;
            setSubjectReference(rootObj, PATIENT_PREFIX + nationalId);
            String resourceType = root.path("resourceType").asText("");
            log.debug("Enriched {} — set subject Patient/{}", resourceType, nationalId);

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Reference enrichment failed — skipping forward: {}", e.getMessage());
            return null;
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

}
