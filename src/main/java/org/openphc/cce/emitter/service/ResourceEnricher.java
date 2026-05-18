package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Enriches FHIR resource JSON by ensuring a Patient subject reference with a
 * national-id is present, and by resolving HAPI internal numeric IDs in
 * configured reference types to their national-id identifiers.
 *
 * <p>The enricher uses <strong>configurable JSON paths</strong> per resource type
 * to locate the RelatedPerson reference in the payload, rather than scanning
 * the entire JSON tree. Paths are defined in
 * {@code emitter.reference-resolution.related-person-paths}.
 *
 * <p>Processing flow:
 * <ol>
 *   <li><strong>RelatedPerson resources</strong> — extracts national-id from
 *       own {@code identifier[]} and adds a {@code Patient/<national-id>}
 *       subject reference</li>
 *   <li><strong>Other resources</strong> — uses the configured path to locate
 *       the RelatedPerson reference, resolves its national-id, and ensures
 *       a {@code Patient/<national-id>} subject reference</li>
 * </ol>
 *
 * <p>Only the {@code subject.reference} is resolved to a national-id. Other
 * references in the payload (e.g. {@code performer}, {@code encounter}) are
 * forwarded as-is.
 *
 * <p>If no path is configured for a resource type, forwarding is skipped with
 * an error log. Any exception during enrichment is logged and forwarding is
 * skipped (returns {@code null}).
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);
    private static final String RELATED_PERSON_PREFIX = "RelatedPerson/";
    private static final String PATIENT_PREFIX = "Patient/";

    private final ReferenceResolver referenceResolver;
    private final ObjectMapper objectMapper;
    private final Map<String, String> relatedPersonPathMap;

    public ResourceEnricher(ReferenceResolver referenceResolver, ObjectMapper objectMapper,
                            EmitterProperties emitterProperties) {
        this.referenceResolver = referenceResolver;
        this.objectMapper = objectMapper;
        this.relatedPersonPathMap = parsePathConfig(
                emitterProperties.getReferenceResolution().getRelatedPersonPaths());
        log.info("Related-person paths configured for resource types: {}", relatedPersonPathMap.keySet());
    }

    /**
     * Parses the {@code ResourceType:dot.path} list into a lookup map.
     * Keys are stored as-is (case-sensitive FHIR resource type names).
     */
    private static Map<String, String> parsePathConfig(List<String> pathEntries) {
        Map<String, String> map = new HashMap<>();
        if (pathEntries == null) return map;
        for (String entry : pathEntries) {
            int colonIdx = entry.indexOf(':');
            if (colonIdx > 0 && colonIdx < entry.length() - 1) {
                map.put(entry.substring(0, colonIdx).trim(), entry.substring(colonIdx + 1).trim());
            }
        }
        return map;
    }

    /**
     * Enriches the FHIR JSON by ensuring a Patient subject with national-id
     * is present, then resolves other resolvable references.
     *
     * <p>Uses configurable JSON paths per resource type to locate the
     * RelatedPerson reference. Only the {@code subject.reference} is resolved;
     * other references in the payload are left unchanged.
     *
     * <p><strong>Strict forwarding rules:</strong> Every resource (except
     * RelatedPerson) must have a configured path, must find a RelatedPerson
     * at that path, and must successfully resolve a national-id. If any step
     * fails, forwarding is skipped with an error log.
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

            ObjectNode rootObj = (ObjectNode) root;
            String resourceType = rootObj.path("resourceType").asText("");

            // Step 0: RelatedPerson — extract national-id from own identifiers
            if ("RelatedPerson".equals(resourceType)) {
                JsonNode identifiers = rootObj.path("identifier");
                String nationalId = referenceResolver.extractNationalIdFromIdentifiers(identifiers);
                if (nationalId == null) {
                    log.error("RelatedPerson resource has no national-id — skipping forward");
                    return null;
                }
                setSubjectReference(rootObj, PATIENT_PREFIX + nationalId);
                log.debug("RelatedPerson resource — added subject Patient/{}", nationalId);
            } else {
                // Step 1: Look up the configured path for this resource type
                String path = relatedPersonPathMap.get(resourceType);
                if (path == null) {
                    log.error("No related-person-path configured for resource type: {} — skipping forward",
                            resourceType);
                    return null;
                }

                // Step 2: Find the RelatedPerson ID at the configured path
                String relatedPersonId = findRelatedPersonIdByPath(rootObj, path);
                if (relatedPersonId == null) {
                    log.error("No RelatedPerson reference found at configured path '{}' for {} — skipping forward",
                            path, resourceType);
                    return null;
                }

                // Step 3: Resolve national-id from RelatedPerson
                String nationalId = referenceResolver.resolveNationalId(
                        "RelatedPerson", relatedPersonId);
                if (nationalId == null) {
                    log.error("Failed to resolve national-id from RelatedPerson/{} for {} — skipping forward",
                            relatedPersonId, resourceType);
                    return null;
                }

                // Step 4: Update/create subject reference with resolved national-id
                setSubjectReference(rootObj, PATIENT_PREFIX + nationalId);
                log.debug("Set Patient subject from RelatedPerson/{}: Patient/{}", relatedPersonId, nationalId);
            }

            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.error("Reference enrichment failed — skipping forward: {}", e.getMessage());
            return null;
        }
    }

    // ── Path-based RelatedPerson lookup ─────────────────────────────

    /**
     * Walks the JSON tree following the dot-separated path to find the first
     * {@code RelatedPerson/{id}} reference.
     *
     * <p>At each segment, if the current node is an array, all elements are
     * traversed. The final segment is expected to be {@code reference} — the
     * method returns the ID portion of the first value starting with
     * {@code RelatedPerson/}.
     *
     * @param root the root JSON object
     * @param path dot-separated path, e.g. {@code "participant.individual.reference"}
     * @return the RelatedPerson ID, or {@code null} if not found at the path
     */
    private String findRelatedPersonIdByPath(ObjectNode root, String path) {
        String[] segments = path.split("\\.");
        List<JsonNode> current = List.of(root);

        // Walk all segments except the last (which is "reference")
        for (int i = 0; i < segments.length - 1; i++) {
            String segment = segments[i];
            List<JsonNode> next = new ArrayList<>();
            for (JsonNode node : current) {
                if (node.isObject()) {
                    JsonNode child = node.get(segment);
                    if (child != null) {
                        if (child.isArray()) {
                            child.forEach(next::add);
                        } else {
                            next.add(child);
                        }
                    }
                }
            }
            current = next;
            if (current.isEmpty()) return null;
        }

        // Last segment — look for RelatedPerson/ value
        String lastSegment = segments[segments.length - 1];
        for (JsonNode node : current) {
            if (node.isObject()) {
                JsonNode refNode = node.get(lastSegment);
                if (refNode != null && refNode.isTextual()) {
                    String ref = refNode.asText();
                    if (ref.startsWith(RELATED_PERSON_PREFIX)) {
                        return ref.substring(RELATED_PERSON_PREFIX.length());
                    }
                }
            }
        }
        return null;
    }

    // ── Patient subject resolution ──────────────────────────────────

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
