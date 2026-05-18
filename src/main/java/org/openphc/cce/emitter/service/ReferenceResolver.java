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

import java.util.List;

/**
 * Resolves FHIR internal numeric IDs to human-readable national identifiers.
 *
 * <p>Fetches the referenced resource from the FHIR server via
 * {@code GET /{resourceType}/{id}?_elements=identifier}, then applies one or more
 * configured strategies to locate the national-id value from the {@code identifier[]} array.
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
 * <p>Failures are logged and return {@code null} so the caller can handle gracefully.
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
        this.matchStrategies = List.copyOf(properties.getReferenceResolution().getNationalIdMatchStrategies());
        this.nationalIdSystemSuffix = properties.getReferenceResolution().getNationalIdSystemSuffix();
        this.nationalIdTypeCode = properties.getReferenceResolution().getNationalIdTypeCode();
    }

    /**
     * Resolves the national-id for a FHIR resource by fetching it from the
     * FHIR server and applying the configured match strategies against its
     * {@code identifier[]}. Used by {@link ResourceEnricher} to resolve
     * RelatedPerson references for patient subject enrichment.
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
     * the configured match strategies. Used by {@link ResourceEnricher} when the
     * resource payload itself carries the identifier (e.g. a RelatedPerson callback),
     * and internally by {@link #resolveNationalId(String, String)} after fetching.
     *
     * @param identifiers JSON array node of FHIR identifiers
     * @return national-id value, or {@code null} if not found
     */
    public String extractNationalIdFromIdentifiers(JsonNode identifiers) {
        if (!identifiers.isArray()) {
            log.warn("No identifier array found — leaving reference unresolved");
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
                log.debug("Resolved national-id={} (strategy={})", value, strategy);
                return value;
            }
        }

        log.warn("No national-id found using strategies {} — leaving reference unresolved", matchStrategies);
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
