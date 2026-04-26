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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves FHIR internal numeric IDs to human-readable national identifiers.
 *
 * <p>Fetches the referenced resource from the FHIR server via
 * {@code GET /{resourceType}/{id}}, finds the {@code identifier[]} entry whose
 * {@code system} ends with {@code /national-id}, and returns the {@code value}.
 * Results are cached in-memory to avoid repeated FHIR server round-trips.
 *
 * <p>Only resolves {@link #RESOLVABLE_TYPES} ({@code Patient}, {@code RelatedPerson},
 * {@code Practitioner}). All other resource types are left unchanged.
 * Failures are logged and return {@code null} so the caller can fall back gracefully.
 */
@Service
public class ReferenceResolver {

    private static final Logger log = LoggerFactory.getLogger(ReferenceResolver.class);

    private static final String NATIONAL_ID_SUFFIX = "/national-id";

    /** Resource types for which national-id resolution is attempted. */
    static final Set<String> RESOLVABLE_TYPES = Set.of("Patient", "RelatedPerson", "Practitioner");

    /**
     * In-memory cache: {@code "Patient/616"} → {@code "NID-123456"}.
     * An empty-string sentinel is stored for known misses to avoid repeated FHIR lookups.
     */
    private final ConcurrentHashMap<String, String> resolvedCache = new ConcurrentHashMap<>();

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
    }

    /**
     * Returns the national-id value for the given FHIR resource, or {@code null}
     * if the resource cannot be fetched or has no {@code /national-id} identifier.
     *
     * <p>Cache is checked first; on a miss the FHIR server is called once and the
     * result (or empty-string sentinel for a miss) is stored.
     *
     * @param resourceType FHIR resource type, e.g. {@code "Patient"}
     * @param resourceId   HAPI FHIR internal numeric ID, e.g. {@code "616"}
     * @return national-id value, or {@code null} if unresolvable
     */
    public String resolveNationalId(String resourceType, String resourceId) {
        if (!RESOLVABLE_TYPES.contains(resourceType)) {
            return null;
        }

        String cacheKey = resourceType + "/" + resourceId;
        String cached = resolvedCache.get(cacheKey);

        if (cached != null) {
            // Empty string is the sentinel for a known miss — avoid re-fetching
            return cached.isEmpty() ? null : cached;
        }

        String nationalId = fetchNationalId(resourceType, resourceId);
        resolvedCache.put(cacheKey, nationalId != null ? nationalId : "");
        return nationalId;
    }

    private String fetchNationalId(String resourceType, String resourceId) {
        log.debug("Resolving reference {}/{} via FHIR client", resourceType, resourceId);

        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource(resourceType)
                    .withId(resourceId)
                    .execute();

            String resourceJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            return extractNationalId(resourceJson, resourceType, resourceId);

        } catch (Exception e) {
            log.warn("Failed to fetch {}/{} from FHIR server: {} — leaving reference unresolved",
                    resourceType, resourceId, e.getMessage());
            return null;
        }
    }

    private String extractNationalId(String resourceJson, String resourceType, String resourceId) {
        try {
            JsonNode root = objectMapper.readTree(resourceJson);
            JsonNode identifiers = root.path("identifier");

            if (identifiers.isArray()) {
                for (JsonNode identifier : identifiers) {
                    String system = identifier.path("system").asText("");
                    if (system.endsWith(NATIONAL_ID_SUFFIX)) {
                        String value = identifier.path("value").asText(null);
                        if (value != null && !value.isBlank()) {
                            log.debug("Resolved {}/{} → national-id={}", resourceType, resourceId, value);
                            return value;
                        }
                    }
                }
            }

            log.warn("No /national-id identifier found for {}/{} — leaving reference unresolved",
                    resourceType, resourceId);
            return null;

        } catch (Exception e) {
            log.warn("Failed to extract national-id from {}/{} response: {}",
                    resourceType, resourceId, e.getMessage());
            return null;
        }
    }

}
