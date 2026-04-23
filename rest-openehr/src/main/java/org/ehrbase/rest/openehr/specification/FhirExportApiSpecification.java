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
package org.ehrbase.rest.openehr.specification;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

/**
 * OpenAPI specification for the FHIR R4 export endpoints.
 *
 * <p>These endpoints allow exporting openEHR compositions as FHIR R4 Bundle resources,
 * enabling interoperability with systems that consume HL7 FHIR format.</p>
 */
@Tag(name = "FHIR EXPORT", description = "Endpoints for exporting openEHR data as FHIR R4 resources")
public interface FhirExportApiSpecification {

    @Operation(
            summary = "Export a composition as FHIR R4 Bundle",
            description = "Transforms an openEHR composition into a FHIR R4 Bundle containing mapped resources "
                    + "(Patient, Observation, Condition, MedicationStatement, etc.). "
                    + "The mapping is driven by the operational template associated with the composition.",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "FHIR R4 Bundle containing mapped resources",
                        content =
                                @Content(
                                        mediaType = "application/fhir+json",
                                        schema = @Schema(implementation = String.class))),
                @ApiResponse(responseCode = "404", description = "Composition or EHR not found"),
                @ApiResponse(responseCode = "500", description = "Transformation failed")
            })
    ResponseEntity<String> exportCompositionAsFhir(
            @Parameter(description = "EHR identifier", required = true) String ehrIdString,
            @Parameter(
                            description = "Versioned composition UID (format: uuid or uuid::system::version)",
                            required = true)
                    String versionedObjectUid,
            @Parameter(description = "Version at time (ISO 8601 timestamp)", required = false) String versionAtTime);
}
