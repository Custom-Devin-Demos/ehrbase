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

import com.nedap.archie.rm.composition.Action;
import com.nedap.archie.rm.composition.AdminEntry;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.composition.Instruction;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.composition.Section;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.History;
import com.nedap.archie.rm.datastructures.Item;
import com.nedap.archie.rm.datastructures.ItemStructure;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datastructures.PointEvent;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.time.LocalDateTime;
import java.util.List;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Encounter;
import org.hl7.fhir.r4.model.MedicationRequest;
import org.hl7.fhir.r4.model.MedicationStatement;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.StringType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpenEhrFhirMapperTest {

    private OpenEhrFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OpenEhrFhirMapper();
    }

    @Test
    void mapEmptyComposition() {
        Composition composition = createMinimalComposition("Dr. Smith");
        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        // Should contain at least the Patient resource
        assertThat(bundle.getEntry()).hasSize(1);
        assertThat(bundle.getEntry().get(0).getResource()).isInstanceOf(Patient.class);
    }

    @Test
    void mapCompositionWithObservation() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Observation observation = createObservation("Blood pressure", 120.0, "mm[Hg]");
        composition.setContent(List.of(observation));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(0).getResource()).isInstanceOf(Patient.class);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(org.hl7.fhir.r4.model.Observation.class);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getCode().getText()).isEqualTo("Blood pressure");
        assertThat(fhirObs.getStatus()).isEqualTo(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
    }

    @Test
    void mapCompositionWithObservationMultipleElements() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Observation observation = createObservationWithMultipleElements(
                "Blood pressure",
                List.of(new ElementData("Systolic", 120.0, "mm[Hg]"), new ElementData("Diastolic", 80.0, "mm[Hg]")));
        composition.setContent(List.of(observation));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        org.hl7.fhir.r4.model.Observation fhirObs =
                (org.hl7.fhir.r4.model.Observation) bundle.getEntry().get(1).getResource();
        assertThat(fhirObs.getComponent()).hasSize(2);
        assertThat(fhirObs.getComponent().get(0).getCode().getText()).isEqualTo("Systolic");
        assertThat(fhirObs.getComponent().get(1).getCode().getText()).isEqualTo("Diastolic");
    }

    @Test
    void mapCompositionWithEvaluation() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Evaluation evaluation = createEvaluation("Problem/Diagnosis");
        composition.setContent(List.of(evaluation));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(Condition.class);

        Condition condition = (Condition) bundle.getEntry().get(1).getResource();
        assertThat(condition.getCode().getText()).isEqualTo("Problem/Diagnosis");
    }

    @Test
    void mapCompositionWithInstruction() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Instruction instruction = createInstruction("Medication order");
        composition.setContent(List.of(instruction));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(MedicationRequest.class);
    }

    @Test
    void mapCompositionWithAction() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Action action = createAction("Medication administration");
        composition.setContent(List.of(action));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(MedicationStatement.class);
    }

    @Test
    void mapCompositionWithAdminEntry() {
        Composition composition = createMinimalComposition("Dr. Smith");

        AdminEntry adminEntry = createAdminEntry("Admission");
        composition.setContent(List.of(adminEntry));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(Encounter.class);
    }

    @Test
    void mapCompositionWithSection() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Section section = new Section();
        section.setName(new DvText("Vital signs"));
        section.setArchetypeNodeId("openEHR-EHR-SECTION.vital_signs.v0");

        Observation observation = createObservation("Blood pressure", 120.0, "mm[Hg]");
        section.setItems(List.of(observation));
        composition.setContent(List.of(section));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        // Patient + Observation (section is traversed, not mapped)
        assertThat(bundle.getEntry()).hasSize(2);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(org.hl7.fhir.r4.model.Observation.class);
    }

    @Test
    void mapCompositionWithMixedContent() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Observation observation = createObservation("Temperature", 37.5, "Cel");
        Evaluation evaluation = createEvaluation("Diabetes mellitus");
        Instruction instruction = createInstruction("Insulin");

        composition.setContent(List.of(observation, evaluation, instruction));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        assertThat(bundle.getEntry()).hasSize(4);
        assertThat(bundle.getEntry().get(0).getResource()).isInstanceOf(Patient.class);
        assertThat(bundle.getEntry().get(1).getResource()).isInstanceOf(org.hl7.fhir.r4.model.Observation.class);
        assertThat(bundle.getEntry().get(2).getResource()).isInstanceOf(Condition.class);
        assertThat(bundle.getEntry().get(3).getResource()).isInstanceOf(MedicationRequest.class);
    }

    @Test
    void extractPatientFromComposer() {
        Composition composition = createMinimalComposition("Dr. Jane Doe");

        Patient patient = mapper.extractPatient(composition);

        assertThat(patient).isNotNull();
        assertThat(patient.getNameFirstRep().getText()).isEqualTo("Dr. Jane Doe");
    }

    @Test
    void extractPatientFromEntrySubject() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Observation observation = createObservation("BP", 120.0, "mm[Hg]");
        PartyIdentified patientSubject = new PartyIdentified();
        patientSubject.setName("John Patient");
        observation.setSubject(patientSubject);
        composition.setContent(List.of(observation));

        Patient patient = mapper.extractPatient(composition);

        // Should use the entry subject (actual patient), not the composer (provider)
        assertThat(patient.getNameFirstRep().getText()).isEqualTo("John Patient");
    }

    @Test
    void extractPatientFallsBackToComposerWhenSubjectIsSelf() {
        Composition composition = createMinimalComposition("Dr. Smith");

        Observation observation = createObservation("BP", 120.0, "mm[Hg]");
        observation.setSubject(new PartySelf());
        composition.setContent(List.of(observation));

        Patient patient = mapper.extractPatient(composition);

        // PartySelf is skipped, falls back to composer
        assertThat(patient.getNameFirstRep().getText()).isEqualTo("Dr. Smith");
    }

    @Test
    void mapDvQuantity() {
        DvQuantity dvQuantity = new DvQuantity("mm[Hg]", 120.0, null);
        org.hl7.fhir.r4.model.Type result = mapper.mapDataValue(dvQuantity);

        assertThat(result).isInstanceOf(Quantity.class);
        Quantity quantity = (Quantity) result;
        assertThat(quantity.getValue().doubleValue()).isEqualTo(120.0);
        assertThat(quantity.getUnit()).isEqualTo("mm[Hg]");
        assertThat(quantity.getSystem()).isEqualTo("http://unitsofmeasure.org");
    }

    @Test
    void mapDvCodedText() {
        DvCodedText dvCodedText =
                new DvCodedText("Hypertension", new CodePhrase(new TerminologyId("SNOMED-CT"), "38341003"));
        org.hl7.fhir.r4.model.Type result = mapper.mapDataValue(dvCodedText);

        assertThat(result).isInstanceOf(CodeableConcept.class);
        CodeableConcept concept = (CodeableConcept) result;
        assertThat(concept.getText()).isEqualTo("Hypertension");
        assertThat(concept.getCodingFirstRep().getSystem()).isEqualTo("http://snomed.info/sct");
        assertThat(concept.getCodingFirstRep().getCode()).isEqualTo("38341003");
    }

    @Test
    void mapDvText() {
        DvText dvText = new DvText("Some free text");
        org.hl7.fhir.r4.model.Type result = mapper.mapDataValue(dvText);

        assertThat(result).isInstanceOf(StringType.class);
        assertThat(((StringType) result).getValue()).isEqualTo("Some free text");
    }

    @Test
    void mapTerminologyIds() {
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("SNOMED-CT")).isEqualTo("http://snomed.info/sct");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("LOINC")).isEqualTo("http://loinc.org");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("ICD10")).isEqualTo("http://hl7.org/fhir/sid/icd-10");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("RxNorm"))
                .isEqualTo("http://www.nlm.nih.gov/research/umls/rxnorm");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("openehr")).isEqualTo("http://openehr.org/id");
        assertThat(OpenEhrFhirMapper.mapTerminologyIdToFhirSystem("custom-system"))
                .isEqualTo("custom-system");
    }

    @Test
    void bundleEntriesHaveFullUrls() {
        Composition composition = createMinimalComposition("Dr. Smith");
        Observation observation = createObservation("BP", 120.0, "mm[Hg]");
        composition.setContent(List.of(observation));

        Bundle bundle = mapper.mapCompositionToBundle(composition);

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            assertThat(entry.getFullUrl()).startsWith("urn:uuid:");
        }
    }

    // ---- Test helper methods ----

    private Composition createMinimalComposition(String composerName) {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test composition"));

        PartyIdentified composer = new PartyIdentified();
        composer.setName(composerName);
        composition.setComposer(composer);

        DvCodedText category = new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433"));
        composition.setCategory(category);

        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));

        EventContext context = new EventContext();
        context.setStartTime(new DvDateTime(LocalDateTime.now()));
        DvCodedText setting = new DvCodedText("other care", new CodePhrase(new TerminologyId("openehr"), "238"));
        context.setSetting(setting);
        composition.setContext(context);

        return composition;
    }

    private Observation createObservation(String name, double value, String units) {
        Observation observation = new Observation();
        observation.setName(new DvText(name));
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.test.v1");
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setSubject(new PartySelf());

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.now()));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element element = new Element();
        element.setArchetypeNodeId("at0004");
        element.setName(new DvText(name));
        element.setValue(new DvQuantity(units, value, null));

        itemTree.setItems(List.of((Item) element));
        event.setData(itemTree);
        history.setEvents(List.of(event));
        observation.setData(history);

        return observation;
    }

    private Observation createObservationWithMultipleElements(String name, List<ElementData> elements) {
        Observation observation = new Observation();
        observation.setName(new DvText(name));
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.test.v1");
        observation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        observation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        observation.setSubject(new PartySelf());

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(LocalDateTime.now()));

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        List<Item> elementList = elements.stream()
                .map(e -> {
                    Element el = new Element();
                    el.setArchetypeNodeId("at0004");
                    el.setName(new DvText(e.name()));
                    el.setValue(new DvQuantity(e.units(), e.value(), null));
                    return (Item) el;
                })
                .toList();

        itemTree.setItems(elementList);
        event.setData(itemTree);
        history.setEvents(List.of(event));
        observation.setData(history);

        return observation;
    }

    private Evaluation createEvaluation(String name) {
        Evaluation evaluation = new Evaluation();
        evaluation.setName(new DvText(name));
        evaluation.setArchetypeNodeId("openEHR-EHR-EVALUATION.test.v1");
        evaluation.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        evaluation.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        evaluation.setSubject(new PartySelf());

        ItemTree data = new ItemTree();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("Tree"));
        evaluation.setData(data);

        return evaluation;
    }

    private Instruction createInstruction(String name) {
        Instruction instruction = new Instruction();
        instruction.setName(new DvText(name));
        instruction.setArchetypeNodeId("openEHR-EHR-INSTRUCTION.test.v1");
        instruction.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        instruction.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        instruction.setSubject(new PartySelf());
        instruction.setNarrative(new DvText(name));

        return instruction;
    }

    private Action createAction(String name) {
        Action action = new Action();
        action.setName(new DvText(name));
        action.setArchetypeNodeId("openEHR-EHR-ACTION.test.v1");
        action.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        action.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        action.setSubject(new PartySelf());
        action.setTime(new DvDateTime(LocalDateTime.now()));

        return action;
    }

    private AdminEntry createAdminEntry(String name) {
        AdminEntry adminEntry = new AdminEntry();
        adminEntry.setName(new DvText(name));
        adminEntry.setArchetypeNodeId("openEHR-EHR-ADMIN_ENTRY.test.v1");
        adminEntry.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        adminEntry.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        adminEntry.setSubject(new PartySelf());

        ItemTree data = new ItemTree();
        data.setArchetypeNodeId("at0001");
        data.setName(new DvText("Tree"));
        adminEntry.setData(data);

        return adminEntry;
    }

    record ElementData(String name, double value, String units) {}
}
