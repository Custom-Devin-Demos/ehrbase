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

import java.util.Map;
import org.hl7.fhir.r4.model.ResourceType;

/**
 * Holds the template-driven mapping configuration that maps openEHR RM type names to FHIR R4
 * resource types and provides archetype node-to-FHIR element mappings.
 *
 * <p>This class defines the default mapping conventions used when no explicit per-template
 * overrides are provided. The mapping is driven by the RM type of each content item within an
 * openEHR Composition:
 *
 * <ul>
 *   <li>{@code OBSERVATION} &rarr; {@link ResourceType#Observation}
 *   <li>{@code EVALUATION} &rarr; {@link ResourceType#Condition}
 *   <li>{@code INSTRUCTION} &rarr; {@link ResourceType#MedicationRequest}
 *   <li>{@code ACTION} &rarr; {@link ResourceType#MedicationStatement} (or {@link ResourceType#Procedure})
 *   <li>{@code ADMIN_ENTRY} &rarr; {@link ResourceType#Encounter}
 * </ul>
 */
public final class ArchetypeToFhirMapping {

    /** Default RM-type-name to FHIR ResourceType mapping. */
    private static final Map<String, ResourceType> DEFAULT_RM_TO_FHIR = Map.of(
            "OBSERVATION", ResourceType.Observation,
            "EVALUATION", ResourceType.Condition,
            "INSTRUCTION", ResourceType.MedicationRequest,
            "ACTION", ResourceType.MedicationStatement,
            "ADMIN_ENTRY", ResourceType.Encounter);

    private ArchetypeToFhirMapping() {
        // utility class
    }

    /**
     * Returns the default FHIR resource type for the given openEHR RM type name.
     *
     * @param rmTypeName the RM type name (e.g. "OBSERVATION", "EVALUATION")
     * @return the corresponding FHIR {@link ResourceType}, or {@code null} if unmapped
     */
    public static ResourceType defaultFhirType(String rmTypeName) {
        return DEFAULT_RM_TO_FHIR.get(rmTypeName);
    }

    /**
     * Returns the complete default mapping from RM type names to FHIR resource types.
     *
     * @return an unmodifiable map of RM type name to FHIR ResourceType
     */
    public static Map<String, ResourceType> defaultMappings() {
        return DEFAULT_RM_TO_FHIR;
    }
}
