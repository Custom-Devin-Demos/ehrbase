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
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

/**
 * FHIR Resource Provider for Condition, backed by openEHR Compositions
 * containing EVALUATION entries.
 */
@Component
public class ConditionResourceProvider implements IResourceProvider {

    private final CompositionService compositionService;
    private final FhirMappingService mappingService;

    public ConditionResourceProvider(CompositionService compositionService, FhirMappingService mappingService) {
        this.compositionService = compositionService;
        this.mappingService = mappingService;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Condition.class;
    }

    /**
     * Reads a Condition by composition ID. The composition is mapped to a FHIR Condition.
     */
    @Read
    public Condition read(@IdParam IdType id) {
        UUID compositionId;
        try {
            compositionId = UUID.fromString(id.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Condition/" + id.getIdPart());
        }

        Optional<UUID> ehrIdOpt = compositionService.getEhrIdForComposition(compositionId);
        if (ehrIdOpt.isEmpty()) {
            throw new ResourceNotFoundException("Condition/" + id.getIdPart());
        }

        UUID ehrId = ehrIdOpt.get();
        Optional<Composition> compositionOpt = compositionService.retrieve(ehrId, compositionId, null);
        if (compositionOpt.isEmpty()) {
            throw new ResourceNotFoundException("Condition/" + id.getIdPart());
        }

        Composition composition = compositionOpt.get();
        String templateId = compositionService.retrieveTemplateId(compositionId);

        return mappingService.toCondition(ehrId, composition, templateId);
    }

    /**
     * Creates a new Condition by persisting it as an openEHR Composition.
     */
    @Create
    public MethodOutcome create(@ResourceParam Condition condition) {
        // Extract patient/EHR reference
        UUID ehrId = extractEhrId(condition);

        // Determine template
        String templateId = extractTemplateId(condition);

        // Map FHIR Condition to openEHR Composition
        Composition composition = mappingService.conditionToComposition(condition, templateId);

        // Persist via CompositionService
        Optional<UUID> compositionId = compositionService.create(ehrId, composition);

        if (compositionId.isEmpty()) {
            throw new InvalidRequestException("Failed to create Condition");
        }

        MethodOutcome outcome = new MethodOutcome();
        outcome.setId(new IdType("Condition", compositionId.get().toString()));
        outcome.setCreated(true);
        return outcome;
    }

    @Search
    public List<Condition> search(@OptionalParam(name = "subject") StringParam subject) {
        // Search is not yet fully implemented - return empty list
        return List.of();
    }

    private UUID extractEhrId(Condition condition) {
        if (condition.hasSubject() && condition.getSubject().hasReference()) {
            String ref = condition.getSubject().getReference();
            if (ref.startsWith("Patient/")) {
                try {
                    return UUID.fromString(ref.substring("Patient/".length()));
                } catch (IllegalArgumentException e) {
                    throw new InvalidRequestException("Invalid Patient reference: " + ref);
                }
            }
        }
        throw new InvalidRequestException("Condition must have a subject reference to Patient/{ehrId}");
    }

    private String extractTemplateId(Condition condition) {
        // Default template for problem list
        return "openEHR-EHR-COMPOSITION.problem_list.v2";
    }
}
