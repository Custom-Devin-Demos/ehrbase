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
package org.ehrbase.api.service;

import com.nedap.archie.rm.composition.Composition;
import java.util.UUID;

/**
 * Service for transforming openEHR Composition RM objects into HL7 FHIR R4 resources.
 *
 * <p>This service provides a template-driven mapping layer that converts openEHR Reference Model
 * {@link Composition} objects (from the Archie library) into FHIR R4 Bundle JSON representations.
 * The mapping is driven by the operational template associated with each composition, allowing
 * per-template customization of the transformation logic.</p>
 *
 * <p>The output is a valid FHIR R4 Bundle (type: collection) containing the mapped resources
 * such as Patient, Observation, Condition, MedicationStatement, etc.</p>
 */
public interface FhirMappingService {

    /**
     * Transforms an openEHR {@link Composition} into a FHIR R4 Bundle JSON string.
     *
     * @param ehrId       The UUID of the EHR containing the composition (used to resolve subject/patient context)
     * @param composition The openEHR Composition RM object to transform
     * @return A JSON string representing a valid FHIR R4 Bundle containing the mapped resources
     * @throws org.ehrbase.api.exception.InternalServerException if the transformation fails
     */
    String toFhirBundle(UUID ehrId, Composition composition);

    /**
     * Checks whether a FHIR mapping configuration exists for the given template ID.
     *
     * @param templateId The operational template identifier
     * @return {@code true} if a mapping is available, {@code false} otherwise
     */
    boolean hasMappingForTemplate(String templateId);
}
