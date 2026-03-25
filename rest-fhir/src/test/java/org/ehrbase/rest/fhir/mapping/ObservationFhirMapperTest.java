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
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.HierObjectId;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.hl7.fhir.r4.model.Coding;
import org.hl7.fhir.r4.model.Quantity;
import org.hl7.fhir.r4.model.Reference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ObservationFhirMapperTest {

    @Mock
    private TemplateMappingRegistry mappingRegistry;

    private ObservationFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ObservationFhirMapper(mappingRegistry);
    }

    @Test
    void toObservation_withDefaultMapping_mapsBasicFields() {
        UUID ehrId = UUID.randomUUID();
        UUID compositionUid = UUID.randomUUID();
        String templateId = "unknown-template";

        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        Composition composition = buildTestComposition(compositionUid, 120.0, "mmHg");

        org.hl7.fhir.r4.model.Observation fhirObs = mapper.toObservation(ehrId, composition, templateId);

        assertNotNull(fhirObs);
        assertEquals(compositionUid.toString(), fhirObs.getId());
        assertEquals(
                org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL,
                fhirObs.getStatus());
        assertEquals("Patient/" + ehrId, fhirObs.getSubject().getReference());

        // Value should be mapped
        assertTrue(fhirObs.hasValueQuantity());
        assertEquals(0, BigDecimal.valueOf(120.0).compareTo(fhirObs.getValueQuantity().getValue()));
        assertEquals("mmHg", fhirObs.getValueQuantity().getUnit());
    }

    @Test
    void toObservation_withTemplateMapping_appliesCodeAndCategory() {
        UUID ehrId = UUID.randomUUID();
        UUID compositionUid = UUID.randomUUID();
        String templateId = "test-template";

        TemplateMappingDefinition mapping = new TemplateMappingDefinition();
        mapping.setTemplateId(templateId);
        mapping.setCodingSystem("http://loinc.org");
        mapping.setCodingCode("85354-9");
        mapping.setCodingDisplay("Blood pressure panel");
        mapping.setCategorySystem("http://terminology.hl7.org/CodeSystem/observation-category");
        mapping.setCategoryCode("vital-signs");
        mapping.setCategoryDisplay("Vital Signs");

        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.of(mapping));

        Composition composition = buildTestComposition(compositionUid, 120.0, "mmHg");

        org.hl7.fhir.r4.model.Observation fhirObs = mapper.toObservation(ehrId, composition, templateId);

        assertNotNull(fhirObs);
        assertTrue(fhirObs.hasCode());
        Coding coding = fhirObs.getCode().getCodingFirstRep();
        assertEquals("http://loinc.org", coding.getSystem());
        assertEquals("85354-9", coding.getCode());

        assertFalse(fhirObs.getCategory().isEmpty());
        assertEquals("vital-signs", fhirObs.getCategoryFirstRep().getCodingFirstRep().getCode());
    }

    @Test
    void toComposition_withDefaultMapping_createsValidComposition() {
        String templateId = "unknown-template";
        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        org.hl7.fhir.r4.model.Observation fhirObs = new org.hl7.fhir.r4.model.Observation();
        fhirObs.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        fhirObs.setSubject(new Reference("Patient/" + UUID.randomUUID()));
        fhirObs.setValue(new Quantity()
                .setValue(98.6)
                .setUnit("degF")
                .setSystem("http://unitsofmeasure.org"));

        Composition composition = mapper.toComposition(fhirObs, templateId);

        assertNotNull(composition);
        assertEquals("openEHR-EHR-COMPOSITION.encounter.v1", composition.getArchetypeNodeId());
        assertNotNull(composition.getContent());
        assertFalse(composition.getContent().isEmpty());

        // Verify it contains an Observation entry
        assertTrue(composition.getContent().get(0) instanceof Observation);
    }

    @Test
    void roundTrip_observationToCompositionAndBack_preservesValue() {
        String templateId = "unknown-template";
        when(mappingRegistry.getMapping(templateId)).thenReturn(Optional.empty());

        // Create FHIR Observation
        UUID ehrId = UUID.randomUUID();
        org.hl7.fhir.r4.model.Observation original = new org.hl7.fhir.r4.model.Observation();
        original.setStatus(org.hl7.fhir.r4.model.Observation.ObservationStatus.FINAL);
        original.setSubject(new Reference("Patient/" + ehrId));
        original.setValue(new Quantity()
                .setValue(72.0)
                .setUnit("/min")
                .setSystem("http://unitsofmeasure.org"));

        // Map to openEHR and back
        Composition composition = mapper.toComposition(original, templateId);
        org.hl7.fhir.r4.model.Observation roundTripped =
                mapper.toObservation(ehrId, composition, templateId);

        // Value should be preserved
        assertTrue(roundTripped.hasValueQuantity());
        assertEquals(0, BigDecimal.valueOf(72.0).compareTo(roundTripped.getValueQuantity().getValue()));
    }

    private Composition buildTestComposition(UUID compositionUid, double value, String units) {
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

        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.test.v1");
        obs.setName(new DvText("Test Observation"));
        obs.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        obs.setEncoding(new CodePhrase(new TerminologyId("IANA_character-sets"), "UTF-8"));
        obs.setSubject(new PartySelf());

        History<ItemStructure> history = new History<>();
        history.setArchetypeNodeId("at0001");
        history.setName(new DvText("History"));

        PointEvent<ItemStructure> event = new PointEvent<>();
        event.setArchetypeNodeId("at0002");
        event.setName(new DvText("Any event"));
        event.setTime(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        history.setOrigin(event.getTime());

        ItemTree itemTree = new ItemTree();
        itemTree.setArchetypeNodeId("at0003");
        itemTree.setName(new DvText("Tree"));

        Element element = new Element();
        element.setArchetypeNodeId("at0004");
        element.setName(new DvText("Value"));
        element.setValue(new DvQuantity(units, value, 2L));

        itemTree.setItems(List.of(element));
        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        composition.setContent(List.of(obs));
        return composition;
    }
}
