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
import java.util.List;
import org.hl7.fhir.r4.model.CapabilityStatement;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.CapabilityStatementRestResourceComponent;
import org.hl7.fhir.r4.model.CapabilityStatement.RestfulCapabilityMode;
import org.hl7.fhir.r4.model.CapabilityStatement.SystemRestfulInteraction;
import org.hl7.fhir.r4.model.CapabilityStatement.TypeRestfulInteraction;
import org.hl7.fhir.r4.model.Enumerations.FHIRVersion;
import org.hl7.fhir.r4.model.Enumerations.PublicationStatus;

/**
 * Custom CapabilityStatement provider for the EHRbase FHIR facade.
 * Declares initial support for Patient, Observation, Condition, and Bundle resources.
 */
public class FhirCapabilityStatementProvider implements IServerConformanceProvider<CapabilityStatement> {

    private RestfulServer restfulServer;

    public FhirCapabilityStatementProvider(RestfulServer restfulServer) {
        this.restfulServer = restfulServer;
    }

    @Override
    @Metadata
    public CapabilityStatement getServerConformance(
            HttpServletRequest theRequest, RequestDetails theRequestDetails) {

        CapabilityStatement cs = new CapabilityStatement();
        cs.setStatus(PublicationStatus.DRAFT);
        cs.setDate(new Date());
        cs.setFhirVersion(FHIRVersion._4_0_1);
        cs.setKind(CapabilityStatement.CapabilityStatementKind.INSTANCE);
        cs.getSoftware().setName("EHRbase FHIR Facade");
        cs.getImplementation().setDescription("EHRbase openEHR-to-FHIR facade");

        // Define the REST server component
        CapabilityStatementRestComponent rest = cs.addRest();
        rest.setMode(RestfulCapabilityMode.SERVER);

        // Patient resource
        rest.addResource(buildResourceComponent("Patient", List.of(TypeRestfulInteraction.READ)));

        // Observation resource
        rest.addResource(buildResourceComponent("Observation", List.of(TypeRestfulInteraction.READ)));

        // Condition resource
        rest.addResource(buildResourceComponent("Condition", List.of(TypeRestfulInteraction.READ)));

        // Bundle resource (system-level transaction/batch support)
        rest.addResource(buildResourceComponent("Bundle", List.of(TypeRestfulInteraction.READ)));

        // Declare transaction support at system level
        rest.addInteraction().setCode(SystemRestfulInteraction.TRANSACTION);
        rest.addInteraction().setCode(SystemRestfulInteraction.BATCH);

        return cs;
    }

    private CapabilityStatementRestResourceComponent buildResourceComponent(
            String resourceType, List<TypeRestfulInteraction> interactions) {
        CapabilityStatementRestResourceComponent resource = new CapabilityStatementRestResourceComponent();
        resource.setType(resourceType);
        for (TypeRestfulInteraction interaction : interactions) {
            resource.addInteraction().setCode(interaction);
        }
        return resource;
    }

    @Override
    public void setRestfulServer(RestfulServer restfulServer) {
        this.restfulServer = restfulServer;
    }
}
