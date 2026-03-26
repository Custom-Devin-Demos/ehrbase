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
 * Response DTO for the bulk EHR export endpoint.
 * Contains pagination metadata and a list of exported EHR entries.
 *
 * @param meta    pagination and result metadata
 * @param entries list of exported EHR entries with their compositions
 */
public record BulkExportResponse(
        @JsonProperty("meta") Meta meta,
        @JsonProperty("entries") List<EhrExportEntry> entries) {

    /**
     * Metadata about the export result including pagination info.
     *
     * @param totalEhrs         total number of matching EHRs
     * @param totalCompositions  total number of compositions returned
     * @param fetch              the fetch limit that was applied
     * @param offset             the offset that was applied
     * @param outputFormat       the output format used
     */
    public record Meta(
            @JsonProperty("total_ehrs") int totalEhrs,
            @JsonProperty("total_compositions") int totalCompositions,
            @JsonProperty("fetch") Long fetch,
            @JsonProperty("offset") Long offset,
            @JsonProperty("output_format") String outputFormat) {}

    /**
     * A single EHR export entry containing the EHR ID and its compositions.
     *
     * @param ehrId        the EHR identifier
     * @param compositions list of compositions serialized in the requested format
     */
    public record EhrExportEntry(
            @JsonProperty("ehr_id") UUID ehrId,
            @JsonProperty("compositions") List<Object> compositions) {}
}
