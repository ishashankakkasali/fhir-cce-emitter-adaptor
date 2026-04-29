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
 * Enriches FHIR resource JSON by resolving HAPI internal numeric IDs in
 * {@code Patient}, {@code RelatedPerson}, and {@code Practitioner} reference
 * strings to their national-id identifiers.
 *
 * <p>Example transformation:
 * <pre>
 *   Before: { "reference": "Patient/616" }
 *   After:  { "reference": "Patient/NID-123456" }
 * </pre>
 *
 * <p>The enricher walks the entire JSON tree and replaces every matching
 * {@code reference} field it encounters. References to types not configured for
 * resolution (see {@code emitter.reference-resolution.resolvable-types}) are left
 * unchanged.
 *
 * <p>This is a fail-safe operation — any exception during enrichment is logged and
 * the original JSON is returned so that forwarding to OpenHIM is never blocked.
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
     * Walks the FHIR JSON and replaces resolvable reference strings with their
     * national-id equivalents. Returns the original JSON if enrichment fails.
     *
     * @param resourceJson raw FHIR resource JSON
     * @return enriched JSON, or the original JSON on failure
     */
    public String enrichReferences(String resourceJson) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            // Per-request cache: lives only for the duration of this callback so a
            // single notification fetches each referenced resource at most once,
            // and stale values cannot be served across separate callbacks.
            Map<String, String> requestCache = new HashMap<>();
            if (root.isObject()) {
                enrichNode((ObjectNode) root, requestCache);
            }
            return objectMapper.writeValueAsString(root);
        } catch (Exception e) {
            log.warn("Reference enrichment failed — forwarding original resource: {}", e.getMessage());
            return resourceJson;
        }
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
