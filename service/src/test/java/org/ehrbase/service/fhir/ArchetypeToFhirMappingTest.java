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

import org.hl7.fhir.r4.model.ResourceType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ArchetypeToFhirMapping}.
 */
class ArchetypeToFhirMappingTest {

    @Test
    void defaultFhirType_observation() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("OBSERVATION")).isEqualTo(ResourceType.Observation);
    }

    @Test
    void defaultFhirType_evaluation() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("EVALUATION")).isEqualTo(ResourceType.Condition);
    }

    @Test
    void defaultFhirType_instruction() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("INSTRUCTION")).isEqualTo(ResourceType.MedicationRequest);
    }

    @Test
    void defaultFhirType_action() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("ACTION")).isEqualTo(ResourceType.MedicationStatement);
    }

    @Test
    void defaultFhirType_adminEntry() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("ADMIN_ENTRY")).isEqualTo(ResourceType.Encounter);
    }

    @Test
    void defaultFhirType_unknown_returnsNull() {
        assertThat(ArchetypeToFhirMapping.defaultFhirType("UNKNOWN")).isNull();
    }

    @Test
    void defaultMappings_containsAllExpectedEntries() {
        assertThat(ArchetypeToFhirMapping.defaultMappings()).hasSize(5);
        assertThat(ArchetypeToFhirMapping.defaultMappings())
                .containsKeys("OBSERVATION", "EVALUATION", "INSTRUCTION", "ACTION", "ADMIN_ENTRY");
    }
}
