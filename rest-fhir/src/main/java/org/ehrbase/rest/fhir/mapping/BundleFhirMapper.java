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
import com.nedap.archie.rm.support.identification.ObjectRef;
import java.time.OffsetDateTime;
import java.time.temporal.TemporalAccessor;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Bundle.BundleEntryComponent;
import org.hl7.fhir.r4.model.Bundle.BundleEntryResponseComponent;
import org.hl7.fhir.r4.model.Bundle.BundleType;
import org.hl7.fhir.r4.model.Bundle.HTTPVerb;
import org.hl7.fhir.r4.model.Meta;
import org.springframework.stereotype.Component;

/**
 * Maps between openEHR Contributions and FHIR transaction Bundles.
 *
 * <p>A Contribution in openEHR represents a commit containing one or more versioned objects.
 * This maps to a FHIR Bundle of type "transaction-response" where each version reference
 * becomes a Bundle entry.
 */
@Component
public class BundleFhirMapper {

    /**
     * Maps an openEHR Contribution to a FHIR transaction-response Bundle.
     *
     * @param ehrId        the EHR identifier
     * @param contribution the openEHR Contribution
     * @return a FHIR Bundle
     */
    public Bundle toBundle(UUID ehrId, Contribution contribution) {
        Bundle bundle = new Bundle();
        bundle.setType(BundleType.TRANSACTIONRESPONSE);

        // Set Bundle ID from contribution UID
        if (contribution.getUid() != null) {
            bundle.setId(contribution.getUid().getValue());
        }

        // Set timestamp from audit
        if (contribution.getAudit() != null
                && contribution.getAudit().getTimeCommitted() != null
                && contribution.getAudit().getTimeCommitted().getValue() != null) {
            TemporalAccessor temporal = contribution.getAudit().getTimeCommitted().getValue();
            if (temporal instanceof OffsetDateTime odt) {
                bundle.setTimestamp(Date.from(odt.toInstant()));
            }
        }

        // Set meta
        Meta meta = new Meta();
        meta.setLastUpdated(bundle.getTimestamp());
        bundle.setMeta(meta);

        // Map each version reference to a Bundle entry
        List<? extends ObjectRef<?>> versions = contribution.getVersions();
        if (versions != null) {
            for (ObjectRef<?> versionRef : versions) {
                BundleEntryComponent entry = new BundleEntryComponent();

                // Set the full URL for the versioned object
                String objectId = versionRef.getId().getValue();
                String type = versionRef.getType();
                entry.setFullUrl(buildFullUrl(ehrId, type, objectId));

                // Set response information
                BundleEntryResponseComponent response = new BundleEntryResponseComponent();
                response.setStatus("200 OK");
                response.setLocation(buildFullUrl(ehrId, type, objectId));
                entry.setResponse(response);

                bundle.addEntry(entry);
            }
        }

        return bundle;
    }

    /**
     * Builds a resource URL from the EHR ID, type, and object ID.
     */
    private String buildFullUrl(UUID ehrId, String type, String objectId) {
        if ("COMPOSITION".equals(type)) {
            return "Composition/" + objectId;
        } else if ("EHR_STATUS".equals(type)) {
            return "Patient/" + ehrId;
        } else if ("FOLDER".equals(type)) {
            return "List/" + objectId;
        }
        return type + "/" + objectId;
    }
}
