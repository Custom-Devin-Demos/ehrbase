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

import ca.uhn.fhir.rest.annotation.Metadata;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IServerConformanceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;
import org.springframework.stereotype.Component;

@Component
public class EhrbaseCapabilityStatementProvider implements IServerConformanceProvider<CapabilityStatement> {

    @Override
    @Metadata
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {
        CapabilityStatement cs = new CapabilityStatement();
        cs.setStatus(PublicationStatus.ACTIVE);
        cs.setFhirVersion(org.hl7.fhir.r4.model.Enumerations.FHIRVersion._4_0_1);
        cs.setFormat(List.of(
                new org.hl7.fhir.r4.model.CodeType("application/fhir+json"),
                new org.hl7.fhir.r4.model.CodeType("application/fhir+xml")));
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);

        CapabilityStatement.CapabilityStatementSoftwareComponent software =
                new CapabilityStatement.CapabilityStatementSoftwareComponent();
        software.setName("EHRbase FHIR Facade");
        cs.setSoftware(software);

        CapabilityStatement.CapabilityStatementRestComponent rest =
                new CapabilityStatement.CapabilityStatementRestComponent();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);

        rest.addResource(buildResourceComponent("Patient"));
        rest.addResource(buildResourceComponent("Observation"));
        rest.addResource(buildResourceComponent("Condition"));
        rest.addResource(buildResourceComponent("Bundle"));

        cs.addRest(rest);
        return cs;
    }

    private CapabilityStatement.CapabilityStatementRestResourceComponent buildResourceComponent(String type) {
        CapabilityStatement.CapabilityStatementRestResourceComponent resource =
                new CapabilityStatement.CapabilityStatementRestResourceComponent();
        resource.setType(type);
        return resource;
    }

    @Override
    public void setRestfulServer(RestfulServer server) {
        // no-op
    }
}
