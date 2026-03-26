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

import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.ehrbase.api.dto.BulkExportRequest;

/**
 * Service for performing bulk EHR export operations.
 * Supports exporting multiple EHRs with their compositions in openEHR canonical JSON or FHIR R4 format.
 */
public interface BulkExportService {

    /**
     * Resolve EHR IDs from either an explicit list or an AQL WHERE clause.
     *
     * @param request the bulk export request containing either ehr_ids or aql_filter
     * @return a list of matching EHR UUIDs
     */
    List<UUID> resolveEhrIds(BulkExportRequest request);

    /**
     * Stream the bulk export result to the given output stream as a JSON response.
     * Uses streaming to handle large datasets without exhausting memory.
     *
     * @param request      the bulk export request
     * @param outputStream the output stream to write the JSON response to
     */
    void streamExport(BulkExportRequest request, OutputStream outputStream);
}
