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
package org.ehrbase.api.service.fhir;

import com.nedap.archie.rm.composition.Composition;
import java.util.UUID;

/**
 * Service for transforming openEHR Composition RM objects into FHIR R4 Bundle JSON.
 *
 * <p>This service converts openEHR compositions (from the Archie library) into FHIR R4 Bundle
 * resources, mapping archetype content to the appropriate FHIR resource types (Patient, Condition,
 * Observation, MedicationStatement, etc.).
 */
public interface FhirCompositionService {

    /**
     * Serializes an openEHR {@link Composition} into a FHIR R4 Bundle JSON string.
     *
     * @param ehrId       the EHR identifier that owns this composition
     * @param composition the openEHR Composition RM object
     * @return JSON string representing a FHIR R4 Bundle
     */
    String toFhirBundle(UUID ehrId, Composition composition);
}
