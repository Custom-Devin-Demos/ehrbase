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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nedap.archie.rm.archetyped.Archetyped;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.composition.Observation;
import com.nedap.archie.rm.datastructures.Element;
import com.nedap.archie.rm.datastructures.ItemTree;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.datavalues.quantity.DvQuantity;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import java.time.LocalDateTime;
import java.util.List;
import org.ehrbase.api.exception.InternalServerException;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FhirCompositionServiceImp}.
 */
class FhirCompositionServiceImpTest {

    private FhirCompositionServiceImp service;

    @BeforeEach
    void setUp() {
        service = new FhirCompositionServiceImp();
    }

    @Test
    void toFhirBundle_producesValidBundle() {
        Composition composition = createTestComposition();

        Bundle bundle = service.toFhirBundle(composition);

        assertThat(bundle).isNotNull();
        assertThat(bundle.getType()).isEqualTo(Bundle.BundleType.COLLECTION);
        // Patient + 1 Observation
        assertThat(bundle.getEntry()).hasSize(2);
    }

    @Test
    void serializeToFhirJson_producesValidJson() {
        Composition composition = createTestComposition();

        String json = service.serializeToFhirJson(composition);

        assertThat(json).isNotBlank();
        assertThat(json).contains("\"resourceType\"");
        assertThat(json).contains("\"Bundle\"");
        assertThat(json).contains("\"Patient\"");
        assertThat(json).contains("\"Observation\"");
    }

    @Test
    void validateBundle_returnsOperationOutcome() {
        Composition composition = createTestComposition();
        Bundle bundle = service.toFhirBundle(composition);

        OperationOutcome outcome = service.validateBundle(bundle);

        assertThat(outcome).isNotNull();
        assertThat(outcome.getIssue()).isNotEmpty();
    }

    @Test
    void getFhirContext_isNotNull() {
        assertThat(service.getFhirContext()).isNotNull();
    }

    private Composition createTestComposition() {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));
        composition.setComposer(new PartySelf());
        Archetyped archetypeDetails = new Archetyped();
        archetypeDetails.setArchetypeId(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"));
        archetypeDetails.setRmVersion("1.1.0");
        composition.setArchetypeDetails(archetypeDetails);

        Observation obs = new Observation();
        obs.setArchetypeNodeId("openEHR-EHR-OBSERVATION.body_temperature.v2");
        obs.setName(new DvText("Body Temperature"));

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
        element.setName(new DvText("Temperature"));
        element.setValue(new DvQuantity("Cel", 37.5, null));
        itemTree.setItems(List.of(element));

        event.setData(itemTree);
        history.setEvents(List.of(event));
        obs.setData(history);

        composition.setContent(List.of(obs));
        return composition;
    }
}
