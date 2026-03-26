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
import java.util.UUID;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.FhirCompositionService;
import org.ehrbase.api.util.LocatableUtils;
import org.ehrbase.rest.BaseController;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller providing a FHIR R4 serialization endpoint for openEHR compositions.
 *
 * <p>This controller exposes a dedicated path alongside the existing openEHR REST API that returns
 * compositions serialized as FHIR R4 Bundles rather than openEHR canonical formats.
 */
@ConditionalOnBean(FhirCompositionService.class)
@RestController
@RequestMapping(
        path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/ehr",
        produces = {MediaType.APPLICATION_JSON_VALUE})
public class OpenehrFhirCompositionController extends BaseController {

    private static final String FHIR_MEDIA_TYPE = "application/fhir+json";

    private final CompositionService compositionService;
    private final FhirCompositionService fhirCompositionService;

    public OpenehrFhirCompositionController(
            CompositionService compositionService, FhirCompositionService fhirCompositionService) {
        this.compositionService = Objects.requireNonNull(compositionService);
        this.fhirCompositionService = Objects.requireNonNull(fhirCompositionService);
    }

    /**
     * Retrieves a composition and returns it serialized as a FHIR R4 Bundle.
     *
     * @param ehrIdString          the EHR ID
     * @param versionedObjectUid   the composition versioned object UID (may include version)
     * @param templateId           optional template ID override for mapping configuration
     * @return FHIR R4 Bundle JSON
     */
    @GetMapping(
            value = "/{ehr_id}/composition/{versioned_object_uid}/fhir",
            produces = {FHIR_MEDIA_TYPE, MediaType.APPLICATION_JSON_VALUE})
    public ResponseEntity<String> getCompositionAsFhir(
            @PathVariable(value = "ehr_id") String ehrIdString,
            @PathVariable(value = "versioned_object_uid") String versionedObjectUid,
            @RequestParam(value = "templateId", required = false) String templateId) {

        UUID ehrId = getEhrUuid(ehrIdString);
        UUID compositionUid = extractVersionedObjectUidFromVersionUid(versionedObjectUid);

        Integer version = extractVersionFromVersionUid(versionedObjectUid).isPresent()
                ? extractVersionFromVersionUid(versionedObjectUid).getAsInt()
                : null;

        var composition = compositionService
                .retrieve(ehrId, compositionUid, version)
                .orElseThrow(() -> new ObjectNotFoundException(
                        COMPOSITION, "No COMPOSITION with given id: %s".formatted(compositionUid)));

        // Use provided templateId or extract from composition
        String effectiveTemplateId = templateId != null ? templateId : LocatableUtils.getTemplateId(composition);

        String fhirBundle = fhirCompositionService.serialize(composition, effectiveTemplateId);

        return ResponseEntity.ok().contentType(MediaType.parseMediaType(FHIR_MEDIA_TYPE)).body(fhirBundle);
    }
}
