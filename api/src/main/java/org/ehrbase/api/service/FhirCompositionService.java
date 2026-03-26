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

/**
 * Service interface for transforming openEHR {@link Composition} objects into FHIR R4 Bundle
 * resources.
 *
 * <p>The mapping is template-driven: each operational template defines how archetype nodes map to
 * FHIR resource types and elements. The produced FHIR Bundle passes R4 validation.
 */
public interface FhirCompositionService {

    /**
     * Converts an openEHR {@link Composition} into a FHIR R4 Bundle JSON string.
     *
     * @param composition the openEHR composition to transform
     * @return a valid FHIR R4 Bundle as a JSON string
     */
    String serialize(Composition composition);

    /**
     * Converts an openEHR {@link Composition} into a FHIR R4 Bundle JSON string, using the
     * specified template ID for mapping configuration lookup.
     *
     * @param composition the openEHR composition to transform
     * @param templateId  the operational template ID to drive the mapping
     * @return a valid FHIR R4 Bundle as a JSON string
     */
    String serialize(Composition composition, String templateId);
}
