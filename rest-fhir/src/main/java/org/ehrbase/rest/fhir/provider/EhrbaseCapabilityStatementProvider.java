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

import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.server.IServerConformanceProvider;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CodeType;
import org.hl7.fhir.r4.model.Enumerations;

/**
 * Provides the FHIR CapabilityStatement (served at /fhir/metadata) declaring
 * initial support for Patient, Observation, Condition, and Bundle resources.
 */
public class EhrbaseCapabilityStatementProvider implements IServerConformanceProvider<CapabilityStatement> {

    private RestfulServer restfulServer;

    @Override
    public CapabilityStatement getServerConformance(
            HttpServletRequest httpServletRequest, RequestDetails requestDetails) {

        CapabilityStatement cs = new CapabilityStatement();
        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);
        cs.setFormat(List.of(new CodeType("application/fhir+json"), new CodeType("application/fhir+xml")));
        cs.setSoftware(
                new CapabilityStatement.CapabilityStatementSoftwareComponent().setName("EHRbase FHIR Facade"));

        CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);

        addResourceComponent(rest, "Patient");
        addResourceComponent(rest, "Observation");
        addResourceComponent(rest, "Condition");
        addResourceComponent(rest, "Bundle");

        return cs;
    }

    @Override
    public void setRestfulServer(RestfulServer restfulServer) {
        this.restfulServer = restfulServer;
    }

    private static void addResourceComponent(
            CapabilityStatement.CapabilityStatementRestComponent rest, String resourceType) {
        CapabilityStatement.CapabilityStatementRestResourceComponent resource = rest.addResource();
        resource.setType(resourceType);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
    }
}
