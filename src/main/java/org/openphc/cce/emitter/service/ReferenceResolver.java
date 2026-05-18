package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the national-id for a FHIR resource by determining the identity-source
 * reference (configurable, default: {@code RelatedPerson}) and extracting the
 * national-id from its {@code identifier[]} array.
 *
 * <p>For resources that ARE the configured identity-source type, the national-id is
 * extracted directly from their own identifiers. For all other resources, the resolver
 * walks a configurable JSON path to locate the identity-source reference, fetches it
 * from the FHIR server, and extracts the national-id.
 *
 * <p>Supported match strategies (tried in configured order, first match wins):
 * <ul>
 *   <li>{@code use-official} — {@code identifier.use == "official"} (FHIR R4 standard)</li>
 *   <li>{@code type-code} — {@code identifier.type.coding[].code == nationalIdTypeCode}
 *       (default: {@code NI} from HL7 v2-0203)</li>
 *   <li>{@code system-suffix} — {@code identifier.system.endsWith(nationalIdSystemSuffix)};
 *       used by SPICE (default suffix: {@code /national-id})</li>
 * </ul>
 *
 * <p>Failures are logged and return {@code null} so the caller can handle gracefully.
 */
@Service
public class ReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceResolver.class);

    private final List<String> matchStrategies;
    private final String nationalIdSystemSuffix;
    private final String nationalIdTypeCode;
    private final String identityResourceType;
    private final String identityResourcePrefix;
    private final Map<String, String> referencePathMap;

    private final EmitterProperties properties;
    private final FhirContext fhirContext;
    private final FhirClientFactory fhirClientFactory;
    private final ObjectMapper objectMapper;

    public ReferenceResolver(EmitterProperties properties,
                             FhirContext fhirContext,
                             FhirClientFactory fhirClientFactory,
                             ObjectMapper objectMapper) {
        this.properties = properties;
        this.fhirContext = fhirContext;
        this.fhirClientFactory = fhirClientFactory;
        this.objectMapper = objectMapper;

        var refConfig = properties.getReferenceResolution();
        this.matchStrategies = List.copyOf(refConfig.getNationalIdMatchStrategies());
        this.nationalIdSystemSuffix = refConfig.getNationalIdSystemSuffix();
        this.nationalIdTypeCode = refConfig.getNationalIdTypeCode();
        this.identityResourceType = refConfig.getIdentityResourceType();
        this.identityResourcePrefix = identityResourceType + "/";
        this.referencePathMap = parsePathConfig(refConfig.getIdentityResourcePaths());

        log.info("Identity resource type: {}, reference paths configured for: {}",
                identityResourceType, referencePathMap.keySet());
    }

    /**
     * Resolves the national-id for a FHIR resource JSON payload.
     *
     * <p>If the resource IS the configured identity-source type, the national-id is
     * extracted from its own {@code identifier[]}. Otherwise, the configured path is
     * walked to find the identity-source reference, which is fetched from the FHIR
     * server to extract the national-id.
     *
     * @param root parsed FHIR resource JSON tree (must be an ObjectNode)
     * @return national-id value, or {@code null} if unresolvable (forwarding should be skipped)
     */
    public String resolveNationalIdFromResource(JsonNode root) {
        String resourceType = root.path("resourceType").asText("");
        try {

            // Identity-source resource — extract from own identifiers
            if (identityResourceType.equals(resourceType)) {
                JsonNode identifiers = root.path("identifier");
                String nationalId = extractNationalIdFromIdentifiers(identifiers);
                if (nationalId == null) {
                    log.error("{} resource has no national-id — skipping forward", identityResourceType);
                }
                return nationalId;
            }

            // Other resources — find identity-source reference at configured path
            String path = referencePathMap.get(resourceType);
            if (path == null) {
                log.error("No reference path configured for resource type: {} — skipping forward",
                        resourceType);
                return null;
            }

            // personIdentifier: the FHIR resource ID extracted from the identity-resource
            // reference at the configured path (e.g. "499063" from "RelatedPerson/499063")
            String personIdentifier = findIdentitySourceIdByPath(root, path);
            if (personIdentifier == null) {
                log.error("No {} reference found at configured path '{}' for {} — skipping forward",
                        identityResourceType, path, resourceType);
                return null;
            }

            // Fetch the identity-source resource and extract national-id
            String nationalId = resolveNationalId(identityResourceType, personIdentifier);
            if (nationalId == null) {
                log.error("Failed to resolve national-id from {}/{} for {} — skipping forward",
                        identityResourceType, personIdentifier, resourceType);
            }
            return nationalId;

        } catch (Exception e) {
            log.error("National-id resolution failed for {}: {} — skipping forward",
                    resourceType, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a FHIR resource by type and ID from the FHIR server and extracts
     * the national-id from its {@code identifier[]}.
     *
     * @param resourceType FHIR resource type, e.g. {@code "RelatedPerson"}
     * @param resourceId   FHIR resource ID
     * @return national-id value, or {@code null} if unresolvable
     */
    public String resolveNationalId(String resourceType, String resourceId) {
        log.debug("Resolving reference {}/{} via FHIR client (_elements=identifier)",
                resourceType, resourceId);

        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource(resourceType)
                    .withId(resourceId)
                    .elementsSubset("identifier")
                    .execute();

            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode root = objectMapper.readTree(resourceJson);
            return extractNationalIdFromIdentifiers(root.path("identifier"));

        } catch (Exception e) {
            log.warn("Failed to resolve national-id for {}/{}: {} — leaving reference unresolved",
                    resourceType, resourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the national-id value from a FHIR {@code identifier[]} array using
     * the configured match strategies.
     *
     * @param identifiers JSON array node of FHIR identifiers
     * @return national-id value, or {@code null} if not found
     */
    public String extractNationalIdFromIdentifiers(JsonNode identifiers) {
        if (!identifiers.isArray()) {
            log.warn("No identifier array found — leaving reference unresolved");
            return null;
        }

        for (String strategy : matchStrategies) {
            String value = switch (strategy) {
                case "use-official" -> findByUseOfficial(identifiers);
                case "type-code"    -> findByTypeCode(identifiers);
                case "system-suffix" -> findBySystemSuffix(identifiers);
                default -> {
                    log.warn("Unknown national-id match strategy '{}' — skipping", strategy);
                    yield null;
                }
            };
            if (value != null) {
                log.debug("Resolved national-id={} (strategy={})", value, strategy);
                return value;
            }
        }

        log.warn("No national-id found using strategies {} — leaving reference unresolved", matchStrategies);
        return null;
    }

    // ── Path-based identity-source lookup ───────────────────────────

    /**
     * Walks the JSON tree following the dot-separated path to find the first
     * reference matching the configured identity-source type.
     *
     * @param root the root JSON node
     * @param path dot-separated path, e.g. {@code "participant.individual.reference"}
     * @return the identity-source resource ID, or {@code null} if not found
     */
    private String findIdentitySourceIdByPath(JsonNode root, String path) {
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

        // Last segment — look for identity-resource-type reference value
        String lastSegment = segments[segments.length - 1];
        for (JsonNode node : current) {
            if (node.isObject()) {
                JsonNode refNode = node.get(lastSegment);
                if (refNode != null && refNode.isTextual()) {
                    String ref = refNode.asText();
                    if (ref.startsWith(identityResourcePrefix)) {
                        return ref.substring(identityResourcePrefix.length());
                    }
                }
            }
        }
        return null;
    }

    // ── Path config parsing ─────────────────────────────────────────

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

    // ── National-id match strategies ────────────────────────────────

    private String findBySystemSuffix(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            String system = id.path("system").asText("");
            if (system.endsWith(nationalIdSystemSuffix)) {
                String value = id.path("value").asText(null);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    private String findByUseOfficial(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            if ("official".equals(id.path("use").asText(null))) {
                String value = id.path("value").asText(null);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    private String findByTypeCode(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            JsonNode codings = id.path("type").path("coding");
            if (codings.isArray()) {
                for (JsonNode coding : codings) {
                    if (nationalIdTypeCode.equals(coding.path("code").asText(null))) {
                        String value = id.path("value").asText(null);
                        if (value != null && !value.isBlank()) return value;
                    }
                }
            }
        }
        return null;
    }

}
