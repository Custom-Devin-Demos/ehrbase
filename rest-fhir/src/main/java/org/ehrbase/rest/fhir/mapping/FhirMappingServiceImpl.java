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
package org.ehrbase.rest.fhir.mapping;

import com.nedap.archie.rm.changecontrol.Contribution;
import com.nedap.archie.rm.composition.Composition;
import com.nedap.archie.rm.ehr.EhrStatus;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Service;

/**
 * Default implementation of {@link FhirMappingService} that delegates to
 * individual resource-specific mappers.
 */
@Service
public class FhirMappingServiceImpl implements FhirMappingService {

    private final PatientFhirMapper patientMapper;
    private final ObservationFhirMapper observationMapper;
    private final ConditionFhirMapper conditionMapper;
    private final BundleFhirMapper bundleMapper;

    public FhirMappingServiceImpl(
            PatientFhirMapper patientMapper,
            ObservationFhirMapper observationMapper,
            ConditionFhirMapper conditionMapper,
            BundleFhirMapper bundleMapper) {
        this.patientMapper = patientMapper;
        this.observationMapper = observationMapper;
        this.conditionMapper = conditionMapper;
        this.bundleMapper = bundleMapper;
    }

    @Override
    public Patient toPatient(UUID ehrId, EhrStatus ehrStatus) {
        return patientMapper.toPatient(ehrId, ehrStatus);
    }

    @Override
    public EhrStatus toEhrStatus(Patient patient) {
        return patientMapper.toEhrStatus(patient);
    }

    @Override
    public Observation toObservation(UUID ehrId, Composition composition, String templateId) {
        return observationMapper.toObservation(ehrId, composition, templateId);
    }

    @Override
    public Composition observationToComposition(Observation observation, String templateId) {
        return observationMapper.toComposition(observation, templateId);
    }

    @Override
    public Condition toCondition(UUID ehrId, Composition composition, String templateId) {
        return conditionMapper.toCondition(ehrId, composition, templateId);
    }

    @Override
    public Composition conditionToComposition(Condition condition, String templateId) {
        return conditionMapper.toComposition(condition, templateId);
    }

    @Override
    public Bundle toBundle(UUID ehrId, Contribution contribution) {
        return bundleMapper.toBundle(ehrId, contribution);
    }
}
