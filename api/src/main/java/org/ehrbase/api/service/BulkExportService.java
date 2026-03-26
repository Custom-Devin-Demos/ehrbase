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

import org.ehrbase.api.dto.BulkExportRequest;
import org.ehrbase.api.dto.BulkExportResponse;

/**
 * Service interface for bulk EHR export operations.
 * Supports exporting multiple EHRs with their compositions, filtered by EHR IDs
 * or AQL WHERE clause, with pagination support.
 */
public interface BulkExportService {

    /**
     * Performs a bulk export of EHRs and their compositions.
     *
     * @param request The bulk export request containing filter criteria, output format, and pagination
     * @return A {@link BulkExportResponse} containing the exported data and pagination metadata
     */
    BulkExportResponse export(BulkExportRequest request);
}
