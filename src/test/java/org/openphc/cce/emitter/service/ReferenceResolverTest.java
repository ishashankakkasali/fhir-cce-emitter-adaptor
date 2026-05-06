package org.openphc.cce.emitter.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.gclient.IRead;
import ca.uhn.fhir.rest.gclient.IReadExecutable;
import ca.uhn.fhir.rest.gclient.IReadTyped;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.hl7.fhir.r4.model.RelatedPerson;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openphc.cce.emitter.config.EmitterProperties;
import org.openphc.cce.emitter.config.EmitterProperties.FhirServerConfig;
import org.openphc.cce.emitter.config.EmitterProperties.ReferenceResolutionConfig;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferenceResolver}.
 *
 * <p>Verifies all three national-id match strategies plus caching, including
 * fixtures modelled after SPICE and flat-system-name Patient identifier
 * structures.
 */
@ExtendWith(MockitoExtension.class)
class ReferenceResolverTest {

    private static FhirContext fhirContext;
    private static ObjectMapper objectMapper;

    @Mock private FhirClientFactory fhirClientFactory;
    @Mock private IGenericClient fhirClient;
    @Mock private IRead readBuilder;
    @Mock private IReadTyped<IBaseResource> readTyped;
    @Mock private IReadExecutable<IBaseResource> readExecutable;

    /** Per-test request-scoped cache, fresh for every test method. */
    private Map<String, String> cache;

