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

import java.util.List;

/**
 * Request DTO for the bulk EHR export endpoint.
 * Supports two modes: explicit EHR IDs list or an AQL WHERE clause filter.
 *
 * @param ehrIds          Optional list of EHR IDs to export
 * @param aqlWhereClause  Optional AQL WHERE clause to filter EHRs (e.g., "e/ehr_status/subject/external_ref/id/value = '12345'")
 * @param outputFormat    Desired output format: "CANONICAL_JSON" (default) or "FHIR_R4"
 * @param fetch           Maximum number of compositions to return per page (pagination)
 * @param offset          Offset for pagination (0-based)
 */
public record BulkExportRequest(
        List<String> ehrIds, String aqlWhereClause, String outputFormat, Long fetch, Long offset) {

    /**
     * Canonical openEHR JSON output format.
     */
    public static final String FORMAT_CANONICAL_JSON = "CANONICAL_JSON";

    /**
     * FHIR R4 output format.
     */
    public static final String FORMAT_FHIR_R4 = "FHIR_R4";

    /**
     * Returns the effective output format, defaulting to CANONICAL_JSON if not specified.
     */
    public String effectiveOutputFormat() {
        return outputFormat != null && !outputFormat.isBlank() ? outputFormat : FORMAT_CANONICAL_JSON;
    }
}
