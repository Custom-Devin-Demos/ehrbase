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
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.ehrbase.api.dto.BulkExportResponse;
import org.springframework.http.ResponseEntity;

/**
 * OpenAPI specification for the bulk EHR export endpoint.
 */
@Tag(name = "BULK EXPORT")
@SuppressWarnings("java:S107")
public interface BulkExportApiSpecification {

    @Operation(
            summary = "Bulk export EHRs and their compositions",
            description = "Accepts a list of EHR IDs or an AQL WHERE clause filter and returns all matching "
                    + "compositions grouped by EHR. Supports pagination via fetch/offset parameters "
                    + "and output in openEHR canonical JSON or FHIR R4 format.")
    ResponseEntity<BulkExportResponse> exportBulk(Map<String, Object> requestBody, String accept, String contentType);
}