    @BeforeAll
    static void initContext() {
        fhirContext = FhirContext.forR4();
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void setUp() {
        cache = new HashMap<>();
        lenient().when(fhirClientFactory.createClient(any(FhirServerConfig.class))).thenReturn(fhirClient);
        lenient().when(fhirClient.read()).thenReturn(readBuilder);
        lenient().when(readBuilder.resource(anyString())).thenReturn(readTyped);
        lenient().when(readTyped.withId(anyString())).thenReturn(readExecutable);
        // elementsSubset is a varargs method; stub for any String[] to cover both single
        // ("identifier") and multi ("identifier","link") invocations.
        lenient().when(readExecutable.elementsSubset(any(String[].class))).thenReturn(readExecutable);
    }

    private EmitterProperties props(List<String> strategies, String suffix, String typeCode) {
        return props(strategies, suffix, typeCode, List.of());
    }

    private EmitterProperties props(List<String> strategies, String suffix, String typeCode,
                                    List<String> linkFollow) {
        EmitterProperties p = new EmitterProperties();
        p.setFhirServer(new FhirServerConfig());
        ReferenceResolutionConfig rr = new ReferenceResolutionConfig();
        rr.setResolvableTypes(List.of("Patient"));
        rr.setNationalIdMatchStrategies(strategies);
        rr.setNationalIdSystemSuffix(suffix);
        rr.setNationalIdTypeCode(typeCode);
        rr.setLinkFollow(linkFollow);
        p.setReferenceResolution(rr);
        return p;
    }

    private void stubReturn(IBaseResource resource) {
        when(readExecutable.execute()).thenReturn(resource);
    }

    private ReferenceResolver newResolver(EmitterProperties p) {
        return new ReferenceResolver(p, fhirContext, fhirClientFactory, objectMapper);
    }

    @Nested
    @DisplayName("Strategy: use-official (FHIR R4 standard)")
    class UseOfficialStrategy {

        @Test
        @DisplayName("returns identifier value where use=official")
        void resolvesByUseOfficial() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.SECONDARY)
                    .setSystem("https://example.org/id/local")
                    .setValue("L-55");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("https://example.org/id/national")
                    .setValue("NID-10001");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("use-official"), "/national-id", "NI"));

            assertEquals("NID-10001", resolver.resolveNationalId("Patient", "123", cache));
        }

        @Test
        @DisplayName("returns null when no identifier has use=official")
        void noOfficialIdentifier() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier().setSystem("X").setValue("Y");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("use-official"), "/national-id", "NI"));

            assertNull(resolver.resolveNationalId("Patient", "123", cache));
        }
    }

    @Nested
    @DisplayName("Strategy: type-code (HL7 v2-0203)")
    class TypeCodeStrategy {

        @Test
        @DisplayName("returns identifier value where type.coding[].code matches configured code")
        void resolvesByTypeCode() {
            Patient patient = new Patient();
            patient.setId("124");
            Identifier id = patient.addIdentifier();
            id.setSystem("https://country.gov/id").setValue("NID-20002");
            id.getType().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                    .setCode("NI")
                    .setDisplay("National unique individual identifier");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("type-code"), "/national-id", "NI"));

            assertEquals("NID-20002", resolver.resolveNationalId("Patient", "124", cache));
        }

        @Test
        @DisplayName("respects configured type code (e.g. PPN for passport)")
        void resolvesByCustomTypeCode() {
            Patient patient = new Patient();
            patient.setId("124");
            Identifier id = patient.addIdentifier();
            id.setSystem("https://country.gov/passport").setValue("P-99999");
            id.getType().addCoding()
                    .setSystem("http://terminology.hl7.org/CodeSystem/v2-0203")
                    .setCode("PPN");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("type-code"), "/national-id", "PPN"));

            assertEquals("P-99999", resolver.resolveNationalId("Patient", "124", cache));
        }
    }

    @Nested
    @DisplayName("Strategy: system-suffix (SPICE / custom servers)")
    class SystemSuffixStrategy {

        @Test
        @DisplayName("matches SPICE-style identifier with /national-id suffix")
        void resolvesSpiceStyle() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/identity-type")
                    .setValue("National ID");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/national-id")
                    .setValue("NID-1774256338");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/village-id")
                    .setValue("34");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertEquals("NID-1774256338", resolver.resolveNationalId("Patient", "616", cache));
        }

        @Test
        @DisplayName("matches flat identifier system (system=\"NID\")")
        void resolvesFlatSystemStyle() {
            // Some servers use flat system names like "NID" and "UPI"
            // (no use field, no type.coding).
            // With suffix="NID", endsWith("NID") matches the NID entry exactly.
            Patient patient = new Patient();
            patient.setId("251119-0001-4106");
            patient.addIdentifier().setSystem("NID").setValue("1192880005226000");
            patient.addIdentifier().setSystem("UPI").setValue("251119-0001-4106");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "NID", "NI"));

            assertEquals("1192880005226000",
                    resolver.resolveNationalId("Patient", "251119-0001-4106", cache));
        }

        @Test
        @DisplayName("returns null when no identifier system ends with the configured suffix")
        void noMatchingSuffix() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/village-id").setValue("34");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertNull(resolver.resolveNationalId("Patient", "616", cache));
        }
    }

    @Nested
    @DisplayName("Multi-strategy ordering (defaults: use-official → type-code → system-suffix)")
    class MultiStrategyOrdering {

        private final List<String> defaults =
                List.of("use-official", "type-code", "system-suffix");

        @Test
        @DisplayName("falls through to system-suffix for SPICE Patient (no use/type fields)")
        void spiceFallsThroughToSuffix() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/national-id").setValue("NID-SPICE");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            assertEquals("NID-SPICE", resolver.resolveNationalId("Patient", "616", cache));
        }

        @Test
        @DisplayName("use-official wins over later strategies when present")
        void useOfficialWinsFirst() {
            Patient patient = new Patient();
            patient.setId("123");
            patient.addIdentifier()
                    .setUse(Identifier.IdentifierUse.OFFICIAL)
                    .setSystem("https://example.org/national")
                    .setValue("OFFICIAL-WINS");
            patient.addIdentifier()
                    .setSystem("http://spice/fhir/national-id")
                    .setValue("SUFFIX-LOSES");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(props(defaults, "/national-id", "NI"));

            assertEquals("OFFICIAL-WINS", resolver.resolveNationalId("Patient", "123", cache));
        }
    }

    @Nested
    @DisplayName("Caching (per-request scope)")
    class CachingBehavior {

        @Test
        @DisplayName("FHIR server is called only once per (type,id) within a single request")
        void cachesResolvedValue() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/national-id").setValue("NID-1");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertEquals("NID-1", resolver.resolveNationalId("Patient", "616", cache));
            assertEquals("NID-1", resolver.resolveNationalId("Patient", "616", cache));
            assertEquals("NID-1", resolver.resolveNationalId("Patient", "616", cache));

            verify(fhirClientFactory, times(1)).createClient(any(FhirServerConfig.class));
        }

        @Test
        @DisplayName("cache miss (no national-id found) is also cached within the same request")
        void cachesMissesAsEmpty() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/village-id").setValue("34");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertNull(resolver.resolveNationalId("Patient", "616", cache));
            assertNull(resolver.resolveNationalId("Patient", "616", cache));

            verify(fhirClientFactory, times(1)).createClient(any(FhirServerConfig.class));
        }

        @Test
        @DisplayName("a separate request (fresh cache) re-fetches — cache does NOT leak across requests")
        void freshCacheRefetches() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier().setSystem("http://spice/fhir/national-id").setValue("NID-1");
            stubReturn(patient);

            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            // Request 1
            assertEquals("NID-1", resolver.resolveNationalId("Patient", "616", new HashMap<>()));
            // Request 2 — different (fresh) cache map
            assertEquals("NID-1", resolver.resolveNationalId("Patient", "616", new HashMap<>()));

            verify(fhirClientFactory, times(2)).createClient(any(FhirServerConfig.class));
        }
    }

    @Nested
    @DisplayName("isResolvable / non-resolvable types")
    class ResolvableTypes {

        @Test
        @DisplayName("returns null and skips FHIR fetch for unconfigured types")
        void skipsUnconfiguredType() {
            ReferenceResolver resolver = newResolver(
                    props(List.of("system-suffix"), "/national-id", "NI"));

            assertNull(resolver.resolveNationalId("Practitioner", "5", cache));
            verify(fhirClientFactory, times(0)).createClient(any(FhirServerConfig.class));
        }
    }

    @Nested
    @DisplayName("Link-follow (cross-resource resolution: Patient → RelatedPerson)")
    class LinkFollow {

        /** SPICE-shaped Patient that has NO national-id of its own, but links to a RelatedPerson. */
        private Patient spicePatientLinkingTo(String patientId, String relatedPersonId) {
            Patient patient = new Patient();
            patient.setId(patientId);
            // Patient has demographic identifiers but no /national-id of its own
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/identity-type")
                    .setValue("National ID");
            patient.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/village-id")
                    .setValue("34");
            patient.addLink().setOther(new Reference("RelatedPerson/" + relatedPersonId));
            return patient;
        }

        /** SPICE-shaped RelatedPerson that carries the national-id under .../fhir/national-id. */
        private RelatedPerson spiceRelatedPerson(String relatedPersonId, String nationalId) {
            RelatedPerson rp = new RelatedPerson();
            rp.setId(relatedPersonId);
            rp.addIdentifier()
                    .setSystem("http://spice-20-server-hapi-fhir-1:8080/fhir/national-id")
                    .setValue(nationalId);
            return rp;
        }

        @Test
        @DisplayName("resolves Patient/616 to the linked RelatedPerson's national-id")
        void followsLinkToRelatedPerson() {
            Patient patient = spicePatientLinkingTo("616", "498113");
            RelatedPerson rp = spiceRelatedPerson("498113", "1234567890011");
            // First read → Patient, second read → RelatedPerson
            when(readExecutable.execute()).thenReturn(patient, rp);

            ReferenceResolver resolver = newResolver(props(
                    List.of("use-official", "type-code", "system-suffix"),
                    "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("1234567890011", resolver.resolveNationalId("Patient", "616", cache));
        }

        @Test
        @DisplayName("requests both 'identifier' and 'link' elements for link-follow source types")
        void requestsLinkElementForSourceType() {
            Patient patient = spicePatientLinkingTo("616", "498113");
            RelatedPerson rp = spiceRelatedPerson("498113", "NID-LINKED");
            when(readExecutable.execute()).thenReturn(patient, rp);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            resolver.resolveNationalId("Patient", "616", cache);

            // Patient fetch should request identifier + link
            verify(readExecutable).elementsSubset("identifier", "link");
            // Linked RelatedPerson fetch should request identifier only
            verify(readExecutable).elementsSubset("identifier");
        }

        @Test
        @DisplayName("falls back to Patient's own identifier[] when no matching link[] entry exists")
        void fallsBackWhenNoLink() {
            // Patient has its own /national-id AND no link[] — fallback path returns the Patient's own value
            Patient patient = new Patient();
            patient.setId("616");
            patient.addIdentifier()
                    .setSystem("http://spice/fhir/national-id")
                    .setValue("NID-FROM-PATIENT");
            when(readExecutable.execute()).thenReturn(patient);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("NID-FROM-PATIENT",
                    resolver.resolveNationalId("Patient", "616", cache));
            // Only one fetch — no recursion since no link target was found
            verify(fhirClientFactory, times(1)).createClient(any(FhirServerConfig.class));
        }

        @Test
        @DisplayName("falls back to Patient's identifier[] when linked RelatedPerson has no national-id")
        void fallsBackWhenLinkedTargetHasNoNationalId() {
            Patient patient = spicePatientLinkingTo("616", "498113");
            patient.addIdentifier()
                    .setSystem("http://spice/fhir/national-id")
                    .setValue("NID-FALLBACK");
            RelatedPerson rp = new RelatedPerson();
            rp.setId("498113");
            rp.addIdentifier().setSystem("http://spice/fhir/village-id").setValue("34");
            when(readExecutable.execute()).thenReturn(patient, rp);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("NID-FALLBACK",
                    resolver.resolveNationalId("Patient", "616", cache));
        }

        @Test
        @DisplayName("ignores link[] entries pointing at unrelated resource types")
        void ignoresUnrelatedLinkTargets() {
            // Link only to Person (not RelatedPerson) — link-follow finds nothing,
            // falls back to Patient's own identifier[]
            Patient patient = new Patient();
            patient.setId("616");
            patient.addLink().setOther(new Reference("Person/9999"));
            patient.addIdentifier()
                    .setSystem("http://spice/fhir/national-id")
                    .setValue("NID-OWN");
            when(readExecutable.execute()).thenReturn(patient);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("NID-OWN", resolver.resolveNationalId("Patient", "616", cache));
            verify(fhirClientFactory, times(1)).createClient(any(FhirServerConfig.class));
        }

        @Test
        @DisplayName("handles absolute link references (http://server/fhir/RelatedPerson/498113)")
        void resolvesAbsoluteLinkReference() {
            Patient patient = new Patient();
            patient.setId("616");
            patient.addLink().setOther(new Reference(
                    "http://spice-20-server-hapi-fhir-1:8080/fhir/RelatedPerson/498113"));
            RelatedPerson rp = spiceRelatedPerson("498113", "NID-ABS");
            when(readExecutable.execute()).thenReturn(patient, rp);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("NID-ABS", resolver.resolveNationalId("Patient", "616", cache));
        }

        @Test
        @DisplayName("caches the linked RelatedPerson lookup across multiple Patients in the same request")
        void cachesLinkedTargetAcrossPatients() {
            // Two different Patients both linking to the SAME RelatedPerson —
            // RelatedPerson should be fetched only once due to per-request cache.
            Patient p1 = spicePatientLinkingTo("616", "498113");
            Patient p2 = spicePatientLinkingTo("617", "498113");
            RelatedPerson rp = spiceRelatedPerson("498113", "NID-SHARED");
            // Order: Patient/616, RelatedPerson/498113, Patient/617 (RelatedPerson/498113 served from cache)
            when(readExecutable.execute()).thenReturn(p1, rp, p2);

            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertEquals("NID-SHARED", resolver.resolveNationalId("Patient", "616", cache));
            assertEquals("NID-SHARED", resolver.resolveNationalId("Patient", "617", cache));

            // 3 client creations: Patient/616, RelatedPerson/498113, Patient/617 (RelatedPerson NOT re-fetched)
            verify(fhirClientFactory, times(3)).createClient(any(FhirServerConfig.class));
        }

        @Test
        @DisplayName("does NOT rewrite RelatedPerson references in payload (link-follow does not affect resolvableTypes gate)")
        void doesNotRewriteRelatedPersonReferences() {
            // resolvableTypes=[Patient] only. Asking to resolve a RelatedPerson reference
            // directly should still return null and skip any FHIR fetch.
            ReferenceResolver resolver = newResolver(props(
                    List.of("system-suffix"), "/national-id", "NI",
                    List.of("Patient:RelatedPerson")));

            assertNull(resolver.resolveNationalId("RelatedPerson", "498113", cache));
            verify(fhirClientFactory, times(0)).createClient(any(FhirServerConfig.class));
        }
    }
}
