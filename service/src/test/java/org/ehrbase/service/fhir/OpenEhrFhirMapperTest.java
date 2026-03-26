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
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.time.LocalDateTime;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Resource;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OpenEhrFhirMapper}.
 */
class OpenEhrFhirMapperTest {

    private OpenEhrFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OpenEhrFhirMapper();
    }

    @Test
    void mapEmptyComposition_producesBundle_withPatientOnly() {
        Composition composition = createMinimalComposition();

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        assertThat(bundle.getEntry()).hasSize(1);
        assertThat(bundle.getEntry().get(0).getResource()).isInstanceOf(Patient.class);
    }

    @Test
    void mapComposition_withNamedComposer_extractsPatient() {
        Composition composition = createMinimalComposition();
        PartyIdentified composer = new PartyIdentified();
        composer.setName("Dr. Smith");
        composition.setComposer(composer);

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        Patient patient = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(patient.getName()).isNotEmpty();
        assertThat(patient.getName().get(0).getText()).isEqualTo("Dr. Smith");
    }

    @Test
    void mapComposition_withPartySelf_extractsPatient() {
        Composition composition = createMinimalComposition();
        composition.setComposer(new PartySelf());

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        Patient patient = (Patient) bundle.getEntry().get(0).getResource();
        assertThat(patient.getName()).isNotEmpty();
        assertThat(patient.getName().get(0).getText()).isEqualTo("Self");
    }

    @Test
    void mapObservation_withSingleQuantityValue() {
        Composition composition = createMinimalComposition();
        Observation obs = createObservationWithQuantity("Blood Pressure", 120.0, "mmHg");
        composition.setContent(List.of(obs));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        // Patient + 1 Observation
        assertThat(bundle.getEntry()).hasSize(2);
        Resource fhirObs = bundle.getEntry().get(1).getResource();
        assertThat(fhirObs).isInstanceOf(org.hl7.fhir.r4.model.Observation.class);

        org.hl7.fhir.r4.model.Observation observation = (org.hl7.fhir.r4.model.Observation) fhirObs;
        assertThat(observation.getStatus())
                .isEqualTo(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        assertThat(observation.getCode().getText()).isEqualTo("Blood Pressure");
    }

    @Test
    void mapObservation_withMultipleElements_producesComponents() {
        Composition composition = createMinimalComposition();
        Observation obs = createObservationWithMultipleElements();
        composition.setContent(List.of(obs));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getComponent()).hasSize(2);
        assertThat(fhirObs.getComponent().get(0).getCode().getText()).isEqualTo("Systolic");
        assertThat(fhirObs.getComponent().get(1).getCode().getText()).isEqualTo("Diastolic");
    }

    @Test
    void mapEvaluation_producesCondition() {
        Composition composition = createMinimalComposition();
        Evaluation evaluation = createEvaluation("Diabetes Mellitus");
        composition.setContent(List.of(evaluation));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        Resource resource = bundle.getEntry().get(1).getResource();
        assertThat(resource).isInstanceOf(Condition.class);

        Condition condition = (Condition) resource;
        assertThat(condition.getCode().getText()).isEqualTo("Diabetes Mellitus");
        assertThat(condition.getClinicalStatus().getCodingFirstRep().getCode()).isEqualTo("active");
    }

    @Test
    void mapSection_traversesNestedContent() {
        Composition composition = createMinimalComposition();
        Section section = new Section();
        section.setName(new DvText("Vital Signs"));
        section.setArchetypeNodeId("openEHR-EHR-SECTION.vital_signs.v1");

        Observation obs = createObservationWithQuantity("Heart Rate", 72.0, "beats/min");
        section.setItems(List.of(obs));
        composition.setContent(List.of(section));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        // Patient + 1 Observation (from within the section)
        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource())
                .isInstanceOf(org.hl7.fhir.r4.model.Observation.class);
    }

    @Test
    void mapDataValue_dvQuantity() {
        DvQuantity dvQuantity = new DvQuantity("kg", 75.5, null);
        var result = mapper.mapDataValue(dvQuantity);

        assertThat(result).isInstanceOf(Quantity.class);
        Quantity quantity = (Quantity) result;
        assertThat(quantity.getValue().doubleValue()).isEqualTo(75.5);
        assertThat(quantity.getUnit()).isEqualTo("kg");
        assertThat(quantity.getSystem()).isEqualTo("http://unitsofmeasure.org");
    }

    @Test
    void mapDataValue_dvCodedText() {
        DvCodedText dvCodedText =
                new DvCodedText("Hypertension", new CodePhrase(new TerminologyId("SNOMED-CT"), "38341003"));
        var result = mapper.mapDataValue(dvCodedText);

        assertThat(result).isInstanceOf(CodeableConcept.class);
        CodeableConcept concept = (CodeableConcept) result;
        assertThat(concept.getText()).isEqualTo("Hypertension");
        assertThat(concept.getCodingFirstRep().getSystem()).isEqualTo("http://snomed.info/sct");
        assertThat(concept.getCodingFirstRep().getCode()).isEqualTo("38341003");
    }

    @Test
    void mapDataValue_dvText() {
        DvText dvText = new DvText("Some plain text");
        var result = mapper.mapDataValue(dvText);

        assertThat(result).isInstanceOf(StringType.class);
        assertThat(((StringType) result).getValue()).isEqualTo("Some plain text");
    }

    @Test
    void mapDataValue_null_returnsNull() {
        assertThat(mapper.mapDataValue(null)).isNull();
    }

    @Test
    void mapTerminologyIdToFhirSystem_knownSystems() {
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("SNOMED-CT")).isEqualTo("http://snomed.info/sct");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("LOINC")).isEqualTo("http://loinc.org");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("ICD10"))
                .isEqualTo("http://hl7.org/fhir/sid/icd-10");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("RxNorm"))
                .isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("local")).isEqualTo("http://openehr.org/local");
    }

    @Test
    void mapTerminologyIdToFhirSystem_unknownPassthrough() {
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("custom-system")).isEqualTo("custom-system");
    }

    @Test
    void mapTerminologyIdToFhirSystem_null() {
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem(null)).isNull();
    }

    @Test
    void bundleEntries_haveFullUrls() {
        Composition composition = createMinimalComposition();
        Observation obs = createObservationWithQuantity("Test", 1.0, "unit");
        composition.setContent(List.of(obs));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            assertThat(entry.getFullUrl()).startsWith("urn:uuid:");
        }
    }

    // ---- Helper methods to create test openEHR RM objects ----

    private Composition createMinimalComposition() {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setComposer(new PartySelf());
        Archetyped archetypeDetails = new Archetyped();
        archetypeDetails.setArchetypeId(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"));
        archetypeDetails.setRmVersion("1.1.0");
        composition.setArchetypeDetails(archetypeDetails);
        return composition;
    }

    private Observation createObservationWithQuantity(String name, double magnitude, String units) {
        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.test.v1");
        obs.setName(new DvText(name));

        // Create data: HISTORY → EVENT → ITEM_TREE → ELEMENT
        com.nedap.archie.rm.datastructures.History<com.nedap.archie.rm.datastructures.ItemStructure> history =
                new com.nedap.archie.rm.datastructures.History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        com.nedap.archie.rm.datastructures.PointEvent<com.nedap.archie.rm.datastructures.ItemStructure> event =
                new com.nedap.archie.rm.datastructures.PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.now()));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element element = new Element();
        element.setArchetypeNodeId("at0004");
        element.setName(new DvText(name));
        element.setValue(new DvQuantity(units, magnitude, null));
        itemTree.setItems(List.of(element));

        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        return obs;
    }

    private Observation createObservationWithMultipleElements() {
        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.blood_pressure.v2");
        obs.setName(new DvText("Blood Pressure"));

        com.nedap.archie.rm.datastructures.History<com.nedap.archie.rm.datastructures.ItemStructure> history =
                new com.nedap.archie.rm.datastructures.History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        com.nedap.archie.rm.datastructures.PointEvent<com.nedap.archie.rm.datastructures.ItemStructure> event =
                new com.nedap.archie.rm.datastructures.PointEvent<>();
        event.setArchetypeNodeId("at0006");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.now()));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element systolic = new Element();
        systolic.setArchetypeNodeId("at0004");
        systolic.setName(new DvText("Systolic"));
        systolic.setValue(new DvQuantity("mmHg", 120.0, null));

        Element diastolic = new Element();
        diastolic.setArchetypeNodeId("at0005");
        diastolic.setName(new DvText("Diastolic"));
        diastolic.setValue(new DvQuantity("mmHg", 80.0, null));

        itemTree.setItems(List.of(systolic, diastolic));
        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        return obs;
    }

    private Evaluation createEvaluation(String name) {
        Evaluation evaluation = new Evaluation();
        evaluation.setArchetypeNodeId("openEHR-EHR-EVALUATION.problem_diagnosis.v1");
        evaluation.setName(new DvText(name));

        ItemTree data = new ItemTree();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("structure"));

        Element element = new Element();
        element.setArchetypeNodeId("at0002");
        element.setName(new DvText("Problem/Diagnosis name"));
        element.setValue(new DvText("Type 2 diabetes"));
        data.setItems(List.of(element));

        evaluation.setData(data);
        return evaluation;
    }
}
