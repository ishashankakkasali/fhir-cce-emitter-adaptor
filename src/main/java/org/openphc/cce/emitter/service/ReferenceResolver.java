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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves FHIR internal numeric IDs to human-readable national identifiers.
 *
 * <p>Fetches the referenced resource from the FHIR server via
 * {@code GET /{resourceType}/{id}?_elements=identifier}, then applies one or more
 * configured strategies to locate the national-id value from the {@code identifier[]} array.
 * Results are cached <strong>per request</strong> via a caller-supplied map (typically created
 * fresh in {@link ResourceEnricher#enrichReferences(String)} per inbound callback) so the
 * same reference is fetched at most once per FHIR resource notification.
 *
 * <p>Supported match strategies (tried in configured order, first match wins):
 * <ul>
 *   <li>{@code system-suffix} — {@code identifier.system.endsWith(nationalIdSystemSuffix)};
 *       used by SPICE (default suffix: {@code /national-id})</li>
 *   <li>{@code use-official} — {@code identifier.use == "official"} (FHIR R4 standard)</li>
 *   <li>{@code type-code} — {@code identifier.type.coding[].code == nationalIdTypeCode}
 *       (default: {@code NI} from HL7 v2-0203)</li>
 * </ul>
 *
 * <p>Only resolves resource types listed in
 * {@code emitter.reference-resolution.resolvable-types} (configurable, defaults to
 * {@code Patient}). Failures are logged and return {@code null} so the caller can fall
 * back gracefully.
 */
@Service
public class ReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceResolver.class);

    /**
     * Ordered list of strategies used to locate the national-id in {@code identifier[]}.
     * Populated from {@code emitter.reference-resolution.national-id-match-strategies}.
     */
    private final List<String> matchStrategies;

    /**
     * Identifier system suffix used by the {@code system-suffix} strategy.
     * Populated from {@code emitter.reference-resolution.national-id-system-suffix}.
     */
    private final String nationalIdSystemSuffix;

    /**
     * Identifier type code used by the {@code type-code} strategy.
     * Populated from {@code emitter.reference-resolution.national-id-type-code}.
     */
    private final String nationalIdTypeCode;

    /**
     * Resource types for which national-id resolution is attempted.
     * Populated from {@code emitter.reference-resolution.resolvable-types}.
     */
    private final Set<String> resolvableTypes;

    /**
     * Optional source-type → target-type "follow link" map. When the resolver is asked
     * to resolve a reference whose type is a key in this map, it fetches the resource's
     * {@code link[].other.reference}, finds the link pointing at the mapped target type,
     * and recursively resolves that target's national-id instead.
     * Populated from {@code emitter.reference-resolution.link-follow}.
     */
    private final Map<String, String> linkFollow;

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
        this.resolvableTypes = Set.copyOf(properties.getReferenceResolution().getResolvableTypes());
        this.matchStrategies = List.copyOf(properties.getReferenceResolution().getNationalIdMatchStrategies());
        this.nationalIdSystemSuffix = properties.getReferenceResolution().getNationalIdSystemSuffix();
        this.nationalIdTypeCode = properties.getReferenceResolution().getNationalIdTypeCode();
        this.linkFollow = parseLinkFollow(properties.getReferenceResolution().getLinkFollow());
    }

    /** Parses {@code ["Patient:RelatedPerson", "Foo:Bar"]} into {@code {Patient→RelatedPerson, Foo→Bar}}. */
    private static Map<String, String> parseLinkFollow(List<String> entries) {
        if (entries == null || entries.isEmpty()) return Map.of();
        Map<String, String> map = new HashMap<>();
        for (String entry : entries) {
            if (entry == null) continue;
            int colon = entry.indexOf(':');
            if (colon > 0 && colon < entry.length() - 1) {
                String source = entry.substring(0, colon).trim();
                String target = entry.substring(colon + 1).trim();
                if (!source.isEmpty() && !target.isEmpty()) {
                    map.put(source, target);
                }
            }
        }
        return Map.copyOf(map);
    }

    /**
     * Returns {@code true} if the given FHIR resource type is configured for national-id resolution.
     *
     * @param resourceType FHIR resource type, e.g. {@code "Patient"}
     */
    public boolean isResolvable(String resourceType) {
        return resolvableTypes.contains(resourceType);
    }

    /**
     * Returns the national-id value for the given FHIR resource, or {@code null}
     * if the resource cannot be fetched or has no national-id identifier.
     *
     * <p>The {@code requestCache} is checked first; on a miss the FHIR server is
     * called once and the result (or an empty-string sentinel for a miss) is stored
     * back into the same map. Callers are expected to pass a fresh map per inbound
     * callback so caching is scoped to a single request and never serves stale data
     * across requests.
     *
     * @param resourceType FHIR resource type, e.g. {@code "Patient"}
     * @param resourceId   HAPI FHIR internal numeric ID, e.g. {@code "616"}
     * @param requestCache per-request cache, mutable; never {@code null}
     * @return national-id value, or {@code null} if unresolvable
     */
    public String resolveNationalId(String resourceType, String resourceId, Map<String, String> requestCache) {
        if (!resolvableTypes.contains(resourceType)) {
            return null;
        }
        return resolveNationalIdDirect(resourceType, resourceId, requestCache);
    }

    /**
     * Resolves the national-id for any resource type, bypassing the
     * {@code resolvableTypes} gate. Used by {@link ResourceEnricher} to resolve
     * RelatedPerson references for patient subject enrichment.
     *
     * @param resourceType FHIR resource type, e.g. {@code "RelatedPerson"}
     * @param resourceId   FHIR resource ID
     * @param requestCache per-request cache, mutable; never {@code null}
     * @return national-id value, or {@code null} if unresolvable
     */
    public String resolveNationalIdDirect(String resourceType, String resourceId, Map<String, String> requestCache) {

        String cacheKey = resourceType + "/" + resourceId;
        String cached = requestCache.get(cacheKey);

        if (cached != null) {
            // Empty string is the sentinel for a known miss — avoid re-fetching within this request
            return cached.isEmpty() ? null : cached;
        }

        String nationalId = fetchNationalId(resourceType, resourceId, requestCache);
        requestCache.put(cacheKey, nationalId != null ? nationalId : "");
        return nationalId;
    }

    private String fetchNationalId(String resourceType, String resourceId, Map<String, String> requestCache) {
        String linkTargetType = linkFollow.get(resourceType);
        log.debug("Resolving reference {}/{} via FHIR client (_elements=identifier{})",
                resourceType, resourceId, linkTargetType != null ? ",link" : "");

        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            // For link-follow source types, also request the link field so we can pivot
            // to the linked target resource (e.g. Patient → linked RelatedPerson).
            String[] elements = linkTargetType != null
                    ? new String[]{"identifier", "link"}
                    : new String[]{"identifier"};
            IBaseResource resource = client.read()
                    .resource(resourceType)
                    .withId(resourceId)
                    .elementsSubset(elements)
                    .execute();

            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);

            // Try the linked target first (when configured).
            // Note: link-follow bypasses the resolvableTypes gate intentionally — the
            // target type only needs to provide a value, it does not need to be
            // independently rewritten in the outbound payload.
            if (linkTargetType != null) {
                String linkedId = findLinkTarget(resourceJson, linkTargetType);
                if (linkedId != null) {
                    log.debug("Following link from {}/{} → {}/{}",
                            resourceType, resourceId, linkTargetType, linkedId);
                    String linkedCacheKey = linkTargetType + "/" + linkedId;
                    String cachedLinked = requestCache.get(linkedCacheKey);
                    String resolved;
                    if (cachedLinked != null) {
                        resolved = cachedLinked.isEmpty() ? null : cachedLinked;
                    } else {
                        resolved = fetchNationalId(linkTargetType, linkedId, requestCache);
                        requestCache.put(linkedCacheKey, resolved != null ? resolved : "");
                    }
                    if (resolved != null) {
                        return resolved;
                    }
                    log.debug("Linked {}/{} did not yield a national-id — falling back to source identifiers",
                            linkTargetType, linkedId);
                }
            }

            // Fallback (or default path): extract national-id from the source's own identifier[]
            return extractNationalId(resourceJson, resourceType, resourceId);

        } catch (Exception e) {
            log.warn("Failed to fetch {}/{} from FHIR server: {} — leaving reference unresolved",
                    resourceType, resourceId, e.getMessage());
            return null;
        }
    }

    /** Returns the id of the first {@code link[].other.reference} matching {@code targetType/}. */
    private String findLinkTarget(String resourceJson, String targetType) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            JsonNode links = root.path("link");
            if (!links.isArray()) return null;
            String prefix = targetType + "/";
            for (JsonNode link : links) {
                String ref = link.path("other").path("reference").asText("");
                if (ref.startsWith(prefix)) {
                    return ref.substring(prefix.length());
                }
                // Also handle absolute references like http://server/fhir/RelatedPerson/123
                int idx = ref.lastIndexOf("/" + prefix);
                if (idx >= 0) {
                    return ref.substring(idx + prefix.length() + 1);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse link[] for link-follow: {}", e.getMessage());
        }
        return null;
    }

    private String extractNationalId(String resourceJson, String resourceType, String resourceId) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            JsonNode identifiers = root.path("identifier");
            return extractNationalIdFromIdentifiers(identifiers, resourceType, resourceId);
        } catch (Exception e) {
            log.warn("Failed to extract national-id from {}/{} response: {}",
                    resourceType, resourceId, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the national-id value from a FHIR {@code identifier[]} array using
     * the configured match strategies. Used by {@link ResourceEnricher} when the
     * resource payload itself carries the identifier (e.g. a RelatedPerson callback).
     *
     * @param identifiers JSON array node of FHIR identifiers
     * @return national-id value, or {@code null} if not found
     */
    public String extractNationalIdFromIdentifiers(JsonNode identifiers) {
        return extractNationalIdFromIdentifiers(identifiers, "inline", "inline");
    }

    private String extractNationalIdFromIdentifiers(JsonNode identifiers, String resourceType, String resourceId) {
        if (!identifiers.isArray()) {
            log.warn("No identifier array found for {}/{} — leaving reference unresolved",
                    resourceType, resourceId);
            return null;
        }

        // Try each configured strategy in order; return first match
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
                log.debug("Resolved {}/{} → national-id={} (strategy={})",
                        resourceType, resourceId, value, strategy);
                return value;
            }
        }

        log.warn("No national-id found for {}/{} using strategies {} — leaving reference unresolved",
                resourceType, resourceId, matchStrategies);
        return null;
    }

    /** Matches {@code identifier.system.endsWith(nationalIdSystemSuffix)}. */
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

    /** Matches {@code identifier.use == "official"} (FHIR R4 standard). */
    private String findByUseOfficial(JsonNode identifiers) {
        for (JsonNode id : identifiers) {
            if ("official".equals(id.path("use").asText(null))) {
                String value = id.path("value").asText(null);
                if (value != null && !value.isBlank()) return value;
            }
        }
        return null;
    }

    /** Matches {@code identifier.type.coding[].code == nationalIdTypeCode} (HL7 v2-0203, default NI). */
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
