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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TemplateMappingRegistryTest {

    @Test
    void loadMappings_loadsVitalSignsMapping() {
        TemplateMappingRegistry registry = new TemplateMappingRegistry();
        registry.loadMappings();

        Optional<TemplateMappingDefinition> mapping =
                registry.getMapping("openEHR-EHR-COMPOSITION.health_summary.v1");

        assertTrue(mapping.isPresent());
        assertEquals("Observation", mapping.get().getFhirResourceType());
        assertEquals("http://loinc.org", mapping.get().getCodingSystem());
        assertEquals("85354-9", mapping.get().getCodingCode());
        assertNotNull(mapping.get().getFieldMappings());
        assertTrue(mapping.get().getFieldMappings().size() >= 2);
    }

    @Test
    void loadMappings_loadsProblemListMapping() {
        TemplateMappingRegistry registry = new TemplateMappingRegistry();
        registry.loadMappings();

        Optional<TemplateMappingDefinition> mapping =
                registry.getMapping("openEHR-EHR-COMPOSITION.problem_list.v2");

        assertTrue(mapping.isPresent());
        assertEquals("Condition", mapping.get().getFhirResourceType());
        assertEquals("http://snomed.info/sct", mapping.get().getCodingSystem());
        assertNotNull(mapping.get().getFieldMappings());
        assertTrue(mapping.get().getFieldMappings().size() >= 3);
    }

    @Test
    void getMapping_withUnknownTemplate_returnsEmpty() {
        TemplateMappingRegistry registry = new TemplateMappingRegistry();
        registry.loadMappings();

        Optional<TemplateMappingDefinition> mapping = registry.getMapping("non-existent-template");

        assertTrue(mapping.isEmpty());
    }

    @Test
    void getRegisteredTemplateIds_returnsAllTemplates() {
        TemplateMappingRegistry registry = new TemplateMappingRegistry();
        registry.loadMappings();

        assertTrue(registry.getRegisteredTemplateIds().size() >= 2);
        assertTrue(registry.getRegisteredTemplateIds().contains("openEHR-EHR-COMPOSITION.health_summary.v1"));
        assertTrue(registry.getRegisteredTemplateIds().contains("openEHR-EHR-COMPOSITION.problem_list.v2"));
    }
}
