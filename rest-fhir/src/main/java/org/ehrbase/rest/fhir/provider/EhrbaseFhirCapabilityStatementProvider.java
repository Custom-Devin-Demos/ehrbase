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
import java.util.Date;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.Condition;
import org.hl7.fhir.r4.model.Enumerations;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;
import org.springframework.stereotype.Component;

/**
 * Provides the FHIR CapabilityStatement (metadata endpoint) for the EHRbase FHIR facade.
 * Declares initial support for Patient, Observation, Condition, and Bundle resources.
 */
@Component
public class EhrbaseFhirCapabilityStatementProvider implements IServerConformanceProvider<CapabilityStatement> {

    private RestfulServer restfulServer;

    @Override
    public void setRestfulServer(RestfulServer restfulServer) {
        this.restfulServer = restfulServer;
    }

    @Metadata
    @Override
    public CapabilityStatement getServerConformance(HttpServletRequest request, RequestDetails requestDetails) {
        CapabilityStatement cs = new CapabilityStatement();

        cs.setStatus(Enumerations.PublicationStatus.ACTIVE);
        cs.setName("EHRbaseFhirFacade");
        cs.setTitle("EHRbase FHIR R4 Facade");
        cs.setDescription("FHIR R4 facade over the EHRbase openEHR Clinical Data Repository");
        cs.setDate(new Date());
        cs.setFhirVersion(Enumerations.FHIRVersion._4_0_1);

        cs.getSoftware().setName("EHRbase").setVersion("2.30.0-SNAPSHOT");

        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.addFormat("application/fhir+json");
        cs.addFormat("application/fhir+xml");

        CapabilityStatement.CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(CapabilityStatement.RestfulCapabilityMode.SERVER);

        addResourceComponent(rest, Patient.class.getSimpleName());
        addResourceComponent(rest, Observation.class.getSimpleName());
        addResourceComponent(rest, Condition.class.getSimpleName());
        addResourceComponent(rest, Bundle.class.getSimpleName());

        return cs;
    }

    private void addResourceComponent(CapabilityStatement.CapabilityStatementRestComponent rest, String resourceType) {
        CapabilityStatement.CapabilityStatementRestResourceComponent resource = rest.addResource();
        resource.setType(resourceType);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.READ);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.CREATE);
        resource.addInteraction().setCode(CapabilityStatement.TypeRestfulInteraction.SEARCHTYPE);
    }
}
