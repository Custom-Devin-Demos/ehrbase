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
import ca.uhn.fhir.rest.annotation.Read;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.annotation.Search;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import com.nedap.archie.rm.ehr.EhrStatus;
import java.util.List;
import java.util.UUID;
import org.ehrbase.api.service.EhrService;
import org.ehrbase.rest.fhir.mapping.FhirMappingService;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.IdType;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/**
 * FHIR Resource Provider for Patient, backed by EHR + EHR_STATUS.
 * GET /fhir/Patient/{id} returns a FHIR Patient derived from an existing EHR.
 */
@Component
public class PatientResourceProvider implements IResourceProvider {

    private final EhrService ehrService;
    private final FhirMappingService mappingService;

    public PatientResourceProvider(EhrService ehrService, FhirMappingService mappingService) {
        this.ehrService = ehrService;
        this.mappingService = mappingService;
    }

    @Override
    public Class<? extends IBaseResource> getResourceType() {
        return Patient.class;
    }

    /**
     * Reads a Patient by EHR ID. The EHR's EHR_STATUS.subject is mapped to FHIR Patient fields.
     */
    @Read
    public Patient read(@IdParam IdType id) {
        UUID ehrId;
        try {
            ehrId = UUID.fromString(id.getIdPart());
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException("Patient/" + id.getIdPart());
        }

        if (!ehrService.hasEhr(ehrId)) {
            throw new ResourceNotFoundException("Patient/" + id.getIdPart());
        }

        EhrStatus ehrStatus = ehrService.getEhrStatus(ehrId);
        return mappingService.toPatient(ehrId, ehrStatus);
    }

    /**
     * Creates a new EHR from a FHIR Patient resource.
     */
    @Create
    public MethodOutcome create(@ResourceParam Patient patient) {
        EhrStatus ehrStatus = mappingService.toEhrStatus(patient);
        UUID ehrId = ehrService.create(null, ehrStatus);

        MethodOutcome outcome = new MethodOutcome();
        outcome.setId(new IdType("Patient", ehrId.toString()));
        outcome.setCreated(true);
        return outcome;
    }

    @Search
    public List<Patient> search() {
        // Search is not yet implemented - return empty list
        return List.of();
    }
}
