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
package org.ehrbase.rest.fhir.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import com.nedap.archie.rm.archetyped.Archetyped;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Evaluation;
import com.nedap.archie.rm.composition.EventContext;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartyIdentified;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.CodeableConcept;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConditionFhirMapperTest {

    @Mock
    private TemplateMappingRegistry mappingRegistry;

    private ConditionFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ConditionFhirMapper(mappingRegistry);
    }

    @Test
    void toCondition_withDefaultMapping_mapsBasicFields() {
        UUID ehrId = UUID.randomUUID();
        UUID compositionUid = UUID.randomUUID();
        String templateId = "unknown-template";

        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        Composition composition = buildTestConditionComposition(compositionUid, "Hypertension", "38341003");

        Condition condition = mapper.toCondition(ehrId, composition, templateId);

        assertNotNull(condition);
        assertEquals(compositionUid.toString(), condition.getId());
        assertEquals("Patient/" + ehrId, condition.getSubject().getReference());

        // Clinical status should be set
        assertTrue(condition.hasClinicalStatus());
        assertEquals(
                "active",
                condition.getClinicalStatus().getCodingFirstRep().getCode());

        // Code should be mapped from evaluation data
        assertTrue(condition.hasCode());
    }

    @Test
    void toComposition_withDefaultMapping_createsValidComposition() {
        String templateId = "unknown-template";
        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        Condition condition = new Condition();
        condition.setSubject(new Reference("Patient/" + UUID.randomUUID()));
        condition.setClinicalStatus(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://terminology.hl7.org/CodeSystem/condition-clinical")
                        .setCode("active")));
        condition.setCode(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://snomed.info/sct")
                        .setCode("38341003")
                        .setDisplay("Hypertension")));

        Composition composition = mapper.toComposition(condition, templateId);

        assertNotNull(composition);
        assertEquals("openEHR-EHR-COMPOSITION.encounter.v1", composition.getArchetypeNodeId());
        assertNotNull(composition.getContent());
        assertFalse(composition.getContent().isEmpty());

        // Verify it contains an Evaluation entry
        assertTrue(composition.getContent().get(0) instanceof Evaluation);
    }

    @Test
    void roundTrip_conditionToCompositionAndBack_preservesCode() {
        String templateId = "unknown-template";
        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        // Create FHIR Condition
        UUID ehrId = UUID.randomUUID();
        Condition original = new Condition();
        original.setSubject(new Reference("Patient/" + ehrId));
        original.setCode(new CodeableConcept()
                .addCoding(new Coding()
                        .setSystem("http://snomed.info/sct")
                        .setCode("73211009")
                        .setDisplay("Diabetes mellitus")));

        // Map to openEHR and back
        Composition composition = mapper.toComposition(original, templateId);
        Condition roundTripped = mapper.toCondition(ehrId, composition, templateId);

        // Code should be preserved
        assertTrue(roundTripped.hasCode());
        assertEquals("73211009", roundTripped.getCode().getCodingFirstRep().getCode());
        assertEquals("Diabetes mellitus", roundTripped.getCode().getCodingFirstRep().getDisplay());
    }

    private Composition buildTestConditionComposition(UUID compositionUid, String problemName, String snomedCode) {
        Composition composition = new Composition();
        composition.setUid(new ObjectVersionId(compositionUid.toString(), "test-system", "1"));
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));
        composition.setCategory(
                new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433")));
        composition.setComposer(new PartyIdentified(null, "Test", null));
        composition.setArchetypeDetails(
                new Archetyped(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"), "1.1.0"));

        EventContext context = new EventContext();
        context.setStartTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        context.setSetting(
                new DvCodedText("other care", new CodePhrase(new TerminologyId("openehr"), "238")));
        composition.setContext(context);

        Evaluation eval = new Evaluation();
        eval.setArchetypeNodeId("openEHR-EHR-EVALUATION.problem_diagnosis.v1");
        eval.setName(new DvText("Problem/Diagnosis"));
        eval.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        eval.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        eval.setSubject(new PartySelf());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0001");
        itemTree.setName(new DvText("structure"));

        Element element = new Element();
        element.setArchetypeNodeId("at0002");
        element.setName(new DvText("Problem/Diagnosis name"));
        element.setValue(new DvCodedText(
                problemName,
                new CodePhrase(new TerminologyId("http://snomed.info/sct"), snomedCode)));

        itemTree.setItems(List.of(element));
        eval.setData(itemTree);

        composition.setContent(List.of(eval));
        return composition;
    }
}
