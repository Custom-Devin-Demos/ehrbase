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

import com.nedap.archie.rm.changecontrol.Contribution;
import com.nedap.archie.rm.generic.AuditDetails;
import com.nedap.archie.rm.support.identification.HierObjectId;
import com.nedap.archie.rm.support.identification.ObjectRef;
import com.nedap.archie.rm.support.identification.ObjectVersionId;
import com.nedap.archie.rm.datavalues.quantity.datetime.DvDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BundleFhirMapperTest {

    private BundleFhirMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new BundleFhirMapper();
    }

    @Test
    void toBundle_withContribution_mapsBundleCorrectly() {
        UUID ehrId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();
        UUID compositionId = UUID.randomUUID();

        Contribution contribution = new Contribution();
        contribution.setUid(new HierObjectId(contributionId.toString()));

        AuditDetails audit = new AuditDetails();
        audit.setTimeCommitted(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        contribution.setAudit(audit);

        ObjectRef<ObjectVersionId> ref = new ObjectRef<>(
                new ObjectVersionId(compositionId.toString()),
                "local",
                "COMPOSITION");
        contribution.setVersions(List.of(ref));

        Bundle bundle = mapper.toBundle(ehrId, contribution);

        assertNotNull(bundle);
        assertEquals(Bundle.BundleType.TRANSACTIONRESPONSE, bundle.getType());
        assertEquals(contributionId.toString(), bundle.getId());

        assertFalse(bundle.getEntry().isEmpty());
        assertEquals(1, bundle.getEntry().size());

        Bundle.BundleEntryComponent entry = bundle.getEntry().get(0);
        assertTrue(entry.getFullUrl().contains(compositionId.toString()));
        assertEquals("200 OK", entry.getResponse().getStatus());
    }

    @Test
    void toBundle_withEmptyContribution_returnsEmptyBundle() {
        UUID ehrId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();

        Contribution contribution = new Contribution();
        contribution.setUid(new HierObjectId(contributionId.toString()));

        AuditDetails audit = new AuditDetails();
        audit.setTimeCommitted(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        contribution.setAudit(audit);

        Bundle bundle = mapper.toBundle(ehrId, contribution);

        assertNotNull(bundle);
        assertEquals(Bundle.BundleType.TRANSACTIONRESPONSE, bundle.getType());
        assertTrue(bundle.getEntry().isEmpty());
    }

    @Test
    void toBundle_withMultipleVersions_mapsAllEntries() {
        UUID ehrId = UUID.randomUUID();
        UUID contributionId = UUID.randomUUID();
        UUID comp1 = UUID.randomUUID();
        UUID comp2 = UUID.randomUUID();

        Contribution contribution = new Contribution();
        contribution.setUid(new HierObjectId(contributionId.toString()));

        AuditDetails audit = new AuditDetails();
        audit.setTimeCommitted(new DvDateTime(OffsetDateTime.now(ZoneOffset.UTC)));
        contribution.setAudit(audit);

        ObjectRef<ObjectVersionId> ref1 = new ObjectRef<>(
                new ObjectVersionId(comp1.toString()), "local", "COMPOSITION");
        ObjectRef<ObjectVersionId> ref2 = new ObjectRef<>(
                new ObjectVersionId(comp2.toString()), "local", "COMPOSITION");
        contribution.setVersions(List.of(ref1, ref2));

        Bundle bundle = mapper.toBundle(ehrId, contribution);

        assertNotNull(bundle);
        assertEquals(2, bundle.getEntry().size());
    }
}
