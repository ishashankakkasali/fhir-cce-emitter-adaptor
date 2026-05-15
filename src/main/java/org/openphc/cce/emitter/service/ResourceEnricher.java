package org.openphc.cce.emitter.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
 * <p>The enricher performs a <strong>single-pass tree scan</strong> that:
 * <ol>
 *   <li>Finds the first {@code RelatedPerson/{id}} reference <em>anywhere</em>
 *       in the payload (not limited to specific fields)</li>
 *   <li>Collects all nodes containing a {@code reference} field for later
 *       resolution</li>
 * </ol>
 *
 * <p>After the scan:
 * <ol>
 *   <li><strong>Patient subject resolution</strong> — ensures
 *       {@code subject.reference} points to {@code Patient/<national-id>}
 *       using the RelatedPerson found during the scan</li>
 *   <li><strong>Reference enrichment</strong> — resolves each collected
 *       reference node whose type is in the configured {@code resolvable-types}
 *       to its national-id equivalent, using the cache pre-populated during
 *       subject resolution</li>
 * </ol>
 *
 * <h3>Patient subject resolution rules</h3>
 * <ul>
 *   <li>If the resource IS a {@code RelatedPerson} — extracts national-id from
 *       its own identifiers and adds a Patient subject</li>
 *   <li>If {@code subject.reference} is {@code "Patient/<fhirId>"} and a
 *       RelatedPerson reference exists — resolves national-id from the
 *       RelatedPerson and replaces the Patient subject</li>
 *   <li>If no Patient subject but a RelatedPerson reference is found —
 *       resolves national-id and adds {@code subject.reference}</li>
 *   <li>If subject is non-Patient and no RelatedPerson — returns {@code null}
 *       (forwarding skipped)</li>
 *   <li>If no subject and no RelatedPerson — identity/admin resource,
 *       forwarded as-is</li>
 * </ul>
 *
 * <p>This is a fail-safe operation — any exception during enrichment is logged
 * and the original JSON is returned so that forwarding to OpenHIM is never blocked.
 */
@Service
public class ResourceEnricher {

