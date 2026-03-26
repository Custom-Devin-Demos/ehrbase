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

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.ehrbase.api.dto.BulkExportRequest;
import org.ehrbase.api.dto.BulkExportResponse;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.BulkExportService;
import org.ehrbase.rest.BaseController;
import org.ehrbase.rest.openehr.specification.BulkExportApiSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller for the bulk EHR export endpoint.
 * Provides POST /rest/openehr/v1/export for exporting multiple EHRs and their compositions.
 */
@ConditionalOnMissingBean(name = "primaryopenehrbulkexportcontroller")
@RestController
@RequestMapping(
        path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/export",
        produces = {MediaType.APPLICATION_JSON_VALUE})
public class OpenehrBulkExportController extends BaseController implements BulkExportApiSpecification {

    private static final String EHR_IDS = "ehr_ids";
    private static final String AQL_WHERE_CLAUSE = "aql_where_clause";
    private static final String OUTPUT_FORMAT = "output_format";
    private static final String FETCH_PARAM = "fetch";
    private static final String OFFSET_PARAM = "offset";

    private final Logger logger = LoggerFactory.getLogger(getClass());

    private final BulkExportService bulkExportService;

    public OpenehrBulkExportController(BulkExportService bulkExportService) {
        this.bulkExportService = Objects.requireNonNull(bulkExportService);
    }

    @Override
    @PostMapping(consumes = {MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<BulkExportResponse> exportBulk(
            @RequestBody Map<String, Object> requestBody,
            @RequestHeader(name = ACCEPT, required = false) String accept,
            @RequestHeader(name = CONTENT_TYPE) String contentType) {

        logger.debug("Bulk export request: {}", requestBody);

        BulkExportRequest exportRequest = parseBulkExportRequest(requestBody);
        BulkExportResponse response = bulkExportService.export(exportRequest);

        return ResponseEntity.ok(response);
    }

    @SuppressWarnings("unchecked")
    private BulkExportRequest parseBulkExportRequest(Map<String, Object> requestBody) {

        if (requestBody == null || requestBody.isEmpty()) {
            throw new InvalidApiParameterException("Request body must not be empty.");
        }

        // Parse ehr_ids
        List<String> ehrIds = null;
        Object rawEhrIds = requestBody.get(EHR_IDS);
        if (rawEhrIds != null) {
            if (rawEhrIds instanceof List<?> list) {
                ehrIds = list.stream().map(Object::toString).toList();
            } else {
                throw new InvalidApiParameterException("'ehr_ids' must be a JSON array of strings.");
            }
        }

        // Parse aql_where_clause
        String aqlWhereClause = null;
        Object rawAqlWhere = requestBody.get(AQL_WHERE_CLAUSE);
        if (rawAqlWhere != null) {
            if (rawAqlWhere instanceof String s) {
                aqlWhereClause = s;
            } else {
                throw new InvalidApiParameterException("'aql_where_clause' must be a string.");
            }
        }

        // Parse output_format
        String outputFormat = Optional.ofNullable(requestBody.get(OUTPUT_FORMAT))
                .map(Object::toString)
                .orElse(null);

        // Parse fetch
        Long fetch = getOptionalLong(requestBody, FETCH_PARAM).orElse(null);

        // Parse offset
        Long offset = getOptionalLong(requestBody, OFFSET_PARAM).orElse(null);

        return new BulkExportRequest(ehrIds, aqlWhereClause, outputFormat, fetch, offset);
    }

    private Optional<Long> getOptionalLong(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        try {
            return Optional.of(Long.parseLong(value.toString()));
        } catch (NumberFormatException e) {
            throw new InvalidApiParameterException("'%s' must be a valid number.".formatted(key));
        }
    }
}
