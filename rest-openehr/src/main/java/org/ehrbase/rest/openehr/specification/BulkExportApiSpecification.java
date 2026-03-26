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
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.ehrbase.api.dto.BulkExportRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * OpenAPI specification for the bulk EHR export endpoint.
 */
@Tag(name = "BULK EXPORT")
@SuppressWarnings("unused")
public interface BulkExportApiSpecification {

    @Operation(
            summary = "Bulk export EHRs with compositions",
            description =
                    """
            Export multiple EHRs and their compositions in a single streamed response.
            Accepts either a list of EHR IDs or an AQL WHERE clause to filter matching EHRs.
            Supports pagination via fetch/offset parameters and output in openEHR canonical JSON or FHIR R4 format.
            """,
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "Streamed bulk export response",
                        content =
                                @Content(
                                        mediaType = "application/json",
                                        schema = @Schema(implementation = Object.class))),
                @ApiResponse(responseCode = "400", description = "Invalid request parameters"),
                @ApiResponse(responseCode = "401", description = "Unauthorized"),
                @ApiResponse(responseCode = "404", description = "One or more EHR IDs not found")
            })
    ResponseEntity<StreamingResponseBody> bulkExport(BulkExportRequest request, String accept);
}
