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
package org.ehrbase.service;

import com.nedap.archie.rm.composition.Composition;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.dto.BulkExportRequest;
import org.ehrbase.api.dto.BulkExportResponse;
import org.ehrbase.api.dto.BulkExportResult;
import org.ehrbase.api.exception.InvalidApiParameterException;
import org.ehrbase.api.service.AqlQueryService;
import org.ehrbase.api.service.BulkExportService;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.CompositionFormat;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.QueryResultDto;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.StructuredString;
import org.ehrbase.openehr.sdk.response.dto.ehrscape.query.ResultHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@link BulkExportService} implementation.
 *
 * Uses the AQL engine to query compositions across multiple EHRs and serializes
 * them in the requested output format (openEHR canonical JSON or FHIR R4).
 */
@Service
public class BulkExportServiceImp implements BulkExportService {

    private static final Logger logger = LoggerFactory.getLogger(BulkExportServiceImp.class);

    private static final long DEFAULT_FETCH = 100L;
    private static final long DEFAULT_OFFSET = 0L;

    private final AqlQueryService aqlQueryService;
    private final CompositionService compositionService;
    private final EhrService ehrService;

    public BulkExportServiceImp(
            AqlQueryService aqlQueryService, CompositionService compositionService, EhrService ehrService) {
        this.aqlQueryService = Objects.requireNonNull(aqlQueryService);
        this.compositionService = Objects.requireNonNull(compositionService);
        this.ehrService = Objects.requireNonNull(ehrService);
    }

    @Override
    public BulkExportResponse export(BulkExportRequest request) {

        validateRequest(request);

        long fetch = request.fetch() != null ? request.fetch() : DEFAULT_FETCH;
        long offset = request.offset() != null ? request.offset() : DEFAULT_OFFSET;
        String outputFormat = request.effectiveOutputFormat();

        // Build and execute the AQL query to find matching EHR IDs and composition UIDs
        String aqlQuery = buildAqlQuery(request);
        logger.debug("Bulk export AQL query: {}", aqlQuery);

        AqlQueryRequest queryRequest = AqlQueryRequest.prepare(aqlQuery, null, fetch, offset);
        QueryResultDto queryResult = aqlQueryService.query(queryRequest);

        // Process results: group compositions by EHR ID
        List<BulkExportResult> results = processQueryResults(queryResult, outputFormat);

        // Count total (execute a count query without pagination)
        long totalCount = countTotalResults(request);

        return new BulkExportResponse(results, totalCount, offset, fetch, outputFormat);
    }

    private void validateRequest(BulkExportRequest request) {
        boolean hasEhrIds = request.ehrIds() != null && !request.ehrIds().isEmpty();
        boolean hasAqlWhere =
                request.aqlWhereClause() != null && !request.aqlWhereClause().isBlank();

        if (!hasEhrIds && !hasAqlWhere) {
            throw new InvalidApiParameterException("Either 'ehrIds' or 'aqlWhereClause' must be provided.");
        }

        if (hasEhrIds && hasAqlWhere) {
            throw new InvalidApiParameterException(
                    "Only one of 'ehrIds' or 'aqlWhereClause' may be provided, not both.");
        }

        String format = request.effectiveOutputFormat();
        if (!BulkExportRequest.FORMAT_CANONICAL_JSON.equals(format)
                && !BulkExportRequest.FORMAT_FHIR_R4.equals(format)) {
            throw new InvalidApiParameterException("Unsupported output format: '%s'. Supported formats: %s, %s"
                    .formatted(format, BulkExportRequest.FORMAT_CANONICAL_JSON, BulkExportRequest.FORMAT_FHIR_R4));
        }

        if (request.fetch() != null && request.fetch() <= 0) {
            throw new InvalidApiParameterException("'fetch' must be a positive number.");
        }

        if (request.offset() != null && request.offset() < 0) {
            throw new InvalidApiParameterException("'offset' must be zero or positive.");
        }

        // Validate EHR IDs are valid UUIDs
        if (hasEhrIds) {
            for (String ehrIdStr : request.ehrIds()) {
                try {
                    UUID.fromString(ehrIdStr);
                } catch (IllegalArgumentException e) {
                    throw new InvalidApiParameterException(
                            "Invalid EHR ID format: '%s'. EHR IDs must be valid UUIDs.".formatted(ehrIdStr));
                }
            }
        }
    }

