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

import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.ehr.EhrStatus;
import com.nedap.archie.rm.generic.PartyProxy;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.GenericId;
import com.nedap.archie.rm.support.identification.ObjectId;
import com.nedap.archie.rm.support.identification.ObjectRef;
import com.nedap.archie.rm.support.identification.PartyRef;
import java.util.UUID;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.hl7.fhir.r4.model.Reference;
import org.springframework.stereotype.Component;

/**
 * Maps between openEHR EHR + EHR_STATUS.subject and FHIR Patient resources.
 *
 * <p>In openEHR, the subject of an EHR is represented as a PartyProxy (typically PartySelf
 * or PartyIdentified) within the EHR_STATUS. This mapper translates that subject reference
 * into a FHIR Patient resource and vice versa.
 */
@Component
public class PatientFhirMapper {

    private static final String EHR_SYSTEM = "urn:ietf:rfc:4122";
    private static final String OPENEHR_NAMESPACE = "openEHR";

    /**
     * Maps an openEHR EHR + EHR_STATUS to a FHIR Patient.
     *
     * @param ehrId     the EHR UUID (used as the FHIR Patient.id)
     * @param ehrStatus the EHR_STATUS containing subject information
     * @return a FHIR Patient resource
     */
    public Patient toPatient(UUID ehrId, EhrStatus ehrStatus) {
        Patient patient = new Patient();
        patient.setId(ehrId.toString());

        // Map EHR ID as an identifier
        patient.addIdentifier(new Identifier()
                .setSystem(EHR_SYSTEM)
                .setValue(ehrId.toString())
                .setUse(Identifier.IdentifierUse.OFFICIAL));

        // Map subject external reference if present
        PartyProxy subject = ehrStatus.getSubject();
        if (subject != null && subject.getExternalRef() != null) {
            ObjectRef<? extends ObjectId> externalRef = subject.getExternalRef();
            String subjectId = externalRef.getId().getValue();
            String namespace = externalRef.getNamespace();

            patient.addIdentifier(new Identifier()
                    .setSystem(namespace != null ? namespace : OPENEHR_NAMESPACE)
                    .setValue(subjectId)
                    .setUse(Identifier.IdentifierUse.SECONDARY));
        }

        // Map EHR_STATUS name to Patient name if available
        if (ehrStatus.getName() != null) {
            patient.addName().setText(ehrStatus.getName().getValue());
        }

        // Map is_queryable and is_modifiable as extensions
        patient.setActive(ehrStatus.isModifiable());

        // Link back to the managing organization (EHRbase)
        patient.setManagingOrganization(new Reference().setDisplay("EHRbase"));

        return patient;
    }

    /**
     * Maps a FHIR Patient to an openEHR EHR_STATUS.
     *
     * @param patient the FHIR Patient resource
     * @return an EHR_STATUS with subject information populated
     */
    public EhrStatus toEhrStatus(Patient patient) {
        EhrStatus ehrStatus = new EhrStatus();
        ehrStatus.setArchetypeNodeId("openEHR-EHR-EHR_STATUS.generic.v1");
        ehrStatus.setName(new DvText("EHR Status"));
        ehrStatus.setQueryable(true);
        ehrStatus.setModifiable(patient.getActiveElement() != null && !patient.getActiveElement().isEmpty()
                ? patient.getActive() : true);

        // Build subject from Patient identifiers
        PartySelf subject = new PartySelf();

        // Find the subject identifier (prefer SECONDARY, fall back to first non-EHR identifier)
        Identifier subjectIdentifier = findSubjectIdentifier(patient);

        if (subjectIdentifier != null) {
            PartyRef partyRef = new PartyRef();
            partyRef.setId(new GenericId(subjectIdentifier.getValue(), "FHIR"));
            partyRef.setNamespace(
                    subjectIdentifier.getSystem() != null ? subjectIdentifier.getSystem() : OPENEHR_NAMESPACE);
            partyRef.setType("PERSON");
            subject.setExternalRef(partyRef);
        }

        ehrStatus.setSubject(subject);

        return ehrStatus;
    }

    private Identifier findSubjectIdentifier(Patient patient) {
        if (patient.getIdentifier() == null || patient.getIdentifier().isEmpty()) {
            return null;
        }

        // Prefer SECONDARY identifiers (these represent the subject reference)
        return patient.getIdentifier().stream()
                .filter(id -> Identifier.IdentifierUse.SECONDARY.equals(id.getUse()))
                .findFirst()
                // Fall back to any identifier that's not the EHR system identifier
                .orElseGet(() -> patient.getIdentifier().stream()
                        .filter(id -> !EHR_SYSTEM.equals(id.getSystem()))
                        .findFirst()
                        .orElse(null));
    }
}
