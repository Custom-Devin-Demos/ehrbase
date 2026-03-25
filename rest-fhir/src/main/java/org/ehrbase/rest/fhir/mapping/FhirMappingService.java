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

/**
 * Service interface for bidirectional mapping between openEHR RM types and FHIR R4 resources.
 */
public interface FhirMappingService {

    /**
     * Maps an openEHR EHR + EHR_STATUS to a FHIR Patient resource.
     *
     * @param ehrId     the EHR identifier
     * @param ehrStatus the EHR_STATUS containing subject information
     * @return a FHIR Patient resource
     */
    Patient toPatient(UUID ehrId, EhrStatus ehrStatus);

    /**
     * Maps a FHIR Patient resource to an openEHR EHR_STATUS.
     *
     * @param patient the FHIR Patient resource
     * @return an EHR_STATUS with subject information populated
     */
    EhrStatus toEhrStatus(Patient patient);

    /**
     * Maps an openEHR Composition containing OBSERVATION entries to a FHIR Observation.
     *
     * @param ehrId       the EHR identifier
     * @param composition the openEHR Composition
     * @param templateId  the template identifier used for mapping configuration
     * @return a FHIR Observation resource
     */
    Observation toObservation(UUID ehrId, Composition composition, String templateId);

    /**
     * Maps a FHIR Observation to an openEHR Composition.
     *
     * @param observation the FHIR Observation resource
     * @param templateId  the target openEHR template identifier
     * @return an openEHR Composition
     */
    Composition observationToComposition(Observation observation, String templateId);

    /**
     * Maps an openEHR Composition containing EVALUATION entries to a FHIR Condition.
     *
     * @param ehrId       the EHR identifier
     * @param composition the openEHR Composition
     * @param templateId  the template identifier used for mapping configuration
     * @return a FHIR Condition resource
     */
    Condition toCondition(UUID ehrId, Composition composition, String templateId);

    /**
     * Maps a FHIR Condition to an openEHR Composition.
     *
     * @param condition  the FHIR Condition resource
     * @param templateId the target openEHR template identifier
     * @return an openEHR Composition
     */
    Composition conditionToComposition(Condition condition, String templateId);

    /**
     * Maps an openEHR Contribution to a FHIR transaction Bundle.
     *
     * @param ehrId        the EHR identifier
     * @param contribution the openEHR Contribution
     * @return a FHIR Bundle of type transaction-response
     */
    Bundle toBundle(UUID ehrId, Contribution contribution);
}
