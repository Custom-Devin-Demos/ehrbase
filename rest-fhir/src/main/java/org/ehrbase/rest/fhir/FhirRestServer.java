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
package org.ehrbase.rest.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.rest.server.RestfulServer;
import jakarta.servlet.ServletException;
import java.util.List;
import org.ehrbase.rest.fhir.provider.BundleTransactionProvider;
import org.ehrbase.rest.fhir.provider.ConditionResourceProvider;
import org.ehrbase.rest.fhir.provider.FhirCapabilityStatementProvider;
import org.ehrbase.rest.fhir.provider.ObservationResourceProvider;
import org.ehrbase.rest.fhir.provider.PatientResourceProvider;

/**
 * HAPI FHIR R4 server that mounts at /fhir/* and coexists with the openEHR REST API.
 */
public class FhirRestServer extends RestfulServer {

    private static final long serialVersionUID = 1L;

    public FhirRestServer() {
        super(FhirContext.forR4());
    }

    @Override
    protected void initialize() throws ServletException {
        // Register resource providers
        setResourceProviders(List.of(
                new PatientResourceProvider(),
                new ObservationResourceProvider(),
                new ConditionResourceProvider()));

        // Register plain providers (Bundle transaction/batch)
        registerProvider(new BundleTransactionProvider());

        // Register custom CapabilityStatement provider
        setServerConformanceProvider(new FhirCapabilityStatementProvider(this));
    }
}