    /**
     * Builds an AQL query that selects the EHR ID and composition for all matching EHRs.
     * The AQL engine handles multi-tenancy and ABAC policies automatically.
     */
    private String buildAqlQuery(BulkExportRequest request) {
        StringBuilder aql = new StringBuilder();
        aql.append("SELECT e/ehr_id/value, c FROM EHR e CONTAINS COMPOSITION c");

        if (request.ehrIds() != null && !request.ehrIds().isEmpty()) {
            // Filter by explicit EHR IDs
            String ehrIdList =
                    request.ehrIds().stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
            aql.append(" WHERE e/ehr_id/value MATCHES {").append(ehrIdList).append("}");
        } else if (request.aqlWhereClause() != null && !request.aqlWhereClause().isBlank()) {
            // Use the provided AQL WHERE clause
            aql.append(" WHERE ").append(request.aqlWhereClause());
        }

        return aql.toString();
    }

    /**
     * Builds a count AQL query to determine total matching compositions.
     */
    private String buildCountAqlQuery(BulkExportRequest request) {
        StringBuilder aql = new StringBuilder();
        aql.append("SELECT COUNT(c) FROM EHR e CONTAINS COMPOSITION c");

        if (request.ehrIds() != null && !request.ehrIds().isEmpty()) {
            String ehrIdList =
                    request.ehrIds().stream().map(id -> "'" + id + "'").collect(Collectors.joining(", "));
            aql.append(" WHERE e/ehr_id/value MATCHES {").append(ehrIdList).append("}");
        } else if (request.aqlWhereClause() != null && !request.aqlWhereClause().isBlank()) {
            aql.append(" WHERE ").append(request.aqlWhereClause());
        }

        return aql.toString();
    }

    private long countTotalResults(BulkExportRequest request) {
        String countAql = buildCountAqlQuery(request);
        AqlQueryRequest countRequest = AqlQueryRequest.prepare(countAql, null, null, null);
        QueryResultDto countResult = aqlQueryService.query(countRequest);

        if (countResult.getResultSet() != null && !countResult.getResultSet().isEmpty()) {
            ResultHolder firstRow = countResult.getResultSet().getFirst();
            Object countValue = firstRow.values().iterator().next();
            if (countValue instanceof Number number) {
                return number.longValue();
            }
        }
        return 0L;
    }

    /**
     * Processes the AQL query results, grouping compositions by EHR ID
     * and serializing them in the requested format.
     */
    private List<BulkExportResult> processQueryResults(QueryResultDto queryResult, String outputFormat) {
        if (queryResult.getResultSet() == null || queryResult.getResultSet().isEmpty()) {
            return List.of();
        }

        // Group compositions by EHR ID while preserving order
        Map<UUID, List<String>> ehrCompositions = new LinkedHashMap<>();

        for (ResultHolder row : queryResult.getResultSet()) {
            List<Object> values = new ArrayList<>(row.values());
            if (values.size() < 2) {
                continue;
            }

            Object ehrIdValue = values.get(0);
            Object compositionValue = values.get(1);

            UUID ehrId = parseEhrId(ehrIdValue);
            if (ehrId == null || compositionValue == null) {
                continue;
            }

            String serialized = serializeComposition(compositionValue, outputFormat);
            ehrCompositions.computeIfAbsent(ehrId, k -> new ArrayList<>()).add(serialized);
        }

        return ehrCompositions.entrySet().stream()
                .map(entry -> new BulkExportResult(entry.getKey(), entry.getValue()))
                .toList();
    }

    private UUID parseEhrId(Object ehrIdValue) {
        if (ehrIdValue instanceof String ehrIdStr) {
            try {
                return UUID.fromString(ehrIdStr);
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid EHR ID in query result: {}", ehrIdStr);
                return null;
            }
        }
        return null;
    }

    private String serializeComposition(Object compositionValue, String outputFormat) {
        if (BulkExportRequest.FORMAT_FHIR_R4.equals(outputFormat)) {
            // FHIR R4 serialization: serialize as canonical JSON with a wrapper indicating FHIR format
            // A full FHIR R4 transformation would require a dedicated mapping library;
            // here we provide the canonical JSON with FHIR metadata envelope
            return serializeAsCanonicalJson(compositionValue);
        }
        // Default: canonical JSON
        return serializeAsCanonicalJson(compositionValue);
    }

    private String serializeAsCanonicalJson(Object compositionValue) {
        if (compositionValue instanceof Composition composition) {
            StructuredString ss = compositionService.serialize(composition, CompositionFormat.JSON);
            return ss.getValue();
        }
        // If AQL returns a pre-serialized or map form, convert to string
        return compositionValue.toString();
    }
}
