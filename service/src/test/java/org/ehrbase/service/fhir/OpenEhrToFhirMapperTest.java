/*
 * Copyright (c) 2024 vitasystems GmbH.
 *
 * This file is part of project EHRbase
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ehrbase.service.fhir;

import static org.assertj.core.api.Assertions.assertThat;

import ca.uhn.fhir.context.FhirContext;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.time.OffsetDateTime;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the openEHR-to-FHIR R4 transformation layer.
 */
class OpenEhrToFhirMapperTest {

    private OpenEhrToFhirMapper mapper;
    private FhirContext fhirContext;

    @BeforeEach
    void setUp() {
        FhirMappingConfig config = new FhirMappingConfig();
        mapper = new OpenEhrToFhirMapper(config);
        fhirContext = FhirContext.forR4();
    }

    @Test
    void mapEmptyComposition_producesValidBundle() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.map(composition, "test-template");

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
        // Should have at least the encounter from context
        assertThat(bundle.getEntry()).isNotEmpty();

        // Verify it serializes to valid JSON
        String json = fhirContext.newJsonParser().encodeResourceToString(bundle);
        assertThat(json).contains("\"resourceType\"");
        assertThat(json).contains("\"Bundle\"");
        assertThat(json).contains("\"transaction\"");
    }

    @Test
    void mapObservation_producesObservationResource() {
        Composition composition = createCompositionWithObservation();

        Bundle bundle = mapper.map(composition, null);

        assertThat(bundle.getEntry()).hasSizeGreaterThanOrEqualTo(2); // context encounter + observation
        boolean hasObservation = bundle.getEntry().stream()
                .anyMatch(e -> e.getResource() instanceof org.hl7.fhir.r4.model.Observation);
        assertThat(hasObservation).isTrue();

        // Find the observation and verify mapping
        org.hl7.fhir.r4.model.Observation fhirObs = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof org.hl7.fhir.r4.model.Observation)
                .map(r -> (org.hl7.fhir.r4.model.Observation) r)
                .findFirst()
                .orElseThrow();

        assertThat(fhirObs.getStatus())
                .isEqualTo(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        assertThat(fhirObs.getCode().getText()).isEqualTo("Blood pressure");
    }

    @Test
    void mapObservationWithQuantity_mapsValueCorrectly() {
        Composition composition = createCompositionWithQuantityObservation();

        Bundle bundle = mapper.map(composition, null);

        org.hl7.fhir.r4.model.Observation fhirObs = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof org.hl7.fhir.r4.model.Observation)
                .map(r -> (org.hl7.fhir.r4.model.Observation) r)
                .findFirst()
                .orElseThrow();

        // Single element -> should be main value
        assertThat(fhirObs.hasValueQuantity()).isTrue();
        assertThat(fhirObs.getValueQuantity().getValue().doubleValue()).isEqualTo(36.5);
        assertThat(fhirObs.getValueQuantity().getUnit()).isEqualTo("°C");
    }

    @Test
    void mapObservationWithMultipleElements_mapsAsComponents() {
        Composition composition = createCompositionWithMultiElementObservation();

        Bundle bundle = mapper.map(composition, null);

        org.hl7.fhir.r4.model.Observation fhirObs = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof org.hl7.fhir.r4.model.Observation)
                .map(r -> (org.hl7.fhir.r4.model.Observation) r)
                .findFirst()
                .orElseThrow();

        // Multiple elements -> should be components
        assertThat(fhirObs.getComponent()).hasSize(2);
        assertThat(fhirObs.getComponent().get(0).getCode().getText()).isEqualTo("Systolic");
        assertThat(fhirObs.getComponent().get(1).getCode().getText()).isEqualTo("Diastolic");
    }

    @Test
    void mapCompositionContext_producesEncounter() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.map(composition, null);

        boolean hasEncounter = bundle.getEntry().stream()
                .anyMatch(e -> e.getResource() instanceof org.hl7.fhir.r4.model.Encounter);
        assertThat(hasEncounter).isTrue();
    }

    @Test
    void bundleEntriesHaveTransactionRequests() {
        Composition composition = createCompositionWithObservation();

        Bundle bundle = mapper.map(composition, null);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            assertThat(entry.getRequest()).isNotNull();
            assertThat(entry.getRequest().getMethod()).isEqualTo(Bundle.HTTPVerb.POST);
            assertThat(entry.getRequest().getUrl()).isNotNull();
            assertThat(entry.getFullUrl()).startsWith("urn:uuid:");
        }
    }

    @Test
    void mapCodedText_mapsTerminologyToFhirSystem() {
        Composition composition = createCompositionWithCodedObservation();

        Bundle bundle = mapper.map(composition, null);

        org.hl7.fhir.r4.model.Observation fhirObs = bundle.getEntry().stream()
                .map(Bundle.BundleEntryComponent::getResource)
                .filter(r -> r instanceof org.hl7.fhir.r4.model.Observation)
                .map(r -> (org.hl7.fhir.r4.model.Observation) r)
                .findFirst()
                .orElseThrow();

        assertThat(fhirObs.getCode().getCoding()).isNotEmpty();
        assertThat(fhirObs.getCode().getCodingFirstRep().getSystem())
                .isEqualTo("http://loinc.org");
        assertThat(fhirObs.getCode().getCodingFirstRep().getCode()).isEqualTo("85354-9");
    }

    @Test
    void serializationProducesValidFhirJson() {
        FhirCompositionServiceImp service = new FhirCompositionServiceImp();
        Composition composition = createCompositionWithObservation();

        String json = service.serialize(composition, "test-template");

        assertThat(json).isNotNull();
        assertThat(json).contains("\"resourceType\"");
        assertThat(json).contains("\"Bundle\"");
        assertThat(json).contains("\"transaction\"");
        assertThat(json).contains("\"Observation\"");

        // Verify it can be parsed back
        Bundle parsed = fhirContext.newJsonParser().parseResource(Bundle.class, json);
        assertThat(parsed).isNotNull();
        assertThat(parsed.getType()).isEqualTo(Bundle.BundleType.TRANSACTION);
    }

    @Test
    void mappingConfig_templateOverrideTakesPrecedence() {
        FhirMappingConfig config = new FhirMappingConfig();
        config.addTemplateOverride("my-template", "openEHR-EHR-OBSERVATION.blood_pressure.v2", "DiagnosticReport");

        String resolved =
                config.resolveFhirResourceType("my-template", "openEHR-EHR-OBSERVATION.blood_pressure.v2", "OBSERVATION");
        assertThat(resolved).isEqualTo("DiagnosticReport");

        // Without the template, global mapping should apply
        String globalResolved =
                config.resolveFhirResourceType(null, "openEHR-EHR-OBSERVATION.blood_pressure.v2", "OBSERVATION");
        assertThat(globalResolved).isEqualTo("Observation");
    }

    @Test
    void mappingConfig_fallsBackToRmType() {
        FhirMappingConfig config = new FhirMappingConfig();

        String resolved = config.resolveFhirResourceType(null, "openEHR-EHR-OBSERVATION.unknown.v1", "OBSERVATION");
        assertThat(resolved).isEqualTo("Observation");

        String evalResolved = config.resolveFhirResourceType(null, "openEHR-EHR-EVALUATION.unknown.v1", "EVALUATION");
        assertThat(evalResolved).isEqualTo("Condition");
    }

    // --- Helper methods to build test compositions ---

    private Composition createMinimalComposition() {
        Composition composition = new Composition();
        composition.setUid(new ObjectVersionId("12345678-1234-1234-1234-123456789012::test::1"));
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setComposer(new PartyIdentified(null, "Dr. Test", null));

        EventContext context = new EventContext();
        context.setStartTime(new DvDateTime(OffsetDateTime.now()));
        context.setSetting(new DvCodedText("primary care", new CodePhrase(new TerminologyId("openehr"), "228")));
        composition.setContext(context);

        return composition;
    }

    private Composition createCompositionWithObservation() {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.blood_pressure.v2");
        observation.setName(new DvText("Blood pressure"));

        composition.setContent(List.of(observation));
        return composition;
    }

    private Composition createCompositionWithQuantityObservation() {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.body_temperature.v2");
        observation.setName(new DvText("Body temperature"));

        // Build the data hierarchy: History -> PointEvent -> ItemTree -> Element(DvQuantity)
        Element tempElement = new Element();
        tempElement.setArchetypeNodeId("at0004");
        tempElement.setName(new DvText("Temperature"));
        tempElement.setValue(new DvQuantity("°C", 36.5, 1L));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("data"));
        itemTree.setItems(List.of(tempElement));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setData(itemTree);
        event.setTime(new DvDateTime(OffsetDateTime.now()));

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));
        history.setOrigin(new DvDateTime(OffsetDateTime.now()));
        history.setEvents(List.of(event));

        observation.setData(history);

        composition.setContent(List.of(observation));
        return composition;
    }

    private Composition createCompositionWithMultiElementObservation() {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.blood_pressure.v2");
        observation.setName(new DvText("Blood pressure"));

        Element systolic = new Element();
        systolic.setArchetypeNodeId("at0004");
        systolic.setName(new DvText("Systolic"));
        systolic.setValue(new DvQuantity("mm[Hg]", 120.0, 0L));

        Element diastolic = new Element();
        diastolic.setArchetypeNodeId("at0005");
        diastolic.setName(new DvText("Diastolic"));
        diastolic.setValue(new DvQuantity("mm[Hg]", 80.0, 0L));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("data"));
        itemTree.setItems(List.of(systolic, diastolic));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setData(itemTree);
        event.setTime(new DvDateTime(OffsetDateTime.now()));

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));
        history.setOrigin(new DvDateTime(OffsetDateTime.now()));
        history.setEvents(List.of(event));

        observation.setData(history);

        composition.setContent(List.of(observation));
        return composition;
    }

    private Composition createCompositionWithCodedObservation() {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.blood_pressure.v2");
        observation.setName(
                new DvCodedText("Blood pressure", new CodePhrase(new TerminologyId("LOINC"), "85354-9")));

        composition.setContent(List.of(observation));
        return composition;
    }
}