    private static final Logger log = LoggerFactory.getLogger(ResourceEnricher.class);
    private static final String RELATED_PERSON_PREFIX = "RelatedPerson/";
    private static final String PATIENT_PREFIX = "Patient/";

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
     * <p>Uses a single-pass tree scan to find the first RelatedPerson reference
     * and collect all reference-bearing nodes, then processes both subject
     * resolution and reference enrichment from the scan results.
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON, the original JSON on failure, or {@code null}
     *         if forwarding should be skipped (no Patient subject or
     *         RelatedPerson reference found)
     */
    public String enrichReferences(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            if (!root.isObject()) {
                return objectMapper.writeValueAsString(root);
            }

            ObjectNode rootObj = (ObjectNode) root;
            String resourceType = rootObj.path("resourceType").asText("");
            Map<String, String> requestCache = new HashMap<>();

            // Special case: RelatedPerson resource — extract national-id from own identifiers
            if ("RelatedPerson".equals(resourceType)) {
                JsonNode identifiers = rootObj.path("identifier");
                String nationalId = referenceResolver.extractNationalIdFromIdentifiers(identifiers);
                if (nationalId == null) {
                    log.info("RelatedPerson resource has no national-id — skipping forward");
                    return null;
                }
                setSubjectReference(rootObj, PATIENT_PREFIX + nationalId);
                requestCache.put(PATIENT_PREFIX + nationalId, nationalId);
                log.debug("RelatedPerson resource — added subject Patient/{}", nationalId);
            }

            // Single-pass tree scan: find first RelatedPerson reference + collect all reference nodes
            TreeScanResult scan = scanTree(rootObj);

            // For non-RelatedPerson resources: resolve patient subject using found RelatedPerson
            if (!"RelatedPerson".equals(resourceType)) {
                boolean shouldSkip = ensurePatientSubject(rootObj, scan.relatedPersonId, requestCache);
                if (shouldSkip) {
                    return null;
                }
            }

            // Resolve all collected reference nodes (cache is pre-populated from subject resolution)
            for (ObjectNode refNode : scan.referenceNodes) {
                String original = refNode.get("reference").asText();
                String resolved = resolveReference(original, requestCache);
                if (resolved != null && !resolved.equals(original)) {
                    refNode.put("reference", resolved);
                    log.debug("Enriched reference: {} → {}", original, resolved);
                }
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
     * @param root            the root JSON object
     * @param relatedPersonId the RelatedPerson ID found during tree scan, or {@code null}
     * @param requestCache    per-request cache for resolved national-ids
     * @return {@code true} if forwarding should be skipped (no patient context found)
     */
    private boolean ensurePatientSubject(ObjectNode root, String relatedPersonId,
                                         Map<String, String> requestCache) {
        JsonNode subjectNode = root.path("subject");

        // No subject field → check if a RelatedPerson reference was found to determine
        // whether this is a clinical resource needing a Patient subject or an identity/admin resource
        if (subjectNode.isMissingNode() || !subjectNode.isObject()) {
            if (relatedPersonId != null) {
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    setSubjectReference(root, PATIENT_PREFIX + nationalId);
                    requestCache.put(PATIENT_PREFIX + nationalId, nationalId);
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
        boolean hasPatientSubject = subjectRef != null && subjectRef.startsWith(PATIENT_PREFIX);

        if (hasPatientSubject) {
            // Patient subject exists — resolve national-id from RelatedPerson if available
            if (relatedPersonId != null) {
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    String originalPatientId = subjectRef.substring(PATIENT_PREFIX.length());
                    setSubjectReference(root, PATIENT_PREFIX + nationalId);
                    // Pre-populate cache so reference resolution doesn't re-fetch
                    requestCache.put(PATIENT_PREFIX + originalPatientId, nationalId);
                    requestCache.put(PATIENT_PREFIX + nationalId, nationalId);
                    log.debug("Enriched Patient subject: Patient/{} → Patient/{}", originalPatientId, nationalId);
                }
            }
            return false;
        } else {
            // Subject exists but is NOT Patient (e.g. Group)
            if (relatedPersonId != null) {
                String nationalId = referenceResolver.resolveNationalIdDirect(
                        "RelatedPerson", relatedPersonId, requestCache);
                if (nationalId != null) {
                    setSubjectReference(root, PATIENT_PREFIX + nationalId);
                    requestCache.put(PATIENT_PREFIX + nationalId, nationalId);
                    log.debug("Replaced non-Patient subject with Patient/{}", nationalId);
                    return false;
                }
            }
            log.info("Non-Patient subject, no resolvable RelatedPerson — skipping forward");
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

    // ── Single-pass tree scan ───────────────────────────────────────

    /**
     * Result of scanning the FHIR resource JSON tree in a single pass.
     * Holds the first RelatedPerson ID found and all reference-bearing nodes.
     */
    private static class TreeScanResult {
        String relatedPersonId;
        final List<ObjectNode> referenceNodes = new ArrayList<>();
    }

    /**
     * Performs a single-pass recursive scan of the JSON tree.
     * Finds the first {@code RelatedPerson/{id}} reference anywhere in the payload
     * and collects all nodes that contain a {@code reference} field.
     */
    private TreeScanResult scanTree(ObjectNode root) {
        TreeScanResult result = new TreeScanResult();
        scanNode(root, result);
        return result;
    }

    /**
     * Recursively scans an {@link ObjectNode} for RelatedPerson references and
     * collects all reference-bearing nodes.
     *
     * <p>Skips {@code meta} and {@code text} sub-trees — they are not meaningful
     * for reference resolution and skipping them avoids traversing large narrative HTML.
     */
    private void scanNode(ObjectNode node, TreeScanResult result) {
        if (node.has("reference")) {
            String ref = node.get("reference").asText("");

            // Track first RelatedPerson reference found anywhere in the tree
            if (result.relatedPersonId == null && ref.startsWith(RELATED_PERSON_PREFIX)) {
                result.relatedPersonId = ref.substring(RELATED_PERSON_PREFIX.length());
            }

            // Collect all reference nodes for later resolution
            result.referenceNodes.add(node);
        }

        node.fields().forEachRemaining(entry -> {
            String key = entry.getKey();
            JsonNode child = entry.getValue();
            if ("meta".equals(key) || "text".equals(key)) {
                return;
            }
            if (child.isObject()) {
                scanNode((ObjectNode) child, result);
            } else if (child.isArray()) {
                child.forEach(element -> {
                    if (element.isObject()) {
                        scanNode((ObjectNode) element, result);
                    }
                });
            }
        });
    }

    // ── Reference resolution ────────────────────────────────────────

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
