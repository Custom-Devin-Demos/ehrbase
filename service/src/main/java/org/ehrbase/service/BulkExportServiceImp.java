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

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.nedap.archie.rm.composition.Composition;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ehrbase.api.dto.AqlQueryRequest;
import org.ehrbase.api.dto.BulkExportRequest;
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
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * {@link BulkExportService} implementation.
 * Uses the AQL engine for EHR resolution and the CompositionService for data retrieval and serialization.
 */
@Service
public class BulkExportServiceImp implements BulkExportService {

    private static final Logger logger = LoggerFactory.getLogger(BulkExportServiceImp.class);

    private static final String DEFAULT_AQL_ALL_EHRS = "SELECT e/ehr_id/value FROM EHR e";
    private static final String AQL_COMPOSITIONS_FOR_EHR =
            "SELECT c FROM EHR e CONTAINS COMPOSITION c WHERE e/ehr_id/value = '%s'";

    private final EhrService ehrService;
    private final AqlQueryService aqlQueryService;
    private final CompositionService compositionService;
    private final JsonFactory jsonFactory;

    public BulkExportServiceImp(
            EhrService ehrService, AqlQueryService aqlQueryService, CompositionService compositionService) {
        this.ehrService = ehrService;
        this.aqlQueryService = aqlQueryService;
        this.compositionService = compositionService;
        this.jsonFactory = new JsonFactory();
    }

    @Override
    public List<UUID> resolveEhrIds(BulkExportRequest request) {
        if (request.ehrIds() != null && !request.ehrIds().isEmpty()) {
            // Validate that all provided EHR IDs exist
            for (UUID ehrId : request.ehrIds()) {
                ehrService.checkEhrExists(ehrId);
            }
            return request.ehrIds();
        }

        if (request.aqlFilter() != null && !request.aqlFilter().isBlank()) {
            return resolveEhrIdsFromAql(request.aqlFilter());
        }

        // Neither provided — invalid request
        throw new InvalidApiParameterException(
                "Either 'ehr_ids' or 'aql_filter' must be provided in the bulk export request.");
    }

    @PreAuthorize("isAuthenticated()")
    @Override
    public void streamExport(BulkExportRequest request, OutputStream outputStream) {
        List<UUID> ehrIds = resolveEhrIds(request);
        BulkExportRequest.OutputFormat format = request.effectiveOutputFormat();

        long offset = request.offset() != null ? request.offset() : 0;
        long fetch = request.fetch() != null ? request.fetch() : Long.MAX_VALUE;

        logger.info(
                "Starting bulk export for {} EHR(s), format={}, offset={}, fetch={}",
                ehrIds.size(),
                format,
                offset,
                fetch);

        try (JsonGenerator gen = jsonFactory.createGenerator(outputStream, JsonEncoding.UTF8)) {
            gen.writeStartObject();

            // Write meta
            writeMeta(gen, ehrIds.size(), format, request.fetch(), request.offset());

            // Write entries array (streamed)
            gen.writeArrayFieldStart("entries");

            long compositionsWritten = 0;
            long compositionsSkipped = 0;

            for (UUID ehrId : ehrIds) {
                List<Composition> compositions = retrieveCompositionsForEhr(ehrId);

                gen.writeStartObject();
                gen.writeStringField("ehr_id", ehrId.toString());
                gen.writeArrayFieldStart("compositions");

                for (Composition composition : compositions) {
                    // Apply global offset/fetch across all compositions
                    if (compositionsSkipped < offset) {
                        compositionsSkipped++;
                        continue;
                    }
                    if (compositionsWritten >= fetch) {
                        break;
                    }

                    writeComposition(gen, composition, format);
                    compositionsWritten++;
                }

                gen.writeEndArray(); // end compositions
                gen.writeEndObject(); // end ehr entry

                // Flush periodically to support streaming
                gen.flush();

                if (compositionsWritten >= fetch) {
                    break;
                }
            }

            gen.writeEndArray(); // end entries
            gen.writeEndObject(); // end root
            gen.flush();

            logger.info("Bulk export completed: {} compositions exported", compositionsWritten);

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to stream bulk export response", e);
        }
    }

