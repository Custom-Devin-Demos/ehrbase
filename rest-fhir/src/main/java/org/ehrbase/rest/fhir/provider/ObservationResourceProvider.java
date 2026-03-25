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
package org.ehrbase.rest.fhir.provider;

import ca.uhn.fhir.rest.annotation.Create;
import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.OptionalParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.nedap.archie.rm.composition.Composition;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.ehrbase.api.service.CompositionService;
import org.ehrbase.rest.fhir.mapping.FhirMappingService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Observation;
import org.springframework.stereotype.Component;

/**
 * FHIR Resource Provider for Observation, backed by openEHR Compositions
 * containing OBSERVATION entries.
 */
@Component
public class ObservationResourceProvider implements IResourceProvider {

    private final CompositionService compositionService;
    private final FhirMappingService mappingService;

    public ObservationResourceProvider(CompositionService compositionService, FhirMappingService mappingService) {
        this.compositionService = compositionService;
        this.mappingService = mappingService;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Observation.class;
    }

    /**
     * Reads an Observation by composition ID. The composition is mapped to a FHIR Observation.
     */
    @Read
    public Observation read(@IdParam IdType id) {
        UUID compositionId;
        try {
            compositionId = UUID.fromString(id.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Observation/" + id.getIdPart());
        }

        Optional<UUID> ehrIdOpt = compositionService.getEhrIdForComposition(compositionId);
        if (ehrIdOpt.isEmpty()) {
            throw new ResourceNotFoundException("Observation/" + id.getIdPart());
        }

        UUID ehrId = ehrIdOpt.get();
        Optional<Composition> compositionOpt = compositionService.retrieve(ehrId, compositionId, null);
        if (compositionOpt.isEmpty()) {
            throw new ResourceNotFoundException("Observation/" + id.getIdPart());
        }

        Composition composition = compositionOpt.get();
        String templateId = compositionService.retrieveTemplateId(compositionId);

        return mappingService.toObservation(ehrId, composition, templateId);
    }

    /**
     * Creates a new Observation by persisting it as an openEHR Composition.
     */
    @Create
    public MethodOutcome create(@ResourceParam Observation observation) {
        // Extract patient/EHR reference
        UUID ehrId = extractEhrId(observation);

        // Determine template from meta profile or use default
        String templateId = extractTemplateId(observation);

        // Map FHIR Observation to openEHR Composition
        Composition composition = mappingService.observationToComposition(observation, templateId);

        // Persist via CompositionService
        Optional<UUID> compositionId = compositionService.create(ehrId, composition);

        if (compositionId.isEmpty()) {
            throw new InvalidRequestException("Failed to create Observation");
        }

        MethodOutcome outcome = new MethodOutcome();
        outcome.setId(new IdType("Observation", compositionId.get().toString()));
        outcome.setCreated(true);
        return outcome;
    }

    @Search
    public List<Observation> search(@OptionalParam(name = "subject") StringParam subject) {
        // Search is not yet fully implemented - return empty list
        return List.of();
    }

    private UUID extractEhrId(Observation observation) {
        if (observation.hasSubject() && observation.getSubject().hasReference()) {
            String ref = observation.getSubject().getReference();
            if (ref.startsWith("Patient/")) {
                try {
                    return UUID.fromString(ref.substring("Patient/".length()));
                } catch (IllegalArgumentException e) {
                    throw new InvalidRequestException("Invalid Patient reference: " + ref);
                }
            }
        }
        throw new InvalidRequestException("Observation must have a subject reference to Patient/{ehrId}");
    }

    private String extractTemplateId(Observation observation) {
        if (observation.hasMeta() && observation.getMeta().hasProfile()) {
            // Could map FHIR profiles to template IDs in the future
        }
        // Default template for vital signs
        return "openEHR-EHR-COMPOSITION.health_summary.v1";
    }
}
