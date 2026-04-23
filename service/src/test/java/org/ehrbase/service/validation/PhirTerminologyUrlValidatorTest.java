/*
 * Copyright (c) 2026 vitasystems GmbH.
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
package org.ehrbase.service.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PhirTerminologyUrlValidatorTest {

    @Test
    void isPhirTerminology_HappyPath_SchemeOnly() {
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("phir://"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("phir://some/code-system?url=foo"));
    }

    @Test
    void isPhirTerminology_HappyPath_CdcAuthority() {
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("//phir.cdc.gov"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("//phir.cdc.gov/CodeSystem?url=urn:oid:1.2.3"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("terminology://phir.cdc.gov"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov/ValueSet/$expand"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("http://phir.cdc.gov"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("http://phir.cdc.gov/"));
    }

    @Test
    void isPhirTerminology_HappyPath_CaseInsensitive() {
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("PHIR://SOME/Path"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("HTTPS://PHIR.CDC.GOV/ValueSet"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("Terminology://PHIR.cdc.gov"));
    }

    @Test
    void isPhirTerminology_NegativePath_RejectsFhirAuthorities() {
        // FHIR authorities (owned by FhirTerminologyValidation) must not be mistaken for PHIR.
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("//fhir.hl7.org"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("terminology://fhir.hl7.org"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("//hl7.org/fhir"));
    }

    @Test
    void isPhirTerminology_NegativePath_RejectsLookalikesAndUnknown() {
        // Substring match, not prefix match — "phir" must not be recognized mid-URI.
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("https://example.com/phir/CodeSystem"));
        // Typo / look-alike authority is not an accepted PHIR endpoint.
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.example.com/"));
        // Unknown, non-PHIR URIs.
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("http://snomed.info/sct"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("urn:oid:2.16.840.1.113883.6.96"));
    }

    @Test
    void isPhirTerminology_NegativePath_RejectsSubdomainSpoofing() {
        // Subdomain-of-accepted-host attack — must not be recognized as PHIR.
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov.evil.com/CodeSystem"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("http://phir.cdc.gov.evil.com"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("//phir.cdc.gov.evil.com/ValueSet"));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("terminology://phir.cdc.gov.evil.com"));
    }

    @Test
    void isPhirTerminology_HappyPath_HostBoundaryTerminators() {
        // Any of the host/path boundary terminators should be accepted after the authority.
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov:8443/CodeSystem"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov?query=foo"));
        assertTrue(PhirTerminologyUrlValidator.isPhirTerminology("https://phir.cdc.gov#section"));
    }

    @Test
    void isPhirTerminology_NegativePath_RejectsNullAndBlank() {
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology(null));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology(""));
        assertFalse(PhirTerminologyUrlValidator.isPhirTerminology("   "));
    }

    @Test
    void acceptedPhirServiceApis_IsNonEmptyAndImmutable() {
        Assertions.assertFalse(PhirTerminologyUrlValidator.ACCEPTED_PHIR_SERVICE_APIS.isEmpty());
        Assertions.assertTrue(PhirTerminologyUrlValidator.ACCEPTED_PHIR_SERVICE_APIS.contains("phir://"));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> PhirTerminologyUrlValidator.ACCEPTED_PHIR_SERVICE_APIS.add("//new-authority"));
    }
}
