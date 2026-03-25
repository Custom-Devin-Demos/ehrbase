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

import ca.uhn.fhir.rest.annotation.IdParam;
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.nedap.archie.rm.changecontrol.Contribution;
import java.util.List;
import java.util.UUID;
import org.ehrbase.api.service.ContributionService;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.rest.fhir.mapping.FhirMappingService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.IdType;
import org.springframework.stereotype.Component;

/**
 * FHIR Resource Provider for Bundle, backed by openEHR Contributions.
 * A Contribution maps to a FHIR transaction-response Bundle.
 */
@Component
public class BundleResourceProvider implements IResourceProvider {

    private final ContributionService contributionService;
    private final EhrService ehrService;
    private final FhirMappingService mappingService;

    public BundleResourceProvider(
            ContributionService contributionService,
            EhrService ehrService,
            FhirMappingService mappingService) {
        this.contributionService = contributionService;
        this.ehrService = ehrService;
        this.mappingService = mappingService;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Bundle.class;
    }

    /**
     * Reads a Bundle by contribution ID. The ID format is {ehrId}:{contributionId}.
     */
    @Read
    public Bundle read(@IdParam IdType id) {
        String idPart = id.getIdPart();

        // Parse composite ID format: {ehrId}:{contributionId}
        String[] parts = idPart.split(":");
        if (parts.length != 2) {
            throw new ResourceNotFoundException(
                    "Bundle/" + idPart + " - Expected format: {ehrId}:{contributionId}");
        }

        UUID ehrId;
        UUID contributionId;
        try {
            ehrId = UUID.fromString(parts[0]);
            contributionId = UUID.fromString(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Bundle/" + idPart);
        }

        if (!ehrService.hasEhr(ehrId)) {
            throw new ResourceNotFoundException("Bundle/" + idPart + " - EHR not found");
        }

        Contribution contribution = contributionService.getContribution(ehrId, contributionId);
        return mappingService.toBundle(ehrId, contribution);
    }

    @Search
    public List<Bundle> search() {
        // Search is not yet implemented - return empty list
        return List.of();
    }
}
