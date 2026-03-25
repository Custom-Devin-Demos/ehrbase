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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nedap.archie.rm.datavalues.DvText;
import com.nedap.archie.rm.ehr.EhrStatus;
import com.nedap.archie.rm.generic.PartySelf;
import com.nedap.archie.rm.support.identification.GenericId;
import com.nedap.archie.rm.support.identification.PartyRef;
import java.util.UUID;
import org.hl7.fhir.r4.model.Identifier;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PatientFhirMapperTest {

    private PatientFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new PatientFhirMapper();
    }

    @Test
    void toPatient_withSubjectExternalRef_mapsIdentifiers() {
        UUID ehrId = UUID.randomUUID();
        EhrStatus status = new EhrStatus();
        status.setArchetypeNodeId("openEHR-EHR-EHR_STATUS.generic.v1");
        status.setName(new DvText("EHR Status"));
        status.setModifiable(true);
        status.setQueryable(true);

        PartySelf subject = new PartySelf();
        PartyRef partyRef = new PartyRef();
        partyRef.setId(new GenericId("patient-123", "FHIR"));
        partyRef.setNamespace("http://hospital.example.com");
        partyRef.setType("PERSON");
        subject.setExternalRef(partyRef);
        status.setSubject(subject);

        Patient patient = mapper.toPatient(ehrId, status);

        assertNotNull(patient);
        assertEquals(ehrId.toString(), patient.getId());

        // Should have two identifiers: EHR system + subject
        assertEquals(2, patient.getIdentifier().size());

        // First identifier is the EHR UUID
        Identifier ehrIdentifier = patient.getIdentifier().get(0);
        assertEquals("urn:ietf:rfc:4122", ehrIdentifier.getSystem());
        assertEquals(ehrId.toString(), ehrIdentifier.getValue());
        assertEquals(Identifier.IdentifierUse.OFFICIAL, ehrIdentifier.getUse());

        // Second identifier is the subject reference
        Identifier subjectIdentifier = patient.getIdentifier().get(1);
        assertEquals("http://hospital.example.com", subjectIdentifier.getSystem());
        assertEquals("patient-123", subjectIdentifier.getValue());
        assertEquals(Identifier.IdentifierUse.SECONDARY, subjectIdentifier.getUse());

        // Active should reflect is_modifiable
        assertTrue(patient.getActive());
    }

    @Test
    void toPatient_withoutSubjectRef_mapsMinimalPatient() {
        UUID ehrId = UUID.randomUUID();
        EhrStatus status = new EhrStatus();
        status.setArchetypeNodeId("openEHR-EHR-EHR_STATUS.generic.v1");
        status.setName(new DvText("EHR Status"));
        status.setModifiable(true);
        status.setQueryable(true);
        status.setSubject(new PartySelf());

        Patient patient = mapper.toPatient(ehrId, status);

        assertNotNull(patient);
        assertEquals(ehrId.toString(), patient.getId());
        assertEquals(1, patient.getIdentifier().size()); // Only EHR identifier
    }

    @Test
    void toEhrStatus_withIdentifiers_mapsSubject() {
        Patient patient = new Patient();
        patient.setId("test-patient");
        patient.setActive(true);

        patient.addIdentifier(new Identifier()
                .setSystem("urn:ietf:rfc:4122")
                .setValue(UUID.randomUUID().toString())
                .setUse(Identifier.IdentifierUse.OFFICIAL));

        patient.addIdentifier(new Identifier()
                .setSystem("http://hospital.example.com")
                .setValue("patient-456")
                .setUse(Identifier.IdentifierUse.SECONDARY));

        EhrStatus status = mapper.toEhrStatus(patient);

        assertNotNull(status);
        assertEquals("openEHR-EHR-EHR_STATUS.generic.v1", status.getArchetypeNodeId());
        assertTrue(status.isModifiable());
        assertTrue(status.isQueryable());

        // Subject should have external ref from SECONDARY identifier
        assertNotNull(status.getSubject());
        assertNotNull(status.getSubject().getExternalRef());
        assertEquals("patient-456", status.getSubject().getExternalRef().getId().getValue());
        assertEquals(
                "http://hospital.example.com",
                status.getSubject().getExternalRef().getNamespace());
    }

    @Test
    void toEhrStatus_withInactivePatient_setsNotModifiable() {
        Patient patient = new Patient();
        patient.setId("test-patient");
        patient.setActive(false);

        EhrStatus status = mapper.toEhrStatus(patient);

        assertNotNull(status);
        assertFalse(status.isModifiable());
    }

    @Test
    void roundTrip_patientToEhrStatusAndBack_preservesIdentifiers() {
        UUID ehrId = UUID.randomUUID();
        EhrStatus originalStatus = new EhrStatus();
        originalStatus.setArchetypeNodeId("openEHR-EHR-EHR_STATUS.generic.v1");
        originalStatus.setName(new DvText("EHR Status"));
        originalStatus.setModifiable(true);
        originalStatus.setQueryable(true);

        PartySelf subject = new PartySelf();
        PartyRef partyRef = new PartyRef();
        partyRef.setId(new GenericId("roundtrip-id", "FHIR"));
        partyRef.setNamespace("http://test.example.com");
        partyRef.setType("PERSON");
        subject.setExternalRef(partyRef);
        originalStatus.setSubject(subject);

        // Map to FHIR and back
        Patient patient = mapper.toPatient(ehrId, originalStatus);
        EhrStatus roundTrippedStatus = mapper.toEhrStatus(patient);

        // Verify round-trip fidelity
        assertNotNull(roundTrippedStatus.getSubject());
        assertNotNull(roundTrippedStatus.getSubject().getExternalRef());
        assertEquals(
                "roundtrip-id",
                roundTrippedStatus.getSubject().getExternalRef().getId().getValue());
        assertEquals(
                "http://test.example.com",
                roundTrippedStatus.getSubject().getExternalRef().getNamespace());
    }
}
