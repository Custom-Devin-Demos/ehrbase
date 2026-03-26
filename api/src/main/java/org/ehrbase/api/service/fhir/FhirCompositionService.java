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
import org.hl7.fhir.r4.model.Bundle;

/**
 * Service interface for transforming openEHR Composition RM objects into FHIR R4 Bundle resources.
 *
 * <p>The transformation is template-driven: each operational template defines how archetype nodes
 * map onto FHIR resource types and elements. The service leverages the existing
 * {@link org.ehrbase.openehr.sdk.webtemplate.templateprovider.TemplateProvider} and web template
 * introspection to drive the mapping.
 */
public interface FhirCompositionService {

    /**
     * Transforms an openEHR {@link Composition} into a FHIR R4 {@link Bundle}.
     *
     * <p>The returned Bundle is of type {@link Bundle.BundleType#COLLECTION} and contains one entry
     * per mapped clinical content item (Observation, Condition, MedicationStatement, etc.) found
     * within the composition's content hierarchy.
     *
     * @param composition the openEHR RM Composition object to transform
     * @return a valid FHIR R4 Bundle containing the mapped resources
     * @throws org.ehrbase.api.exception.InternalServerException if the transformation fails
     */
    Bundle toFhirBundle(Composition composition);

    /**
     * Serializes an openEHR {@link Composition} to a FHIR R4 JSON string.
     *
     * @param composition the openEHR RM Composition object to transform and serialize
     * @return a JSON string representing the FHIR R4 Bundle
     */
    String serializeToFhirJson(Composition composition);

    /**
     * Validates a FHIR R4 {@link Bundle} against the FHIR R4 specification.
     *
     * @param bundle the FHIR R4 Bundle to validate
     * @return the validation outcome containing any errors or warnings
     */
    org.hl7.fhir.r4.model.OperationOutcome validateBundle(Bundle bundle);
}
