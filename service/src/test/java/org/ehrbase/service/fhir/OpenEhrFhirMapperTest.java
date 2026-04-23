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

import com.nedap.archie.rm.archetyped.Archetyped;
import com.nedap.archie.rm.archetyped.TemplateId;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
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
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenEhrFhirMapperTest {

    private OpenEhrFhirMapper mapper;
    private UUID testEhrId;

    @BeforeEach
    void setUp() {
        mapper = new OpenEhrFhirMapper();
        testEhrId = UUID.randomUUID();
    }

    @Test
    void shouldMapEmptyCompositionToBundle() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        // Should contain at least the Patient resource
        assertThat(bundle.getEntry()).hasSizeGreaterThanOrEqualTo(1);
        assertThat(bundle.getEntry().get(0).getResource()).isInstanceOf(Patient.class);
    }

    @Test
    void shouldMapPatientFromEhrId() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        Patient patient = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(patient.getId()).isEqualTo(testEhrId.toString());
        assertThat(patient.getIdentifier()).isNotEmpty();
        assertThat(patient.getIdentifier().get(0).getValue()).isEqualTo(testEhrId.toString());
    }

    @Test
    void shouldMapPatientNameFromComposer() {
        Composition composition = createMinimalComposition();
        PartyIdentified composer = new PartyIdentified();
        composer.setName("Dr. Jane Smith");
        composition.setComposer(composer);

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        Patient patient = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(patient.getName()).isNotEmpty();
        assertThat(patient.getName().get(0).getText()).isEqualTo("Dr. Jane Smith");
    }

    @Test
    void shouldMapObservationToFhirObservation() {
        Composition composition = createCompositionWithObservation();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        assertThat(bundle.getEntry()).hasSize(2); // Patient + Observation
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(org.hl7.fhir.r4.model.Observation.class);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getStatus()).isEqualTo(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        assertThat(fhirObs.getSubject().getReference()).contains(testEhrId.toString());
    }

    @Test
    void shouldMapObservationQuantityValue() {
        Composition composition = createCompositionWithObservation();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getValue()).isInstanceOf(Quantity.class);

        Quantity quantity = (Quantity) fhirObs.getValue();
        assertThat(quantity.getValue().doubleValue()).isEqualTo(120.0);
        assertThat(quantity.getUnit()).isEqualTo("mmHg");
    }

    @Test
    void shouldMapEvaluationToCondition() {
        Composition composition = createCompositionWithEvaluation();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        assertThat(bundle.getEntry()).hasSize(2); // Patient + Condition
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(Condition.class);

        Condition condition = (Condition) bundle.getEntry().get(1).getResource();
        assertThat(condition.getSubject().getReference()).contains(testEhrId.toString());
    }

    @Test
    void shouldApplyTemplateDrivenMapping() {
        Composition composition = createCompositionWithObservation();

        FhirMappingConfig config = new FhirMappingConfig(
                "test.template.v1",
                List.of(new FhirMappingConfig.ArchetypeFhirMapping(
                        "openEHR-EHR-OBSERVATION.blood_pressure.v2",
                        "Observation",
                        "http://hl7.org/fhir/StructureDefinition/vitalsigns",
                        Map.of(
                                "openEHR-EHR-OBSERVATION.blood_pressure.v2",
                                new FhirMappingConfig.FhirCodeMapping(
                                        "http://loinc.org", "85354-9", "Blood pressure panel")))));

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, config);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();

        // Should have the configured LOINC code
        assertThat(fhirObs.getCode().getCoding()).isNotEmpty();
        assertThat(fhirObs.getCode().getCoding().get(0).getSystem()).isEqualTo("http://loinc.org");
        assertThat(fhirObs.getCode().getCoding().get(0).getCode()).isEqualTo("85354-9");

        // Should have the configured profile
        assertThat(fhirObs.getMeta().getProfile()).isNotEmpty();
        assertThat(fhirObs.getMeta().getProfile().get(0).getValue())
                .isEqualTo("http://hl7.org/fhir/StructureDefinition/vitalsigns");
    }

    @Test
    void shouldMapTerminologySystemsCorrectly() {
        Composition composition = createCompositionWithCodedObservation("SNOMED-CT", "271649006");

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getValue()).isNotNull();
    }

    @Test
    void shouldSetBundleIdAndType() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        assertThat(bundle.getId()).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
    }

    @Test
    void shouldSetFullUrlOnBundleEntries() {
        Composition composition = createCompositionWithObservation();

        Bundle bundle = mapper.mapCompositionToBundle(testEhrId, composition, null);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            assertThat(entry.getFullUrl()).startsWith("urn:uuid:");
        }
    }

    // ---- Helper methods to create test data ----

    private Composition createMinimalComposition() {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));

        Archetyped archetypeDetails = new Archetyped();
        archetypeDetails.setArchetypeId(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"));
        TemplateId templateId = new TemplateId();
        templateId.setValue("test.template.v1");
        archetypeDetails.setTemplateId(templateId);
        composition.setArchetypeDetails(archetypeDetails);

        DvCodedText category = new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433"));
        composition.setCategory(category);

        DvCodedText language = new DvCodedText("en", new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));

        return composition;
    }

    private Composition createCompositionWithObservation() {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.blood_pressure.v2");
        observation.setName(new DvText("Blood Pressure"));
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setSubject(new com.nedap.archie.rm.generic.PartySelf());

        // Create observation data with a quantity value
        History<ItemStructure> data = new History<>();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("History"));
        data.setOrigin(new DvDateTime(LocalDateTime.of(2024, 1, 15, 10, 30)));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0006");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.of(2024, 1, 15, 10, 30)));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element systolicElement = new Element();
        systolicElement.setArchetypeNodeId("at0004");
        systolicElement.setName(new DvText("Systolic"));
        systolicElement.setValue(new DvQuantity("mmHg", 120.0, 0L));

        Element diastolicElement = new Element();
        diastolicElement.setArchetypeNodeId("at0005");
        diastolicElement.setName(new DvText("Diastolic"));
        diastolicElement.setValue(new DvQuantity("mmHg", 80.0, 0L));

        itemTree.setItems(List.of(systolicElement, diastolicElement));
        event.setData(itemTree);
        data.setEvents(List.of(event));

        observation.setData(data);
        composition.setContent(List.of(observation));

        return composition;
    }

    private Composition createCompositionWithEvaluation() {
        Composition composition = createMinimalComposition();

        Evaluation evaluation = new Evaluation();
        evaluation.setArchetypeNodeId("openEHR-EHR-EVALUATION.problem_diagnosis.v1");
        evaluation.setName(new DvText("Problem/Diagnosis"));
        evaluation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        evaluation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        evaluation.setSubject(new com.nedap.archie.rm.generic.PartySelf());

        ItemTree data = new ItemTree();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("structure"));

        Element diagnosisElement = new Element();
        diagnosisElement.setArchetypeNodeId("at0002");
        diagnosisElement.setName(new DvText("Problem/Diagnosis name"));
        diagnosisElement.setValue(
                new DvCodedText("Hypertension", new CodePhrase(new TerminologyId("SNOMED-CT"), "38341003")));

        data.setItems(List.of(diagnosisElement));
        evaluation.setData(data);

        composition.setContent(List.of(evaluation));

        return composition;
    }

    private Composition createCompositionWithCodedObservation(String terminologyId, String code) {
        Composition composition = createMinimalComposition();

        Observation observation = new Observation();
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.lab_test.v1");
        observation.setName(new DvText("Lab Test"));
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setSubject(new com.nedap.archie.rm.generic.PartySelf());

        History<ItemStructure> data = new History<>();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("History"));
        data.setOrigin(new DvDateTime(LocalDateTime.of(2024, 1, 15, 10, 30)));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.of(2024, 1, 15, 10, 30)));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element resultElement = new Element();
        resultElement.setArchetypeNodeId("at0004");
        resultElement.setName(new DvText("Result"));
        resultElement.setValue(new DvCodedText("Positive", new CodePhrase(new TerminologyId(terminologyId), code)));

        itemTree.setItems(List.of(resultElement));
        event.setData(itemTree);
        data.setEvents(List.of(event));

        observation.setData(data);
        composition.setContent(List.of(observation));

        return composition;
    }
}
