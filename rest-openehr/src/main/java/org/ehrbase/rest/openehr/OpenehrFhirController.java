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

import com.nedap.archie.rm.composition.Composition;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.UUID;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.FhirMappingService;
import org.ehrbase.rest.BaseController;
import org.ehrbase.rest.openehr.specification.FhirExportApiSpecification;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for exporting openEHR compositions as FHIR R4 resources.
 *
 * <p>This controller exposes endpoints that allow clients to retrieve compositions
 * transformed into HL7 FHIR R4 Bundle format ({@code application/fhir+json}).
 * The transformation is driven by the operational template associated with each
 * composition, allowing customizable mapping per template.</p>
 *
 * <p>Example usage:</p>
 * <pre>
 * GET /rest/openehr/v1/ehr/{ehr_id}/composition/{uid}/fhir
 * Accept: application/fhir+json
 * </pre>
 */
@ConditionalOnMissingBean(name = "primaryopenehrfhircontroller")
@RestController
@RequestMapping(path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/ehr")
public class OpenehrFhirController extends BaseController implements FhirExportApiSpecification {

    public static final String APPLICATION_FHIR_JSON_VALUE = "application/fhir+json";

    private final CompositionService compositionService;
    private final FhirMappingService fhirMappingService;

    public OpenehrFhirController(CompositionService compositionService, FhirMappingService fhirMappingService) {
        this.compositionService = Objects.requireNonNull(compositionService);
        this.fhirMappingService = Objects.requireNonNull(fhirMappingService);
    }

    /**
     * Exports a specific composition as a FHIR R4 Bundle.
     *
     * @param ehrIdString        The EHR identifier
     * @param versionedObjectUid The versioned composition UID
     * @param versionAtTime      Optional version-at-time parameter (ISO 8601)
     * @return ResponseEntity containing the FHIR R4 Bundle JSON
     */
    @Override
    @GetMapping(value = "/{ehr_id}/composition/{versioned_object_uid}/fhir", produces = APPLICATION_FHIR_JSON_VALUE)
    public ResponseEntity<String> exportCompositionAsFhir(
            @PathVariable(value = "ehr_id") String ehrIdString,
            @PathVariable(value = "versioned_object_uid") String versionedObjectUid,
            @RequestParam(value = "version_at_time", required = false) String versionAtTime) {

        UUID ehrId = getEhrUuid(ehrIdString);
        UUID compositionUid = extractVersionedObjectUidFromVersionUid(versionedObjectUid);

        int version = extractVersionFromVersionUid(versionedObjectUid)
                .orElseGet(() -> getVersionByTimestamp(versionAtTime, compositionUid)
                        .orElseGet(() -> compositionService.getLastVersionNumber(ehrId, compositionUid)));

        if (compositionService.isDeleted(ehrId, compositionUid, version)) {
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }

        Composition composition = compositionService
                .retrieve(ehrId, compositionUid, version)
                .orElseThrow(() -> new ObjectNotFoundException(
                        COMPOSITION, "Could not find composition with id: %s".formatted(compositionUid)));

        String fhirBundle = fhirMappingService.toFhirBundle(ehrId, composition);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, APPLICATION_FHIR_JSON_VALUE);

        return new ResponseEntity<>(fhirBundle, headers, HttpStatus.OK);
    }

    private OptionalInt getVersionByTimestamp(String versionAtTime, UUID compositionUid) {
        return decodeVersionAtTime(versionAtTime)
                .map(t -> compositionService.getVersionByTimestamp(compositionUid, t))
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }
}
