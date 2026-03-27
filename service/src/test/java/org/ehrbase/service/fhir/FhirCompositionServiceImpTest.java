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
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FhirCompositionServiceImpTest {

    private FhirCompositionServiceImp service;

    @BeforeEach
    void setUp() {
        service = new FhirCompositionServiceImp();
    }

    @Test
    void toFhirBundleReturnsValidBundle() {
        Composition composition = createTestComposition();

        Bundle bundle = service.toFhirBundle(composition);

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        assertThat(bundle.getEntry()).isNotEmpty();
    }

    @Test
    void serializeToFhirJsonProducesValidJson() {
        Composition composition = createTestComposition();

        String json = service.serializeToFhirJson(composition);

        assertThat(json).isNotBlank();
        assertThat(json).contains("\"resourceType\" : \"Bundle\"");
        assertThat(json).contains("\"type\" : \"collection\"");
        assertThat(json).contains("\"resourceType\" : \"Patient\"");
        assertThat(json).contains("\"resourceType\" : \"Observation\"");
    }

    @Test
    void serializeBundleToJsonRoundTrip() {
        Composition composition = createTestComposition();
        Bundle bundle = service.toFhirBundle(composition);

        String json = service.serializeBundleToJson(bundle);

        // Verify it can be parsed back
        FhirContext ctx = FhirContext.forR4();
        Bundle parsed = ctx.newJsonParser().parseResource(Bundle.class, json);
        assertThat(parsed.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        assertThat(parsed.getEntry()).hasSameSizeAs(bundle.getEntry());
    }

    @Test
    void validateBundleReturnsOutcome() {
        Composition composition = createTestComposition();
        Bundle bundle = service.toFhirBundle(composition);

        OperationOutcome outcome = service.validateBundle(bundle);

        assertThat(outcome).isNotNull();
        assertThat(outcome.getIssue()).isNotEmpty();
    }

    @Test
    void getFhirContextReturnsR4() {
        assertThat(service.getFhirContext()).isNotNull();
        assertThat(service.getFhirContext().getVersion().getVersion().name()).contains("R4");
    }

    // ---- Helper methods ----

    private Composition createTestComposition() {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test encounter"));

        PartyIdentified composer = new PartyIdentified();
        composer.setName("Dr. Smith");
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

        // Add an observation
        Observation observation = new Observation();
        observation.setName(new DvText("Body temperature"));
        observation.setArchetypeNodeId("openEHR-EHR-OBSERVATION.body_temperature.v2");
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
        element.setName(new DvText("Temperature"));
        element.setValue(new DvQuantity("Cel", 37.5, null));

        itemTree.setItems(List.of((Item) element));
        event.setData(itemTree);
        history.setEvents(List.of(event));
        observation.setData(history);

        composition.setContent(List.of(observation));

        return composition;
    }
}
