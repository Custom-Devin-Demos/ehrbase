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

import static org.ehrbase.api.rest.HttpRestContext.EHR_ID;
import static org.ehrbase.api.rest.HttpRestContext.TEMPLATE_ID;

import com.nedap.archie.rm.composition.Composition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.ehrbase.api.exception.ObjectNotFoundException;
import org.ehrbase.api.rest.HttpRestContext;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.api.service.SystemService;
import org.ehrbase.api.service.fhir.FhirCompositionService;
import org.ehrbase.api.util.LocatableUtils;
import org.ehrbase.rest.BaseController;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.OperationOutcome;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller providing FHIR R4 representations of openEHR Compositions.
 *
 * <p>This controller exposes a dedicated endpoint that retrieves a Composition from the repository,
 * transforms it into a FHIR R4 Bundle using the {@link FhirCompositionService}, and returns the
 * result as {@code application/fhir+json}.
 */
@ConditionalOnMissingBean(name = "primaryopenehrfhircompositioncontroller")
@RestController
@RequestMapping(
        path = BaseController.API_CONTEXT_PATH_WITH_VERSION + "/ehr",
        produces = {"application/fhir+json", MediaType.APPLICATION_JSON_VALUE})
@Tag(name = "FHIR", description = "FHIR R4 representations of openEHR Compositions")
public class OpenehrFhirCompositionController extends BaseController {

    /** Standard FHIR JSON media type. */
    public static final String APPLICATION_FHIR_JSON = "application/fhir+json";

    private final CompositionService compositionService;
    private final FhirCompositionService fhirCompositionService;
    private final SystemService systemService;

    public OpenehrFhirCompositionController(
            CompositionService compositionService,
            FhirCompositionService fhirCompositionService,
            SystemService systemService) {
        this.compositionService = Objects.requireNonNull(compositionService);
        this.fhirCompositionService = Objects.requireNonNull(fhirCompositionService);
        this.systemService = Objects.requireNonNull(systemService);
    }

    /**
     * Retrieves a Composition and returns it as a FHIR R4 Bundle.
     */
    @GetMapping("/{ehr_id}/composition/{versioned_object_uid}/fhir")
    @Operation(
            summary = "Get composition as FHIR R4 Bundle",
            description =
                    "Retrieves an openEHR Composition and transforms it into a FHIR R4 Bundle "
                            + "containing mapped clinical resources (Observation, Condition, "
                            + "MedicationStatement, MedicationRequest, Encounter, etc.).",
            responses = {
                @ApiResponse(
                        responseCode = "200",
                        description = "FHIR R4 Bundle",
                        content =
                                @Content(
                                        mediaType = APPLICATION_FHIR_JSON,
                                        schema = @Schema(implementation = String.class))),
                @ApiResponse(responseCode = "404", description = "Composition not found")
            })
    public ResponseEntity<String> getCompositionAsFhir(
            @Parameter(description = "EHR identifier") @PathVariable(value = "ehr_id") String ehrIdString,
            @Parameter(description = "Versioned object UID or version UID of the composition")
                    @PathVariable(value = "versioned_object_uid")
                    String versionedObjectUid,
            @Parameter(description = "Version at time") @RequestParam(value = "version_at_time", required = false)
                    String versionAtTime,
            @Parameter(description = "Whether to include validation results")
                    @RequestParam(value = "validate", required = false, defaultValue = "false")
                    boolean validate) {

        UUID ehrId = getEhrUuid(ehrIdString);
        UUID compositionUid = extractVersionedObjectUidFromVersionUid(versionedObjectUid);

        int version = extractVersionFromVersionUid(versionedObjectUid)
                .orElseGet(() -> getVersionByTimestamp(versionAtTime, compositionUid)
                        .orElseGet(() -> compositionService.getLastVersionNumber(ehrId, compositionUid)));

        if (compositionService.isDeleted(ehrId, compositionUid, version)) {
            return ResponseEntity.noContent().build();
        }

        Composition composition = compositionService
                .retrieve(ehrId, compositionUid, version)
                .orElseThrow(() -> new ObjectNotFoundException(
                        COMPOSITION, "No COMPOSITION with given id: %s".formatted(compositionUid)));

        String templateId = LocatableUtils.getTemplateId(composition);

        // Transform to FHIR R4 Bundle
        Bundle bundle = fhirCompositionService.toFhirBundle(composition);

        // Optionally validate the bundle
        if (validate) {
            OperationOutcome outcome = fhirCompositionService.validateBundle(bundle);
            // Add the OperationOutcome as the last entry in the bundle
            Bundle.BundleEntryComponent outcomeEntry = bundle.addEntry();
            outcomeEntry.setFullUrl("urn:uuid:validation-outcome");
            outcomeEntry.setResource(outcome);
        }

        // Serialize to FHIR JSON using the service's shared FhirContext
        String fhirJson = fhirCompositionService.serializeBundleToJson(bundle);

        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, APPLICATION_FHIR_JSON);
        headers.setETag(
                "\"" + compositionUid + "::" + systemService.getSystemId() + "::" + version + "\"");

        HttpRestContext.register(EHR_ID, ehrId, TEMPLATE_ID, templateId);

        return new ResponseEntity<>(fhirJson, headers, HttpStatus.OK);
    }

    private OptionalInt getVersionByTimestamp(String versionAtTime, UUID compositionUid) {
        return decodeVersionAtTime(versionAtTime)
                .map(t -> compositionService.getVersionByTimestamp(compositionUid, t))
                .map(OptionalInt::of)
                .orElseGet(OptionalInt::empty);
    }
}
