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
package org.ehrbase.rest.openehr;

import java.util.Objects;
import org.ehrbase.api.dto.BulkExportRequest;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.BulkExportService;
import org.ehrbase.rest.BaseController;
import org.ehrbase.rest.openehr.specification.BulkExportApiSpecification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * Controller for the bulk EHR export endpoint.
 * Provides POST /rest/openehr/v1/export for exporting multiple EHRs with their compositions
 * in a single streamed response.
 */
@ConditionalOnMissingBean(name = "primaryopenehrbulkexportcontroller")
@RestController
@RequestMapping(
        path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/export",
        produces = {MediaType.APPLICATION_JSON_VALUE})
public class OpenehrBulkExportController extends BaseController implements BulkExportApiSpecification {

    private final BulkExportService bulkExportService;

    public OpenehrBulkExportController(BulkExportService bulkExportService) {
        this.bulkExportService = Objects.requireNonNull(bulkExportService);
    }

    @Override
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<StreamingResponseBody> bulkExport(
            @RequestBody BulkExportRequest request,
            @RequestHeader(name = ACCEPT, required = false) String accept) {

        // Validate request: at least one of ehr_ids or aql_filter must be provided
        if ((request.ehrIds() == null || request.ehrIds().isEmpty())
                && (request.aqlFilter() == null || request.aqlFilter().isBlank())) {
            throw new InvalidApiParameterException(
                    "Either 'ehr_ids' or 'aql_filter' must be provided in the bulk export request.");
        }

        // Validate pagination parameters
        if (request.fetch() != null && request.fetch() < 0) {
            throw new InvalidApiParameterException("'fetch' parameter must be non-negative.");
        }
        if (request.offset() != null && request.offset() < 0) {
            throw new InvalidApiParameterException("'offset' parameter must be non-negative.");
        }

        StreamingResponseBody responseBody = outputStream -> bulkExportService.streamExport(request, outputStream);

        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(responseBody);
    }
}