    /**
     * Resolves EHR IDs using an AQL WHERE clause.
     * Constructs a full AQL query: SELECT e/ehr_id/value FROM EHR e WHERE {aqlFilter}
     */
    private List<UUID> resolveEhrIdsFromAql(String aqlFilter) {
        String aql = DEFAULT_AQL_ALL_EHRS + " WHERE " + aqlFilter;
        AqlQueryRequest queryRequest = AqlQueryRequest.prepare(aql, Map.of(), null, null);
        QueryResultDto result = aqlQueryService.query(queryRequest);

        List<UUID> ehrIds = new ArrayList<>();
        if (result.getResultSet() != null) {
            for (ResultHolder row : result.getResultSet()) {
                Object value = row.values().iterator().next();
                if (value instanceof String s) {
                    ehrIds.add(UUID.fromString(s));
                }
            }
        }
        return ehrIds;
    }

    /**
     * Retrieves all compositions for a given EHR using AQL.
     */
    private List<Composition> retrieveCompositionsForEhr(UUID ehrId) {
        String aql = AQL_COMPOSITIONS_FOR_EHR.formatted(ehrId);
        AqlQueryRequest queryRequest = AqlQueryRequest.prepare(aql, Map.of(), null, null);
        QueryResultDto result = aqlQueryService.query(queryRequest);

        List<Composition> compositions = new ArrayList<>();
        if (result.getResultSet() != null) {
            for (ResultHolder row : result.getResultSet()) {
                Object value = row.values().iterator().next();
                if (value instanceof Composition c) {
                    compositions.add(c);
                }
            }
        }
        return compositions;
    }

    /**
     * Writes a single composition to the JSON generator in the requested format.
     */
    private void writeComposition(
            JsonGenerator gen, Composition composition, BulkExportRequest.OutputFormat format) throws IOException {
        switch (format) {
            case CANONICAL_JSON -> {
                StructuredString serialized = compositionService.serialize(composition, CompositionFormat.JSON);
                // Write raw JSON value directly
                gen.writeRawValue(serialized.getValue());
            }
            case FHIR_R4 -> {
                // FHIR R4 output wraps the canonical JSON in a FHIR-compatible Bundle entry structure.
                // Full HL7 FHIR transformation requires the hapi-fhir library to be added as a
                // runtime dependency. This implementation provides a structured envelope that can
                // be consumed by FHIR-aware systems.
                gen.writeStartObject();
                gen.writeStringField("resourceType", "DocumentReference");
                gen.writeStringField("status", "current");
                gen.writeObjectFieldStart("content");
                gen.writeObjectFieldStart("attachment");
                gen.writeStringField("contentType", "application/openehr+json");

                StructuredString serialized = compositionService.serialize(composition, CompositionFormat.JSON);
                gen.writeFieldName("data");
                gen.writeRawValue(serialized.getValue());

                gen.writeEndObject(); // end attachment
                gen.writeEndObject(); // end content
                gen.writeEndObject(); // end DocumentReference
            }
        }
    }

    /**
     * Writes the metadata section to the JSON generator.
     */
    private void writeMeta(
            JsonGenerator gen,
            int totalEhrs,
            BulkExportRequest.OutputFormat format,
            Long fetch,
            Long offset)
            throws IOException {
        gen.writeObjectFieldStart("meta");
        gen.writeNumberField("total_ehrs", totalEhrs);
        gen.writeStringField("output_format", format.name());
        if (fetch != null) {
            gen.writeNumberField("fetch", fetch);
        }
        if (offset != null) {
            gen.writeNumberField("offset", offset);
        }
        gen.writeEndObject();
    }
}
