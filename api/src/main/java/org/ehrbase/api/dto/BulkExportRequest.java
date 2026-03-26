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
package org.ehrbase.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.UUID;

/**
 * Request DTO for the bulk EHR export endpoint.
 * Accepts either a list of EHR IDs or an AQL WHERE clause to filter matching EHRs.
 *
 * @param ehrIds       optional list of specific EHR IDs to export
 * @param aqlFilter    optional AQL WHERE clause to filter EHRs (e.g., "e/ehr_status/subject/external_ref/id/value = '...'")
 * @param outputFormat the desired output format: CANONICAL_JSON (default) or FHIR_R4
 * @param fetch        maximum number of compositions to return (pagination)
 * @param offset       number of compositions to skip (pagination)
 */
public record BulkExportRequest(
        @JsonProperty("ehr_ids") List<UUID> ehrIds,
        @JsonProperty("aql_filter") String aqlFilter,
        @JsonProperty("output_format") OutputFormat outputFormat,
        @JsonProperty("fetch") Long fetch,
        @JsonProperty("offset") Long offset) {

    /**
     * Supported output formats for the bulk export.
     */
    public enum OutputFormat {
        CANONICAL_JSON,
        FHIR_R4
    }

    /**
     * Returns the effective output format, defaulting to CANONICAL_JSON if not specified.
     */
    public OutputFormat effectiveOutputFormat() {
        return outputFormat != null ? outputFormat : OutputFormat.CANONICAL_JSON;
    }
}
