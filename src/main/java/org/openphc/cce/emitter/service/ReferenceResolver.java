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
    private static final String PRACTITIONER_PREFIX = "Practitioner/";

    private final List<String> matchStrategies;
    private final String nationalIdSystemSuffix;
    private final String nationalIdTypeCode;
    private final String personIdentityResourceType;
    private final String personIdentityResourcePrefix;
    private final Map<String, String> personIdentityReferencePathMap;

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
        this.personIdentityResourceType = refConfig.getPersonIdentityResourceType();
        this.personIdentityResourcePrefix = personIdentityResourceType + "/";
        this.personIdentityReferencePathMap = parsePathConfig(refConfig.getPersonIdentityReferencePaths());

        log.info("Identity resource type: {}, reference paths configured for: {}",
                personIdentityResourceType, personIdentityReferencePathMap.keySet());
    }

    /**
     * Resolves the national-id for a FHIR resource JSON payload.
     *
     * <p>If the resource IS the configured identity-source type, the national-id is
     * extracted from its own {@code identifier[]}. Otherwise, the configured path is
     * walked to find the identity-source reference, which is fetched from the FHIR
     * server to extract the national-id.
     *
     * @param incomingPayload parsed FHIR resource JSON tree (must be an ObjectNode)
     * @return national-id value, or {@code null} if unresolvable (forwarding should be skipped)
     */
    public String resolveNationalIdFromPayload(JsonNode incomingPayload) {
        String resourceType = incomingPayload.path("resourceType").asText("");
        try {

            // Identity-source resource — extract from own identifiers
            if (personIdentityResourceType.equals(resourceType)) {
                JsonNode identifiers = incomingPayload.path("identifier");
                String nationalId = extractNationalIdFromIdentifiers(identifiers);
                if (nationalId == null) {
                    log.error("{} resource has no national-id — skipping forward", personIdentityResourceType);
                }
                return nationalId;
            }

            // Other resources — find identity-source reference at configured path
            String personIdentityReferencePath = personIdentityReferencePathMap.get(resourceType);
            if (personIdentityReferencePath == null) {
                log.error("No reference path configured for resource type: {} — skipping forward",
                        resourceType);
                return null;
            }

            // personIdentityReferenceIdentifier: the FHIR resource ID parsed from the person reference
            // string at the configured path (e.g. "499063" from "RelatedPerson/499063")
            String personReferenceIdentifier = extractPersonReferenceIdentifierAtPath(incomingPayload, personIdentityReferencePath);
            if (personReferenceIdentifier == null) {
                log.error("No {} reference found at configured path '{}' for {} — skipping forward",
                        personIdentityResourceType, personIdentityReferencePath, resourceType);
                return null;
            }

            // Fetch the identity-source resource and extract national-id
            String nationalId = fetchAndExtractNationalId(personIdentityResourceType, personReferenceIdentifier);
            if (nationalId == null) {
                log.error("Failed to resolve national-id from {}/{} for {} — skipping forward",
                        personIdentityResourceType, personReferenceIdentifier, resourceType);
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
     * @param personIdentityResourceType FHIR resource type, e.g. {@code "RelatedPerson"}
     * @param personReferenceIdentifier   the person reference identifier extracted from the incoming payload
     * @return national-id value, or {@code null} if unresolvable
     */
    public String fetchAndExtractNationalId(String personIdentityResourceType, String personReferenceIdentifier) {
        log.debug("Resolving reference {}/{} via FHIR client (_elements=identifier)",
                personIdentityResourceType, personReferenceIdentifier);

        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource(personIdentityResourceType)
                    .withId(personReferenceIdentifier)
                    .elementsSubset("identifier")
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode responsePayloadNode = objectMapper.readTree(responseJson);
            return extractNationalIdFromIdentifiers(responsePayloadNode.path("identifier"));

        } catch (Exception e) {
            log.warn("Failed to resolve national-id for {}/{}: {} — leaving reference unresolved",
                    personIdentityResourceType, personReferenceIdentifier, e.getMessage());
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

    // ── Path-based identity-source lookup (recursive) ──────────────

    /**
     * Extracts the person identity resource ID by traversing the FHIR resource JSON
     * along a dot-separated path (e.g. {@code "participant.individual.reference"}).
     *
     * <p><b>Example:</b> Given an Encounter payload with path {@code "participant.individual.reference"}
     * and identity resource type {@code "RelatedPerson"}:
     * <pre>
     *   Input JSON:
     *   {
     *     "resourceType": "Encounter",
     *     "participant": [
     *       { "individual": { "reference": "RelatedPerson/499063" } },
     *       { "individual": { "reference": "Practitioner/123" } }
     *     ]
     *   }
     *
     *   Path segments: ["participant", "individual", "reference"]
     *   Traversal: root → participant (array, fan-out) → individual → reference
     *   Result: "499063" (from first matching "RelatedPerson/499063")
     * </pre>
     *
     * @param incomingPayload the incoming FHIR resource JSON tree (e.g. Encounter, ServiceRequest)
     * @param personIdentityReferencePath dot-separated path to the reference field
     *                            (e.g. {@code "participant.individual.reference"})
     * @return the person identity resource ID (e.g. {@code "499063"}), or {@code null} if
     *         no matching reference found at the path
     */
    private String extractPersonReferenceIdentifierAtPath(JsonNode incomingPayload, String personIdentityReferencePath) {
        String[] pathSegments = personIdentityReferencePath.split("\\.");
        // Start recursion at segmentIndex=0 (first path segment, e.g. pathSegments[0]="participant")
        // — the recursive walker increments this as it descends through intermediate segments
        return walkPathToPersonReference(incomingPayload, pathSegments, 0);
    }

    /**
     * Recursively walks the FHIR JSON tree along the configured path segments to locate
     * a person identity reference (e.g. {@code "RelatedPerson/499063"}).
     *
     * <p><b>Recursive strategy:</b>
     * <ol>
     *   <li><b>Base case (null/missing):</b> Node doesn't exist → return {@code null}</li>
     *   <li><b>Array fan-out:</b> If the current node is a JSON array (e.g. {@code participant[]}),
     *       recurse into each array element at the SAME depth — first match wins.
     *       <pre>
     *       participant: [{...}, {...}] → try element[0], then element[1], ...
     *       </pre></li>
     *   <li><b>Leaf segment (last in path):</b> Read the field value and check if it starts with
     *       the identity resource prefix (e.g. {@code "RelatedPerson/"}).
     *       <pre>
     *       node = { "reference": "RelatedPerson/499063" }
     *       segments[depth] = "reference" → extracts "499063"
     *       </pre></li>
     *   <li><b>Intermediate segment:</b> Descend into the named child field and recurse
     *       with {@code depth + 1}.
     *       <pre>
     *       node = { "individual": { "reference": "RelatedPerson/499063" } }
     *       segments[depth] = "individual" → descend into node.individual, depth++
     *       </pre></li>
     * </ol>
     *
     * @param currentPayloadNode     the JSON node within the incoming payload at the current traversal position
     * @param pathSegments    the full array of path segments (e.g. {@code ["participant", "individual", "reference"]})
     * @param segmentIndex    zero-based index into {@code pathSegments} indicating which segment to process next
     * @return the person identity resource ID (e.g. {@code "499063"}), or {@code null} if not found
     */
    private String walkPathToPersonReference(JsonNode currentPayloadNode, String[] pathSegments, int segmentIndex) {
        if (currentPayloadNode == null || currentPayloadNode.isMissingNode()) {
            return null;
        }

        // Array fan-out: e.g. "participant" is an array — recurse into each element at same depth
        if (currentPayloadNode.isArray()) {
            for (JsonNode arrayElement : currentPayloadNode) {
                String personReferenceId = walkPathToPersonReference(arrayElement, pathSegments, segmentIndex);
                if (personReferenceId != null) {
                    return personReferenceId;
                }
            }
            return null;
        }

        // Leaf segment: e.g. segmentIndex=2, segments[2]="reference" → read the reference string value
        if (segmentIndex == pathSegments.length - 1) {
            String referenceValue = currentPayloadNode.path(pathSegments[segmentIndex]).asText(null);
            if (referenceValue != null && referenceValue.startsWith(personIdentityResourcePrefix)) {
                return referenceValue.substring(personIdentityResourcePrefix.length());
            }
            return null;
        }

        // Intermediate segment: e.g. segmentIndex=1, segments[1]="individual" → descend into child
        JsonNode childPayloadNode = currentPayloadNode.path(pathSegments[segmentIndex]);
        return walkPathToPersonReference(childPayloadNode, pathSegments, segmentIndex + 1);
    }

    // ── Path config parsing ─────────────────────────────────────────

    static Map<String, String> parsePathConfig(List<String> pathEntries) {
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

    // ── Practitioner reference path walking ──────────────────────────

    /**
     * Extracts the first Practitioner reference node by traversing the FHIR resource JSON
     * along a dot-separated path (e.g. {@code "participant.individual"}).
     *
     * <p><b>Example:</b> Given an Encounter payload with path {@code "participant.individual"}:
     * <pre>
     *   Input JSON:
     *   {
     *     "resourceType": "Encounter",
     *     "participant": [
     *       { "individual": { "reference": "Practitioner/12345" } },
     *       { "individual": { "reference": "RelatedPerson/499063" } }
     *     ]
     *   }
     *
     *   Path segments: ["participant", "individual"]
     *   Traversal: root → participant (array, fan-out) → individual
     *   Result: { "reference": "Practitioner/12345" } (first matching Practitioner node)
     * </pre>
     *
     * <p><b>Example:</b> Given an Observation payload with path {@code "performer"}:
     * <pre>
     *   Input JSON:
     *   {
     *     "resourceType": "Observation",
     *     "performer": [
     *       { "reference": "Practitioner/789" },
     *       { "reference": "Organization/5" }
     *     ]
     *   }
     *
     *   Path segments: ["performer"]
     *   Traversal: root → performer (array at leaf, iterate for Practitioner)
     *   Result: { "reference": "Practitioner/789" } (first matching Practitioner node)
     * </pre>
     *
     * @param incomingPayload         the incoming FHIR resource JSON tree (e.g. Encounter, Observation)
     * @param practitionerDisplayPath dot-separated path to the Practitioner reference
     *                                (e.g. {@code "participant.individual"})
     * @return the first Practitioner reference object node (e.g. {@code {"reference": "Practitioner/12345"}}),
     *         or {@code null} if no Practitioner reference found at the path
     */
    public JsonNode extractPractitionerRefNodeAtPath(JsonNode incomingPayload, String practitionerDisplayPath) {
        // Split dot-path into segments for recursive tree traversal
        // e.g. "participant.individual" → segments ["participant", "individual"]
        String[] pathSegments = practitionerDisplayPath.split("\\.");
        // The second argument (0) is the starting depth — begins at the first path segment.
        // Example: path "participant.individual" → segments ["participant", "individual"]
        //   depth=0 → descend into "participant" (may be array — fan-out each element)
        //   depth=1 → at leaf "individual" → check if it has "reference": "Practitioner/..."
        return findFirstPractitionerNode(incomingPayload, pathSegments, 0);
    }

    /**
     * Walks the JSON tree along path segments and returns the first node whose
     * {@code reference} field starts with "Practitioner/".
     *
     * <ol>
     *   <li><b>Base case (null/missing):</b> Node doesn't exist → return {@code null}</li>
     *   <li><b>Array fan-out:</b> If current node is a JSON array (e.g. {@code participant[]}),
     *       recurse into each element at the SAME depth — first match wins.
     *       <pre>
     *       participant: [{...}, {...}] → try element[0], then element[1], ...
     *       </pre></li>
     *   <li><b>Intermediate segment:</b> Descend into named child field and recurse
     *       with {@code depth + 1}.
     *       <pre>
     *       node = { "individual": { "reference": "Practitioner/123" } }
     *       segments[depth] = "individual" → descend, depth++
     *       </pre></li>
     *   <li><b>Leaf segment (last in path):</b> Get child node. If it's an array,
     *       iterate to find the first Practitioner reference. If it's a single object,
     *       check directly.
     *       <pre>
     *       performer: [{"reference": "Practitioner/123"}, ...] → return first match
     *       individual: {"reference": "Practitioner/123"} → return if match
     *       </pre></li>
     * </ol>
     *
     * @param currentPayloadNode  current JSON node in traversal (initially the full enrichable payload)
     * @param pathSegments        dot-split path segments (e.g. {@code ["participant", "individual"]})
     * @param segmentIndex        zero-based index into pathSegments (0 = start from first segment)
     * @return the first Practitioner reference object node, or {@code null} if not found
     */
    private JsonNode findFirstPractitionerNode(JsonNode currentPayloadNode, String[] pathSegments, int segmentIndex) {
        if (currentPayloadNode == null || currentPayloadNode.isMissingNode()) {
            return null;
        }

        // Array fan-out: e.g. "participant" is an array — recurse into each element at same depth
        // participant: [{ "individual": {...} }, { "individual": {...} }] → try element[0], then element[1]
        if (currentPayloadNode.isArray()) {
            for (JsonNode arrayElement : currentPayloadNode) {
                JsonNode result = findFirstPractitionerNode(arrayElement, pathSegments, segmentIndex);
                if (result != null) return result;
            }
            return null;
        }

        // e.g. segmentIndex=0, pathSegments[0]="participant" → get node.participant
        JsonNode childPayloadNode = currentPayloadNode.path(pathSegments[segmentIndex]);

        // Intermediate segment: e.g. segmentIndex=0, pathSegments[0]="participant" → descend into child, segmentIndex++
        // node = { "participant": [{ "individual": {...} }] }
        // segments[0] = "participant" → descend into node.participant, segmentIndex becomes 1
        if (segmentIndex < pathSegments.length - 1) {
            return findFirstPractitionerNode(childPayloadNode, pathSegments, segmentIndex + 1);
        }

        // Leaf segment: e.g. segmentIndex=1, pathSegments[1]="individual" → childPayloadNode is the reference object
        // node = { "individual": { "reference": "Practitioner/123" } } → return the object if Practitioner
        // node = { "performer": [{"reference": "Practitioner/123"}, {"reference": "Organization/5"}] } → return first match
        if (childPayloadNode.isArray()) {
            for (JsonNode arrayElement : childPayloadNode) {
                if (isPractitionerReference(arrayElement)) return arrayElement;
            }
            return null;
        }
        return isPractitionerReference(childPayloadNode) ? childPayloadNode : null;
    }

    /**
     * Checks whether a JSON node is a FHIR reference object pointing to a Practitioner.
     *
     * <p><b>Example match:</b>
     * <pre>
     *   { "reference": "Practitioner/123" }  → true
     *   { "reference": "Organization/5" }    → false
     *   "Practitioner/123"                   → false (not an object)
     * </pre>
     *
     * @param candidateReferenceNode a JSON node to test — typically an {@code arrayElement}
     *        or {@code childPayloadNode} from {@link #findFirstPractitionerNode}
     * @return {@code true} if the node is an object with a {@code reference} field
     *         starting with {@code "Practitioner/"}
     */
    private boolean isPractitionerReference(JsonNode candidateReferenceNode) {
        if (!candidateReferenceNode.isObject()) return false;
        String referenceValue = candidateReferenceNode.path("reference").asText(null);
        return referenceValue != null && referenceValue.startsWith(PRACTITIONER_PREFIX);
    }

    // ── Practitioner display name resolution ────────────────────────

    /**
     * Fetches a Practitioner resource from the FHIR server and extracts a
     * human-readable display name from its {@code name[]} array.
     * Results are cached in-memory to avoid repeated FHIR calls for the same practitioner.
     *
     * <p>Name extraction priority:
     * <ol>
     *   <li>{@code name[0].text} — pre-composed display name (preferred)</li>
     *   <li>{@code name[0].given[0] + " " + name[0].family} — assembled from parts</li>
     *   <li>{@code name[0].family} — family name only (fallback)</li>
     * </ol>
     *
     * @param practitionerId the Practitioner resource ID (e.g. "12345")
     * @return display name string, or {@code null} if the resource cannot be fetched
     *         or has no usable name
     */
    public String fetchPractitionerDisplayName(String practitionerId) {
        log.debug("Fetching Practitioner/{} for display name resolution", practitionerId);
        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource("Practitioner")
                    .withId(practitionerId)
                    .elementsSubset("name")
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode responseNode = objectMapper.readTree(responseJson);
            return extractDisplayNameFromNames(responseNode.path("name"));

        } catch (Exception e) {
            log.warn("Failed to fetch Practitioner/{} for display name: {}", practitionerId, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a human-readable display name from a FHIR {@code name[]} (HumanName) array.
     *
     * @param names JSON array of HumanName objects
     * @return display name, or {@code null} if no usable name found
     */
    private String extractDisplayNameFromNames(JsonNode names) {
        if (!names.isArray() || names.isEmpty()) {
            return null;
        }

        JsonNode name = names.get(0);

        // Prefer pre-composed text
        String text = name.path("text").asText(null);
        if (text != null && !text.isBlank()) {
            return text;
        }

        // Assemble from given + family
        String family = name.path("family").asText(null);
        JsonNode givenArray = name.path("given");
        String given = (givenArray.isArray() && !givenArray.isEmpty())
                ? givenArray.get(0).asText(null) : null;

        if (given != null && !given.isBlank() && family != null && !family.isBlank()) {
            return given + " " + family;
        }
        if (family != null && !family.isBlank()) {
            return family;
        }
        if (given != null && !given.isBlank()) {
            return given;
        }

        return null;
    }

    // ── Location enrichment resolution ──────────────────────────────

    /**
     * Fetches an Encounter resource from the FHIR server and extracts its
     * {@code location[]} array as a JSON node.
     *
     * <p>The Encounter's {@code location[]} structure in FHIR R4:
     * <pre>
     *   "location": [
     *     { "location": { "reference": "Location/123", "display": "Ward A" }, "status": "active" }
     *   ]
     * </pre>
     *
     * @param encounterId the Encounter resource ID (e.g. "456")
     * @return the {@code location[]} JSON array from the Encounter, or {@code null}
     *         if the Encounter cannot be fetched or has no location entries
     */
    public JsonNode fetchEncounterLocations(String encounterId) {
        log.debug("Fetching Encounter/{} for location resolution (_elements=location)", encounterId);
        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource("Encounter")
                    .withId(encounterId)
                    .elementsSubset("location")
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode responseNode = objectMapper.readTree(responseJson);
            JsonNode locations = responseNode.path("location");
            if (locations.isArray() && !locations.isEmpty()) {
                return locations;
            }
            return null;

        } catch (Exception e) {
            log.warn("Failed to fetch Encounter/{} for location: {}", encounterId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a Location resource from the FHIR server and extracts its display name.
     *
     * <p>Name extraction priority:
     * <ol>
     *   <li>{@code name} — the Location's human-readable name</li>
     * </ol>
     *
     * @param locationId the Location resource ID (e.g. "123")
     * @return the Location name, or {@code null} if the resource cannot be fetched
     *         or has no name
     */
    public String fetchLocationDisplayName(String locationId) {
        log.debug("Fetching Location/{} for display name resolution", locationId);
        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource("Location")
                    .withId(locationId)
                    .elementsSubset("name")
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode responseNode = objectMapper.readTree(responseJson);
            String name = responseNode.path("name").asText(null);
            return (name != null && !name.isBlank()) ? name : null;

        } catch (Exception e) {
            log.warn("Failed to fetch Location/{} for display name: {}", locationId, e.getMessage());
            return null;
        }
    }

    /**
     * Fetches an Organization resource from the FHIR server and extracts its display name.
     *
     * @param organizationId the Organization resource ID (e.g. "1302")
     * @return the Organization name, or {@code null} if the resource cannot be fetched
     *         or has no name
     */
    public String fetchOrganizationDisplayName(String organizationId) {
        log.debug("Fetching Organization/{} for display name resolution", organizationId);
        try {
            IGenericClient client = fhirClientFactory.createClient(properties.getFhirServer());
            IBaseResource resource = client.read()
                    .resource("Organization")
                    .withId(organizationId)
                    .elementsSubset("name")
                    .execute();

            String responseJson = fhirContext.newJsonParser().encodeResourceToString(resource);
            JsonNode responseNode = objectMapper.readTree(responseJson);
            String name = responseNode.path("name").asText(null);
            return (name != null && !name.isBlank()) ? name : null;

        } catch (Exception e) {
            log.warn("Failed to fetch Organization/{} for display name: {}", organizationId, e.getMessage());
            return null;
        }
    }

}
