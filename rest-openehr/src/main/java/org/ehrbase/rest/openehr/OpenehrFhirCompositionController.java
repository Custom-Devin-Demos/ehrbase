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
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.fhir.FhirCompositionService;
import org.ehrbase.rest.BaseController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller that exposes an openEHR-to-FHIR R4 transformation endpoint.
 *
 * <p>This controller retrieves an openEHR composition by its versioned object UID and returns
 * it as a FHIR R4 Bundle in JSON format ({@code application/fhir+json}).
 */
@ConditionalOnMissingBean(name = "primaryopenehrfhircompositioncontroller")
@RestController
@RequestMapping(path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/ehr")
public class OpenehrFhirCompositionController extends BaseController {

    /** Standard FHIR JSON media type. */
    public static final String APPLICATION_FHIR_JSON_VALUE = "application/fhir+json";

    public static final MediaType APPLICATION_FHIR_JSON = new MediaType("application", "fhir+json");

    private final CompositionService compositionService;
    private final FhirCompositionService fhirCompositionService;

    public OpenehrFhirCompositionController(
            CompositionService compositionService, FhirCompositionService fhirCompositionService) {
        this.compositionService = Objects.requireNonNull(compositionService);
        this.fhirCompositionService = Objects.requireNonNull(fhirCompositionService);
    }

    /**
     * Retrieves an openEHR composition and returns it as a FHIR R4 Bundle.
     *
     * @param ehrIdString              the EHR identifier
     * @param versionedObjectUid       the composition versioned object UID (may include version)
     * @param version                  optional explicit version number
     * @return FHIR R4 Bundle JSON
     */
    @GetMapping(value = "/{ehr_id}/composition/{versioned_object_uid}/fhir", produces = APPLICATION_FHIR_JSON_VALUE)
    public ResponseEntity<String> getCompositionAsFhir(
            @PathVariable(value = "ehr_id") String ehrIdString,
            @PathVariable(value = "versioned_object_uid") String versionedObjectUid,
            @RequestParam(value = "version", required = false) Integer version) {

        UUID ehrId = getEhrUuid(ehrIdString);
        UUID compositionUid = extractVersionedObjectUidFromVersionUid(versionedObjectUid);

        int resolvedVersion = Optional.ofNullable(version)
                .or(() -> {
                    var extracted = extractVersionFromVersionUid(versionedObjectUid);
                    return extracted.isPresent() ? Optional.of(extracted.getAsInt()) : Optional.empty();
                })
                .orElseGet(() -> compositionService.getLastVersionNumber(ehrId, compositionUid));

        var composition = compositionService
                .retrieve(ehrId, compositionUid, resolvedVersion)
                .orElseThrow(() -> new ObjectNotFoundException(
                        "composition",
                        "Could not find composition with id %s in EHR %s".formatted(compositionUid, ehrId)));

        String fhirJson = fhirCompositionService.toFhirBundle(ehrId, composition);

        return ResponseEntity.ok().contentType(APPLICATION_FHIR_JSON).body(fhirJson);
    }
}
