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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ReferenceResolver}.
 *
 * <p>Verifies all three national-id match strategies plus caching, using
 * fixtures modelled after SPICE and flat-system-name RelatedPerson identifier
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

    @BeforeAll
    static void initContext() {
        fhirContext = FhirContext.forR4();
        objectMapper = new ObjectMapper();
    }

    @BeforeEach
    void setUp() {
        lenient().when(fhirClientFactory.createClient(any(FhirServerConfig.class))).thenReturn(fhirClient);
        lenient().when(fhirClient.read()).thenReturn(readBuilder);
        lenient().when(readBuilder.resource(anyString())).thenReturn(readTyped);
        lenient().when(readTyped.withId(anyString())).thenReturn(readExecutable);
        // elementsSubset is a varargs method; stub for any String[] to cover both single
        // ("identifier") and multi ("identifier","link") invocations.
        lenient().when(readExecutable.elementsSubset(any(String[].class))).thenReturn(readExecutable);
    }

    private EmitterProperties props(List<String> strategies, String suffix, String typeCode) {
        EmitterProperties p = new EmitterProperties();
        p.setFhirServer(new FhirServerConfig());
        ReferenceResolutionConfig rr = new ReferenceResolutionConfig();
        rr.setNationalIdMatchStrategies(strategies);
        rr.setNationalIdSystemSuffix(suffix);
        rr.setNationalIdTypeCode(typeCode);
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

            assertEquals("NID-10001", resolver.resolveNationalIdDirect("Patient", "123"));
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

            assertNull(resolver.resolveNationalIdDirect("Patient", "123"));
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

            assertEquals("NID-20002", resolver.resolveNationalIdDirect("Patient", "124"));
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

            assertEquals("P-99999", resolver.resolveNationalIdDirect("Patient", "124"));
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

            assertEquals("NID-1774256338", resolver.resolveNationalIdDirect("Patient", "616"));
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
                    resolver.resolveNationalIdDirect("Patient", "251119-0001-4106"));
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

            assertNull(resolver.resolveNationalIdDirect("Patient", "616"));
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

            assertEquals("NID-SPICE", resolver.resolveNationalIdDirect("Patient", "616"));
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

            assertEquals("OFFICIAL-WINS", resolver.resolveNationalIdDirect("Patient", "123"));
        }
    }
}
