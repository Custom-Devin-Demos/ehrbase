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
import com.nedap.archie.rm.archetyped.TemplateId;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.datatypes.CodePhrase;
import com.nedap.archie.rm.datavalues.DvCodedText;
import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.support.identification.ArchetypeID;
import com.nedap.archie.rm.support.identification.TerminologyId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FhirMappingServiceImpTest {

    private FhirMappingServiceImp service;

    @BeforeEach
    void setUp() {
        service = new FhirMappingServiceImp();
    }

    @Test
    void shouldProduceValidFhirJson() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition("test.template.v1");

        String fhirJson = service.toFhirBundle(ehrId, composition);

        assertThat(fhirJson).isNotBlank();
        assertThat(fhirJson).contains("\"resourceType\"");
        assertThat(fhirJson).contains("\"Bundle\"");
        assertThat(fhirJson).contains("\"collection\"");
        assertThat(fhirJson).contains("\"Patient\"");
    }

    @Test
    void shouldContainEhrIdInPatientIdentifier() {
        UUID ehrId = UUID.randomUUID();
        Composition composition = createMinimalComposition("test.template.v1");

        String fhirJson = service.toFhirBundle(ehrId, composition);

        assertThat(fhirJson).contains(ehrId.toString());
    }

    @Test
    void shouldRejectNullEhrId() {
        Composition composition = createMinimalComposition("test.template.v1");

        assertThatThrownBy(() -> service.toFhirBundle(null, composition)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldRejectNullComposition() {
        UUID ehrId = UUID.randomUUID();

        assertThatThrownBy(() -> service.toFhirBundle(ehrId, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void shouldReportHasMappingForRegisteredTemplate() {
        assertThat(service.hasMappingForTemplate("test.template.v1")).isFalse();

        service.registerMappingConfig(new FhirMappingConfig("test.template.v1", List.of()));

        assertThat(service.hasMappingForTemplate("test.template.v1")).isTrue();
    }

    @Test
    void shouldRemoveMappingConfig() {
        service.registerMappingConfig(new FhirMappingConfig("test.template.v1", List.of()));
        assertThat(service.hasMappingForTemplate("test.template.v1")).isTrue();

        service.removeMappingConfig("test.template.v1");
        assertThat(service.hasMappingForTemplate("test.template.v1")).isFalse();
    }

    private Composition createMinimalComposition(String templateIdValue) {
        Composition composition = new Composition();
        composition.setArchetypeNodeId("openEHR-EHR-COMPOSITION.encounter.v1");
        composition.setName(new DvText("Test Composition"));

        Archetyped archetypeDetails = new Archetyped();
        archetypeDetails.setArchetypeId(new ArchetypeID("openEHR-EHR-COMPOSITION.encounter.v1"));
        TemplateId templateId = new TemplateId();
        templateId.setValue(templateIdValue);
        archetypeDetails.setTemplateId(templateId);
        composition.setArchetypeDetails(archetypeDetails);

        DvCodedText category = new DvCodedText("event", new CodePhrase(new TerminologyId("openehr"), "433"));
        composition.setCategory(category);
        composition.setLanguage(new CodePhrase(new TerminologyId("ISO_639-1"), "en"));
        composition.setTerritory(new CodePhrase(new TerminologyId("ISO_3166-1"), "US"));

        return composition;
    }
}
